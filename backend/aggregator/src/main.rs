//! Aggregator: the judge. For each caller line the gateway sends that line's worker scores; the aggregator
//! merges them into the call's memory in Redis and decides SAFE / UNSURE / SCAM for the whole call so far
//! (rules in policy.rs, settings in config.rs, Redis in store.rs).
//!
//!   gRPC  AggregatorService.Aggregate   (AGGREGATOR_GRPC_ADDR, default 0.0.0.0:50052)
//!   HTTP  GET /health                   (AGGREGATOR_HTTP_ADDR, default 0.0.0.0:8082) - 503 if Redis is down
//!
//! A request without call_id is a one-shot (the gateway's /submit-transcript): no memory, no Redis.
//! BERT second opinion (bert.rs): asked only when the fast vote is unsure or alarmed, if BERT_ADDR is set.
//! Logs: one "verdict" line per decision {call_id, seq, verdict, combined, fast, bert, rule, scores}. Never text.

mod bert;
mod config;
mod policy;
mod store;

use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use actix_web::{get, web, App, HttpResponse, HttpServer};
use tonic::transport::Server as TonicServer;
use tonic::{Request, Response, Status};

pub mod scam_detect {
    tonic::include_proto!("scam_detect");
}

use bert::{BertClient, SecondOpinion};
use config::{Settings, SIGNALS};
use policy::{Decision, LineScores};
use scam_detect::aggregator_service_server::{AggregatorService, AggregatorServiceServer};
use scam_detect::{AggregateRequest, AggregateResponse, Reason, WorkerScore};
use store::{Store, StoreError};

const MAX_CALL_ID_LEN: usize = 128;

struct Aggregator {
    settings: Arc<Settings>,
    store: Store,
    /// None = BERT off (BERT_ADDR not set).
    bert: Option<BertClient>,
}

/// This line's scores by signal. Unknown signals are ignored (logged); a repeated signal keeps its highest
/// score; scores are clamped to 0-1. NaN/infinite is a bad request.
fn line_scores(scores: &[WorkerScore]) -> Result<LineScores, Status> {
    let mut line: LineScores = [None; 3];
    for s in scores {
        if !s.score.is_finite() {
            return Err(Status::invalid_argument(format!("score for '{}' is not a number", s.signal)));
        }
        match SIGNALS.iter().position(|name| *name == s.signal) {
            Some(i) => {
                let score = s.score.clamp(0.0, 1.0);
                line[i] = Some(line[i].map_or(score, |old| old.max(score)));
            }
            None => tracing::warn!(signal = %s.signal, "ignoring score for unknown signal"),
        }
    }
    Ok(line)
}

fn to_proto_verdict(v: policy::Verdict) -> scam_detect::Verdict {
    match v {
        policy::Verdict::Safe => scam_detect::Verdict::Safe,
        policy::Verdict::Unsure => scam_detect::Verdict::Unsure,
        policy::Verdict::Scam => scam_detect::Verdict::Scam,
    }
}

