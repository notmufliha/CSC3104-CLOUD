//! Call memory in Redis, so any aggregator copy can handle any line of any call, and restarting the aggregator
//! mid-call loses nothing.
//!
//!   call:<call_id>     hash: started_at_ms, window (JSON, last N lines' scores), streak, red, caller_lines,
//!                      verdict, combined, version. Deleted `call_ttl` after its last update.
//!   calllog:<call_id>  list: one JSON verdict-log entry per caller line {call_id, seq, time_ms, verdict,
//!                      combined, fast, bert, bert_status, rule, scores, recent}. Never transcript text (the
//!                      call's recent lines pass through the aggregator to BERT but are never stored).
//!                      Same expiry; capped at LOG_MAX entries. Workstream B adds one final "event":"end" row.
//!   callend:<call_id>  Workstream B tombstone, hash: state (claimed|done), ended_by, ended_at_ms, and once done
//!                      the end result (final_verdict, call_score, live_verdict, review, top_window_seqs, reason).
//!                      Scores and fixed texts only. Expires after AGG_END_TOMBSTONE_SECS. While it exists the
//!                      call takes no new lines (the save script refuses them atomically).
//!   calls:active       Workstream B sorted set: call_id -> time of its last caller line (ms). The silence
//!                      timeout scans it; a call leaves it when it ends.
//!
//! BERT: after reading the call, the update asks BERT (once; a retry reuses the answer) if
//! policy::wants_second_opinion says the answer depends on it.
//!
//! Two copies updating the same call at once: each update reads the call, runs policy::decide, then saves with
//! a Lua script that only writes if `version` is still what was read (and bumps it). The loser re-reads and
//! tries again. (Redis WATCH would not work here: it belongs to one connection, and all requests share one.)

use std::collections::HashMap;
use std::time::Duration;

use redis::aio::ConnectionManager;
use redis::Script;

use crate::bert::SecondOpinion;
use crate::config::{Settings, SIGNALS};
use crate::policy::{self, CallState, Decision, LineScores, Verdict};

/// Attempts before giving up when other updates to the same call keep winning. Between attempts it waits a
/// short random time (up to attempt × 2 ms) so competing updates stop colliding.
const MAX_ATTEMPTS: u32 = 20;
/// Verdict-log entries kept per call.
const LOG_MAX: i64 = 1000;

/// Workstream B: index of calls by the time of their last caller line, for the silence timeout.
pub const ACTIVE_KEY: &str = "calls:active";

pub fn call_key(call_id: &str) -> String {
    format!("call:{call_id}")
}
pub fn log_key(call_id: &str) -> String {
    format!("calllog:{call_id}")
}
pub fn end_key(call_id: &str) -> String {
    format!("callend:{call_id}")
}

/// KEYS[1] = call hash, KEYS[2] = verdict log, KEYS[3] = end tombstone, KEYS[4] = active-call index.
/// ARGV[1] = version that was read, ARGV[2] = ttl (s), ARGV[3] = log entry, ARGV[4] = log cap,
/// ARGV[5] = call_id, ARGV[6] = now (ms), ARGV[7..] = field, value, field, value, ...
/// Returns 1 if saved, 0 if someone else saved first, -1 if the call has ended (nothing written).
const SAVE_LUA: &str = r#"
if redis.call('EXISTS', KEYS[3]) == 1 then return -1 end
local current = redis.call('HGET', KEYS[1], 'version') or '0'
if current ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], 'version', tostring(tonumber(ARGV[1]) + 1), 'last_line_ms', ARGV[6], unpack(ARGV, 7))
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('RPUSH', KEYS[2], ARGV[3])
redis.call('LTRIM', KEYS[2], -tonumber(ARGV[4]), -1)
redis.call('EXPIRE', KEYS[2], ARGV[2])
redis.call('ZADD', KEYS[4], ARGV[6], ARGV[5])
return 1
"#;

/// Workstream B, step 1 of an end: claim it. KEYS[1] = end tombstone. ARGV[1] = ended_by, ARGV[2] = now (ms),
/// ARGV[3] = tombstone ttl (s). Returns {} if this caller claimed it (and must finish it), otherwise the
/// existing tombstone's fields (field, value, ...): someone else ended the call first.
const CLAIM_END_LUA: &str = r#"
if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('HGETALL', KEYS[1]) end
redis.call('HSET', KEYS[1], 'state', 'claimed', 'ended_by', ARGV[1], 'ended_at_ms', ARGV[2])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return {}
"#;

