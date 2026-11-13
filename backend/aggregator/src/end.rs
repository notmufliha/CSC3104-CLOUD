//! Workstream B: knowing when a call ends (contract: docs/workstream-b-contract.md).
//!
//! A call ends when the phone says so (EndCall, through the gateway's POST /v1/call/end) or when no caller line
//! arrived for AGG_SILENCE_SECS (the silence task below, ended_by "silence"). Either way, `end_call`:
//!
//!   1. claims the end in Redis (callend:<id>). Exactly one request across all aggregator copies wins; any other
//!      gets the stored result (or waits briefly while the winner's review runs). Late lines are refused from now.
//!   2. runs the end-of-call review (Workstream C.6-C.8, `end_of_call_review` below) on the caller lines, within
//!      AGG_END_REVIEW_TIMEOUT_MS: BERT reads the whole call window by window. If it can't (BERT off or down, too
//!      few lines, too slow), the live verdict is the final one ("fallback").
//!   3. stores the result in the tombstone, deletes call:<id>, takes the call off calls:active and adds a final
//!      "event":"end" row to the verdict log (calllog:<id> + stdout). Scores only.
//!
//! The caller lines are passed to the review and dropped: never stored, never logged.

use std::ops::Range;
use std::sync::Arc;
use std::time::{Duration, Instant};

use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::bert::{self, BertClient, SecondOpinion};
use crate::config::Settings;
use crate::metrics::Metrics;
use crate::policy::{self, CallState, Verdict};
use crate::store::{Claim, EndRecord, Store, StoreError};

/// Everything that can end a call. The phone sends the first three; "silence" is the aggregator's own.
pub const ENDED_BY: [&str; 4] = ["script_finished", "user_hung_up", "user_discarded", "silence"];
pub const SILENCE: &str = "silence";
/// Calls ended per sweep of the silence task (the rest wait for the next sweep).
const SWEEP_LIMIT: usize = 100;
/// While another request finishes the same end, check its result this often...
const WAIT_STEP: Duration = Duration::from_millis(50);
/// ...for at most the review timeout plus this.
const WAIT_EXTRA: Duration = Duration::from_millis(500);
/// Reason for a red/yellow call whose strong lines have left the window.
const REASON_EARLIER: &str = "Warning signs earlier in the call";
/// C.6: each review window = the caller's last this-many lines at some line, the same window the live check
/// sends BERT (the gateway's BERT_CONTEXT_LINES).
pub const REVIEW_WINDOW_LINES: usize = 5;
/// C.6: at most this many windows per call: one per caller line up to 42 lines; a longer call is tiled with
/// back-to-back windows from its last line (every line still read once; the gateway sends at most 200 lines,
/// i.e. 40 windows), so it still fits AGG_END_REVIEW_TIMEOUT_MS.
pub const MAX_REVIEW_WINDOWS: usize = 40;
/// C.6: windows scored at the same time (the BERT pod has GRPC_THREADS=2 per copy, 2 copies in Kubernetes).
const REVIEW_PARALLEL: usize = 4;

/// What the end-of-call review (C.6/C.8) returns.
#[derive(Clone, Debug, PartialEq)]
pub struct Review {
    pub verdict: Verdict,
    pub score: f32,
    /// The riskiest window of caller lines (C.8), by seq. Never text.
    pub top_window_seqs: Vec<i64>,
    /// Fixed reason text; empty = use the live one.
    pub reason: String,
}

/// C.6: the review windows for a call with `n` caller lines, as index ranges into its lines: the last
/// REVIEW_WINDOW_LINES lines at every line from line bert::MIN_LINES on, i.e. exactly the windows the live check
/// could have sent BERT during the call. So a red BERT confirmed live is always seen again by the review.
/// A call too long for that (more than MAX_REVIEW_WINDOWS such windows) is tiled instead: back-to-back windows
/// ending at the last line, the line 5 before it, and so on, so every line is still read (the newest
/// MAX_REVIEW_WINDOWS windows if even that is too many).
pub fn review_windows(n: usize) -> Vec<Range<usize>> {
    if n < bert::MIN_LINES {
        return vec![];
    }
    // `end` = a window's last line (1-based); the window is the up-to-5 lines ending there
    let ends: Vec<usize> = if n + 1 - bert::MIN_LINES <= MAX_REVIEW_WINDOWS {
        (bert::MIN_LINES..=n).collect()
    } else {
        let mut tiled: Vec<usize> =
            (bert::MIN_LINES..=n).rev().step_by(REVIEW_WINDOW_LINES).take(MAX_REVIEW_WINDOWS).collect();
        tiled.reverse();
        if tiled.len() < MAX_REVIEW_WINDOWS && tiled[0] > REVIEW_WINDOW_LINES {
            tiled.insert(0, bert::MIN_LINES); // the opening lines too (the earliest tile starts after line 1)
        }
        tiled
    };
    ends.into_iter().map(|end| end.saturating_sub(REVIEW_WINDOW_LINES)..end).collect()
}

