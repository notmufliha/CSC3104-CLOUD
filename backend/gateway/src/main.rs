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
//!
//! Logs: one "timing" line per worker call and one per aggregator call (milliseconds, outcome), plus one per
//! request. Transcript text is never logged.

mod calls;
mod config;
mod language;

use std::collections::{BTreeMap, HashMap};
use std::time::{Duration, Instant};

use actix_web::{error, get, post, web, App, HttpResponse, HttpServer};
use serde::{Deserialize, Serialize};
use tonic::transport::{Channel, Endpoint};

pub mod scam_detect {
    tonic::include_proto!("scam_detect");
}

use calls::{Answer, CallStore, Reason};
use config::{Settings, SIGNALS};
use scam_detect::aggregator_service_client::AggregatorServiceClient;
use scam_detect::worker_service_client::WorkerServiceClient;
use scam_detect::{AggregateRequest, TranscriptRequest, WorkerScore};

const MAX_CALL_ID_LEN: usize = 128;
const MAX_TEXT_CHARS: usize = 10_000;
/// A report is the call's caller lines (at most 200 x 2000 characters, checked again by the review service).
const MAX_REPORT_BYTES: usize = 512 * 1024;
const REVIEW_TIMEOUT: Duration = Duration::from_secs(5);

struct AppState {
    settings: Settings,
    /// (language, signal) -> channel. Built once at start-up and reused (a Channel clone is cheap).
    workers: HashMap<(&'static str, &'static str), Channel>,
    aggregator: Channel,
    calls: CallStore,
    http: hyper::Client<hyper::client::HttpConnector>,
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
            Ok(Answer {
                verdict,
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
    // A line that is not scored (receiver line, or a caller line resent with a seq already scored):
    // answer with the call's current state (the aggregator's last answer), no worker call.
    let unscored = |duplicate: bool| {
        let last = state.calls.last_answer(&call_id);
        let a = last.clone().unwrap_or_default();
        HttpResponse::Ok().json(UtteranceResponse {
            call_id: call_id.clone(),
            seq,
            scored: false,
            duplicate,
            verdict: last.as_ref().map(|a| a.verdict.clone()),
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
        })
    };

    // 2.2 receiver lines: no worker call, just the call's last verdict
    if !req.speaker.trim().eq_ignore_ascii_case("caller") {
        if let Some(s) = seq {
            state.calls.mark_seq(&call_id, s);
        }
        tracing::info!(call_id = %call_id, seq, speaker = "receiver", "receiver line: no workers called");
        return unscored(false);
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
            return unscored(true);
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
    let model_versions: BTreeMap<&'static str, String> =
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
    state.calls.push_context(&call_id, &req.text, n);
    state.calls.set_answer(&call_id, answer.clone());

    let latency_ms = start.elapsed().as_millis() as u64;
    tracing::info!(target: "timing", stage = "request", call_id = %call_id, seq, lang, ms = latency_ms,
                   verdict = %answer.verdict, missing = missing.len());
    HttpResponse::Ok().json(UtteranceResponse {
        call_id,
        seq,
        scored: true,
        duplicate: false,
        verdict: Some(answer.verdict),
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
    })
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
                   bert_context_lines = settings.bert_context_lines, "aggregator address");
    let bind_addr = settings.bind_addr.clone();
    let state = web::Data::new(AppState {
        aggregator: lazy_channel(&settings.aggregator_addr),
        calls: CallStore::new(settings.call_ttl, settings.max_calls),
        http: hyper::Client::new(),
        workers,
        settings,
    });
    tracing::info!(review_url = state.settings.review_url.as_deref().unwrap_or("(off)"), "report forwarding");
    tracing::info!(addr = %bind_addr, "starting gateway");

    HttpServer::new(move || {
        // malformed JSON -> 400 with a JSON body (the default is plain text)
        let json_cfg = web::JsonConfig::default().limit(64 * 1024).error_handler(|err, _| {
            let msg = err.to_string();
            error::InternalError::from_response(err, HttpResponse::BadRequest().json(ErrorResponse { error: msg }))
                .into()
        });
        App::new()
            .app_data(state.clone())
            .app_data(json_cfg)
            .service(utterance)
            .service(submit_transcript)
            .service(healthz)
            .service(
                web::resource("/v1/report")
                    .app_data(web::JsonConfig::default().limit(MAX_REPORT_BYTES).error_handler(|err, _| {
                        let msg = err.to_string();
                        error::InternalError::from_response(
                            err,
                            HttpResponse::BadRequest().json(ErrorResponse { error: msg }),
                        )
                        .into()
                    }))
                    .route(web::post().to(report)),
            )
    })
    .bind(bind_addr)?
    .run()
    .await
}
