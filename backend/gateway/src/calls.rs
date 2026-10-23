//! What the gateway remembers per call. The decision memory (each signal's recent highest score, the "2 in a
//! row" streak, red-stays-red) lives in Redis and belongs to the aggregator; the gateway only keeps:
//!
//!   - which line numbers (`seq`) it has already scored, so a resent line is scored once;
//!   - the aggregator's last answer, so a receiver line or a resent line gets the call's current verdict
//!     without calling the workers.
//!
//! Lives in the gateway's memory: with several gateway replicas, all lines of one call should reach the same
//! replica (sticky routing on call_id) for these two to work; the verdicts themselves are right either way.
//! Calls are forgotten after CALL_TTL_SECS without a new line.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Remember at most this many sequence numbers per call (a call has far fewer lines than this).
const MAX_SEQS_PER_CALL: usize = 10_000;

#[derive(Clone, Debug, serde::Serialize)]
pub struct Reason {
    pub signal: String,
    pub score: f32,
    pub text: String,
}

/// The aggregator's answer for the call after its latest caller line.
#[derive(Clone, Debug, Default)]
pub struct Answer {
    pub verdict: String,
    pub combined_score: f32,
    pub reasons: Vec<Reason>,
    pub action: String,
    pub recent_scores: BTreeMap<String, f32>,
    pub warming_up: bool,
    pub quorum_met: bool,
    pub caller_lines: u32,
}

struct CallState {
    last: Option<Answer>,
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

    /// The aggregator's last answer for this call; None before its first scored caller line.
    pub fn last_answer(&self, call_id: &str) -> Option<Answer> {
        let mut calls = self.calls.lock().unwrap();
        calls.get_mut(call_id).and_then(|c| {
            c.last_seen = Instant::now();
            c.last.clone()
        })
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

    pub fn set_answer(&self, call_id: &str, answer: Answer) {
        let mut calls = self.calls.lock().unwrap();
        let c = self.get_or_create(&mut calls, call_id);
        c.last = Some(answer);
        c.last_seen = Instant::now();
    }

    pub fn len(&self) -> usize {
        self.calls.lock().unwrap().len()
    }

    fn get_or_create<'a>(&self, calls: &'a mut HashMap<String, CallState>, call_id: &str) -> &'a mut CallState {
        if !calls.contains_key(call_id) {
            self.evict(calls);
            calls.insert(
                call_id.to_string(),
                CallState { last: None, seen_seqs: HashSet::new(), last_seen: Instant::now() },
            );
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
    fn keeps_the_last_answer() {
        let store = CallStore::new(Duration::from_secs(60), 100);
        assert!(store.last_answer("c1").is_none());
        store.set_answer("c1", Answer { verdict: "UNSURE".into(), caller_lines: 1, ..Default::default() });
        store.set_answer("c1", Answer { verdict: "SCAM".into(), caller_lines: 2, ..Default::default() });
        let a = store.last_answer("c1").unwrap();
        assert_eq!((a.verdict.as_str(), a.caller_lines), ("SCAM", 2));
        assert!(store.last_answer("unknown").is_none());
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
            store.set_answer(id, Answer::default());
        }
        assert!(store.len() <= 2);
    }
}
