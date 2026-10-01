use actix_web::{get, App, HttpResponse, HttpServer};
use std::env;
use tonic::transport::Server as TonicServer;
use tonic::{Request, Response, Status};

pub mod scam_detect {
    tonic::include_proto!("scam_detect");
}

use scam_detect::aggregator_service_server::{AggregatorService, AggregatorServiceServer};
use scam_detect::{AggregateRequest, AggregateResponse, Verdict, WorkerScore};

// TODO: these weights are an equal-split placeholder. Once there's labeled
// data to validate against, tune per-signal weights (e.g. impersonation may
// deserve more weight than urgency) instead of a flat 1/3 each.
const WEIGHTS: [f32; 3] = [1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0];
const SCAM_THRESHOLD: f32 = 0.6;
const UNSURE_THRESHOLD: f32 = 0.45;

#[derive(Default)]
struct Aggregator;

/// Looks up the configured weight for a given signal name. Unrecognized
/// signals (e.g. a future worker type not yet wired into WEIGHTS) fall back
/// to an even split of the remaining weight budget -- TODO: replace with an
/// explicit signal->weight config once the signal set stabilizes.
fn weight_for(signal: &str) -> f32 {
    match signal {
        "urgency" => WEIGHTS[0],
        "money" => WEIGHTS[1],
        "impersonation" => WEIGHTS[2],
        _ => 1.0 / 3.0,
    }
}

fn verdict_for(combined_score: f32) -> Verdict {
    if combined_score >= SCAM_THRESHOLD {
        Verdict::Scam
    } else if combined_score >= UNSURE_THRESHOLD {
        Verdict::Unsure
    } else {
        Verdict::Safe
    }
}

/// Weighted sum over whatever scores arrived. This is NOT re-normalized to
/// the scores actually present (i.e. missing a worker doesn't boost the
/// remaining weights) -- a transcript with fewer signals reporting in tends
/// toward SAFE/UNSURE by design, since a verdict of SCAM should require
/// corroboration from multiple signals, not just one. TODO: revisit this
/// policy once partial-quorum behavior is validated against real traffic.
fn weighted_sum(scores: &[WorkerScore]) -> f32 {
    scores
        .iter()
        .map(|s| weight_for(&s.signal) * s.score)
        .sum()
}

#[tonic::async_trait]
impl AggregatorService for Aggregator {
    async fn aggregate(
        &self,
        request: Request<AggregateRequest>,
    ) -> Result<Response<AggregateResponse>, Status> {
        let scores = request.into_inner().scores;
        if scores.is_empty() {
            return Err(Status::invalid_argument("scores must not be empty"));
        }

        let combined_score = weighted_sum(&scores);
        let verdict = verdict_for(combined_score);

        tracing::info!(%combined_score, ?verdict, num_scores = scores.len(), "aggregated");

        Ok(Response::new(AggregateResponse {
            verdict: verdict as i32,
            combined_score,
        }))
    }
}

#[get("/health")]
async fn health() -> HttpResponse {
    HttpResponse::Ok().json(serde_json::json!({ "status": "ok" }))
}

#[actix_web::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt::init();

    let grpc_addr = env::var("AGGREGATOR_GRPC_ADDR")
        .unwrap_or_else(|_| "0.0.0.0:50052".to_string())
        .parse()?;
    let http_addr = env::var("AGGREGATOR_HTTP_ADDR").unwrap_or_else(|_| "0.0.0.0:8082".to_string());

    tracing::info!(%grpc_addr, %http_addr, "starting aggregator");

    // Run the gRPC server (the actual Aggregate endpoint the gateway calls)
    // on a background task, and a small Actix-web HTTP server (health
    // checks / future admin endpoints) on the main task. Either exiting
    // (e.g. a bind failure) brings the process down.
    let grpc_handle = tokio::spawn(async move {
        TonicServer::builder()
            .add_service(AggregatorServiceServer::new(Aggregator))
            .serve(grpc_addr)
            .await
    });

    let http_server = HttpServer::new(|| App::new().service(health))
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
