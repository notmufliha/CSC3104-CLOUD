//! Call memory in Redis, so any aggregator copy can handle any line of any call, and restarting the aggregator
//! mid-call loses nothing.
//!
//!   call:<call_id>     hash: started_at_ms, window (JSON, last N lines' scores), streak, red, caller_lines,
//!                      verdict, combined, version. Deleted `call_ttl` after its last update.
//!   calllog:<call_id>  list: one JSON verdict-log entry per caller line {call_id, seq, time_ms, verdict,
//!                      combined, rule, scores, recent}. Never transcript text (the aggregator never sees any).
//!                      Same expiry; capped at LOG_MAX entries.
//!
//! Two copies updating the same call at once: each update reads the call, runs policy::decide, then saves with
//! a Lua script that only writes if `version` is still what was read (and bumps it). The loser re-reads and
//! tries again. (Redis WATCH would not work here: it belongs to one connection, and all requests share one.)

use std::collections::HashMap;
use std::time::Duration;

use redis::aio::ConnectionManager;
use redis::Script;

use crate::config::{Settings, SIGNALS};
use crate::policy::{self, CallState, Decision, LineScores, Verdict};

/// Attempts before giving up when other updates to the same call keep winning. Between attempts it waits a
/// short random time (up to attempt × 2 ms) so competing updates stop colliding.
const MAX_ATTEMPTS: u32 = 20;
/// Verdict-log entries kept per call.
const LOG_MAX: i64 = 1000;

/// KEYS[1] = call hash, KEYS[2] = verdict log. ARGV[1] = version that was read, ARGV[2] = ttl (s),
/// ARGV[3] = log entry, ARGV[4] = log cap, ARGV[5..] = field, value, field, value, ...
/// Returns 1 if saved, 0 if someone else saved first.
const SAVE_LUA: &str = r#"
local current = redis.call('HGET', KEYS[1], 'version') or '0'
if current ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], 'version', tostring(tonumber(ARGV[1]) + 1), unpack(ARGV, 5))
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('RPUSH', KEYS[2], ARGV[3])
redis.call('LTRIM', KEYS[2], -tonumber(ARGV[4]), -1)
redis.call('EXPIRE', KEYS[2], ARGV[2])
return 1
"#;

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
                    return Store { con, save: Script::new(SAVE_LUA) };
                }
                Err(e) => {
                    tracing::warn!(redis = %url_for_log, error = %e, "redis not reachable yet, retrying in 1 s");
                    tokio::time::sleep(Duration::from_secs(1)).await;
                }
            }
        }
    }

    pub async fn ping(&self) -> Result<(), StoreError> {
        let mut con = self.con.clone();
        redis::cmd("PING").query_async::<String>(&mut con).await?;
        Ok(())
    }

    /// Load the call, apply this line with policy::decide, save. Returns the decision.
    pub async fn apply(
        &self,
        call_id: &str,
        seq: u64,
        line: LineScores,
        now_ms: i64,
        call_time_ms: i64,
        cfg: &Settings,
    ) -> Result<Decision, StoreError> {
        let mut con = self.con.clone();
        let (call_key, log_key) = (format!("call:{call_id}"), format!("calllog:{call_id}"));
        for attempt in 0..MAX_ATTEMPTS {
            if attempt > 0 {
                tokio::time::sleep(backoff(attempt)).await;
            }
            let stored: HashMap<String, String> = redis::cmd("HGETALL").arg(&call_key).query_async(&mut con).await?;
            let (state, version) = from_hash(&stored)?;
            let (state, decision) = policy::decide(state, line, now_ms, call_time_ms, cfg);
            let entry = log_entry(call_id, seq, now_ms, &line, &decision);

            let mut invocation = self.save.key(&call_key);
            invocation
                .key(&log_key)
                .arg(version)
                .arg(cfg.call_ttl.as_secs())
                .arg(entry)
                .arg(LOG_MAX);
            for (field, value) in to_hash(&state) {
                invocation.arg(field).arg(value);
            }
            let saved: i64 = invocation.invoke_async(&mut con).await?;
            if saved == 1 {
                return Ok(decision);
            }
            tracing::debug!(call_id, "another update to this call won, retrying");
        }
        Err(StoreError::Busy)
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

/// One verdict-log entry (3.8): ids, time, verdict and scores. Never text.
pub fn log_entry(call_id: &str, seq: u64, now_ms: i64, line: &LineScores, d: &Decision) -> String {
    let named = |scores: &LineScores| -> serde_json::Map<String, serde_json::Value> {
        SIGNALS.iter().zip(scores).map(|(s, v)| (s.to_string(), serde_json::json!(v.map(clean)))).collect()
    };
    serde_json::json!({
        "call_id": call_id,
        "seq": seq,
        "time_ms": now_ms,
        "verdict": d.verdict.as_str(),
        "combined": clean(d.combined),
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
        let d = crate::policy::decide_one_shot(line, &cfg);
        let entry: serde_json::Value = serde_json::from_str(&log_entry("c1", 3, 42, &line, &d)).unwrap();
        assert_eq!(entry["scores"]["urgency"], serde_json::json!(0.6));
        assert_eq!(entry["scores"]["impersonation"], serde_json::Value::Null);
        assert_eq!(entry["verdict"], "SCAM");
        let keys: Vec<_> = entry.as_object().unwrap().keys().cloned().collect();
        assert_eq!(keys, ["call_id", "combined", "recent", "rule", "scores", "seq", "time_ms", "verdict"]);
    }

    #[test]
    fn corrupt_hash_is_an_error() {
        let h: HashMap<String, String> = [("window".to_string(), "not json".to_string())].into();
        assert!(matches!(from_hash(&h), Err(StoreError::Corrupt(_))));
    }
}
