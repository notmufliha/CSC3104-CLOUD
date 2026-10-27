//! Settings, read once from environment variables at start-up, so the same image runs in Docker Compose
//! and Kubernetes. Only the values differ, e.g. Compose `URGENCY_EN_ADDR=http://urgency_en:50051`,
//! Kubernetes `URGENCY_EN_ADDR=urgency-en:50051` (a missing `http://` is added).

use std::env;
use std::time::Duration;

/// The three signals every language has, in a fixed order.
pub const SIGNALS: [&str; 3] = ["urgency", "money", "impersonation"];

pub struct Settings {
    pub bind_addr: String,
    pub aggregator_addr: String,
    /// (language, signal, address) for every worker.
    pub workers: Vec<(&'static str, &'static str, String)>,
    /// How long to wait for the workers (all three run at the same time).
    pub worker_timeout: Duration,
    /// How long to wait for the aggregator (Redis + the BERT second opinion when it is asked).
    /// worker + aggregator budget = the 2 s promise to the phone: 1400 + 550 ms by default.
    pub aggregator_timeout: Duration,
    /// Caller lines per call sent to the aggregator for BERT (this line included). 0 = never send text.
    pub bert_context_lines: usize,
    /// Forget a call this long after its last utterance.
    pub call_ttl: Duration,
    /// Upper bound on calls kept in memory.
    pub max_calls: usize,
}

impl Settings {
    pub fn from_env() -> Settings {
        let mut workers = Vec::new();
        for lang in crate::language::SUPPORTED {
            for signal in SIGNALS {
                let key = format!("{}_{}_ADDR", signal.to_uppercase(), lang.to_uppercase());
                let default = format!("http://{signal}_{lang}:50051"); // Compose service names
                workers.push((lang, signal, normalize_addr(&env::var(key).unwrap_or(default))));
            }
        }
        Settings {
            bind_addr: env::var("GATEWAY_BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8080".to_string()),
            aggregator_addr: normalize_addr(
                &env::var("AGGREGATOR_ADDR").unwrap_or_else(|_| "http://aggregator:50052".to_string()),
            ),
            workers,
            worker_timeout: Duration::from_millis(env_u64("WORKER_TIMEOUT_MS", 1400)),
            aggregator_timeout: Duration::from_millis(env_u64("AGGREGATOR_TIMEOUT_MS", 550)),
            bert_context_lines: env_u64("BERT_CONTEXT_LINES", 5) as usize,
            call_ttl: Duration::from_secs(env_u64("CALL_TTL_SECS", 1800)),
            max_calls: env_u64("MAX_CALLS", 10_000) as usize,
        }
    }
}

fn env_u64(key: &str, default: u64) -> u64 {
    env::var(key).ok().and_then(|v| v.trim().parse().ok()).unwrap_or(default)
}

/// tonic needs a scheme; Kubernetes-style settings often leave it out ("urgency-en:50051").
pub fn normalize_addr(addr: &str) -> String {
    let addr = addr.trim();
    if addr.starts_with("http://") || addr.starts_with("https://") {
        addr.to_string()
    } else {
        format!("http://{addr}")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds_scheme_when_missing() {
        assert_eq!(normalize_addr("urgency-en:50051"), "http://urgency-en:50051");
        assert_eq!(normalize_addr(" http://urgency_en:50051 "), "http://urgency_en:50051");
    }
}