/// C.7: the call's final verdict from its review score (the highest window): SCAM at AGG_BERT_SCAM_THRESHOLD
/// (0.6, the score at which BERT confirms a red live), UNSURE at AGG_UNSURE_THRESHOLD (0.45), else SAFE.
pub fn review_verdict(score: f32, cfg: &Settings) -> Verdict {
    if score >= cfg.bert_scam_threshold {
        Verdict::Scam
    } else if score >= cfg.unsure_threshold {
        Verdict::Unsure
    } else {
        Verdict::Safe
    }
}

/// C.6-C.8: the review from every window's score (`scores[i]` for `windows[i]`): call score = the highest window,
/// verdict from it (C.7), and that window's seqs (C.8, the earliest window on a tie: where it first turned risky).
pub fn summarize(seqs: &[i64], windows: &[Range<usize>], scores: &[f32], cfg: &Settings) -> Option<Review> {
    let mut top: Option<(usize, f32)> = None;
    for (i, &s) in scores.iter().enumerate() {
        if top.is_none_or(|(_, best)| s > best) {
            top = Some((i, s));
        }
    }
    let (i, score) = top?;
    let verdict = review_verdict(score, cfg);
    Some(Review {
        verdict,
        score,
        top_window_seqs: seqs[windows[i].clone()].to_vec(),
        // red/yellow: the live call's own reason when it has one (build_record), else BERT's
        reason: String::new(),
    })
}

/// THE END-OF-CALL REVIEW (Workstream C.6-C.8). BERT reads the whole call: every window of the caller's last 5
/// lines (`review_windows`), scored through the same BertService.ScoreContext the live check uses, a few at a
/// time; the call score is the highest window (C.7) and its seqs are returned (C.8).
///
/// None (then the live verdict is the final one, "fallback") when BERT is off (BERT_ADDR empty or
/// AGG_MODE=fast), the call has fewer than 3 caller lines (BERT abstains, as live), or any window fails: a
/// partial review could miss the riskiest window. `end_call` also cuts it at AGG_END_REVIEW_TIMEOUT_MS. The text
/// is passed to BERT and dropped: never stored or logged.
pub async fn end_of_call_review(
    bert_client: Option<&BertClient>,
    cfg: &Settings,
    _call_id: &str,
    lines: &[(i64, String)],
    language: &str,
) -> Option<Review> {
    let client = bert_client?.with_timeout(cfg.end_review_timeout);
    let lines: Vec<&(i64, String)> = lines.iter().filter(|(_, t)| !t.trim().is_empty()).collect();
    let windows = review_windows(lines.len());
    if windows.is_empty() {
        return None;
    }
    let permits = Arc::new(Semaphore::new(REVIEW_PARALLEL));
    let mut jobs = JoinSet::new();
    for (i, w) in windows.iter().enumerate() {
        let window: Vec<String> = lines[w.clone()].iter().map(|(_, t)| t.clone()).collect();
        let (client, language, permits) = (client.clone(), language.to_string(), permits.clone());
        jobs.spawn(async move {
            let _permit = permits.acquire_owned().await.ok()?;
            let mut second = SecondOpinion::new(Some(&client), &window, &language);
            second.ask().await.map(|score| (i, score))
        });
    }
    let mut scores = vec![0.0_f32; windows.len()];
    while let Some(done) = jobs.join_next().await {
        match done {
            Ok(Some((i, score))) => scores[i] = score,
            _ => return None, // a window failed: no review (dropping `jobs` cancels the rest)
        }
    }
    let seqs: Vec<i64> = lines.iter().map(|(seq, _)| *seq).collect();
    summarize(&seqs, &windows, &scores, cfg)
}

/// The end result: the review's answer if there is one, otherwise the call's live verdict ("fallback").
/// `state` None = the call was never seen (or its memory expired): verdict unknown.
pub fn build_record(state: Option<&CallState>, review: Option<Review>, ended_by: &str, cfg: &Settings) -> EndRecord {
    let live = state.map(|s| s.verdict);
    let live_reason = state.map(|s| live_reason(s, cfg)).unwrap_or_default();
    match review {
        Some(r) => EndRecord {
            final_verdict: Some(r.verdict),
            call_score: r.score,
            live_verdict: live,
            review: "bert".to_string(),
            top_window_seqs: r.top_window_seqs,
            reason: match (r.reason.is_empty(), r.verdict) {
                (false, _) => r.reason,
                (true, Verdict::Safe) => String::new(),
                (true, _) if !live_reason.is_empty() => live_reason,
                (true, _) => policy::reason_text("bert").to_string(),
            },
            ended_by: ended_by.to_string(),
        },
        None => EndRecord {
            final_verdict: live,
            call_score: state.map(|s| s.combined).unwrap_or(0.0),
            live_verdict: live,
            review: "fallback".to_string(),
            top_window_seqs: vec![],
            reason: live_reason,
            ended_by: ended_by.to_string(),
        },
    }
}

