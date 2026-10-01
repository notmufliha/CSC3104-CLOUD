mod config;
mod language;

use actix_web::{post, web, App, HttpResponse, HttpServer};
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tonic::transport::Channel;

pub mod scam_detect {
    tonic::include_proto!("scam_detect");
}

use scam_detect::aggregator_service_client::AggregatorServiceClient;
use scam_detect::worker_service_client::WorkerServiceClient;
use scam_detect::{AggregateRequest, TranscriptRequest, WorkerScore};

/// Per-worker call budget. If a worker hasn't replied within this window the
/// gateway proceeds without it (partial-quorum aggregation) rather than
/// blocking the whole request on the slowest/dead worker.
const WORKER_TIMEOUT: Duration = Duration::from_millis(2000);

#[derive(Deserialize)]
struct SubmitTranscriptRequest {
    transcript: String,
    language: String,
}

#[derive(Serialize)]
struct SubmitTranscriptResponse {
    verdict: String,
    combined_score: f32,
    signals_received: Vec<String>,
    signals_expected: usize,
}

#[derive(Serialize)]
struct ErrorResponse {
    error: String,
}

/// Calls one worker's ClassifyTranscript RPC, bounded by WORKER_TIMEOUT.
/// Returns None on connect error, RPC error, or timeout -- all of which are
/// treated the same way by the caller: "this signal didn't make it in time".
async fn call_worker(
    addr: String,
    signal_name: &'static str,
    transcript: String,
    language: String,
) -> Option<WorkerScore> {
    let call = async {
        // connect_lazy defers the actual TCP connect to first use, which
        // keeps this cheap to call per-request. TODO: pool/reuse channels
        // instead of dialing fresh each request once load testing says it
        // matters.
        let channel = Channel::from_shared(addr.clone())
            .ok()?
            .connect_lazy();
        let mut client = WorkerServiceClient::new(channel);
        let request = tonic::Request::new(TranscriptRequest {
            transcript,
            language,
        });
        client.classify_transcript(request).await.ok().map(|r| r.into_inner())
    };

    match tokio::time::timeout(WORKER_TIMEOUT, call).await {
        Ok(Some(score)) => Some(score),
        Ok(None) => {
            tracing::warn!(signal = signal_name, addr = %addr, "worker call failed");
            None
        }
        Err(_) => {
            tracing::warn!(signal = signal_name, addr = %addr, "worker call timed out");
            None
        }
    }
}

#[post("/submit-transcript")]
async fn submit_transcript(body: web::Json<SubmitTranscriptRequest>) -> HttpResponse {
    let language = match language::validate_language(&body.language) {
        Ok(lang) => lang,
        Err(e) => return HttpResponse::BadRequest().json(ErrorResponse { error: e }),
    };

    let endpoints = config::worker_endpoints_for(language);

    // Fan out to all three signal workers concurrently. Each call is
    // independently timed out, so one slow/dead worker can't stall the
    // others -- we just end up aggregating on whatever came back in time.
    let (urgency, money, impersonation) = tokio::join!(
        call_worker(
            endpoints.urgency,
            "urgency",
            body.transcript.clone(),
            language.to_string()
        ),
        call_worker(
            endpoints.money,
            "money",
            body.transcript.clone(),
            language.to_string()
        ),
        call_worker(
            endpoints.impersonation,
            "impersonation",
            body.transcript.clone(),
            language.to_string()
        ),
    );

    let scores: Vec<WorkerScore> = [urgency, money, impersonation]
        .into_iter()
        .flatten()
        .collect();

    if scores.is_empty() {
        return HttpResponse::GatewayTimeout().json(ErrorResponse {
            error: "no worker responded in time".to_string(),
        });
    }

    let signals_received: Vec<String> = scores.iter().map(|s| s.signal.clone()).collect();

    let aggregator_addr = config::aggregator_addr();
    let channel = match Channel::from_shared(aggregator_addr.clone()) {
        Ok(endpoint) => endpoint.connect_lazy(),
        Err(e) => {
            return HttpResponse::InternalServerError().json(ErrorResponse {
                error: format!("invalid aggregator address: {e}"),
            })
        }
    };
    let mut aggregator_client = AggregatorServiceClient::new(channel);

    let aggregate_result = tokio::time::timeout(
        WORKER_TIMEOUT,
        aggregator_client.aggregate(tonic::Request::new(AggregateRequest { scores })),
    )
    .await;

    match aggregate_result {
        Ok(Ok(response)) => {
            let response = response.into_inner();
            HttpResponse::Ok().json(SubmitTranscriptResponse {
                verdict: scam_detect::Verdict::try_from(response.verdict)
                    .map(|v| format!("{v:?}"))
                    .unwrap_or_else(|_| "VERDICT_UNSPECIFIED".to_string()),
                combined_score: response.combined_score,
                signals_received,
                signals_expected: 3,
            })
        }
        Ok(Err(status)) => HttpResponse::BadGateway().json(ErrorResponse {
            error: format!("aggregator error: {status}"),
        }),
        Err(_) => HttpResponse::GatewayTimeout().json(ErrorResponse {
            error: "aggregator call timed out".to_string(),
        }),
    }
}

#[actix_web::main]
async fn main() -> std::io::Result<()> {
    tracing_subscriber::fmt::init();

    let bind_addr = config::gateway_bind_addr();
    tracing::info!(addr = %bind_addr, "starting gateway");

    HttpServer::new(|| App::new().service(submit_transcript))
        .bind(bind_addr)?
        .run()
        .await
}