/// Workstream B, step 2 of an end: store the result, forget the call, add the final verdict-log row.
/// KEYS[1] = end tombstone, KEYS[2] = call hash, KEYS[3] = verdict log, KEYS[4] = active-call index.
/// ARGV[1] = tombstone ttl (s), ARGV[2] = call_id, ARGV[3] = log entry, ARGV[4] = log cap, ARGV[5] = log ttl (s),
/// ARGV[6..] = result field, value, ...
const FINISH_END_LUA: &str = r#"
redis.call('HSET', KEYS[1], 'state', 'done', unpack(ARGV, 6))
redis.call('EXPIRE', KEYS[1], ARGV[1])
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[4], ARGV[2])
redis.call('RPUSH', KEYS[3], ARGV[3])
redis.call('LTRIM', KEYS[3], -tonumber(ARGV[4]), -1)
redis.call('EXPIRE', KEYS[3], ARGV[5])
return 1
"#;

/// Workstream B: a finished call's result, as kept in its tombstone. Scores and fixed texts only.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct EndRecord {
    /// None = unknown (no caller line of this call was ever scored).
    pub final_verdict: Option<Verdict>,
    pub call_score: f32,
    pub live_verdict: Option<Verdict>,
    /// "bert" | "fallback"
    pub review: String,
    pub top_window_seqs: Vec<i64>,
    pub reason: String,
    pub ended_by: String,
}

impl EndRecord {
    fn to_fields(&self) -> Vec<(&'static str, String)> {
        let v = |v: Option<Verdict>| v.map(|v| v.as_str()).unwrap_or("").to_string();
        vec![
            ("final_verdict", v(self.final_verdict)),
            ("call_score", self.call_score.to_string()),
            ("live_verdict", v(self.live_verdict)),
            ("review", self.review.clone()),
            ("top_window_seqs", serde_json::to_string(&self.top_window_seqs).expect("ints serialize")),
            ("reason", self.reason.clone()),
            ("ended_by", self.ended_by.clone()),
        ]
    }

    fn from_fields(h: &HashMap<String, String>) -> EndRecord {
        let get = |k: &str| h.get(k).cloned().unwrap_or_default();
        EndRecord {
            final_verdict: Verdict::parse(&get("final_verdict")),
            call_score: get("call_score").parse().unwrap_or(0.0),
            live_verdict: Verdict::parse(&get("live_verdict")),
            review: get("review"),
            top_window_seqs: serde_json::from_str(&get("top_window_seqs")).unwrap_or_default(),
            reason: get("reason"),
            ended_by: get("ended_by"),
        }
    }
}

/// Result of trying to claim a call's end.
#[derive(Debug, PartialEq)]
pub enum Claim {
    /// This caller ended the call and must finish it (review, save, clean up).
    New,
    /// Already ended and finished: the stored result.
    Done(EndRecord),
    /// Another request is finishing the end right now (its review is running).
    InProgress { ended_by: String },
}

fn pairs(flat: Vec<String>) -> HashMap<String, String> {
    let mut it = flat.into_iter();
    let mut out = HashMap::new();
    while let (Some(k), Some(v)) = (it.next(), it.next()) {
        out.insert(k, v);
    }
    out
}

#[derive(Debug)]
pub enum StoreError {
    /// Redis down, slow, or returned something unexpected.
    Redis(String),
    /// The stored call could not be read back (should not happen unless someone edited it by hand).
    Corrupt(String),
    /// Other updates to the same call won MAX_ATTEMPTS times in a row.
    Busy,
}

impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StoreError::Redis(e) => write!(f, "redis: {e}"),
            StoreError::Corrupt(e) => write!(f, "stored call unreadable: {e}"),
            StoreError::Busy => write!(f, "too many simultaneous updates to this call"),
        }
    }
}

impl From<redis::RedisError> for StoreError {
    fn from(e: redis::RedisError) -> Self {
        StoreError::Redis(e.to_string())
    }
}

#[derive(Clone)]
pub struct Store {
    con: ConnectionManager,
    save: Script,
    claim_end: Script,
    finish_end: Script,
}

