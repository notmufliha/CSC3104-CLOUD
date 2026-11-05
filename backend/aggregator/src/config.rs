//! Settings, read once from environment variables at start-up (later a Kubernetes ConfigMap), so weights and
//! thresholds can be tuned without rebuilding the image. Every variable is optional; the defaults are the
//! Workstream 3 rules. A value that is set but invalid (not a number, out of range) stops the aggregator at
//! start-up instead of being silently replaced by the default.

use std::env;
use std::time::Duration;

/// The three signals every language has.
pub const SIGNALS: [&str; 3] = ["urgency", "money", "impersonation"];

#[derive(Debug, Clone)]
pub struct Settings {
    pub grpc_addr: String,
    pub http_addr: String,
    pub redis_url: String,
    /// Weight per signal, in SIGNALS order. A signal with no score shares its weight among the others.
    pub weights: [f32; 3],
    /// Combined score at or above this, on `scam_streak` updates in a row -> SCAM.
    pub scam_threshold: f32,
    /// Combined score at or above this (and below scam_threshold) -> UNSURE.
    pub unsure_threshold: f32,
    /// BERT score below this is considered safe for the live decision table.
    pub bert_safe_threshold: f32,
    /// BERT score at or above this confirms SCAM.
    pub bert_scam_threshold: f32,
    /// When BERT is unavailable, this many independent strong signals are required for SCAM.
    pub fallback_strong_signal_threshold: f32,
    /// Consecutive BERT disagreements needed to clear a live red alert.
    pub red_disagreements_to_clear: u32,
    /// How many recent caller lines each signal's highest score is taken over.
    pub window_lines: usize,
    /// Stay quiet (SAFE) this long after the call starts...
    pub warmup: Duration,
    /// ...unless some score is at or above this.
    pub warmup_override: f32,
    /// Updates in a row with combined >= scam_threshold needed for SCAM.
    pub scam_streak: u32,
    /// Money at or above this while another signal is at or above `money_rule_other` -> SCAM immediately.
    pub money_rule: f32,
    pub money_rule_other: f32,
    /// ...both within this many of the most recent caller lines. Default 6 = the whole window (the original
    /// rule); 2 = money and the other signal must come from the same or adjacent caller lines.
    pub money_rule_lines: usize,
    /// A signal at or above this is listed as a reason.
    pub reason_threshold: f32,
    /// Fewer workers than this answering a line -> UNSURE ("not enough data").
    pub min_quorum: usize,
    /// A call's memory is deleted this long after its last update.
    pub call_ttl: Duration,
    /// BERT second opinion (BertService) address; empty = off (the aggregator then works exactly as before).
    pub bert_addr: String,
    /// How long to wait for BERT. The gateway's AGGREGATOR_TIMEOUT_MS must leave room for this + Redis.
    pub bert_timeout: Duration,
    /// final = (1 - w) * fast vote + w * BERT, when BERT answered. 0.5 = plain average (task 7.4).
    pub bert_weight: f32,
    /// BERT below this cancels the instant money rule (the call then goes through the normal 2-in-a-row path
    /// with the averaged score); would catch "This is DBS, your card bill is due". Default 0 = OFF: the team
    /// tunes for recall (F2), so a second opinion may not cancel strong evidence until the evaluation (7.5)
    /// shows it is safe to (try 0.1-0.2 there).
    pub bert_veto: f32,
}

impl Settings {
    pub fn from_env() -> Result<Settings, String> {
        Settings::from_lookup(|key| env::var(key).ok())
    }

