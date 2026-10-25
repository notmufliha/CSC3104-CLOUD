//! The BERT second opinion (BertService, backend/workers/bert): asked at most once per caller line, and only
//! when policy::wants_second_opinion says the answer without it would be "not enough data", money rule, high
//! or unsure. If BERT is off, slow or down, the line is decided exactly as before (fallback = the fast vote).
//!
//! The call's recent caller lines come from the gateway in AggregateRequest.context; they are passed to BERT
//! and dropped. Never stored (Redis stays text-free) and never logged.

use std::time::{Duration, Instant};

use tonic::transport::{Channel, Endpoint};

use crate::scam_detect::bert_service_client::BertServiceClient;
use crate::scam_detect::ContextRequest;

/// Most lines / characters per line forwarded to BERT (its tokenizer keeps the last 256 tokens anyway).
const MAX_LINES: usize = 10;
const MAX_CHARS_PER_LINE: usize = 1000;

#[derive(Clone)]
pub struct BertClient {
    channel: Channel,
    timeout: Duration,
}

impl BertClient {
    /// None when `addr` is empty (BERT off). The channel connects lazily, so the aggregator starts even when
    /// the BERT pod is not up yet.
    pub fn new(addr: &str, timeout: Duration) -> Option<BertClient> {
        if addr.trim().is_empty() {
            return None;
        }
        let addr = if addr.starts_with("http://") || addr.starts_with("https://") {
            addr.to_string()
        } else {
            format!("http://{addr}")
        };
        let channel = Endpoint::from_shared(addr.clone())
            .unwrap_or_else(|e| panic!("invalid BERT_ADDR '{addr}': {e}"))
            .connect_timeout(Duration::from_millis(200))
            .connect_lazy();
        Some(BertClient { channel, timeout })
    }
}

/// One line's second opinion: asked lazily, at most once (a Redis retry reuses the answer).
pub struct SecondOpinion<'a> {
    client: Option<&'a BertClient>,
    lines: Vec<String>,
    language: String,
    answer: Option<(Option<f32>, &'static str)>,
    pub ms: u64,
}

impl<'a> SecondOpinion<'a> {
    pub fn new(client: Option<&'a BertClient>, context: &[String], language: &str) -> SecondOpinion<'a> {
        let lines = context
            .iter()
            .rev()
            .take(MAX_LINES)
            .rev()
            .map(|l| {
                let n = l.chars().count();
                l.chars().skip(n.saturating_sub(MAX_CHARS_PER_LINE)).collect::<String>()
            })
            .filter(|l| !l.trim().is_empty())
            .collect();
        SecondOpinion { client, lines, language: language.to_string(), answer: None, ms: 0 }
    }

    /// BERT's score, or None if it is off, has nothing to read, or did not answer in time.
    pub async fn ask(&mut self) -> Option<f32> {
        if let Some((score, _)) = self.answer {
            return score;
        }
        let (score, status) = match self.client {
            None => (None, "off"),
            Some(_) if self.lines.is_empty() => (None, "no_context"),
            Some(c) => {
                let start = Instant::now();
                let mut client = BertServiceClient::new(c.channel.clone());
                let request = ContextRequest { lines: self.lines.clone(), language: self.language.clone() };
                let result = tokio::time::timeout(c.timeout, client.score_context(request)).await;
                self.ms = start.elapsed().as_millis() as u64;
                match result {
                    Ok(Ok(reply)) => {
                        let s = reply.into_inner().score;
                        if s.is_finite() {
                            (Some(s.clamp(0.0, 1.0)), "ok")
                        } else {
                            (None, "error")
                        }
                    }
                    Ok(Err(_)) => (None, "error"),
                    Err(_) => (None, "timeout"),
                }
            }
        };
        self.answer = Some((score, status));
        score
    }

    /// "ok" | "timeout" | "error" | "no_context" | "off" once asked; "skipped" if never asked ("off" if BERT
    /// is switched off).
    pub fn status(&self) -> &'static str {
        match (self.answer, self.client) {
            (Some((_, status)), _) => status,
            (None, None) => "off",
            (None, Some(_)) => "skipped",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn off_and_skipped() {
        let mut s = SecondOpinion::new(None, &["hello".to_string()], "en");
        assert_eq!(s.status(), "off");
        assert_eq!(s.ask().await, None);
        assert_eq!(s.status(), "off");
        assert!(BertClient::new("  ", Duration::from_millis(100)).is_none());
    }

    #[tokio::test]
    async fn unreachable_bert_is_an_error_not_a_hang() {
        // nothing listens on port 9: the call fails fast and the line falls back to the fast vote
        let c = BertClient::new("127.0.0.1:9", Duration::from_millis(300)).unwrap();
        let mut s = SecondOpinion::new(Some(&c), &["我是警察".to_string()], "zh");
        assert_eq!(s.status(), "skipped");
        assert_eq!(s.ask().await, None);
        assert!(matches!(s.status(), "error" | "timeout"));
        assert!(s.ms < 1000);
    }

    #[tokio::test]
    async fn empty_context_is_not_sent() {
        let c = BertClient::new("127.0.0.1:9", Duration::from_millis(300)).unwrap();
        let mut s = SecondOpinion::new(Some(&c), &["  ".to_string()], "en");
        assert_eq!(s.ask().await, None);
        assert_eq!(s.status(), "no_context");
    }

    #[test]
    fn keeps_the_last_lines_and_the_end_of_long_lines() {
        let ctx: Vec<String> = (0..12).map(|i| format!("line {i}")).collect();
        let s = SecondOpinion::new(None, &ctx, "en");
        assert_eq!(s.lines.len(), MAX_LINES);
        assert_eq!(s.lines.last().unwrap(), "line 11");
        let long = "a".repeat(5000) + "END";
        let s = SecondOpinion::new(None, &[long], "en");
        assert_eq!(s.lines[0].chars().count(), MAX_CHARS_PER_LINE);
        assert!(s.lines[0].ends_with("END"));
    }
}