impl Store {
    /// Connects to Redis; keeps trying (with a log line each time) until it works, so the aggregator can start
    /// before Redis is ready. Afterwards ConnectionManager reconnects on its own.
    pub async fn connect(url: &str, url_for_log: &str) -> Store {
        let client = redis::Client::open(url).unwrap_or_else(|e| panic!("invalid REDIS_URL '{url_for_log}': {e}"));
        let config = redis::aio::ConnectionManagerConfig::new()
            .set_connection_timeout(Some(Duration::from_millis(500)))
            .set_response_timeout(Some(Duration::from_millis(250)));
        loop {
            match client.get_connection_manager_with_config(config.clone()).await {
                Ok(con) => {
                    tracing::info!(redis = %url_for_log, "connected to redis");
                    return Store {
                        con,
                        save: Script::new(SAVE_LUA),
                        claim_end: Script::new(CLAIM_END_LUA),
                        finish_end: Script::new(FINISH_END_LUA),
                    };
                }
                Err(e) => {
                    tracing::warn!(redis = %url_for_log, error = %e, "redis not reachable yet, retrying in 1 s");
                    tokio::time::sleep(Duration::from_secs(1)).await;
                }
            }
        }
    }

    #[cfg(test)]
    pub fn con_for_tests(&self) -> ConnectionManager {
        self.con.clone()
    }

    pub async fn ping(&self) -> Result<(), StoreError> {
        let mut con = self.con.clone();
        redis::cmd("PING").query_async::<String>(&mut con).await?;
        Ok(())
    }

    /// Load the call, ask BERT if the answer depends on it, apply this line with policy::decide, save.
    /// Returns the decision, or None if the call has ended (Workstream B.6: the line is not stored or scored).
    #[allow(clippy::too_many_arguments)]
    pub async fn apply(
        &self,
        call_id: &str,
        seq: u64,
        line: LineScores,
        now_ms: i64,
        call_time_ms: i64,
        cfg: &Settings,
        second: &mut SecondOpinion<'_>,
    ) -> Result<Option<Decision>, StoreError> {
        let mut con = self.con.clone();
        let (call_key, log_key, end_key) = (call_key(call_id), log_key(call_id), end_key(call_id));
        for attempt in 0..MAX_ATTEMPTS {
            if attempt > 0 {
                tokio::time::sleep(backoff(attempt)).await;
            }
            // Read the call and whether it ended in one round trip; an ended call costs no BERT call.
            let (stored, ended): (HashMap<String, String>, bool) = redis::pipe()
                .cmd("HGETALL")
                .arg(&call_key)
                .cmd("EXISTS")
                .arg(&end_key)
                .query_async(&mut con)
                .await?;
            if ended {
                return Ok(None);
            }
            let (state, version) = from_hash(&stored)?;
            let bert = if policy::wants_second_opinion(&state, line, now_ms, call_time_ms, cfg) {
                second.ask().await
            } else {
                None
            };
            let (state, decision) = policy::decide(state, line, bert, now_ms, call_time_ms, cfg);
            let entry = log_entry(call_id, seq, now_ms, &line, &decision, second.status());

            let mut invocation = self.save.key(&call_key);
            invocation
                .key(&log_key)
                .key(&end_key)
                .key(ACTIVE_KEY)
                .arg(version)
                .arg(cfg.call_ttl.as_secs())
                .arg(entry)
                .arg(LOG_MAX)
                .arg(call_id)
                .arg(now_ms);
            for (field, value) in to_hash(&state) {
                invocation.arg(field).arg(value);
            }
            let saved: i64 = invocation.invoke_async(&mut con).await?;
            match saved {
                1 => return Ok(Some(decision)),
                -1 => return Ok(None), // ended between the read and the save
                _ => {}
            }
            tracing::debug!(call_id, "another update to this call won, retrying");
        }
        Err(StoreError::Busy)
    }

    // ---------------------------------------------------------------- Workstream B: call end

    /// Claim a call's end: exactly one caller (across all aggregator copies) gets `Claim::New`.
    pub async fn claim_end(&self, call_id: &str, ended_by: &str, now_ms: i64, cfg: &Settings) -> Result<Claim, StoreError> {
        let mut con = self.con.clone();
        let existing: Vec<String> = self
            .claim_end
            .key(end_key(call_id))
            .arg(ended_by)
            .arg(now_ms)
            .arg(cfg.end_tombstone_ttl.as_secs())
            .invoke_async(&mut con)
            .await?;
        if existing.is_empty() {
            return Ok(Claim::New);
        }
        let h = pairs(existing);
        Ok(if h.get("state").map(String::as_str) == Some("done") {
            Claim::Done(EndRecord::from_fields(&h))
        } else {
            Claim::InProgress { ended_by: h.get("ended_by").cloned().unwrap_or_default() }
        })
    }

    /// The stored end result, once the end is finished.
    pub async fn end_result(&self, call_id: &str) -> Result<Option<EndRecord>, StoreError> {
        let mut con = self.con.clone();
        let h: HashMap<String, String> = redis::cmd("HGETALL").arg(end_key(call_id)).query_async(&mut con).await?;
        Ok((h.get("state").map(String::as_str) == Some("done")).then(|| EndRecord::from_fields(&h)))
    }