    /// Same as from_env, reading values through `get` (so tests don't touch the real environment).
    pub fn from_lookup(get: impl Fn(&str) -> Option<String>) -> Result<Settings, String> {
        let s = Settings {
            grpc_addr: text(&get, "AGGREGATOR_GRPC_ADDR", "0.0.0.0:50052"),
            http_addr: text(&get, "AGGREGATOR_HTTP_ADDR", "0.0.0.0:8082"),
            redis_url: text(&get, "REDIS_URL", "redis://redis:6379"),
            weights: [
                number(&get, "AGG_W_URGENCY", 1.0 / 3.0)?,
                number(&get, "AGG_W_MONEY", 1.0 / 3.0)?,
                number(&get, "AGG_W_IMPERSONATION", 1.0 / 3.0)?,
            ],
            scam_threshold: number(&get, "AGG_SCAM_THRESHOLD", 0.6)?,
            unsure_threshold: number(&get, "AGG_UNSURE_THRESHOLD", 0.45)?,
            bert_safe_threshold: number(&get, "AGG_BERT_SAFE_THRESHOLD", 0.3)?,
            bert_scam_threshold: number(&get, "AGG_BERT_SCAM_THRESHOLD", 0.6)?,
            fallback_strong_signal_threshold: number(
                &get,
                "AGG_FALLBACK_STRONG_SIGNAL_THRESHOLD",
                0.8,
            )?,
            red_disagreements_to_clear: number(&get, "AGG_RED_DISAGREEMENTS_TO_CLEAR", 2)?,
            window_lines: number(&get, "AGG_WINDOW_LINES", 6)?,
            warmup: Duration::from_secs(number(&get, "AGG_WARMUP_SECS", 15)?),
            warmup_override: number(&get, "AGG_WARMUP_OVERRIDE", 0.9)?,
            scam_streak: number(&get, "AGG_SCAM_STREAK", 2)?,
            money_rule: number(&get, "AGG_MONEY_RULE", 0.7)?,
            money_rule_other: number(&get, "AGG_MONEY_RULE_OTHER", 0.5)?,
            money_rule_lines: number(&get, "AGG_MONEY_RULE_LINES", 6)?,
            reason_threshold: number(&get, "AGG_REASON_THRESHOLD", 0.5)?,
            min_quorum: number(&get, "AGG_MIN_QUORUM", 2)?,
            call_ttl: Duration::from_secs(number(&get, "AGG_CALL_TTL_SECS", 3600)?),
            bert_addr: text(&get, "BERT_ADDR", ""),
            bert_timeout: Duration::from_millis(number(&get, "BERT_TIMEOUT_MS", 300)?),
            bert_weight: number(&get, "AGG_BERT_WEIGHT", 0.5)?,
            bert_veto: number(&get, "AGG_BERT_VETO", 0.0)?,
        };
        s.validate()?;
        Ok(s)
    }

    fn validate(&self) -> Result<(), String> {
        for (signal, w) in SIGNALS.iter().zip(self.weights) {
            if !w.is_finite() || w < 0.0 {
                return Err(format!(
                    "weight for {signal} must be a number >= 0, got {w}"
                ));
            }
        }
        if self.weights.iter().sum::<f32>() <= 0.0 {
            return Err("at least one weight must be above 0".into());
        }
        for (name, v) in [
            ("AGG_SCAM_THRESHOLD", self.scam_threshold),
            ("AGG_UNSURE_THRESHOLD", self.unsure_threshold),
            ("AGG_BERT_SAFE_THRESHOLD", self.bert_safe_threshold),
            ("AGG_BERT_SCAM_THRESHOLD", self.bert_scam_threshold),
            (
                "AGG_FALLBACK_STRONG_SIGNAL_THRESHOLD",
                self.fallback_strong_signal_threshold,
            ),
            ("AGG_WARMUP_OVERRIDE", self.warmup_override),
            ("AGG_MONEY_RULE", self.money_rule),
            ("AGG_MONEY_RULE_OTHER", self.money_rule_other),
            ("AGG_REASON_THRESHOLD", self.reason_threshold),
            ("AGG_BERT_WEIGHT", self.bert_weight),
            ("AGG_BERT_VETO", self.bert_veto),
        ] {
            if !(0.0..=1.0).contains(&v) {
                return Err(format!("{name} must be between 0 and 1, got {v}"));
            }
        }
        if self.unsure_threshold > self.scam_threshold {
            return Err(format!(
                "AGG_UNSURE_THRESHOLD ({}) must not be above AGG_SCAM_THRESHOLD ({})",
                self.unsure_threshold, self.scam_threshold
            ));
        }
        if self.bert_safe_threshold > self.bert_scam_threshold {
            return Err(format!(
                "AGG_BERT_SAFE_THRESHOLD ({}) must not be above AGG_BERT_SCAM_THRESHOLD ({})",
                self.bert_safe_threshold, self.bert_scam_threshold
            ));
        }
        if self.window_lines == 0 {
            return Err("AGG_WINDOW_LINES must be at least 1".into());
        }
        if self.money_rule_lines == 0 {
            return Err("AGG_MONEY_RULE_LINES must be at least 1".into());
        }
        if self.scam_streak == 0 {
            return Err("AGG_SCAM_STREAK must be at least 1".into());
        }
        if self.red_disagreements_to_clear == 0 {
            return Err("AGG_RED_DISAGREEMENTS_TO_CLEAR must be at least 1".into());
        }
        if !(1..=SIGNALS.len()).contains(&self.min_quorum) {
            return Err(format!(
                "AGG_MIN_QUORUM must be 1-{}, got {}",
                SIGNALS.len(),
                self.min_quorum
            ));
        }
        if self.call_ttl.is_zero() {
            return Err("AGG_CALL_TTL_SECS must be at least 1".into());
        }
        if !self.bert_addr.is_empty() && self.bert_timeout.is_zero() {
            return Err("BERT_TIMEOUT_MS must be at least 1 when BERT_ADDR is set".into());
        }
        Ok(())
    }

