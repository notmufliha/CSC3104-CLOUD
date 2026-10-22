//! Per-call memory: the verdict for a call is built from ALL of its caller lines so far, not just the latest.
//!
//! For each signal we keep the highest score seen in the call; those best scores go to the aggregator.
//! So one clearly pressuring sentence keeps the call flagged even if the next line is small talk, and a
//! signal whose worker missed one line still counts with its earlier score. Because it is a maximum, the
//! order lines arrive in does not change the verdict.
//!
//! Sequence numbers: when the phone sends `seq`, each (call, seq) is scored once. A resent line (same seq)
//! gets the current verdict back without calling the workers again.
//!
//! Lives in the gateway's memory: with several gateway replicas, all lines of one call must reach the same
//! replica (sticky routing on call_id), or this moves to a shared store. Calls are forgotten after
//! CALL_TTL_SECS without a new line.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Remember at most this many sequence numbers per call (a call has far fewer lines than this).
const MAX_SEQS_PER_CALL: usize = 10_000;

#[derive(Clone, Debug, serde::Serialize)]
pub struct Verdict {
    pub verdict: String,
    pub combined_score: f32,
}

/// What the call looks like right now (for receiver lines and resent lines).
pub struct Snapshot {
    pub verdict: Option<Verdict>,
    pub best: BTreeMap<&'static str, f32>,
    pub caller_lines: u32,
}

struct CallState {
    best: BTreeMap<&'static str, f32>,
    verdict: Option<Verdict>,
    caller_lines: u32,
    seen_seqs: HashSet<u64>,
    last_seen: Instant,
}

pub struct CallStore {
    calls: Mutex<HashMap<String, CallState>>,
    ttl: Duration,
    max_calls: usize,
}

impl CallStore {
    pub fn new(ttl: Duration, max_calls: usize) -> CallStore {
        CallStore { calls: Mutex::new(HashMap::new()), ttl, max_calls }
    }

    /// The call as it is now; an unknown call is empty (no verdict yet).
    pub fn snapshot(&self, call_id: &str) -> Snapshot {
        let mut calls = self.calls.lock().unwrap();
        match calls.get_mut(call_id) {
            Some(c) => {
                c.last_seen = Instant::now();
                Snapshot { verdict: c.verdict.clone(), best: c.best.clone(), caller_lines: c.caller_lines }
            }
            None => Snapshot { verdict: None, best: BTreeMap::new(), caller_lines: 0 },
        }
    }

    /// Record that line `seq` of this call has arrived. Returns false if it was already recorded (a resend).
    /// Done before the workers are called, so two copies of the same line arriving together are scored once.
    pub fn mark_seq(&self, call_id: &str, seq: u64) -> bool {
        let mut calls = self.calls.lock().unwrap();
        let c = self.get_or_create(&mut calls, call_id);
        c.last_seen = Instant::now();
        if c.seen_seqs.contains(&seq) {
            return false;
        }
        if c.seen_seqs.len() < MAX_SEQS_PER_CALL {
            c.seen_seqs.insert(seq);
        }
        true
    }

    /// Forget a recorded seq, so the phone's retry is scored (used when scoring this line failed).
    pub fn unmark_seq(&self, call_id: &str, seq: u64) {
        if let Some(c) = self.calls.lock().unwrap().get_mut(call_id) {
            c.seen_seqs.remove(&seq);
        }
    }

    /// Merge this line's scores into the call; returns the call's best score per signal and its line count.
    pub fn merge_scores(&self, call_id: &str, scores: &[(&'static str, f32)]) -> (BTreeMap<&'static str, f32>, u32) {
        let mut calls = self.calls.lock().unwrap();
        let c = self.get_or_create(&mut calls, call_id);
        for &(signal, score) in scores {
            let e = c.best.entry(signal).or_insert(score);
            if score > *e {
                *e = score;
            }
        }
        c.caller_lines += 1;
        c.last_seen = Instant::now();
        (c.best.clone(), c.caller_lines)
    }

    pub fn set_verdict(&self, call_id: &str, verdict: Verdict) {
        if let Some(c) = self.calls.lock().unwrap().get_mut(call_id) {
            c.verdict = Some(verdict);
        }
    }

    pub fn len(&self) -> usize {
        self.calls.lock().unwrap().len()
    }

    fn get_or_create<'a>(&self, calls: &'a mut HashMap<String, CallState>, call_id: &str) -> &'a mut CallState {
        if !calls.contains_key(call_id) {
            self.evict(calls);
            calls.insert(call_id.to_string(), CallState {
                best: BTreeMap::new(),
                verdict: None,
                caller_lines: 0,
                seen_seqs: HashSet::new(),
                last_seen: Instant::now(),
            });
        }
        calls.get_mut(call_id).expect("just inserted")
    }

    /// Only runs when a NEW call is added: drop expired calls, then the longest-idle ones above the cap.
    fn evict(&self, calls: &mut HashMap<String, CallState>) {
        let now = Instant::now();
        calls.retain(|_, c| now.duration_since(c.last_seen) < self.ttl);
        while calls.len() >= self.max_calls {
            let oldest = calls.iter().min_by_key(|(_, c)| c.last_seen).map(|(k, _)| k.clone());
            match oldest {
                Some(k) => {
                    calls.remove(&k);
                }
                None => break,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_best_score_per_signal() {
        let store = CallStore::new(Duration::from_secs(60), 100);
        store.merge_scores("c1", &[("urgency", 0.9), ("money", 0.1)]);
        let (best, n) = store.merge_scores("c1", &[("urgency", 0.2), ("impersonation", 0.7)]);
        assert_eq!(best["urgency"], 0.9);
        assert_eq!(best["money"], 0.1);
        assert_eq!(best["impersonation"], 0.7);
        assert_eq!(n, 2);
        assert!(store.snapshot("c1").verdict.is_none());
        store.set_verdict("c1", Verdict { verdict: "SCAM".into(), combined_score: 0.8 });
        assert_eq!(store.snapshot("c1").verdict.unwrap().verdict, "SCAM");
        assert!(store.snapshot("unknown").verdict.is_none());
    }

    #[test]
    fn each_seq_is_scored_once() {
        let store = CallStore::new(Duration::from_secs(60), 100);
        assert!(store.mark_seq("c1", 1));
        assert!(!store.mark_seq("c1", 1)); // resend
        assert!(store.mark_seq("c1", 3)); // out of order is fine
        assert!(store.mark_seq("c1", 2));
        assert!(store.mark_seq("c2", 1)); // seqs are per call
        store.unmark_seq("c1", 2); // scoring failed -> a retry is scored
        assert!(store.mark_seq("c1", 2));
    }

    #[test]
    fn caps_number_of_calls() {
        let store = CallStore::new(Duration::from_secs(60), 2);
        for id in ["a", "b", "c"] {
            store.merge_scores(id, &[("urgency", 0.5)]);
        }
        assert!(store.len() <= 2);
    }
}