fn to_response(d: Decision, bert_status: &str) -> AggregateResponse {
    AggregateResponse {
        fast_score: d.fast,
        bert_score: d.bert,
        bert_status: bert_status.to_string(),
        verdict: to_proto_verdict(d.verdict) as i32,
        combined_score: d.combined,
        reasons: d
            .reasons
            .into_iter()
            .map(|r| Reason { signal: r.signal.to_string(), score: r.score, text: r.text })
            .collect(),
        action: d.action.to_string(),
        recent_scores: SIGNALS
            .iter()
            .zip(d.recent)
            .filter_map(|(s, v)| v.map(|v| (s.to_string(), v)))
            .collect(),
        warming_up: d.warming_up,
        quorum_met: d.quorum_met,
        caller_lines: d.caller_lines,
    }
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

#[tonic::async_trait]
impl AggregatorService for Aggregator {
    async fn aggregate(&self, request: Request<AggregateRequest>) -> Result<Response<AggregateResponse>, Status> {
        let req = request.into_inner();
        let line = line_scores(&req.scores)?;
        let call_id = req.call_id.trim();
        let now = now_ms();
        let language = req.scores.first().map(|s| s.language.as_str()).unwrap_or("");
        let mut second = SecondOpinion::new(self.bert.as_ref(), &req.context, language);

        // An empty line (no worker answered) is allowed: the quorum rule turns it into UNSURE (or BERT stands
        // in), or SCAM if the call is already red.
        let decision = if call_id.is_empty() {
            let bert = if policy::one_shot_wants_second_opinion(line, &self.settings) {
                second.ask().await
            } else {
                None
            };
            policy::decide_one_shot(line, bert, &self.settings)
        } else {
            if call_id.len() > MAX_CALL_ID_LEN {
                return Err(Status::invalid_argument(format!("call_id longer than {MAX_CALL_ID_LEN} characters")));
            }
            match self.store.apply(call_id, req.seq, line, now, req.call_time_ms, &self.settings, &mut second).await {
                Ok(d) => d,
                Err(e) => {
                    tracing::error!(call_id, seq = req.seq, error = %e, "call memory update failed");
                    return Err(match e {
                        StoreError::Redis(_) => Status::unavailable("call memory (redis) unavailable"),
                        StoreError::Busy => Status::aborted("too many simultaneous updates to this call"),
                        StoreError::Corrupt(_) => Status::internal("stored call unreadable"),
                    });
                }
            }
        };

        // 3.8 verdict log: ids, time, verdict and scores, never text.
        tracing::info!(target: "verdict", entry = %store::log_entry(call_id, req.seq, now, &line, &decision,
                       second.status()), bert_ms = second.ms, "verdict");
        Ok(Response::new(to_response(decision, second.status())))
    }
}

#[get("/health")]
async fn health(store: web::Data<Store>) -> HttpResponse {
    match store.ping().await {
        Ok(()) => HttpResponse::Ok().json(serde_json::json!({ "status": "ok", "redis": "ok" })),
        Err(e) => HttpResponse::ServiceUnavailable()
            .json(serde_json::json!({ "status": "degraded", "redis": e.to_string() })),
    }
}

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
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    init_logging();

    let settings = match Settings::from_env() {
        Ok(s) => s,
        Err(e) => {
            tracing::error!("invalid settings: {e}");
            std::process::exit(2);
        }
    };
    tracing::info!(grpc = %settings.grpc_addr, http = %settings.http_addr, redis = %settings.redis_url_for_log(),
                   weights = ?settings.weights, scam = %settings.scam_threshold, unsure = %settings.unsure_threshold,
                   window_lines = settings.window_lines, warmup_secs = settings.warmup.as_secs(),
                   warmup_override = %settings.warmup_override, scam_streak = settings.scam_streak,
                   money_rule = %settings.money_rule, money_rule_other = %settings.money_rule_other,
                   money_rule_lines = settings.money_rule_lines,
                   reason_threshold = %settings.reason_threshold, min_quorum = settings.min_quorum,
                   call_ttl_secs = settings.call_ttl.as_secs(),
                   bert_addr = if settings.bert_addr.is_empty() { "(off)" } else { settings.bert_addr.as_str() },
                   bert_timeout_ms = settings.bert_timeout.as_millis() as u64, bert_weight = %settings.bert_weight,
                   bert_veto = %settings.bert_veto, "starting aggregator");

    let grpc_addr = settings.grpc_addr.parse()?;
    let http_addr = settings.http_addr.clone();
    let store = Store::connect(&settings.redis_url, &settings.redis_url_for_log()).await;
    let bert = BertClient::new(&settings.bert_addr, settings.bert_timeout);
    let aggregator = Aggregator { settings: Arc::new(settings), store: store.clone(), bert };

    // gRPC server (the Aggregate endpoint the gateway calls) on a background task, a small Actix-web HTTP
    // server (health) on the main task. Either exiting (e.g. a bind failure) brings the process down.
    let grpc_handle = tokio::spawn(async move {
        TonicServer::builder()
            .add_service(AggregatorServiceServer::new(aggregator))
            .serve(grpc_addr)
            .await
    });

    let store_data = web::Data::new(store);
    let http_server = HttpServer::new(move || App::new().app_data(store_data.clone()).service(health))
        .bind(&http_addr)?
        .run();

    tokio::select! {
        res = grpc_handle => {
            res??;
        }
        res = http_server => {
            res?;
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ws(signal: &str, score: f32) -> WorkerScore {
        WorkerScore { signal: signal.into(), score, language: "en".into() }
    }

    #[test]
    fn line_scores_by_signal() {
        let line = line_scores(&[ws("money", 0.8), ws("urgency", 1.4), ws("money", 0.9), ws("other", 0.5)]).unwrap();
        assert_eq!(line, [Some(1.0), Some(0.9), None]); // clamped, highest kept, unknown ignored
        assert_eq!(line_scores(&[]).unwrap(), [None; 3]); // empty line is allowed
        assert!(line_scores(&[ws("money", f32::NAN)]).is_err());
    }

    #[test]
    fn response_has_reasons_action_and_recent_scores() {
        let cfg = Settings::from_lookup(|_| None).unwrap();
        let r = to_response(policy::decide_one_shot([Some(0.6), Some(0.95), None], None, &cfg), "skipped");
        assert_eq!(r.verdict, scam_detect::Verdict::Scam as i32);
        assert_eq!(r.reasons[0].signal, "money");
        assert_eq!(r.action, policy::ACTION_SCAM);
        assert_eq!(r.recent_scores.len(), 2); // impersonation had no score
        assert!(r.quorum_met);
        assert_eq!((r.bert_score, r.bert_status.as_str()), (None, "skipped"));
        assert_eq!(r.fast_score, r.combined_score);
    }
}