    /// REDIS_URL without a password, for logs ("redis://:secret@host:6379" -> "redis://***@host:6379").
    pub fn redis_url_for_log(&self) -> String {
        match (self.redis_url.find("://"), self.redis_url.rfind('@')) {
            (Some(scheme), Some(at)) if at > scheme => {
                format!(
                    "{}***{}",
                    &self.redis_url[..scheme + 3],
                    &self.redis_url[at..]
                )
            }
            _ => self.redis_url.clone(),
        }
    }
}

fn text(get: &impl Fn(&str) -> Option<String>, key: &str, default: &str) -> String {
    get(key)
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| default.to_string())
}

/// Unset or empty -> default; set but not a valid number -> error.
fn number<T: std::str::FromStr>(
    get: &impl Fn(&str) -> Option<String>,
    key: &str,
    default: T,
) -> Result<T, String> {
    match get(key)
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
    {
        None => Ok(default),
        Some(v) => v
            .parse()
            .map_err(|_| format!("{key}='{v}' is not a valid number")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn with(vars: &[(&str, &str)]) -> Result<Settings, String> {
        let map: HashMap<String, String> = vars
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect();
        Settings::from_lookup(|k| map.get(k).cloned())
    }

    #[test]
    fn defaults_are_the_workstream_rules() {
        let s = with(&[]).unwrap();
        assert_eq!(s.weights, [1.0 / 3.0; 3]);
        assert_eq!((s.scam_threshold, s.unsure_threshold), (0.6, 0.45));
        assert_eq!(s.window_lines, 6);
        assert_eq!(s.money_rule_lines, 6); // = the window: the original money rule
        assert_eq!(s.warmup, Duration::from_secs(15));
        assert_eq!(s.warmup_override, 0.9);
        assert_eq!(s.scam_streak, 2);
        assert_eq!((s.money_rule, s.money_rule_other), (0.7, 0.5));
        assert_eq!(s.min_quorum, 2);
        assert_eq!(s.call_ttl, Duration::from_secs(3600));
        assert_eq!(s.redis_url, "redis://redis:6379");
        assert_eq!(s.bert_addr, ""); // BERT off unless BERT_ADDR is set
        assert_eq!(s.bert_timeout, Duration::from_millis(300));
        assert_eq!((s.bert_weight, s.bert_veto), (0.5, 0.0)); // average; veto off
    }

    #[test]
    fn reads_overrides() {
        let s = with(&[
            ("AGG_W_MONEY", "0.5"),
            ("AGG_WINDOW_LINES", " 4 "),
            ("AGG_WARMUP_SECS", ""),
        ])
        .unwrap();
        assert_eq!(s.weights, [1.0 / 3.0, 0.5, 1.0 / 3.0]);
        assert_eq!(s.window_lines, 4);
        assert_eq!(s.warmup, Duration::from_secs(15)); // empty = default
    }

    #[test]
    fn rejects_bad_values() {
        assert!(with(&[("AGG_W_MONEY", "abc")]).is_err());
        assert!(with(&[("AGG_W_MONEY", "-1")]).is_err());
        assert!(with(&[
            ("AGG_W_URGENCY", "0"),
            ("AGG_W_MONEY", "0"),
            ("AGG_W_IMPERSONATION", "0")
        ])
        .is_err());
        assert!(with(&[("AGG_SCAM_THRESHOLD", "1.5")]).is_err());
        assert!(with(&[("AGG_UNSURE_THRESHOLD", "0.7")]).is_err()); // above the scam threshold
        assert!(with(&[("AGG_WINDOW_LINES", "0")]).is_err());
        assert!(with(&[("AGG_MIN_QUORUM", "4")]).is_err());
        assert!(with(&[("AGG_SCAM_STREAK", "0")]).is_err());
        assert!(with(&[("AGG_MONEY_RULE_LINES", "0")]).is_err());
        assert!(with(&[("AGG_BERT_WEIGHT", "1.5")]).is_err());
        assert!(with(&[("BERT_ADDR", "http://bert:50051"), ("BERT_TIMEOUT_MS", "0")]).is_err());
    }

    #[test]
    fn hides_redis_password_in_logs() {
        let s = with(&[("REDIS_URL", "redis://:secret@redis:6379/0")]).unwrap();
        assert_eq!(s.redis_url_for_log(), "redis://***@redis:6379/0");
        assert_eq!(with(&[]).unwrap().redis_url_for_log(), "redis://redis:6379");
    }
}
