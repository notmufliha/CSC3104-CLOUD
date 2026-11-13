//! Gateway: the phone's only entry point. It takes one utterance at a time, sends caller lines to the three
//! workers for the utterance's language at the same time, passes this line's scores to the aggregator (which
//! keeps the call's memory in Redis and decides) and returns the call's current verdict.
//!
//!   POST /v1/utterance        {"call_id": "...", "seq": 1, "speaker": "caller", "text": "...", "lang": "en",
//!                              "t_ms": 22000}       (seq, lang and t_ms optional)
//!   POST /submit-transcript   {"transcript": "...", "language": "en"}   (older one-shot endpoint, no call memory)
//!   GET  /healthz
//!   POST /v1/report           Workstream H: a consented report of a finished call, forwarded to the internal
//!                              review service (the phone never reaches that service directly)
//!   POST /v1/call/end         Workstream B: the call is over. Forwarded to the aggregator's EndCall, which
//!                              returns the final verdict and clears the call's memory. Lines sent after the end
//!                              are answered {"ignored": true, "reason": "call_ended"} and never scored.
//!                              Contract: docs/workstream-b-contract.md
//!   POST /v1/verify           Workstream G.3: the app's Hybrid mode asks BERT about the caller's last 5 lines,
//!                              masked on the phone. Stateless, nothing stored (see verify.rs)
//!
//! Logs: one "timing" line per worker call and one per aggregator call (milliseconds, outcome), plus one per
//! request. Transcript text is never logged.

mod calls;
mod config;
mod language;
mod verify;

use std::collections::{BTreeMap, HashMap};
use std::time::{Duration, Instant};

use actix_web::{error, get, post, web, App, HttpResponse, HttpServer};
use serde::{Deserialize, Serialize};
use tonic::transport::{Channel, Endpoint};

pub mod scam_detect {
    tonic::include_proto!("scam_detect");
}

use calls::{Answer, CallStore, Ended, Reason};
use config::{Settings, SIGNALS};
use scam_detect::aggregator_service_client::AggregatorServiceClient;
use scam_detect::worker_service_client::WorkerServiceClient;
use scam_detect::{AggregateRequest, CallerLine, EndCallRequest, TranscriptRequest, WorkerScore};

const MAX_CALL_ID_LEN: usize = 128;
const MAX_TEXT_CHARS: usize = 10_000;
/// A report is the call's caller lines (at most 200 x 2000 characters, checked again by the review service).
const MAX_REPORT_BYTES: usize = 512 * 1024;
const REVIEW_TIMEOUT: Duration = Duration::from_secs(5);
/// Workstream B: an end message carries the call's caller lines, same caps as a report.
const MAX_END_LINES: usize = 200;
const MAX_END_LINE_CHARS: usize = 2000;
const MAX_END_BYTES: usize = 512 * 1024;
/// What the phone may say ended the call. "silence" is the aggregator's own timeout, never sent by the phone.
const ENDED_BY_PHONE: [&str; 3] = ["script_finished", "user_hung_up", "user_discarded"];
/// Answer to a line that arrives after its call ended (B.6).
const IGNORED_CALL_ENDED: &str = "call_ended";

struct AppState {
    settings: Settings,
    /// (language, signal) -> channel. Built once at start-up and reused (a Channel clone is cheap).
    workers: HashMap<(&'static str, &'static str), Channel>,
    aggregator: Channel,
    calls: CallStore,
    http: hyper::Client<hyper::client::HttpConnector>,
    /// Workstream G.3: BERT verifier for POST /v1/verify; None when BERT_ADDR is not set.
    bert: Option<Channel>,
}

impl AppState {
    /// One-shot transcript: BERT reads its end (no call memory), unless BERT context is switched off.
    fn calls_context_one_shot(&self, transcript: &str) -> Vec<String> {
        if self.settings.bert_context_lines == 0 {
            return vec![];
        }
        let n = transcript.chars().count();
        vec![transcript.chars().skip(n.saturating_sub(2000)).collect()]
    }
}

fn lazy_channel(addr: &str) -> Channel {
    Endpoint::from_shared(addr.to_string())
        .unwrap_or_else(|e| panic!("invalid gRPC address '{addr}': {e}"))
        .connect_timeout(Duration::from_millis(500))
        .connect_lazy()
}

// ------------------------------------------------------------------------------------------- requests

#[derive(Deserialize)]
struct UtteranceRequest {
    call_id: String,
    /// Line number within the call (1, 2, 3, ...). Optional. Echoed back; a resent caller line with a seq that
    /// was already scored is not scored again.
    #[serde(default)]
    seq: Option<u64>,
    speaker: String,
    text: String,
    #[serde(default)]
    lang: Option<String>,
    /// Milliseconds since the call started, as the phone measured it. Optional; without it the aggregator uses
    /// its own clock since the call's first line. Lets a scripted replay say when each line was spoken.
    #[serde(default)]
    t_ms: Option<u64>,
}