/// The strongest signal's reason text over the call's window, the same words as the live `reasons`.
/// Empty when the call is SAFE.
fn live_reason(s: &CallState, cfg: &Settings) -> String {
    if s.verdict == Verdict::Safe {
        return String::new();
    }
    policy::signal_reasons(&policy::recent_highs(&s.window), cfg)
        .first()
        .map(|r| r.text.clone())
        .unwrap_or_else(|| REASON_EARLIER.to_string())
}

/// One verdict-log row for the end (3.8 + B.3): ids, time, verdicts, scores. Never text.
pub fn end_log_entry(call_id: &str, now_ms: i64, r: &EndRecord, review_ms: u64) -> String {
    let v = |v: Option<Verdict>| v.map(|v| v.as_str()).unwrap_or("UNKNOWN");
    serde_json::json!({
        "call_id": call_id,
        "event": "end",
        "time_ms": now_ms,
        "final_verdict": v(r.final_verdict),
        "call_score": r.call_score.to_string().parse::<f64>().unwrap_or(f64::from(r.call_score)),
        "live_verdict": v(r.live_verdict),
        "ended_by": r.ended_by,
        "review": r.review,
        "review_ms": review_ms,
    })
    .to_string()
}

/// The answer to an end request.
#[derive(Clone, Debug, PartialEq)]
pub struct Ended {
    pub record: EndRecord,
    /// The call had already ended before this request: nothing was run again.
    pub already_ended: bool,
}

/// End a call (see the module comment). Safe to call any number of times, from any aggregator copy.
#[allow(clippy::too_many_arguments)]
pub async fn end_call(
    store: &Store,
    cfg: &Settings,
    metrics: &Metrics,
    bert_client: Option<&BertClient>,
    call_id: &str,
    ended_by: &str,
    lines: &[(i64, String)],
    language: &str,
    now_ms: i64,
) -> Result<Ended, StoreError> {
    match store.claim_end(call_id, ended_by, now_ms, cfg).await? {
        Claim::New => {}
        Claim::Done(record) => {
            store.forget_active(call_id).await?;
            return Ok(Ended { record, already_ended: true });
        }
        Claim::InProgress { ended_by: first } => {
            // Two ends at once (e.g. hang-up and the silence timeout): wait for the first one's result.
            let give_up = Instant::now() + cfg.end_review_timeout + WAIT_EXTRA;
            while Instant::now() < give_up {
                tokio::time::sleep(WAIT_STEP).await;
                if let Some(record) = store.end_result(call_id).await? {
                    return Ok(Ended { record, already_ended: true });
                }
            }
            // The first request never finished (its aggregator copy died?): answer from the live state.
            let state = store.read_call(call_id).await.unwrap_or(None);
            return Ok(Ended { record: build_record(state.as_ref(), None, &first, cfg), already_ended: true });
        }
    }

    let state = match store.read_call(call_id).await {
        Ok(s) => s,
        Err(StoreError::Corrupt(e)) => {
            tracing::warn!(call_id, error = %e, "stored call unreadable at end: verdict unknown");
            None
        }
        Err(e) => return Err(e),
    };
    let start = Instant::now();
    let review = if lines.is_empty() {
        None
    } else {
        tokio::time::timeout(cfg.end_review_timeout, end_of_call_review(bert_client, cfg, call_id, lines, language))
            .await
            .ok()
            .flatten()
    };
    let review_ms = start.elapsed().as_millis() as u64;
    let record = build_record(state.as_ref(), review, ended_by, cfg);
    let entry = end_log_entry(call_id, now_ms, &record, review_ms);
    store.finish_end(call_id, &record, &entry, cfg).await?;
    tracing::info!(target: "verdict", entry = %entry, lines = lines.len(), "verdict");
    metrics.record_end(ended_by, &record.review);
    Ok(Ended { record, already_ended: false })
}

/// B.4: end every call with no caller line for `silence` (or, with the silence timeout off, every call whose
/// memory has expired, so the active index doesn't grow forever). Returns how many calls this sweep ended.
pub async fn sweep(store: &Store, cfg: &Settings, metrics: &Metrics, now_ms: i64) -> Result<usize, StoreError> {
    let idle = if cfg.silence.is_zero() { cfg.call_ttl } else { cfg.silence };
    let cutoff = now_ms - idle.as_millis() as i64;
    let mut ended = 0;
    for call_id in store.silent_calls(cutoff, SWEEP_LIMIT).await? {
        let e = end_call(store, cfg, metrics, None, &call_id, SILENCE, &[], "", now_ms).await?;
        if !e.already_ended {
            ended += 1;
            tracing::info!(call_id = %call_id, idle_secs = idle.as_secs(),
                           final_verdict = e.record.final_verdict.map(|v| v.as_str()).unwrap_or("UNKNOWN"),
                           "no caller line for a while: call ended by silence");
        }
    }
    Ok(ended)
}

