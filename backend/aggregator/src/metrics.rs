//! Workstream H.1: counts for Prometheus (GET /metrics on the HTTP port). Numbers only, never text, so the
//! cluster can notice a new scam script (more UNSURE answers, more worker/BERT disagreement) without storing
//! a single sentence.
//!
//!   scamguard_lines_total{verdict}             caller lines answered, by the call's verdict after that line
//!   scamguard_calls_started_total              calls seen (first caller line)
//!   scamguard_bert_total{status}               BERT second opinion outcomes: ok / timeout / error / skipped / ...
//!   scamguard_bert_compared_total              lines where both the workers and BERT gave a score
//!   scamguard_disagreements_total{direction}   workers alarmed (fast >= scam threshold) but BERT safe (< 0.3):
//!                                              direction="bert_safer"; workers calm (fast < unsure threshold)
//!                                              but BERT scam (>= scam threshold): direction="bert_riskier"
//!   scamguard_signal_score_sum / _count{signal} average score per worker signal
//!   scamguard_calls_ended_total{ended_by,review} Workstream B: calls ended (phone or silence), by review type
//!   scamguard_calls_final_total{verdict}       H.1: calls ended, by their final verdict (SAFE / UNSURE / SCAM /
//!                                              UNKNOWN), so the dashboard can show the share of calls, not lines
//!   scamguard_late_lines_total                 Workstream B.6: caller lines refused because their call had ended
//!
//! Counters are per process; Prometheus scrapes every aggregator pod and adds them up.

use std::fmt::Write;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};

use crate::config::{Settings, SIGNALS};
use crate::policy::{Decision, LineScores, Verdict};

const VERDICTS: [&str; 3] = ["SAFE", "UNSURE", "SCAM"];
const BERT_STATUSES: [&str; 6] = ["ok", "timeout", "error", "skipped", "off", "no_context"];
const REVIEWS: [&str; 2] = ["bert", "fallback"];

#[derive(Default)]
pub struct Metrics {
    lines: [AtomicU64; 3],
    calls_started: AtomicU64,
    bert: [AtomicU64; 6],
    bert_other: AtomicU64,
    bert_compared: AtomicU64,
    bert_safer: AtomicU64,
    bert_riskier: AtomicU64,
    workers_higher: AtomicU64,
    bert_higher: AtomicU64,
    /// Sum of scores in millionths (atomics have no f64).
    signal_sum: [AtomicU64; 3],
    signal_count: [AtomicU64; 3],
    /// Workstream B: [ended_by (end::ENDED_BY order)][review (REVIEWS order)].
    ended: [[AtomicU64; 2]; 4],
    /// H.1: [SAFE, UNSURE, SCAM, UNKNOWN] final verdicts of ended calls.
    final_verdicts: [AtomicU64; 4],
    late_lines: AtomicU64,
}

impl Metrics {
    pub fn record(
        &self,
        call_id: &str,
        line: &LineScores,
        d: &Decision,
        bert_status: &str,
        s: &Settings,
    ) {
        let v = match d.verdict {
            Verdict::Safe => 0,
            Verdict::Unsure => 1,
            Verdict::Scam => 2,
        };
        self.lines[v].fetch_add(1, Relaxed);
        if !call_id.is_empty() && d.caller_lines == 1 {
            self.calls_started.fetch_add(1, Relaxed);
        }
        match BERT_STATUSES.iter().position(|x| *x == bert_status) {
            Some(i) => self.bert[i].fetch_add(1, Relaxed),
            None => self.bert_other.fetch_add(1, Relaxed),
        };
        if let Some(b) = d.bert {
            self.bert_compared.fetch_add(1, Relaxed);
            if d.fast >= s.scam_threshold && b < s.bert_safe_threshold {
                self.bert_safer.fetch_add(1, Relaxed);
            } else if d.fast < s.unsure_threshold && b >= s.bert_scam_threshold {
                self.bert_riskier.fetch_add(1, Relaxed);
            }
            if d.fast >= s.unsure_threshold && b < s.unsure_threshold {
                self.workers_higher.fetch_add(1, Relaxed);
            } else if d.fast < s.unsure_threshold && b >= s.unsure_threshold {
                self.bert_higher.fetch_add(1, Relaxed);
            }
        }
        for (i, score) in line.iter().enumerate() {
            if let Some(x) = score {
                self.signal_sum[i].fetch_add((x.clamp(0.0, 1.0) * 1e6) as u64, Relaxed);
                self.signal_count[i].fetch_add(1, Relaxed);
            }
        }
    }