#[derive(Serialize)]
struct UtteranceResponse {
    call_id: String,
    seq: Option<u64>,
    /// false for receiver lines and resent lines: no worker was called, the verdict fields are the call's
    /// current ones (null verdict if none yet).
    scored: bool,
    /// true when this (call_id, seq) was already scored: a resend.
    duplicate: bool,
    verdict: Option<String>,
    live_alert: Option<String>,
    final_verdict: Option<String>,
    combined_score: Option<f32>,
    /// Which signals fired and why, strongest first (empty when SAFE).
    reasons: Vec<Reason>,
    /// One suggested action for the user; empty when SAFE.
    action: String,
    /// True in the call's first seconds, when the aggregator stays quiet unless a score is very high.
    warming_up: bool,
    /// False when fewer than 2 workers answered this line (verdict UNSURE, "not enough data").
    quorum_met: bool,
    language: Option<&'static str>,
    /// "phone" (from the lang field) or "detected" (from the share of Chinese characters).
    language_source: Option<&'static str>,
    /// This line's score per signal; null = that worker did not answer in time.
    scores: BTreeMap<&'static str, Option<f32>>,
    /// Workstream H.8: the model version each worker that answered used (e.g. {"urgency": "v5"}). A worker
    /// that doesn't report a version is left out.
    model_versions: BTreeMap<&'static str, String>,
    missing: Vec<&'static str>,
    /// Each signal's highest score over the call's recent caller lines: what the verdict is built from.
    recent_scores: BTreeMap<String, f32>,
    /// The workers' weighted vote alone (combined_score = this, or its average with BERT when BERT answered).
    fast_score: Option<f32>,
    /// The BERT second opinion's score for the call's recent lines; null when it was not asked or did not answer.
    bert_score: Option<f32>,
    /// "ok" | "timeout" | "error" | "skipped" (not needed: the fast vote was clearly safe) | "off" | "no_context"
    bert_status: Option<String>,
    caller_lines: u32,
    latency_ms: u64,
    /// Workstream B.6: true when the call had already ended. The line was not scored; the phone treats it as
    /// "no change".
    ignored: bool,
    /// Why the line was ignored ("call_ended"); absent when it was not.
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<&'static str>,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

fn bad_request(msg: impl Into<String>) -> HttpResponse {
    HttpResponse::BadRequest().json(ErrorResponse { error: msg.into() })
}

// ------------------------------------------------------------------------------------------- workers

/// Calls one worker, bounded by the worker timeout. Logs how long it took; never the text.
async fn call_worker(
    state: &AppState,
    call_id: &str,
    lang: &'static str,
    signal: &'static str,
    text: String,
) -> (&'static str, Option<f32>, String) {
    let start = Instant::now();
    let mut client = WorkerServiceClient::new(state.workers[&(lang, signal)].clone());
    let request = TranscriptRequest { transcript: text, language: lang.to_string() };
    let result = tokio::time::timeout(state.settings.worker_timeout, client.classify_transcript(request)).await;
    let ms = start.elapsed().as_millis() as u64;
    let (score, version, outcome, code) = match result {
        Ok(Ok(reply)) => {
            let r = reply.into_inner();
            (Some(r.score), r.model_version, "ok", String::new())
        }
        Ok(Err(status)) => (None, String::new(), "error", format!("{:?}", status.code())),
        Err(_) => (None, String::new(), "timeout", String::new()),
    };
    tracing::info!(target: "timing", stage = "worker", call_id, lang, signal, ms, outcome, code = %code,
                   model_version = %version);
    (signal, score, version)
}

/// All three workers for `lang` at the same time; whatever arrives within the timeout is used.
async fn fan_out(
    state: &AppState,
    call_id: &str,
    lang: &'static str,
    text: &str,
) -> Vec<(&'static str, Option<f32>, String)> {
    let (a, b, c) = tokio::join!(
        call_worker(state, call_id, lang, SIGNALS[0], text.to_string()),
        call_worker(state, call_id, lang, SIGNALS[1], text.to_string()),
        call_worker(state, call_id, lang, SIGNALS[2], text.to_string()),
    );
    vec![a, b, c]
}

/// Sends this line's scores (only the workers that answered; possibly none) to the aggregator, which merges
/// them into the call's memory and decides. An empty `call_id` is a one-shot with no memory.
#[allow(clippy::too_many_arguments)]
async fn aggregate(
    state: &AppState,
    call_id: &str,
    seq: Option<u64>,
    t_ms: Option<u64>,
    lang: &'static str,
    scores: &[(&'static str, f32)],
    context: Vec<String>,
) -> Result<Answer, String> {
    let start = Instant::now();
    let mut client = AggregatorServiceClient::new(state.aggregator.clone());
    let request = AggregateRequest {
        scores: scores
            .iter()
            .map(|(signal, score)| WorkerScore {
                signal: signal.to_string(),
                score: *score,
                language: lang.to_string(),
                model_version: String::new(), // the aggregator does not use it
            })
            .collect(),
        call_id: call_id.to_string(),
        seq: seq.unwrap_or(0),
        call_time_ms: t_ms.map(|t| t.min(i64::MAX as u64) as i64).unwrap_or(0),
        context,
    };
    let result = tokio::time::timeout(state.settings.aggregator_timeout, client.aggregate(request)).await;
    let ms = start.elapsed().as_millis() as u64;
    let out = match result {
        Ok(Ok(reply)) => {
            let r = reply.into_inner();
            let verdict = scam_detect::Verdict::try_from(r.verdict)
                .map(|v| v.as_str_name().to_string())
                .unwrap_or_else(|_| "VERDICT_UNSPECIFIED".to_string());
            let live_alert = scam_detect::Verdict::try_from(r.live_alert)
                .map(|v| v.as_str_name().to_string())
                .unwrap_or_else(|_| "VERDICT_UNSPECIFIED".to_string());
            Ok(Answer {
                verdict,
                live_alert,
                final_verdict: r.final_verdict,
                combined_score: r.combined_score,
                reasons: r
                    .reasons
                    .into_iter()
                    .map(|x| Reason { signal: x.signal, score: x.score, text: x.text })
                    .collect(),
                action: r.action,
                recent_scores: r.recent_scores.into_iter().collect(),
                warming_up: r.warming_up,
                quorum_met: r.quorum_met,
                caller_lines: r.caller_lines,
                fast_score: r.fast_score,
                bert_score: r.bert_score,
                bert_status: r.bert_status,
                bert_model_version: r.bert_model_version,
                call_ended: r.call_ended,
            })
        }
        Ok(Err(status)) => Err(format!("aggregator error: {:?}", status.code())),
        Err(_) => Err("aggregator timed out".to_string()),
    };
    let outcome = if out.is_ok() { "ok" } else { "error" };
    let bert = out.as_ref().map(|a| a.bert_status.as_str()).unwrap_or("");
    tracing::info!(target: "timing", stage = "aggregator", call_id, lang, ms, outcome, bert);
    out
}

// ------------------------------------------------------------------------------------------- handlers

#[post("/v1/utterance")]
async fn utterance(state: web::Data<AppState>, body: web::Json<UtteranceRequest>) -> HttpResponse {
    let start = Instant::now();
    let req = body.into_inner();
    let call_id = req.call_id.trim().to_string();
    if call_id.is_empty() || call_id.len() > MAX_CALL_ID_LEN {
        return bad_request(format!("call_id must be 1-{MAX_CALL_ID_LEN} characters"));
    }

    let seq = req.seq;
    // A line that is not scored (receiver line, a caller line resent with a seq already scored, or any line
    // after the call ended): answer with the call's current state (the aggregator's last answer), no worker call.
    let unscored = |duplicate: bool, ignored: bool| {
        let last = state.calls.last_answer(&call_id);
        let a = last.clone().unwrap_or_default();
        HttpResponse::Ok().json(UtteranceResponse {
            call_id: call_id.clone(),
            seq,
            scored: false,
            duplicate,
            verdict: last.as_ref().map(|a| a.verdict.clone()),
            live_alert: last.as_ref().map(|a| a.live_alert.clone()),
            final_verdict: last.as_ref().map(|a| a.final_verdict.clone()),
            combined_score: last.as_ref().map(|a| a.combined_score),
            reasons: a.reasons,
            action: a.action,
            warming_up: a.warming_up,
            quorum_met: a.quorum_met,
            language: None,
            language_source: None,
            scores: BTreeMap::new(),
            model_versions: BTreeMap::new(),
            missing: vec![],
            recent_scores: a.recent_scores,
            fast_score: last.as_ref().map(|a| a.fast_score),
            bert_score: a.bert_score,
            bert_status: last.as_ref().map(|a| a.bert_status.clone()),
            caller_lines: a.caller_lines,
            latency_ms: start.elapsed().as_millis() as u64,
            ignored,
            reason: ignored.then_some(IGNORED_CALL_ENDED),
        })
    };

    // B.6 a line after the call ended (POST /v1/call/end already came): ignored, no worker call.
    if state.calls.is_ended(&call_id) {
        tracing::warn!(call_id = %call_id, seq, "late line after call ended: ignored, not scored");
        return unscored(false, true);
    }

    // 2.2 receiver lines: no worker call, just the call's last verdict
    if !req.speaker.trim().eq_ignore_ascii_case("caller") {
        if let Some(s) = seq {
            state.calls.mark_seq(&call_id, s);
        }
        tracing::info!(call_id = %call_id, seq, speaker = "receiver", "receiver line: no workers called");
        return unscored(false, false);
    }

    if req.text.trim().is_empty() {
        return bad_request("text must not be empty");
    }
    if req.text.chars().count() > MAX_TEXT_CHARS {
        return bad_request(format!("text longer than {MAX_TEXT_CHARS} characters; send one utterance at a time"));
    }

    // 2.3 language: the phone's lang field, else >30% Chinese characters -> zh
    let (lang, source) = match language::choose(req.lang.as_deref(), &req.text) {
        Ok(x) => x,
        Err(e) => return bad_request(e),
    };

    // A resent caller line (same seq) is scored only once.
    if let Some(s) = seq {
        if !state.calls.mark_seq(&call_id, s) {
            tracing::info!(call_id = %call_id, seq = s, "resent line (seq already scored): no workers called");
            return unscored(true, false);
        }
    }
    // If scoring this line fails, forget its seq so the phone's retry is scored.
    let forget_seq = || {
        if let Some(s) = seq {
            state.calls.unmark_seq(&call_id, s);
        }
    };

    // 2.4 + 2.5 fan-out with a timeout
    let results = fan_out(&state, &call_id, lang, &req.text).await;
    let arrived: Vec<(&'static str, f32)> = results.iter().filter_map(|(s, p, _)| p.map(|p| (*s, p))).collect();
    let missing: Vec<&'static str> = results.iter().filter(|(_, p, _)| p.is_none()).map(|(s, _, _)| *s).collect();
    let mut model_versions: BTreeMap<&'static str, String> =
        results.iter().filter(|(_, _, v)| !v.is_empty()).map(|(s, _, v)| (*s, v.clone())).collect();

    if arrived.is_empty() {
        tracing::warn!(call_id = %call_id, seq, lang, ?missing, "no worker answered; the aggregator decides (quorum)");
    }

    // 3.x the aggregator merges this line into the call's memory (Redis) and decides. Always asked, even when
    // no worker answered: then its quorum rule answers UNSURE ("not enough data") unless the BERT second
    // opinion stands in, or SCAM if already red. The call's last caller lines go along for BERT (7.4).
    let n = state.settings.bert_context_lines;
    let context = state.calls.context_with(&call_id, &req.text, n);
    let answer = match aggregate(&state, &call_id, seq, req.t_ms, lang, &arrived, context).await {
        Ok(a) => a,
        Err(e) => {
            forget_seq();
            return HttpResponse::BadGateway().json(ErrorResponse { error: e });
        }
    };
    // B.6 backstop: the aggregator's tombstone says the call ended (its silence timeout, or a line that was
    // already on its way when the end came). The line was not stored; remember the end here too.
    if answer.call_ended {
        state.calls.mark_ended(&call_id, None);
        tracing::warn!(call_id = %call_id, seq, "late line after call ended (aggregator): ignored, not scored");
        return unscored(false, true);
    }
    state.calls.push_context(&call_id, &req.text, n);
    state.calls.set_answer(&call_id, answer.clone());

    if !answer.bert_model_version.is_empty() {
        model_versions.insert("bert", answer.bert_model_version.clone());
    }
    let latency_ms = start.elapsed().as_millis() as u64;
    tracing::info!(target: "timing", stage = "request", call_id = %call_id, seq, lang, ms = latency_ms,
                   verdict = %answer.verdict, missing = missing.len());
    HttpResponse::Ok().json(UtteranceResponse {
        call_id,
        seq,
        scored: true,
        duplicate: false,
        verdict: Some(answer.verdict),
        live_alert: Some(answer.live_alert),
        final_verdict: Some(answer.final_verdict),
        combined_score: Some(answer.combined_score),
        reasons: answer.reasons,
        action: answer.action,
        warming_up: answer.warming_up,
        quorum_met: answer.quorum_met,
        language: Some(lang),
        language_source: Some(match source {
            language::Source::Phone => "phone",
            language::Source::Detected => "detected",
        }),
        scores: results.into_iter().map(|(s, p, _)| (s, p)).collect(),
        model_versions,
        missing,
        recent_scores: answer.recent_scores,
        fast_score: Some(answer.fast_score),
        bert_score: answer.bert_score,
        bert_status: Some(answer.bert_status),
        caller_lines: answer.caller_lines,
        latency_ms,
        ignored: false,
        reason: None,
    })
}

// ------------------------------------------------------------------------------------------- B: call end

#[derive(Deserialize)]
struct EndLine {
    seq: i64,
    text: String,
}

#[derive(Deserialize)]
struct EndRequest {
    call_id: String,
    /// Optional; "end" if given.
    #[serde(default)]
    event: Option<String>,
    ended_by: String,
    #[serde(default)]
    last_seq: Option<i64>,
    /// The call's caller lines, for the end-of-call review. Passed on, never stored or logged here.
    #[serde(default)]
    caller_lines: Vec<EndLine>,
    /// Same as /v1/utterance's lang; without it the language is detected from the lines.
    #[serde(default)]
    lang: Option<String>,
}

#[derive(Serialize)]
struct EndResponse {
    call_id: String,
    /// SAFE | UNSURE | SCAM, or UNKNOWN when no caller line of this call was ever scored.
    final_verdict: String,
    call_score: f32,
    live_verdict: String,
    /// "bert" | "fallback"
    review: String,
    top_window_seqs: Vec<i64>,
    reason: String,
    ended_by: String,
    /// True when the call had already ended: this is the first end's result, nothing was run again.
    already_ended: bool,
    latency_ms: u64,
    /// Set when the aggregator could not be reached: the fields are the gateway's last known verdict.
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

/// Proto Verdict -> the name the phone sees. VERDICT_UNSPECIFIED (call never seen) -> "UNKNOWN".
fn end_verdict_name(v: i32) -> String {
    match scam_detect::Verdict::try_from(v) {
        Ok(scam_detect::Verdict::Safe) => "SAFE",
        Ok(scam_detect::Verdict::Unsure) => "UNSURE",
        Ok(scam_detect::Verdict::Scam) => "SCAM",
        _ => "UNKNOWN",
    }
    .to_string()
}

/// Checks an end message. Returns the language for the review ("" = no lines, nothing to detect).
fn validate_end(req: &EndRequest) -> Result<&'static str, String> {
    let call_id = req.call_id.trim();
    if call_id.is_empty() || call_id.len() > MAX_CALL_ID_LEN {
        return Err(format!("call_id must be 1-{MAX_CALL_ID_LEN} characters"));
    }
    if let Some(e) = &req.event {
        if e.trim() != "end" {
            return Err(format!("event must be \"end\", got \"{e}\""));
        }
    }
    if !ENDED_BY_PHONE.contains(&req.ended_by.trim()) {
        return Err(format!("ended_by must be one of {ENDED_BY_PHONE:?}"));
    }
    if req.caller_lines.len() > MAX_END_LINES {
        return Err(format!("at most {MAX_END_LINES} caller lines"));
    }
    if req.caller_lines.iter().any(|l| l.text.chars().count() > MAX_END_LINE_CHARS) {
        return Err(format!("each caller line must be at most {MAX_END_LINE_CHARS} characters"));
    }
    let has_text = req.caller_lines.iter().any(|l| !l.text.trim().is_empty());
    if req.lang.as_deref().is_some_and(|l| !l.trim().is_empty()) || has_text {
        let joined: String = req.caller_lines.iter().map(|l| l.text.as_str()).collect::<Vec<_>>().join(" ");
        return language::choose(req.lang.as_deref(), &joined).map(|(l, _)| l);
    }
    Ok("")
}

/// B.1/B.2: the phone says the call is over. The aggregator runs the end-of-call review (or falls back to the
/// live verdict), clears the call's memory and returns the final verdict. Always 200 for a well-formed request:
/// if the aggregator can't be reached the answer is the gateway's last known verdict, review "fallback".
async fn call_end(state: web::Data<AppState>, body: web::Json<EndRequest>) -> HttpResponse {
    let start = Instant::now();
    let req = body.into_inner();
    let language = match validate_end(&req) {
        Ok(l) => l,
        Err(e) => return bad_request(e),
    };
    let call_id = req.call_id.trim().to_string();
    let ended_by = req.ended_by.trim().to_string();
    let n_lines = req.caller_lines.len();
    let respond = |e: Ended, already_ended: bool, error: Option<String>| {
        HttpResponse::Ok().json(EndResponse {
            call_id: call_id.clone(),
            final_verdict: e.final_verdict,
            call_score: e.call_score,
            live_verdict: e.live_verdict,
            review: e.review,
            top_window_seqs: e.top_window_seqs,
            reason: e.reason,
            ended_by: e.ended_by,
            already_ended,
            latency_ms: start.elapsed().as_millis() as u64,
            error,
        })
    };

    // Idempotent: this gateway already has the result, so no aggregator call is needed.
    if let Some(done) = state.calls.end_result(&call_id) {
        tracing::info!(target: "timing", stage = "end", call_id = %call_id, ms = start.elapsed().as_millis() as u64,
                       outcome = "already_ended", ended_by = %ended_by);
        return respond(done, true, None);
    }
    // From here on the call is over for this gateway: lines still in flight get "ignored".
    state.calls.mark_ended(&call_id, None);

    let request = EndCallRequest {
        call_id: call_id.clone(),
        ended_by: ended_by.clone(),
        last_seq: req.last_seq.unwrap_or(0),
        caller_lines: req.caller_lines.into_iter().map(|l| CallerLine { seq: l.seq, text: l.text }).collect(),
        language: language.to_string(),
    };
    let mut client = AggregatorServiceClient::new(state.aggregator.clone());
    let result = tokio::time::timeout(state.settings.end_call_timeout, client.end_call(request)).await;
    let ms = start.elapsed().as_millis() as u64;
    match result {
        Ok(Ok(reply)) => {
            let r = reply.into_inner();
            let ended = Ended {
                final_verdict: end_verdict_name(r.final_verdict),
                call_score: r.call_score,
                live_verdict: end_verdict_name(r.live_verdict),
                review: r.review,
                top_window_seqs: r.top_window_seqs,
                reason: r.reason,
                ended_by: r.ended_by,
            };
            tracing::info!(target: "timing", stage = "end", call_id = %call_id, ms, outcome = "ok",
                           ended_by = %ended_by, final_verdict = %ended.final_verdict, review = %ended.review,
                           already_ended = r.already_ended, lines = n_lines);
            state.calls.mark_ended(&call_id, Some(ended.clone()));
            respond(ended, r.already_ended, None)
        }
        failed => {
            let (outcome, code) = match &failed {
                Ok(Err(status)) => ("error", format!("{:?}", status.code())),
                _ => ("timeout", String::new()),
            };
            tracing::warn!(target: "timing", stage = "end", call_id = %call_id, ms, outcome, code = %code,
                           ended_by = %ended_by, "aggregator EndCall failed: answering with the last known verdict");
            // Fallback = what the gateway last heard from the aggregator for this call.
            let last = state.calls.last_answer(&call_id);
            let live = last.as_ref().map(|a| a.verdict.clone()).unwrap_or_else(|| "UNKNOWN".to_string());
            let reason = last
                .as_ref()
                .filter(|a| a.verdict != "SAFE")
                .and_then(|a| a.reasons.first())
                .map(|r| r.text.clone())
                .unwrap_or_default();
            let fallback = Ended {
                final_verdict: live.clone(),
                call_score: last.as_ref().map(|a| a.combined_score).unwrap_or(0.0),
                live_verdict: live,
                review: "fallback".to_string(),
                top_window_seqs: vec![],
                reason,
                ended_by,
            };
            respond(fallback, false, Some("aggregator unavailable".to_string()))
        }
    }
}

#[derive(Deserialize)]
struct SubmitTranscriptRequest {
    transcript: String,
    language: Option<String>,
}

/// Older one-shot endpoint (whole transcript, no call memory), kept for existing curl examples.
#[post("/submit-transcript")]
async fn submit_transcript(state: web::Data<AppState>, body: web::Json<SubmitTranscriptRequest>) -> HttpResponse {
    let (lang, _) = match language::choose(body.language.as_deref(), &body.transcript) {
        Ok(x) => x,
        Err(e) => return bad_request(e),
    };
    let results = fan_out(&state, "-", lang, &body.transcript).await;
    let arrived: Vec<(&'static str, f32)> = results.iter().filter_map(|(s, p, _)| p.map(|p| (*s, p))).collect();
    // empty call_id = one-shot: the aggregator judges these scores alone, with no call memory
    let context = state.calls_context_one_shot(&body.transcript);
    match aggregate(&state, "", None, None, lang, &arrived, context).await {
        Ok(a) => HttpResponse::Ok().json(serde_json::json!({
            "verdict": a.verdict,
            "live_alert": a.live_alert,
            "final_verdict": a.final_verdict,
            "combined_score": a.combined_score,
            "fast_score": a.fast_score,
            "bert_score": a.bert_score,
            "bert_status": a.bert_status,
            "reasons": a.reasons,
            "action": a.action,
            "signals_received": arrived.iter().map(|(s, _)| *s).collect::<Vec<_>>(),
            "signals_expected": 3,
        })),
        Err(e) => HttpResponse::BadGateway().json(ErrorResponse { error: e }),
    }
}

/// Workstream H.3/H.4: the user tapped "Report" or "This was fine" after a call and agreed to share its caller
/// lines. Checked here (consent, size), then forwarded unchanged to the internal review service, which masks
/// the text again and stores it encrypted. The text is never logged here.
async fn report(state: web::Data<AppState>, body: web::Json<serde_json::Value>) -> HttpResponse {
    let Some(base) = state.settings.review_url.as_deref() else {
        return HttpResponse::ServiceUnavailable().json(ErrorResponse { error: "reporting is not set up".into() });
    };
    let v = body.into_inner();
    if v.get("consent").and_then(|c| c.as_bool()) != Some(true) {
        return bad_request("consent is required: nothing was sent");
    }
    let n_lines = v.get("lines").and_then(|l| l.as_array()).map(|l| l.len()).unwrap_or(0);
    if n_lines == 0 {
        return bad_request("a report needs at least one caller line");
    }
    let kind = v.get("kind").and_then(|k| k.as_str()).unwrap_or("").to_string();
    let request = match hyper::Request::post(format!("{base}/reports"))
        .header("content-type", "application/json")
        .body(hyper::Body::from(v.to_string()))
    {
        Ok(r) => r,
        Err(e) => return HttpResponse::InternalServerError().json(ErrorResponse { error: e.to_string() }),
    };
    let start = Instant::now();
    let reply = tokio::time::timeout(REVIEW_TIMEOUT, async {
        let resp = state.http.request(request).await?;
        let status = resp.status().as_u16();
        let bytes = hyper::body::to_bytes(resp.into_body()).await?;
        Ok::<_, hyper::Error>((status, bytes))
    })
    .await;
    let ms = start.elapsed().as_millis() as u64;
    match reply {
        Ok(Ok((status, bytes))) => {
            tracing::info!(target: "timing", stage = "review", ms, status, kind = %kind, lines = n_lines, "report");
            let code = actix_web::http::StatusCode::from_u16(status)
                .unwrap_or(actix_web::http::StatusCode::BAD_GATEWAY);
            HttpResponse::build(code).content_type("application/json").body(bytes)
        }
        Ok(Err(e)) => {
            tracing::warn!(target: "timing", stage = "review", ms, error = %e, "report not delivered");
            HttpResponse::BadGateway().json(ErrorResponse { error: "review service unavailable".into() })
        }
        Err(_) => {
            tracing::warn!(target: "timing", stage = "review", ms, "report timed out");
            HttpResponse::GatewayTimeout().json(ErrorResponse { error: "review service timed out".into() })
        }
    }
}

#[get("/healthz")]
async fn healthz(state: web::Data<AppState>) -> HttpResponse {
    HttpResponse::Ok().json(serde_json::json!({ "status": "ok", "calls_in_memory": state.calls.len() }))
}

// ------------------------------------------------------------------------------------------- main

fn init_logging() {
    let filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    if std::env::var("LOG_FORMAT").map(|v| v.eq_ignore_ascii_case("json")).unwrap_or(false) {
        tracing_subscriber::fmt().with_env_filter(filter).json().init();
    } else {
        // colours only on a real terminal, so `docker logs` stays plain text that scripts can parse
        let ansi = std::io::IsTerminal::is_terminal(&std::io::stdout());
        tracing_subscriber::fmt().with_env_filter(filter).with_ansi(ansi).init();
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    init_logging();
    let settings = Settings::from_env();
    let workers = settings.workers.iter().map(|(l, s, a)| ((*l, *s), lazy_channel(a))).collect();
    for (lang, signal, addr) in &settings.workers {
        tracing::info!(lang, signal, addr = %addr, "worker address");
    }
    tracing::info!(addr = %settings.aggregator_addr, worker_timeout_ms = settings.worker_timeout.as_millis() as u64,
                   aggregator_timeout_ms = settings.aggregator_timeout.as_millis() as u64,
                   end_call_timeout_ms = settings.end_call_timeout.as_millis() as u64,
                   bert_context_lines = settings.bert_context_lines, "aggregator address");
    let bind_addr = settings.bind_addr.clone();
    let state = web::Data::new(AppState {
        aggregator: lazy_channel(&settings.aggregator_addr),
        calls: CallStore::new(settings.call_ttl, settings.max_calls),
        http: hyper::Client::new(),
        bert: settings.bert_addr.as_deref().map(lazy_channel),
        workers,
        settings,
    });
    tracing::info!(bert_addr = state.settings.bert_addr.as_deref().unwrap_or("(off)"),
                   verify_timeout_ms = state.settings.verify_timeout.as_millis() as u64, "verify (hybrid mode)");
    tracing::info!(review_url = state.settings.review_url.as_deref().unwrap_or("(off)"), "report forwarding");
    tracing::info!(addr = %bind_addr, "starting gateway");

    HttpServer::new(move || App::new().app_data(state.clone()).configure(routes))
        .bind(bind_addr)?
        .run()
        .await
}

/// Malformed or too large JSON -> 400 with a JSON body (the default is plain text).
fn json_config(limit: usize) -> web::JsonConfig {
    web::JsonConfig::default().limit(limit).error_handler(|err, _| {
        let msg = err.to_string();
        error::InternalError::from_response(err, HttpResponse::BadRequest().json(ErrorResponse { error: msg })).into()
    })
}

/// Every route, shared by main and the tests.
fn routes(cfg: &mut web::ServiceConfig) {
    cfg.app_data(json_config(64 * 1024))
        .service(utterance)
        .service(submit_transcript)
        .service(healthz)
        .service(web::resource("/v1/report").app_data(json_config(MAX_REPORT_BYTES)).route(web::post().to(report)))
        .service(web::resource("/v1/call/end").app_data(json_config(MAX_END_BYTES)).route(web::post().to(call_end)))
        .service(web::resource("/v1/verify").app_data(json_config(verify::MAX_BYTES)).route(web::post().to(verify::verify)));
}

#[cfg(test)]
mod tests {
    use super::*;
    use actix_web::test as atest;

    /// Nothing listens on port 1: every worker or aggregator call fails at once ("connection refused"). So a
    /// 200 below that needed no worker / aggregator call proves that none was made.
    const NOWHERE: &str = "http://127.0.0.1:1";

    fn state() -> web::Data<AppState> {
        let mut workers = HashMap::new();
        for lang in language::SUPPORTED {
            for signal in SIGNALS {
                workers.insert((lang, signal), lazy_channel(NOWHERE));
            }
        }
        web::Data::new(AppState {
            settings: Settings {
                bind_addr: String::new(),
                aggregator_addr: NOWHERE.into(),
                workers: vec![],
                worker_timeout: Duration::from_millis(300),
                aggregator_timeout: Duration::from_millis(300),
                end_call_timeout: Duration::from_millis(300),
                bert_context_lines: 5,
                call_ttl: Duration::from_secs(60),
                max_calls: 100,
                review_url: None,
                bert_addr: None,
                verify_timeout: Duration::from_millis(300),
            },
            workers,
            aggregator: lazy_channel(NOWHERE),
            calls: CallStore::new(Duration::from_secs(60), 100),
            http: hyper::Client::new(),
            bert: None,
        })
    }

    // ------------------------------------------------------------------ Workstream G.3: POST /v1/verify

    fn with_bert(addr: &str) -> web::Data<AppState> {
        let s = state();
        let mut inner = std::sync::Arc::try_unwrap(s.into_inner()).ok().expect("only reference");
        inner.bert = Some(lazy_channel(addr));
        web::Data::new(inner)
    }

    #[actix_web::test]
    async fn verify_is_off_without_bert_addr() {
        let (code, body) = post(&state(), "/v1/verify", serde_json::json!({"lines": ["Give me the [NUMBER] OTP"]})).await;
        assert_eq!(code, 503);
        assert!(body["error"].as_str().unwrap().contains("BERT_ADDR"));
    }

    #[actix_web::test]
    async fn verify_rejects_bad_requests_before_calling_bert() {
        let s = with_bert(NOWHERE);
        for body in [
            serde_json::json!({"lines": []}),
            serde_json::json!({"lines": ["1", "2", "3", "4", "5", "6"]}),
            serde_json::json!({"lines": ["hello"], "lang": "fr"}),
            serde_json::json!({"text": "not the right shape"}),
        ] {
            let (code, _) = post(&s, "/v1/verify", body).await;
            assert_eq!(code, 400);
        }
    }

    #[actix_web::test]
    async fn verify_reports_a_failed_bert_as_502_so_the_phone_keeps_its_own_answer() {
        let (code, body) = post(&with_bert(NOWHERE), "/v1/verify",
                                serde_json::json!({"lines": ["This is the police", "Read me the [NUMBER] code"], "lang": "en"})).await;
        assert!(code == 502 || code == 504, "got {code}");
        assert!(body["error"].is_string());
    }

    async fn post(state: &web::Data<AppState>, path: &str, body: serde_json::Value) -> (u16, serde_json::Value) {
        let app = atest::init_service(App::new().app_data(state.clone()).configure(routes)).await;
        let req = atest::TestRequest::post().uri(path).set_json(body).to_request();
        let resp = atest::call_service(&app, req).await;
        let status = resp.status().as_u16();
        let bytes = atest::read_body(resp).await;
        (status, serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null))
    }

    fn end(call_id: &str, ended_by: &str) -> serde_json::Value {
        serde_json::json!({"call_id": call_id, "event": "end", "ended_by": ended_by, "last_seq": 4,
                           "caller_lines": [{"seq": 1, "text": "This is the police"}, {"seq": 3, "text": "Give me the OTP"}]})
    }

    #[actix_web::test]
    async fn end_rejects_malformed_requests() {
        let s = state();
        for body in [
            serde_json::json!({"call_id": "", "ended_by": "user_hung_up"}),
            serde_json::json!({"call_id": "x".repeat(129), "ended_by": "user_hung_up"}),
            serde_json::json!({"call_id": "c1", "ended_by": "silence"}), // aggregator-only
            serde_json::json!({"call_id": "c1", "ended_by": "bored"}),
            serde_json::json!({"call_id": "c1", "event": "start", "ended_by": "user_hung_up"}),
            serde_json::json!({"call_id": "c1"}), // ended_by missing
            serde_json::json!({"call_id": "c1", "ended_by": "user_hung_up", "lang": "fr"}),
            serde_json::json!({"call_id": "c1", "ended_by": "user_hung_up",
                               "caller_lines": [{"seq": 1, "text": "x".repeat(2001)}]}),
        ] {
            let (status, json) = post(&s, "/v1/call/end", body).await;
            assert_eq!(status, 400, "{json}");
            assert!(json["error"].is_string());
        }
        let too_many: Vec<_> = (0..201).map(|i| serde_json::json!({"seq": i, "text": "hi"})).collect();
        let (status, _) =
            post(&s, "/v1/call/end", serde_json::json!({"call_id": "c1", "ended_by": "user_hung_up", "caller_lines": too_many})).await;
        assert_eq!(status, 400);
        assert!(!s.calls.is_ended("c1")); // a rejected end ends nothing
    }

    #[actix_web::test]
    async fn end_of_an_unknown_call_is_200_unknown_fallback() {
        let s = state();
        let (status, json) = post(&s, "/v1/call/end", end("never-seen", "script_finished")).await;
        assert_eq!(status, 200, "{json}");
        assert_eq!(json["final_verdict"], "UNKNOWN");
        assert_eq!(json["live_verdict"], "UNKNOWN");
        assert_eq!(json["review"], "fallback");
        assert_eq!(json["ended_by"], "script_finished");
        assert_eq!(json["top_window_seqs"], serde_json::json!([]));
        assert_eq!(json["already_ended"], false);
        assert_eq!(json["error"], "aggregator unavailable");
    }

    #[actix_web::test]
    async fn end_falls_back_to_the_last_verdict_when_the_aggregator_is_down() {
        let s = state();
        s.calls.set_answer("c1", Answer {
            verdict: "SCAM".into(),
            combined_score: 0.8,
            reasons: vec![Reason { signal: "money".into(), score: 0.95, text: "Asked for your OTP or money".into() }],
            ..Default::default()
        });
        let (status, json) = post(&s, "/v1/call/end", end("c1", "user_hung_up")).await;
        assert_eq!(status, 200);
        assert_eq!((json["final_verdict"].as_str(), json["live_verdict"].as_str()), (Some("SCAM"), Some("SCAM")));
        assert_eq!(json["reason"], "Asked for your OTP or money");
        assert!((json["call_score"].as_f64().unwrap() - 0.8).abs() < 1e-6);
        assert_eq!(json["review"], "fallback");
        assert!(s.calls.is_ended("c1"));
    }

    #[actix_web::test]
    async fn a_repeated_end_returns_the_stored_result() {
        let s = state();
        let first = Ended {
            final_verdict: "SCAM".into(),
            call_score: 0.91,
            live_verdict: "UNSURE".into(),
            review: "bert".into(),
            top_window_seqs: vec![9, 11, 13],
            reason: "Asked for your OTP or money".into(),
            ended_by: "script_finished".into(),
        };
        s.calls.mark_ended("c1", Some(first)); // as if the aggregator answered the first end
        let (status, json) = post(&s, "/v1/call/end", end("c1", "user_hung_up")).await;
        assert_eq!(status, 200);
        assert_eq!(json["already_ended"], true);
        assert_eq!(json["ended_by"], "script_finished"); // the first end's value
        assert_eq!(json["final_verdict"], "SCAM");
        assert_eq!(json["top_window_seqs"], serde_json::json!([9, 11, 13]));
        assert!(json.get("error").is_none()); // the aggregator (down here) was not asked
    }

    #[actix_web::test]
    async fn a_line_after_the_end_is_ignored_without_scoring() {
        let s = state();
        s.calls.set_answer("c1", Answer { verdict: "UNSURE".into(), caller_lines: 2, ..Default::default() });
        let (status, _) = post(&s, "/v1/call/end", end("c1", "script_finished")).await;
        assert_eq!(status, 200);
        for speaker in ["caller", "receiver"] {
            let (status, json) = post(&s, "/v1/utterance",
                serde_json::json!({"call_id": "c1", "seq": 9, "speaker": speaker, "text": "late line", "lang": "en"})).await;
            // workers and aggregator are unreachable: a scored line would be 502, so 200 means none was called
            assert_eq!(status, 200, "{json}");
            assert_eq!(json["ignored"], true);
            assert_eq!(json["reason"], "call_ended");
            assert_eq!(json["scored"], false);
            assert_eq!(json["verdict"], "UNSURE"); // the call's last verdict, unchanged
            assert_eq!(json["seq"], 9);
        }
    }

    #[actix_web::test]
    async fn normal_lines_say_not_ignored_and_have_no_reason_field() {
        let s = state();
        let (status, json) = post(&s, "/v1/utterance",
            serde_json::json!({"call_id": "c2", "seq": 2, "speaker": "receiver", "text": "hello?"})).await;
        assert_eq!(status, 200);
        assert_eq!(json["ignored"], false);
        assert!(json.get("reason").is_none());
        // every existing field is still there
        for field in ["call_id", "seq", "scored", "duplicate", "verdict", "combined_score", "reasons", "action",
                      "warming_up", "quorum_met", "language", "language_source", "scores", "model_versions",
                      "missing", "recent_scores", "fast_score", "bert_score", "bert_status", "caller_lines", "latency_ms"] {
            assert!(json.get(field).is_some(), "missing {field}");
        }
        // other calls are not affected by c1 ending
        assert!(!s.calls.is_ended("c2"));
    }

    #[test]
    fn verdict_names_for_the_phone() {
        assert_eq!(end_verdict_name(scam_detect::Verdict::Scam as i32), "SCAM");
        assert_eq!(end_verdict_name(scam_detect::Verdict::Safe as i32), "SAFE");
        assert_eq!(end_verdict_name(scam_detect::Verdict::Unsure as i32), "UNSURE");
        assert_eq!(end_verdict_name(scam_detect::Verdict::Unspecified as i32), "UNKNOWN");
        assert_eq!(end_verdict_name(42), "UNKNOWN");
    }

    #[test]
    fn end_language_comes_from_lang_or_the_lines() {
        let req = |lang: Option<&str>, texts: &[&str]| EndRequest {
            call_id: "c".into(),
            event: None,
            ended_by: "user_hung_up".into(),
            last_seq: None,
            caller_lines: texts.iter().enumerate().map(|(i, t)| EndLine { seq: i as i64, text: t.to_string() }).collect(),
            lang: lang.map(String::from),
        };
        assert_eq!(validate_end(&req(None, &[])).unwrap(), "");
        assert_eq!(validate_end(&req(None, &["我是警察", "你必须马上转账"])).unwrap(), "zh");
        assert_eq!(validate_end(&req(None, &["This is the police"])).unwrap(), "en");
        assert_eq!(validate_end(&req(Some("zh-SG"), &["hello"])).unwrap(), "zh");
    }
}