/// Runs `sweep` every AGG_SILENCE_CHECK_SECS, in every aggregator copy (the claim makes sure each call is ended
/// once). Redis errors are logged and retried on the next tick.
pub async fn silence_loop(store: Store, cfg: Arc<Settings>, metrics: Arc<Metrics>) {
    let mut tick = tokio::time::interval(cfg.silence_check);
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tick.tick().await;
        if let Err(e) = sweep(&store, &cfg, &metrics, crate::now_ms()).await {
            tracing::warn!(error = %e, "silence check failed; retrying next tick");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> Settings {
        Settings::from_lookup(|_| None).unwrap()
    }

    fn call(verdict: Verdict, window: Vec<policy::LineScores>, combined: f32) -> CallState {
        CallState { started_at_ms: 1, caller_lines: window.len() as u32, window, verdict, combined, ..Default::default() }
    }

    #[test]
    fn fallback_is_the_live_verdict_with_its_reason() {
        let s = call(Verdict::Scam, vec![[Some(0.6), Some(0.95), Some(0.2)], [Some(0.1), Some(0.3), Some(0.7)]], 0.82);
        let r = build_record(Some(&s), None, "user_hung_up", &cfg());
        assert_eq!((r.final_verdict, r.live_verdict), (Some(Verdict::Scam), Some(Verdict::Scam)));
        assert_eq!(r.call_score, 0.82);
        assert_eq!(r.review, "fallback");
        assert_eq!(r.reason, "Asked for your OTP or money"); // money 0.95 is the strongest in the window
        assert!(r.top_window_seqs.is_empty());
        assert_eq!(r.ended_by, "user_hung_up");
    }

    #[test]
    fn safe_call_has_no_reason_and_a_red_call_always_has_one() {
        let safe = call(Verdict::Safe, vec![[Some(0.6), Some(0.1), Some(0.1)]], 0.2);
        assert_eq!(build_record(Some(&safe), None, "script_finished", &cfg()).reason, "");
        // red earlier, the strong lines have left the window
        let red = call(Verdict::Scam, vec![[Some(0.1), Some(0.1), Some(0.1)]], 0.1);
        assert_eq!(build_record(Some(&red), None, "script_finished", &cfg()).reason, REASON_EARLIER);
    }

    #[test]
    fn unknown_call_is_unknown() {
        let r = build_record(None, None, SILENCE, &cfg());
        assert_eq!((r.final_verdict, r.live_verdict, r.call_score), (None, None, 0.0));
        assert_eq!((r.review.as_str(), r.reason.as_str(), r.ended_by.as_str()), ("fallback", "", "silence"));
    }

    #[test]
    fn a_review_answer_wins_and_keeps_the_live_verdict_alongside() {
        let s = call(Verdict::Unsure, vec![[Some(0.6), Some(0.5), Some(0.2)]], 0.5);
        let review = Review { verdict: Verdict::Scam, score: 0.91, top_window_seqs: vec![9, 11], reason: String::new() };
        let r = build_record(Some(&s), Some(review), "script_finished", &cfg());
        assert_eq!((r.final_verdict, r.live_verdict), (Some(Verdict::Scam), Some(Verdict::Unsure)));
        assert_eq!((r.call_score, r.review.as_str()), (0.91, "bert"));
        assert_eq!(r.top_window_seqs, [9, 11]);
        assert_eq!(r.reason, "Pressuring you to act now"); // the live reason fills an empty review reason
    }

    // ---------------------------------------------------------------- C.6-C.8: the end-of-call review

    fn lines(texts: &[&str]) -> Vec<(i64, String)> {
        // seqs as the phone numbers them: receiver lines in between, so caller seqs are 1, 3, 5, ...
        texts.iter().enumerate().map(|(i, t)| (2 * i as i64 + 1, t.to_string())).collect()
    }

    #[test]
    fn review_windows_are_the_windows_the_live_check_reads() {
        assert!(review_windows(0).is_empty());
        assert!(review_windows(2).is_empty(), "BERT abstains below 3 caller lines, as live");
        assert_eq!(review_windows(3), vec![0..3]);
        assert_eq!(review_windows(7), vec![0..3, 0..4, 0..5, 1..6, 2..7]);
        for n in 3..=200 {
            let w = review_windows(n);
            assert!(w.len() <= MAX_REVIEW_WINDOWS, "{n} lines -> {} windows", w.len());
            assert_eq!(w.last().unwrap().end, n, "{n}: the last line is always read");
            assert!(w.iter().all(|r| r.len() <= REVIEW_WINDOW_LINES && r.len() >= 3), "{n}: 3-5 lines each");
            assert!(w.windows(2).all(|p| p[0].end < p[1].end), "{n}: in order, no repeats");
            let mut read = vec![false; n];
            w.iter().for_each(|r| r.clone().for_each(|i| read[i] = true));
            assert!(read.iter().all(|x| *x), "{n}: every caller line is read by some window");
        }
        assert_eq!(review_windows(42).len(), 40); // up to 42 lines: one window per line from line 3
        assert_eq!(review_windows(42).first(), Some(&(0..3)));
        assert_eq!(review_windows(43).len(), 9); // longer: back-to-back windows
        assert_eq!(review_windows(200).len(), 40);
    }

    #[test]
    fn review_verdict_uses_bert_confirm_and_unsure_thresholds() {
        let c = cfg();
        assert_eq!(review_verdict(0.6, &c), Verdict::Scam);
        assert_eq!(review_verdict(0.59, &c), Verdict::Unsure);
        assert_eq!(review_verdict(0.45, &c), Verdict::Unsure);
        assert_eq!(review_verdict(0.44, &c), Verdict::Safe);
    }

    #[test]
    fn summary_is_the_highest_window_and_its_seqs() {
        let seqs = [1, 3, 5, 7, 9, 11, 13];
        let w = review_windows(7);
        let r = summarize(&seqs, &w, &[0.1, 0.2, 0.93, 0.93, 0.4], &cfg()).unwrap();
        assert_eq!((r.verdict, r.score), (Verdict::Scam, 0.93));
        assert_eq!(r.top_window_seqs, [1, 3, 5, 7, 9]); // the earliest of the tied windows: where it turned risky
        let safe = summarize(&seqs, &w, &[0.1, 0.0, 0.2, 0.3, 0.05], &cfg()).unwrap();
        assert_eq!((safe.verdict, safe.top_window_seqs), (Verdict::Safe, vec![3, 5, 7, 9, 11]));
        assert_eq!(summarize(&seqs, &[], &[], &cfg()), None);
    }

    #[test]
    fn a_review_without_a_live_reason_uses_berts() {
        let s = call(Verdict::Safe, vec![[Some(0.1), Some(0.1), Some(0.1)]], 0.1);
        let review = |verdict, score| Review { verdict, score, top_window_seqs: vec![1, 3, 5], reason: String::new() };
        let r = build_record(Some(&s), Some(review(Verdict::Scam, 0.8)), "script_finished", &cfg());
        assert_eq!(r.reason, "The conversation as a whole sounds like a scam");
        assert_eq!(build_record(Some(&s), Some(review(Verdict::Safe, 0.1)), "script_finished", &cfg()).reason, "");
    }

    #[tokio::test]
    async fn no_review_without_bert_or_with_too_few_lines() {
        let c = cfg();
        let three = lines(&["hello", "this is the bank", "read me the OTP"]);
        assert_eq!(end_of_call_review(None, &c, "c1", &three, "en").await, None, "BERT off -> fallback");
        // unreachable BERT: fails fast -> fallback, never a hang
        let down = BertClient::new("127.0.0.1:9", Duration::from_millis(300)).unwrap();
        let start = Instant::now();
        assert_eq!(end_of_call_review(Some(&down), &c, "c1", &three, "en").await, None);
        assert!(start.elapsed() < Duration::from_millis(1500));
        // two caller lines (blank ones don't count): BERT is never asked
        let two = lines(&["hello", "  ", "read me the OTP"]);
        assert_eq!(end_of_call_review(Some(&down), &c, "c1", &two, "en").await, None);
    }

    /// A stand-in BERT pod: P(scam) = 0.95 if the window mentions "OTP", 0.05 otherwise; "FAIL" -> error.
    pub(super) mod fake_bert {
        use crate::scam_detect::bert_service_server::{BertService, BertServiceServer};
        use crate::scam_detect::{BertScore, ContextRequest};
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc;
        use tonic::{Request, Response, Status};

        pub struct Fake {
            pub calls: Arc<AtomicUsize>,
        }

        #[tonic::async_trait]
        impl BertService for Fake {
            async fn score_context(&self, r: Request<ContextRequest>) -> Result<Response<BertScore>, Status> {
                self.calls.fetch_add(1, Ordering::SeqCst);
                let r = r.into_inner();
                assert!(r.lines.len() >= 3 && r.lines.len() <= 5, "window of 3-5 lines, got {}", r.lines.len());
                if r.lines.iter().any(|l| l.contains("FAIL")) {
                    return Err(Status::internal("boom"));
                }
                let score = if r.lines.iter().any(|l| l.contains("OTP")) { 0.95 } else { 0.05 };
                Ok(Response::new(BertScore { score, model_version: "fake".into() }))
            }
        }

        /// Starts the fake on a free local port; returns its address and the request counter.
        pub async fn start() -> (String, Arc<AtomicUsize>) {
            let addr = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap();
            let calls = Arc::new(AtomicUsize::new(0));
            let svc = BertServiceServer::new(Fake { calls: calls.clone() });
            tokio::spawn(tonic::transport::Server::builder().add_service(svc).serve(addr));
            for _ in 0..100 {
                if tokio::net::TcpStream::connect(addr).await.is_ok() {
                    break;
                }
                tokio::time::sleep(std::time::Duration::from_millis(10)).await;
            }
            (addr.to_string(), calls)
        }
    }

    #[tokio::test]
    async fn review_reads_the_whole_call_and_points_at_the_risky_part() {
        let (addr, calls) = fake_bert::start().await;
        let bert = BertClient::new(&addr, Duration::from_millis(300)).unwrap();
        let c = cfg();
        // a scam: the OTP request is caller line 6 of 8
        let scam = lines(&["hi", "this is your bank", "we saw a login", "on your account", "to secure it",
                           "read me the OTP we sent", "thank you", "goodbye"]);
        let r = end_of_call_review(Some(&bert), &c, "c1", &scam, "en").await.expect("review ran");
        assert_eq!((r.verdict, r.score), (Verdict::Scam, 0.95));
        assert_eq!(r.top_window_seqs, [3, 5, 7, 9, 11], "the first window that contains the OTP line (seq 11)");
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), 6, "one window per caller line from line 3");

        // a normal call ends SAFE, with the window seqs still given
        let normal = lines(&["hi", "it's the clinic", "about Tuesday", "see you then"]);
        let r = end_of_call_review(Some(&bert), &c, "c2", &normal, "en").await.unwrap();
        assert_eq!((r.verdict, r.score), (Verdict::Safe, 0.05));
        assert_eq!(r.top_window_seqs, [1, 3, 5]);

        // any window failing -> no review (the live verdict is used), not a partial one
        let broken = lines(&["hi", "this is your bank", "read me the OTP", "FAIL"]);
        assert_eq!(end_of_call_review(Some(&bert), &c, "c3", &broken, "en").await, None);
    }

    #[tokio::test]
    async fn a_long_call_is_reviewed_within_the_time_limit() {
        let (addr, calls) = fake_bert::start().await;
        let bert = BertClient::new(&addr, Duration::from_millis(300)).unwrap();
        let mut texts: Vec<String> = (0..200).map(|i| format!("caller line {i}")).collect();
        texts[150] = "read me the OTP".into();
        let long: Vec<(i64, String)> = texts.into_iter().enumerate().map(|(i, t)| (i as i64 + 1, t)).collect();
        let start = Instant::now();
        let r = end_of_call_review(Some(&bert), &cfg(), "c4", &long, "en").await.unwrap();
        assert!(start.elapsed() < cfg().end_review_timeout);
        assert_eq!(calls.load(std::sync::atomic::Ordering::SeqCst), MAX_REVIEW_WINDOWS);
        assert_eq!(r.verdict, Verdict::Scam, "the tiled windows still read line 151");
    }

    #[test]
    fn end_log_row_has_scores_and_no_text() {
        let r = EndRecord {
            final_verdict: Some(Verdict::Scam),
            call_score: 0.6,
            live_verdict: None,
            review: "fallback".into(),
            top_window_seqs: vec![],
            reason: "Asked for your OTP or money".into(),
            ended_by: "silence".into(),
        };
        let row: serde_json::Value = serde_json::from_str(&end_log_entry("c1", 42, &r, 3)).unwrap();
        assert_eq!(row["event"], "end");
        assert_eq!(row["call_score"], serde_json::json!(0.6));
        assert_eq!(row["live_verdict"], "UNKNOWN");
        let mut keys: Vec<_> = row.as_object().unwrap().keys().cloned().collect();
        keys.sort();
        assert_eq!(keys, ["call_id", "call_score", "ended_by", "event", "final_verdict", "live_verdict", "review", "review_ms", "time_ms"]);
    }
}