    /// The call's memory as it is now (None = never seen, or already expired / ended).
    pub async fn read_call(&self, call_id: &str) -> Result<Option<CallState>, StoreError> {
        let mut con = self.con.clone();
        let h: HashMap<String, String> = redis::cmd("HGETALL").arg(call_key(call_id)).query_async(&mut con).await?;
        if h.is_empty() {
            return Ok(None);
        }
        Ok(Some(from_hash(&h)?.0))
    }

    /// Store the end result in the tombstone, delete the call's memory, take it off the active index and add the
    /// final verdict-log row. One atomic step.
    pub async fn finish_end(&self, call_id: &str, record: &EndRecord, log_entry: &str, cfg: &Settings) -> Result<(), StoreError> {
        let mut con = self.con.clone();
        let mut invocation = self.finish_end.key(end_key(call_id));
        invocation
            .key(call_key(call_id))
            .key(log_key(call_id))
            .key(ACTIVE_KEY)
            .arg(cfg.end_tombstone_ttl.as_secs())
            .arg(call_id)
            .arg(log_entry)
            .arg(LOG_MAX)
            .arg(cfg.call_ttl.as_secs());
        for (field, value) in record.to_fields() {
            invocation.arg(field).arg(value);
        }
        let _: i64 = invocation.invoke_async(&mut con).await?;
        Ok(())
    }

    /// Calls whose last caller line is older than `cutoff_ms` (at most `limit`).
    pub async fn silent_calls(&self, cutoff_ms: i64, limit: usize) -> Result<Vec<String>, StoreError> {
        let mut con = self.con.clone();
        Ok(redis::cmd("ZRANGEBYSCORE")
            .arg(ACTIVE_KEY)
            .arg("-inf")
            .arg(cutoff_ms)
            .arg("LIMIT")
            .arg(0)
            .arg(limit)
            .query_async(&mut con)
            .await?)
    }

    /// Take a call off the active index (it already ended elsewhere).
    pub async fn forget_active(&self, call_id: &str) -> Result<(), StoreError> {
        let mut con = self.con.clone();
        let _: i64 = redis::cmd("ZREM").arg(ACTIVE_KEY).arg(call_id).query_async(&mut con).await?;
        Ok(())
    }
}

/// Random wait in [0, attempt × 2 ms). The clock's nanoseconds are random enough to spread retries apart.
fn backoff(attempt: u32) -> Duration {
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0);
    Duration::from_micros(u64::from(nanos % (attempt * 2000)))
}

/// CallState -> hash fields (version is added by the Lua script).
fn to_hash(s: &CallState) -> Vec<(&'static str, String)> {
    vec![
        ("started_at_ms", s.started_at_ms.to_string()),
        ("window", serde_json::to_string(&s.window).expect("scores serialize")),
        ("streak", s.streak.to_string()),
        ("red", if s.red { "1" } else { "0" }.to_string()),
        ("caller_lines", s.caller_lines.to_string()),
        ("verdict", s.verdict.as_str().to_string()),
        ("combined", s.combined.to_string()),
    ]
}

/// Hash fields -> (CallState, version). An empty hash is a call not seen before (version "0").
fn from_hash(h: &HashMap<String, String>) -> Result<(CallState, String), StoreError> {
    if h.is_empty() {
        return Ok((CallState::default(), "0".to_string()));
    }
    fn field<T: std::str::FromStr>(h: &HashMap<String, String>, name: &str) -> Result<T, StoreError> {
        let v = h.get(name).ok_or_else(|| StoreError::Corrupt(format!("missing field {name}")))?;
        v.parse().map_err(|_| StoreError::Corrupt(format!("bad {name}")))
    }
    let window_json = h.get("window").ok_or_else(|| StoreError::Corrupt("missing field window".into()))?;
    let state = CallState {
        started_at_ms: field(h, "started_at_ms")?,
        window: serde_json::from_str(window_json).map_err(|e| StoreError::Corrupt(format!("bad window: {e}")))?,
        streak: field(h, "streak")?,
        red: h.get("red").map(|v| v == "1").unwrap_or(false),
        caller_lines: field(h, "caller_lines")?,
        verdict: h.get("verdict").and_then(|v| Verdict::parse(v)).unwrap_or_default(),
        combined: field(h, "combined")?,
    };
    let version = h.get("version").cloned().unwrap_or_else(|| "0".to_string());
    Ok((state, version))
}

