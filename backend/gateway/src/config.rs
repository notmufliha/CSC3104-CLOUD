use std::env;

/// gRPC addresses for the three worker signals, per supported language.
/// Defaults match the service names in docker-compose.yml; override via env
/// vars for k8s / local dev against a subset of workers.
pub struct WorkerEndpoints {
    pub urgency: String,
    pub money: String,
    pub impersonation: String,
}

pub fn worker_endpoints_for(language: &str) -> WorkerEndpoints {
    let suffix = language.to_uppercase();
    let default = |signal: &str, lang: &str| {
        format!("http://{signal}_{lang}:50051")
    };
    WorkerEndpoints {
        urgency: env::var(format!("URGENCY_{suffix}_ADDR"))
            .unwrap_or_else(|_| default("urgency", language)),
        money: env::var(format!("MONEY_{suffix}_ADDR"))
            .unwrap_or_else(|_| default("money", language)),
        impersonation: env::var(format!("IMPERSONATION_{suffix}_ADDR"))
            .unwrap_or_else(|_| default("impersonation", language)),
    }
}

pub fn aggregator_addr() -> String {
    env::var("AGGREGATOR_ADDR").unwrap_or_else(|_| "http://aggregator:50052".to_string())
}

pub fn gateway_bind_addr() -> String {
    env::var("GATEWAY_BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8080".to_string())
}