/// Against a real Redis: `REDIS_TEST_URL=redis://localhost:6379 cargo test`. Skipped (passes, with a note)
/// when REDIS_TEST_URL is not set. Every test uses its own call ids, so they can share one Redis.
#[cfg(test)]
mod redis_tests {
    use super::*;
    use crate::bert::SecondOpinion;

    async fn setup() -> Option<(Store, Settings, Metrics)> {
        let url = std::env::var("REDIS_TEST_URL").ok().filter(|u| !u.is_empty());
        let Some(url) = url else {
            eprintln!("REDIS_TEST_URL not set: skipping Redis test");
            return None;
        };
        let store = Store::connect(&url, &url).await;
        Some((store, Settings::from_lookup(|_| None).unwrap(), Metrics::default()))
    }

    fn id(name: &str) -> String {
        format!("test-{name}-{}", crate::now_ms())
    }

    async fn line(store: &Store, cfg: &Settings, call_id: &str, seq: u64, scores: policy::LineScores, now: i64) -> bool {
        let mut second = SecondOpinion::new(None, &[], "en");
        store.apply(call_id, seq, scores, now, 0, cfg, &mut second).await.unwrap().is_some()
    }

    async fn exists(store: &Store, key: &str) -> bool {
        let mut con = store.con_for_tests();
        redis::cmd("EXISTS").arg(key).query_async::<bool>(&mut con).await.unwrap()
    }

