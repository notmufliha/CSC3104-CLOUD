//! Workstream G.3: POST /v1/verify, the cloud half of the app's Hybrid privacy level.
//!
//! In Hybrid mode the phone scores every caller line itself. Only at unsure moments (its own score >= 0.45) or on
//! the spot check (every 5th caller line) does it send the caller's last 5 lines, already masked on the phone
//! (numbers, amounts, names, links -> [PHONE_NUMBER], [MONEY_AMOUNT], ...), and asks BERT for a second opinion.
//! This route forwards them to the BERT verifier's existing ScoreContext gRPC method and returns its score.
//!
//!   POST /v1/verify   {"lines": ["...", "..."], "lang": "en"}      (1-5 lines, oldest first; lang optional)
//!   200               {"score": 0.83, "verdict": "SCAM", "model_version": "...", "latency_ms": 140}
//!   503               BERT_ADDR not set (verify is off)      502 BERT failed      504 BERT too slow
//!
//! Stateless: no call id, nothing is stored or remembered, and the text is never logged. The phone gives up
//! after 1.5 s and keeps its own answer, so a slow or missing BERT never blocks a call.

use std::time::Instant;

use actix_web::{web, HttpResponse};
use serde::{Deserialize, Serialize};

use crate::scam_detect::bert_service_client::BertServiceClient;
use crate::scam_detect::ContextRequest;
use crate::{bad_request, language, AppState, ErrorResponse};

/// Hybrid sends the caller's last 5 lines.
pub const MAX_LINES: usize = 5;
pub const MAX_LINE_CHARS: usize = 2000;
pub const MAX_BYTES: usize = 32 * 1024;
/// The aggregator's defaults (AGG_SCAM_THRESHOLD / AGG_UNSURE_THRESHOLD), used to name the score.
const SCAM_THRESHOLD: f32 = 0.6;
const UNSURE_THRESHOLD: f32 = 0.45;

#[derive(Deserialize)]
pub struct VerifyRequest {
    lines: Vec<String>,
    lang: Option<String>,
}

#[derive(Serialize)]
struct VerifyResponse {
    score: f32,
    verdict: &'static str,
    model_version: String,
    latency_ms: u64,
}

pub fn verdict(score: f32) -> &'static str {
    if score >= SCAM_THRESHOLD {
        "SCAM"
    } else if score >= UNSURE_THRESHOLD {
        "UNSURE"
    } else {
        "SAFE"
    }
}

/// 1-5 non-empty lines of reasonable length, and a supported (or detectable) language. Returns the language.
pub fn validate(req: &VerifyRequest) -> Result<&'static str, String> {
    if req.lines.is_empty() || req.lines.len() > MAX_LINES {
        return Err(format!("send 1 to {MAX_LINES} caller lines"));
    }
    if req.lines.iter().any(|l| l.trim().is_empty() || l.chars().count() > MAX_LINE_CHARS) {
        return Err(format!("every line must be 1 to {MAX_LINE_CHARS} characters"));
    }
    language::choose(req.lang.as_deref(), &req.lines.join(" ")).map(|(lang, _)| lang)
}

pub async fn verify(state: web::Data<AppState>, body: web::Json<VerifyRequest>) -> HttpResponse {
    let Some(channel) = state.bert.clone() else {
        return HttpResponse::ServiceUnavailable().json(ErrorResponse { error: "verify is not set up (BERT_ADDR)".into() });
    };
    let req = body.into_inner();
    let lang = match validate(&req) {
        Ok(l) => l,
        Err(e) => return bad_request(e),
    };
    let n_lines = req.lines.len();
    let start = Instant::now();
    let mut client = BertServiceClient::new(channel);
    let result = tokio::time::timeout(
        state.settings.verify_timeout,
        client.score_context(ContextRequest { lines: req.lines, language: lang.to_string() }),
    )
    .await;
    let ms = start.elapsed().as_millis() as u64;
    match result {
        Ok(Ok(reply)) => {
            let r = reply.into_inner();
            let score = r.score.clamp(0.0, 1.0);
            tracing::info!(target: "timing", stage = "verify", lang, lines = n_lines, ms, outcome = "ok",
                           model_version = %r.model_version);
            HttpResponse::Ok().json(VerifyResponse { score, verdict: verdict(score), model_version: r.model_version, latency_ms: ms })
        }
        Ok(Err(status)) => {
            tracing::warn!(target: "timing", stage = "verify", lang, lines = n_lines, ms, outcome = "error",
                           code = ?status.code());
            HttpResponse::BadGateway().json(ErrorResponse { error: "BERT verifier failed".into() })
        }
        Err(_) => {
            tracing::warn!(target: "timing", stage = "verify", lang, lines = n_lines, ms, outcome = "timeout");
            HttpResponse::GatewayTimeout().json(ErrorResponse { error: "BERT verifier timed out".into() })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(lines: &[&str], lang: Option<&str>) -> VerifyRequest {
        VerifyRequest { lines: lines.iter().map(|s| s.to_string()).collect(), lang: lang.map(String::from) }
    }

    #[test]
    fn verdict_uses_the_aggregator_thresholds() {
        assert_eq!(verdict(0.0), "SAFE");
        assert_eq!(verdict(0.449), "SAFE");
        assert_eq!(verdict(0.45), "UNSURE");
        assert_eq!(verdict(0.599), "UNSURE");
        assert_eq!(verdict(0.6), "SCAM");
        assert_eq!(verdict(1.0), "SCAM");
    }

    #[test]
    fn validation() {
        assert_eq!(validate(&req(&["This is the police", "Give me the [NUMBER] code"], Some("en"))), Ok("en"));
        assert_eq!(validate(&req(&["请把验证码告诉我"], None)), Ok("zh"));              // detected
        assert!(validate(&req(&[], None)).is_err());                                   // nothing to check
        assert!(validate(&req(&["a", "b", "c", "d", "e", "f"], None)).is_err());       // more than 5
        assert!(validate(&req(&["ok", "  "], None)).is_err());                          // empty line
        assert!(validate(&req(&[&"x".repeat(MAX_LINE_CHARS + 1)], None)).is_err());
        assert!(validate(&req(&["hello"], Some("fr"))).is_err());                      // unsupported language
    }
}