    /// Workstream B: one call ended (counted once, by whoever ran the end).
    pub fn record_end(&self, ended_by: &str, review: &str, final_verdict: Option<Verdict>) {
        let i = crate::end::ENDED_BY.iter().position(|x| *x == ended_by);
        let j = REVIEWS.iter().position(|x| *x == review);
        if let (Some(i), Some(j)) = (i, j) {
            self.ended[i][j].fetch_add(1, Relaxed);
            let v = match final_verdict {
                Some(Verdict::Safe) => 0,
                Some(Verdict::Unsure) => 1,
                Some(Verdict::Scam) => 2,
                None => 3,
            };
            self.final_verdicts[v].fetch_add(1, Relaxed);
        }
    }

    /// Workstream B.6: a caller line arrived after its call ended and was refused.
    pub fn record_late_line(&self) {
        self.late_lines.fetch_add(1, Relaxed);
    }

    /// Prometheus text format 0.0.4.
    pub fn render(&self) -> String {
        let mut o = String::new();
        let _ = writeln!(o, "# HELP scamguard_lines_total Caller lines answered, by the call's verdict after the line.");
        let _ = writeln!(o, "# TYPE scamguard_lines_total counter");
        for (i, v) in VERDICTS.iter().enumerate() {
            let _ = writeln!(
                o,
                "scamguard_lines_total{{verdict=\"{v}\"}} {}",
                self.lines[i].load(Relaxed)
            );
        }
        let _ = writeln!(o, "# TYPE scamguard_calls_started_total counter");
        let _ = writeln!(
            o,
            "scamguard_calls_started_total {}",
            self.calls_started.load(Relaxed)
        );
        let _ = writeln!(
            o,
            "# HELP scamguard_bert_total BERT second-opinion outcomes per line."
        );
        let _ = writeln!(o, "# TYPE scamguard_bert_total counter");
        for (i, st) in BERT_STATUSES.iter().enumerate() {
            let _ = writeln!(
                o,
                "scamguard_bert_total{{status=\"{st}\"}} {}",
                self.bert[i].load(Relaxed)
            );
        }
        let _ = writeln!(
            o,
            "scamguard_bert_total{{status=\"other\"}} {}",
            self.bert_other.load(Relaxed)
        );
        let _ = writeln!(o, "# TYPE scamguard_bert_compared_total counter");
        let _ = writeln!(
            o,
            "scamguard_bert_compared_total {}",
            self.bert_compared.load(Relaxed)
        );
        let _ = writeln!(
            o,
            "# HELP scamguard_disagreements_total Workers and BERT on opposite sides."
        );
        let _ = writeln!(o, "# TYPE scamguard_disagreements_total counter");
        let _ = writeln!(
            o,
            "scamguard_disagreements_total{{direction=\"bert_safer\"}} {}",
            self.bert_safer.load(Relaxed)
        );
        let _ = writeln!(
            o,
            "scamguard_disagreements_total{{direction=\"bert_riskier\"}} {}",
            self.bert_riskier.load(Relaxed)
        );
        let _ = writeln!(
            o,
            "scamguard_disagreements_total{{direction=\"workers_higher\"}} {}",
            self.workers_higher.load(Relaxed)
        );
        let _ = writeln!(
            o,
            "scamguard_disagreements_total{{direction=\"bert_higher\"}} {}",
            self.bert_higher.load(Relaxed)
        );
        let _ = writeln!(o, "# TYPE scamguard_signal_score_sum counter");
        let _ = writeln!(o, "# TYPE scamguard_signal_score_count counter");
        for (i, sig) in SIGNALS.iter().enumerate() {
            let sum = self.signal_sum[i].load(Relaxed) as f64 / 1e6;
            let _ = writeln!(o, "scamguard_signal_score_sum{{signal=\"{sig}\"}} {sum}");
            let _ = writeln!(
                o,
                "scamguard_signal_score_count{{signal=\"{sig}\"}} {}",
                self.signal_count[i].load(Relaxed)
            );
        }
        let _ = writeln!(o, "# HELP scamguard_calls_ended_total Calls ended, by what ended them and the review used.");
        let _ = writeln!(o, "# TYPE scamguard_calls_ended_total counter");
        for (i, by) in crate::end::ENDED_BY.iter().enumerate() {
            for (j, review) in REVIEWS.iter().enumerate() {
                let n = self.ended[i][j].load(Relaxed);
                let _ = writeln!(o, "scamguard_calls_ended_total{{ended_by=\"{by}\",review=\"{review}\"}} {n}");
            }
        }
        let _ = writeln!(o, "# HELP scamguard_calls_final_total Calls ended, by their final verdict.");
        let _ = writeln!(o, "# TYPE scamguard_calls_final_total counter");
        for (i, v) in ["SAFE", "UNSURE", "SCAM", "UNKNOWN"].iter().enumerate() {
            let _ = writeln!(o, "scamguard_calls_final_total{{verdict=\"{v}\"}} {}", self.final_verdicts[i].load(Relaxed));
        }
        let _ = writeln!(o, "# TYPE scamguard_late_lines_total counter");
        let _ = writeln!(o, "scamguard_late_lines_total {}", self.late_lines.load(Relaxed));
        o
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy;

    #[test]
    fn counts_verdicts_disagreements_and_scores() {
        let cfg = Settings::from_lookup(|_| None).unwrap();
        let m = Metrics::default();
        // workers alarmed, BERT says safe -> bert_safer
        let mut d = policy::decide_one_shot([Some(0.9), Some(0.9), Some(0.9)], Some(0.1), &cfg);
        d.fast = 0.9;
        m.record("call-1", &[Some(0.9), Some(0.9), Some(0.9)], &d, "ok", &cfg);
        // no BERT, one signal missing
        let d2 = policy::decide_one_shot([Some(0.1), None, Some(0.2)], None, &cfg);
        m.record("", &[Some(0.1), None, Some(0.2)], &d2, "skipped", &cfg);
        let out = m.render();
        assert!(out.contains("scamguard_disagreements_total{direction=\"bert_safer\"} 1"));
        assert!(out.contains("scamguard_bert_compared_total 1"));
        assert!(out.contains("scamguard_bert_total{status=\"skipped\"} 1"));
        assert!(out.contains("scamguard_signal_score_count{signal=\"money\"} 1"));
        assert!(out.contains("scamguard_signal_score_count{signal=\"urgency\"} 2"));
        let total: u64 = m.lines.iter().map(|x| x.load(Relaxed)).sum();
        assert_eq!(total, 2);
    }

    #[test]
    fn counts_ended_calls_and_late_lines() {
        let m = Metrics::default();
        m.record_end("silence", "fallback", None);
        m.record_end("user_hung_up", "fallback", Some(Verdict::Scam));
        m.record_end("user_hung_up", "fallback", Some(Verdict::Safe));
        m.record_end("nonsense", "fallback", Some(Verdict::Scam)); // ignored
        m.record_late_line();
        let out = m.render();
        assert!(out.contains("scamguard_calls_ended_total{ended_by=\"silence\",review=\"fallback\"} 1"));
        assert!(out.contains("scamguard_calls_ended_total{ended_by=\"user_hung_up\",review=\"fallback\"} 2"));
        assert!(out.contains("scamguard_calls_ended_total{ended_by=\"user_hung_up\",review=\"bert\"} 0"));
        assert!(out.contains("scamguard_late_lines_total 1"));
        assert!(out.contains("scamguard_calls_final_total{verdict=\"SCAM\"} 1"));
        assert!(out.contains("scamguard_calls_final_total{verdict=\"SAFE\"} 1"));
        assert!(out.contains("scamguard_calls_final_total{verdict=\"UNKNOWN\"} 1"));
    }
}