    async fn active(store: &Store, call_id: &str) -> bool {
        let mut con = store.con_for_tests();
        let score: Option<f64> = redis::cmd("ZSCORE").arg(crate::store::ACTIVE_KEY).arg(call_id).query_async(&mut con).await.unwrap();
        score.is_some()
    }

    #[tokio::test]
    async fn end_deletes_the_call_memory_and_keeps_only_the_tombstone_and_log() {
        let Some((store, cfg, m)) = setup().await else { return };
        let c = id("end");
        let now = crate::now_ms();
        // a money + impersonation line after the warm-up: SCAM by the money rule
        assert!(line(&store, &cfg, &c, 1, [Some(0.2), Some(0.95), Some(0.8)], now).await);
        assert!(exists(&store, &format!("call:{c}")).await);
        assert!(active(&store, &c).await);

        let e = end_call(&store, &cfg, &m, None, &c, "user_hung_up", &[(1, "give me the OTP".into())], "en", now + 1000)
            .await
            .unwrap();
        assert!(!e.already_ended);
        assert_eq!(e.record.final_verdict, Some(Verdict::Scam)); // warm-up override: 0.95 >= 0.9
        assert_eq!(e.record.review, "fallback");
        assert_eq!(e.record.reason, "Asked for your OTP or money");

        assert!(!exists(&store, &format!("call:{c}")).await, "call memory must be gone");
        assert!(!active(&store, &c).await);
        assert!(exists(&store, &format!("callend:{c}")).await);
        let mut con = store.con_for_tests();
        let log: Vec<String> = redis::cmd("LRANGE").arg(format!("calllog:{c}")).arg(0).arg(-1).query_async(&mut con).await.unwrap();
        assert_eq!(log.len(), 2);
        let last: serde_json::Value = serde_json::from_str(&log[1]).unwrap();
        assert_eq!((last["event"].as_str(), last["final_verdict"].as_str()), (Some("end"), Some("SCAM")));
        assert!(!log[1].contains("OTP"), "no transcript text in the verdict log");
        let tomb: std::collections::HashMap<String, String> =
            redis::cmd("HGETALL").arg(format!("callend:{c}")).query_async(&mut con).await.unwrap();
        assert!(tomb.values().all(|v| !v.contains("give me")), "no transcript text in the tombstone");
        let ttl: i64 = redis::cmd("TTL").arg(format!("callend:{c}")).query_async(&mut con).await.unwrap();
        assert!(ttl > 0 && ttl <= 3600);
    }