/// f32 -> f64 without the float noise (0.6f32 logs as 0.6, not 0.6000000238418579).
fn clean(v: f32) -> f64 {
    v.to_string().parse().unwrap_or(f64::from(v))
}

/// One verdict-log entry (3.8): ids, time, verdict and scores (BERT's too). Never text.
pub fn log_entry(call_id: &str, seq: u64, now_ms: i64, line: &LineScores, d: &Decision, bert_status: &str) -> String {
    let named = |scores: &LineScores| -> serde_json::Map<String, serde_json::Value> {
        SIGNALS.iter().zip(scores).map(|(s, v)| (s.to_string(), serde_json::json!(v.map(clean)))).collect()
    };
    serde_json::json!({
        "call_id": call_id,
        "seq": seq,
        "time_ms": now_ms,
        "verdict": d.verdict.as_str(),
        "combined": clean(d.combined),
        "fast": clean(d.fast),
        "bert": d.bert.map(clean),
        "bert_status": bert_status,
        "rule": d.rule,
        "scores": named(line),
        "recent": named(&d.recent),
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn call_state_round_trips_through_the_hash() {
        let state = CallState {
            started_at_ms: 1_700_000_000_000,
            window: vec![[Some(0.5), None, Some(0.25)], [Some(0.125), Some(1.0), Some(0.0)]],
            streak: 1,
            red: true,
            caller_lines: 7,
            verdict: Verdict::Scam,
            combined: 0.625,
        };
        let mut h: HashMap<String, String> = to_hash(&state).into_iter().map(|(k, v)| (k.to_string(), v)).collect();
        h.insert("version".into(), "3".into());
        let (back, version) = from_hash(&h).unwrap();
        assert_eq!(back, state);
        assert_eq!(version, "3");
    }

    #[test]
    fn empty_hash_is_a_new_call() {
        let (state, version) = from_hash(&HashMap::new()).unwrap();
        assert_eq!(state, CallState::default());
        assert_eq!(version, "0");
    }

    #[test]
    fn log_entry_has_clean_numbers_and_no_text() {
        let cfg = crate::config::Settings::from_lookup(|_| None).unwrap();
        let line = [Some(0.6), Some(0.95), None];
        let d = crate::policy::decide_one_shot(line, None, &cfg);
        let entry: serde_json::Value = serde_json::from_str(&log_entry("c1", 3, 42, &line, &d, "skipped")).unwrap();
        assert_eq!(entry["scores"]["urgency"], serde_json::json!(0.6));
        assert_eq!(entry["scores"]["impersonation"], serde_json::Value::Null);
        assert_eq!(entry["verdict"], "SCAM");
        let keys: Vec<_> = entry.as_object().unwrap().keys().cloned().collect();
        assert_eq!(entry["bert"], serde_json::Value::Null);
        assert_eq!(entry["bert_status"], "skipped");
        assert_eq!(
            keys,
            ["bert", "bert_status", "call_id", "combined", "fast", "recent", "rule", "scores", "seq", "time_ms", "verdict"]
        );
    }

    #[test]
    fn end_record_round_trips_through_the_hash() {
        let r = EndRecord {
            final_verdict: Some(Verdict::Scam),
            call_score: 0.875,
            live_verdict: Some(Verdict::Unsure),
            review: "bert".into(),
            top_window_seqs: vec![9, 11, 13],
            reason: "Asked for your OTP or money".into(),
            ended_by: "user_hung_up".into(),
        };
        let h: HashMap<String, String> = r.to_fields().into_iter().map(|(k, v)| (k.to_string(), v)).collect();
        assert_eq!(EndRecord::from_fields(&h), r);
        let unknown = EndRecord { review: "fallback".into(), ended_by: "silence".into(), ..Default::default() };
        let h: HashMap<String, String> = unknown.to_fields().into_iter().map(|(k, v)| (k.to_string(), v)).collect();
        assert_eq!(EndRecord::from_fields(&h), unknown); // "" verdict = unknown
    }

    #[test]
    fn flat_reply_pairs() {
        let h = pairs(vec!["state".into(), "done".into(), "ended_by".into(), "silence".into()]);
        assert_eq!((h["state"].as_str(), h["ended_by"].as_str()), ("done", "silence"));
    }

    #[test]
    fn corrupt_hash_is_an_error() {
        let h: HashMap<String, String> = [("window".to_string(), "not json".to_string())].into();
        assert!(matches!(from_hash(&h), Err(StoreError::Corrupt(_))));
    }
}