    #[tokio::test]
    async fn a_second_end_is_idempotent() {
        let Some((store, cfg, m)) = setup().await else { return };
        let c = id("twice");
        let now = crate::now_ms();
        line(&store, &cfg, &c, 1, [Some(0.1), Some(0.1), Some(0.1)], now).await;
        let first = end_call(&store, &cfg, &m, None, &c, "script_finished", &[], "", now).await.unwrap();
        let second = end_call(&store, &cfg, &m, None, &c, "user_hung_up", &[], "", now + 5).await.unwrap();
        assert!(!first.already_ended);
        assert!(second.already_ended);
        assert_eq!(second.record, first.record);
        assert_eq!(second.record.ended_by, "script_finished"); // the first end's value
        assert_eq!(first.record.final_verdict, Some(Verdict::Safe));
    }

    #[tokio::test]
    async fn ends_racing_each_other_run_once() {
        let Some((store, cfg, m)) = setup().await else { return };
        let c = id("race");
        let now = crate::now_ms();
        line(&store, &cfg, &c, 1, [Some(0.1), Some(0.1), Some(0.1)], now).await;
        let (a, b) = tokio::join!(
            end_call(&store, &cfg, &m, None, &c, "user_hung_up", &[], "", now),
            end_call(&store, &cfg, &m, None, &c, SILENCE, &[], "", now),
        );
        let (a, b) = (a.unwrap(), b.unwrap());
        assert_eq!([a.already_ended, b.already_ended].iter().filter(|x| !**x).count(), 1, "exactly one ran the end");
        assert_eq!(a.record, b.record);
    }

    #[tokio::test]
    async fn a_late_line_is_refused_and_does_not_bring_the_call_back() {
        let Some((store, cfg, m)) = setup().await else { return };
        let c = id("late");
        let now = crate::now_ms();
        line(&store, &cfg, &c, 1, [Some(0.1), Some(0.1), Some(0.1)], now).await;
        end_call(&store, &cfg, &m, None, &c, "user_hung_up", &[], "", now).await.unwrap();
        assert!(!line(&store, &cfg, &c, 2, [Some(0.9), Some(0.9), Some(0.9)], now + 100).await, "late line refused");
        assert!(!exists(&store, &format!("call:{c}")).await, "no new call memory");
        assert!(!active(&store, &c).await);
        // the stored result is unchanged by the late line
        let again = end_call(&store, &cfg, &m, None, &c, "user_hung_up", &[], "", now + 200).await.unwrap();
        assert_eq!(again.record.final_verdict, Some(Verdict::Safe));
    }

    #[tokio::test]
    async fn end_with_bert_runs_the_review_and_stores_only_scores() {
        let Some((store, cfg, m)) = setup().await else { return };
        let (addr, _) = super::tests::fake_bert::start().await;
        let bert = BertClient::new(&addr, Duration::from_millis(300)).unwrap();
        let c = id("review");
        let now = crate::now_ms();
        line(&store, &cfg, &c, 1, [Some(0.1), Some(0.1), Some(0.1)], now).await;
        let lines: Vec<(i64, String)> = ["hi", "this is your bank", "read me the OTP now", "thanks"]
            .iter().enumerate().map(|(i, t)| (2 * i as i64 + 1, t.to_string())).collect();
        let e = end_call(&store, &cfg, &m, Some(&bert), &c, "script_finished", &lines, "en", now + 20_000)
            .await
            .unwrap();
        assert_eq!((e.record.review.as_str(), e.record.final_verdict), ("bert", Some(Verdict::Scam)));
        assert_eq!(e.record.live_verdict, Some(Verdict::Safe));
        assert_eq!(e.record.top_window_seqs, [1, 3, 5]);
        assert_eq!(e.record.reason, "The conversation as a whole sounds like a scam");
        let stored = store.end_result(&c).await.unwrap().expect("stored");
        assert_eq!(stored, e.record);
        let mut con = store.con_for_tests();
        let tomb: std::collections::HashMap<String, String> =
            redis::cmd("HGETALL").arg(format!("callend:{c}")).query_async(&mut con).await.unwrap();
        assert!(tomb.values().all(|v| !v.contains("OTP") && !v.contains("bank")), "no transcript text stored");
    }

    #[tokio::test]
    async fn end_of_an_unknown_call_is_unknown_and_harmless() {
        let Some((store, cfg, m)) = setup().await else { return };
        let c = id("unknown");
        let e = end_call(&store, &cfg, &m, None, &c, "user_discarded", &[], "", crate::now_ms()).await.unwrap();
        assert_eq!((e.record.final_verdict, e.record.review.as_str()), (None, "fallback"));
        assert!(!exists(&store, &format!("call:{c}")).await);
    }

    #[tokio::test]
    async fn thirty_seconds_of_silence_ends_the_call() {
        let Some((store, cfg, m)) = setup().await else { return };
        assert_eq!(cfg.silence, Duration::from_secs(30));
        // Far in the past, so the sweeps below can't touch other tests' calls (they use the real clock).
        let t = crate::now_ms() - 10 * 24 * 3600 * 1000;
        let (quiet, talking) = (id("quiet"), id("talking"));
        line(&store, &cfg, &quiet, 1, [Some(0.2), Some(0.1), Some(0.1)], t).await;
        line(&store, &cfg, &talking, 1, [Some(0.2), Some(0.1), Some(0.1)], t).await;
        line(&store, &cfg, &talking, 3, [Some(0.2), Some(0.1), Some(0.1)], t + 20_000).await;

        sweep(&store, &cfg, &m, t + 29_000).await.unwrap(); // 29 s: nothing ends yet
        assert!(exists(&store, &format!("call:{quiet}")).await);

        sweep(&store, &cfg, &m, t + 31_000).await.unwrap(); // 31 s after quiet's last line, 11 s after talking's
        assert!(!exists(&store, &format!("call:{quiet}")).await, "quiet call ended by silence");
        assert!(exists(&store, &format!("call:{talking}")).await, "a call with a recent line keeps going");
        let r = store.end_result(&quiet).await.unwrap().expect("ended");
        assert_eq!((r.ended_by.as_str(), r.review.as_str()), ("silence", "fallback"));
        assert_eq!(r.final_verdict, Some(Verdict::Safe));

        sweep(&store, &cfg, &m, t + 51_000).await.unwrap();
        assert!(!exists(&store, &format!("call:{talking}")).await);
    }
}
