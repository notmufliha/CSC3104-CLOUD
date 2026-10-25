//! The decision rules: when to stay quiet, when to say UNSURE and when to warn SCAM.
//!
//! Pure logic, no Redis and no gRPC: `decide` takes the call's memory as it was, this line's scores, BERT's
//! score (if it was asked) and the time, and returns the call's new memory plus the answer. store.rs
//! loads/saves the memory and asks BERT, main.rs turns the answer into the gRPC reply. Rules, in the order
//! they are checked:
//!
//!   1. Add this line to the window (last `window_lines` caller lines); each signal's recent score is its
//!      highest in the window, because a scammer says "police", "act now" and "give me the OTP" in different
//!      sentences.
//!   2. Already red          -> SCAM (red stays red for the rest of the call).
//!   3. Fewer than `min_quorum` workers answered this line -> UNSURE "not enough data" (also during warm-up),
//!      unless BERT answered: then BERT's score stands in for the vote and rules 5-9 apply to it.
//!   4. fast = weighted average of the recent scores; a signal with no score shares its weight out.
//!      final = fast, or (1 - bert_weight) * fast + bert_weight * BERT when BERT answered.
//!   5. Warm-up: inside the first `warmup` of the call and no recent score (nor BERT) >= `warmup_override`
//!      -> SAFE (quiet). Rules 6-9 are skipped, the money rule included; the scores stay in the window, so they
//!      still count on the first line after the warm-up.
//!   6. Money rule: money >= `money_rule` while another signal >= `money_rule_other` -> SCAM immediately,
//!      unless BERT answered below `bert_veto` (then rules 7-9 decide on final). bert_veto is 0 (off) by
//!      default: BERT can soften the unsure/high path but not cancel the money rule unless switched on.
//!   7. final >= `scam_threshold` -> one more in the streak; `scam_streak` in a row -> SCAM, else UNSURE.
//!   8. final >= `unsure_threshold` -> UNSURE (streak back to 0).
//!   9. otherwise SAFE (streak back to 0).
//!
//! The cascade (task 7.4): BERT is slow, so it is asked only when the answer WITHOUT it would be decided by
//! rule 3, 6, 7 or 8 (not enough data, money rule, high, unsure); see `wants_second_opinion`. A call that is
//! clearly SAFE, still warming up, or already red never costs a BERT call.

use std::time::Duration;

use crate::config::{Settings, SIGNALS};

/// One caller line's scores in SIGNALS order (urgency, money, impersonation); None = that worker didn't answer.
pub type LineScores = [Option<f32>; 3];

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Verdict {
    #[default]
    Safe,
    Unsure,
    Scam,
}

impl Verdict {
    pub fn as_str(self) -> &'static str {
        match self {
            Verdict::Safe => "SAFE",
            Verdict::Unsure => "UNSURE",
            Verdict::Scam => "SCAM",
        }
    }

    pub fn parse(s: &str) -> Option<Verdict> {
        match s {
            "SAFE" => Some(Verdict::Safe),
            "UNSURE" => Some(Verdict::Unsure),
            "SCAM" => Some(Verdict::Scam),
            _ => None,
        }
    }
}

/// A call's memory (stored in Redis under call:<call_id> by store.rs). Default = a call not seen before.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct CallState {
    /// Aggregator clock (ms since 1970) when the call started; 0 = not started yet.
    pub started_at_ms: i64,
    /// Scores of the most recent caller lines, oldest first, at most `window_lines`.
    pub window: Vec<LineScores>,
    /// Updates in a row with combined >= scam_threshold.
    pub streak: u32,
    /// Once true, the call is SCAM for good.
    pub red: bool,
    pub caller_lines: u32,
    pub verdict: Verdict,
    pub combined: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Reason {
    /// "urgency" / "money" / "impersonation", or "quorum" for "not enough data".
    pub signal: &'static str,
    pub score: f32,
    pub text: String,
}

/// The answer for one line.
#[derive(Clone, Debug, PartialEq)]
pub struct Decision {
    pub verdict: Verdict,
    /// The score the verdict was decided on: `fast`, or the blend with BERT when BERT answered.
    pub combined: f32,
    /// The workers' weighted vote alone (rule 4), before BERT.
    pub fast: f32,
    /// BERT's score, when it was asked and answered.
    pub bert: Option<f32>,
    /// Each signal's highest score in the window (None = no score in the window).
    pub recent: LineScores,
    pub reasons: Vec<Reason>,
    /// One suggested action; empty when SAFE.
    pub action: &'static str,
    pub warming_up: bool,
    pub quorum_met: bool,
    pub caller_lines: u32,
    /// Which rule decided (for logs and tests): red, quorum, warmup, money_rule, streak, high_once, unsure, safe.
    pub rule: &'static str,
}

pub const ACTION_SCAM: &str = "Hang up now. Call the organisation back on its official number, or ScamShield on 1799.";
pub const ACTION_UNSURE: &str = "Don't share any codes or transfer money. Hang up and check with the official number.";

fn reason_text(signal: &str) -> &'static str {
    match signal {
        "money" => "Asked for your OTP or money",
        "impersonation" => "Claims to be police, a bank or the government",
        "urgency" => "Pressuring you to act now",
        "bert" => "The conversation as a whole sounds like a scam",
        _ => "Suspicious",
    }
}

/// How far into the call this line is. The phone's `call_time_ms` wins when given (> 0); otherwise the
/// aggregator's clock since the call's first line.
pub fn elapsed(started_at_ms: i64, now_ms: i64, call_time_ms: i64) -> Duration {
    let ms = if call_time_ms > 0 { call_time_ms } else { now_ms - started_at_ms };
    Duration::from_millis(ms.max(0) as u64)
}

/// Rules whose answer BERT can change; anything else (red, warmup, safe) is decided without it.
const SECOND_OPINION_RULES: [&str; 5] = ["quorum", "money_rule", "high_once", "streak", "unsure"];

/// Would this line's answer be decided by the quorum, money, high or unsure rule without BERT? Only then is
/// BERT worth its cost. Same inputs as `decide`; changes nothing.
pub fn wants_second_opinion(state: &CallState, line: LineScores, now_ms: i64, call_time_ms: i64, cfg: &Settings) -> bool {
    let (_, d) = decide(state.clone(), line, None, now_ms, call_time_ms, cfg);
    SECOND_OPINION_RULES.contains(&d.rule)
}

/// Same for a one-shot request (no call memory).
pub fn one_shot_wants_second_opinion(line: LineScores, cfg: &Settings) -> bool {
    SECOND_OPINION_RULES.contains(&decide_one_shot(line, None, cfg).rule)
}

/// fast vote + BERT -> the score the thresholds are applied to. Without a quorum, BERT stands in alone.
fn blend(fast: f32, bert: Option<f32>, quorum_met: bool, cfg: &Settings) -> f32 {
    match bert {
        Some(b) if quorum_met => (1.0 - cfg.bert_weight) * fast + cfg.bert_weight * b,
        Some(b) => b,
        None => fast,
    }
}

/// Apply one caller line to the call. `state` is the call's memory before this line (Default for a new call).
/// `bert` = BERT's score for the call's recent lines, if it was asked and answered (None otherwise).
pub fn decide(
    mut state: CallState,
    line: LineScores,
    bert: Option<f32>,
    now_ms: i64,
    call_time_ms: i64,
    cfg: &Settings,
) -> (CallState, Decision) {
    if state.started_at_ms == 0 {
        state.started_at_ms = now_ms - call_time_ms.max(0);
    }
    let since_start = elapsed(state.started_at_ms, now_ms, call_time_ms);

    // 1. window + recent highs
    state.window.push(line);
    if state.window.len() > cfg.window_lines {
        let extra = state.window.len() - cfg.window_lines;
        state.window.drain(..extra);
    }
    state.caller_lines += 1;
    let recent = recent_highs(&state.window);
    let fast = combined_score(&recent, cfg);
    let answered = line.iter().filter(|s| s.is_some()).count();
    let quorum_met = answered >= cfg.min_quorum;
    let warming_up = since_start < cfg.warmup;
    let combined = blend(fast, bert, quorum_met, cfg);
    let override_hit =
        recent.iter().flatten().any(|s| *s >= cfg.warmup_override) || bert.is_some_and(|b| b >= cfg.warmup_override);
    let vetoed = bert.is_some_and(|b| b < cfg.bert_veto);

    let (verdict, rule) = if state.red {
        // 2.
        (Verdict::Scam, "red")
    } else if !quorum_met && bert.is_none() {
        // 3.
        (Verdict::Unsure, "quorum")
    } else if warming_up && !override_hit {
        // 5.
        state.streak = 0;
        (Verdict::Safe, "warmup")
    } else if quorum_met && money_rule(&recent, cfg) && !vetoed {
        // 6.
        (Verdict::Scam, "money_rule")
    } else if combined >= cfg.scam_threshold {
        // 7.
        state.streak += 1;
        if state.streak >= cfg.scam_streak {
            (Verdict::Scam, "streak")
        } else {
            (Verdict::Unsure, "high_once")
        }
    } else if combined >= cfg.unsure_threshold {
        // 8.
        state.streak = 0;
        (Verdict::Unsure, "unsure")
    } else {
        // 9.
        state.streak = 0;
        (Verdict::Safe, "safe")
    };

    if verdict == Verdict::Scam {
        state.red = true;
    }
    state.verdict = verdict;
    state.combined = combined;

    let mut reasons = if verdict == Verdict::Safe { vec![] } else { all_reasons(&recent, bert, cfg) };
    if rule == "quorum" {
        reasons.push(Reason {
            signal: "quorum",
            score: answered as f32,
            text: format!("Not enough data: only {answered} of {} checks answered", SIGNALS.len()),
        });
    }
    let decision = Decision {
        verdict,
        combined,
        fast,
        bert,
        recent,
        reasons,
        action: action_for(verdict),
        warming_up,
        quorum_met,
        caller_lines: state.caller_lines,
        rule,
    };
    (state, decision)
}

/// A request with no call_id (the gateway's one-shot /submit-transcript): no memory, so no warm-up and no
/// streak; quorum (or BERT standing in), money rule (with the BERT veto) and the thresholds still apply.
pub fn decide_one_shot(line: LineScores, bert: Option<f32>, cfg: &Settings) -> Decision {
    let recent = line;
    let fast = combined_score(&recent, cfg);
    let answered = line.iter().filter(|s| s.is_some()).count();
    let quorum_met = answered >= cfg.min_quorum;
    let combined = blend(fast, bert, quorum_met, cfg);
    let vetoed = bert.is_some_and(|b| b < cfg.bert_veto);
    let (verdict, rule) = if !quorum_met && bert.is_none() {
        (Verdict::Unsure, "quorum")
    } else if quorum_met && money_rule(&recent, cfg) && !vetoed {
        (Verdict::Scam, "money_rule")
    } else if combined >= cfg.scam_threshold {
        (Verdict::Scam, "streak")
    } else if combined >= cfg.unsure_threshold {
        (Verdict::Unsure, "unsure")
    } else {
        (Verdict::Safe, "safe")
    };
    let reasons = if verdict == Verdict::Safe { vec![] } else { all_reasons(&recent, bert, cfg) };
    Decision {
        verdict,
        combined,
        fast,
        bert,
        recent,
        reasons,
        action: action_for(verdict),
        warming_up: false,
        quorum_met,
        caller_lines: 1,
        rule,
    }
}

/// Each signal's highest score in the window.
fn recent_highs(window: &[LineScores]) -> LineScores {
    let mut out: LineScores = [None; 3];
    for line in window {
        for (best, score) in out.iter_mut().zip(line) {
            if let Some(s) = score {
                *best = Some(best.map_or(*s, |b| b.max(*s)));
            }
        }
    }
    out
}

/// Weighted average over the signals that have a score: a missing signal's weight is shared among the others.
fn combined_score(recent: &LineScores, cfg: &Settings) -> f32 {
    let (mut sum, mut weight) = (0.0f32, 0.0f32);
    for (score, w) in recent.iter().zip(cfg.weights) {
        if let Some(s) = score {
            sum += w * s;
            weight += w;
        }
    }
    if weight > 0.0 {
        sum / weight
    } else {
        0.0
    }
}

fn money_rule(recent: &LineScores, cfg: &Settings) -> bool {
    let money = SIGNALS.iter().position(|s| *s == "money").expect("money is a signal");
    let money_high = recent[money].is_some_and(|m| m >= cfg.money_rule);
    let other_high = recent
        .iter()
        .enumerate()
        .any(|(i, s)| i != money && s.is_some_and(|s| s >= cfg.money_rule_other));
    money_high && other_high
}

/// Signals (and BERT) at or above the reason threshold, strongest first.
fn all_reasons(recent: &LineScores, bert: Option<f32>, cfg: &Settings) -> Vec<Reason> {
    let mut out = signal_reasons(recent, cfg);
    if let Some(b) = bert.filter(|b| *b >= cfg.reason_threshold) {
        out.push(Reason { signal: "bert", score: b, text: reason_text("bert").to_string() });
        out.sort_by(|a, b| b.score.total_cmp(&a.score));
    }
    out
}

/// Signals at or above the reason threshold, strongest first.
fn signal_reasons(recent: &LineScores, cfg: &Settings) -> Vec<Reason> {
    let mut out: Vec<Reason> = SIGNALS
        .iter()
        .zip(recent)
        .filter_map(|(signal, score)| score.filter(|s| *s >= cfg.reason_threshold).map(|s| (*signal, s)))
        .map(|(signal, score)| Reason { signal, score, text: reason_text(signal).to_string() })
        .collect();
    out.sort_by(|a, b| b.score.total_cmp(&a.score));
    out
}

fn action_for(verdict: Verdict) -> &'static str {
    match verdict {
        Verdict::Scam => ACTION_SCAM,
        Verdict::Unsure => ACTION_UNSURE,
        Verdict::Safe => "",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const START: i64 = 1_700_000_000_000; // some aggregator clock time, ms

    fn cfg() -> Settings {
        Settings::from_lookup(|_| None).unwrap()
    }

    fn cfg_with(vars: &[(&str, &str)]) -> Settings {
        let vars: Vec<(String, String)> = vars.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        Settings::from_lookup(|k| vars.iter().find(|(key, _)| key == k).map(|(_, v)| v.clone())).unwrap()
    }

    /// Feed lines (seconds into the call, scores) through decide with the phone's call time, as a scripted
    /// replay would; returns every decision.
    fn replay(cfg: &Settings, lines: &[(i64, LineScores)]) -> (CallState, Vec<Decision>) {
        let mut state = CallState::default();
        let mut out = vec![];
        for (secs, line) in lines {
            let (s, d) = decide(state, *line, None, START + secs * 1000, secs * 1000, cfg);
            state = s;
            out.push(d);
        }
        (state, out)
    }

    fn all(u: f32, m: f32, i: f32) -> LineScores {
        [Some(u), Some(m), Some(i)]
    }

    #[test]
    fn missing_worker_shares_its_weight() {
        // urgency 0.9, money missing, impersonation 0.6: (0.9 + 0.6) / 2, not (0.9 + 0.6) / 3
        let (_, d) = replay(&cfg(), &[(20, [Some(0.9), None, Some(0.6)])]);
        assert!((d[0].combined - 0.75).abs() < 1e-6);
        assert!(d[0].quorum_met);
    }

    #[test]
    fn fewer_than_two_answers_is_unsure_even_in_warmup() {
        let (_, d) = replay(&cfg(), &[(2, [None, Some(0.1), None])]);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Unsure, "quorum"));
        assert!(!d[0].quorum_met && d[0].warming_up);
        assert!(d[0].reasons.iter().any(|r| r.signal == "quorum"));
        assert_eq!(d[0].action, ACTION_UNSURE);
    }

    #[test]
    fn warmup_stays_quiet_even_against_the_money_rule() {
        // 8 s in: "I'm from DBS, read me the code" - money 0.8, impersonation 0.6, nothing >= 0.9
        let (_, d) = replay(&cfg(), &[(8, all(0.2, 0.8, 0.6)), (17, all(0.1, 0.1, 0.1))]);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Safe, "warmup"));
        assert!(d[0].warming_up && d[0].reasons.is_empty() && d[0].action.is_empty());
        // first line after the warm-up: the window still remembers 0.8 / 0.6 -> money rule fires
        assert_eq!((d[1].verdict, d[1].rule), (Verdict::Scam, "money_rule"));
        assert!(!d[1].warming_up);
    }

    #[test]
    fn a_very_high_score_breaks_the_warmup() {
        let (_, d) = replay(&cfg(), &[(5, all(0.2, 0.95, 0.6))]);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Scam, "money_rule"));
        assert!(d[0].warming_up);
    }

    #[test]
    fn money_rule_needs_another_signal() {
        let (_, d) = replay(&cfg(), &[(20, all(0.1, 0.95, 0.1))]);
        assert_ne!(d[0].rule, "money_rule"); // money alone ("your bill is due") is not enough
        assert_ne!(d[0].verdict, Verdict::Scam);
    }

    #[test]
    fn scam_needs_two_high_updates_in_a_row() {
        // money 0.6 keeps the money rule out of it, so only the streak decides
        let (_, d) = replay(&cfg(), &[(20, all(0.65, 0.6, 0.65)), (25, all(0.1, 0.1, 0.1))]);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Unsure, "high_once"));
        // the window still holds the high line, so the next update is high too -> second in a row
        assert_eq!((d[1].verdict, d[1].rule), (Verdict::Scam, "streak"));
    }

    #[test]
    fn a_low_update_resets_the_streak() {
        let cfg = cfg_with(&[("AGG_WINDOW_LINES", "1")]); // window of 1 so a low line really is low
        let (_, d) = replay(&cfg, &[(20, all(0.65, 0.6, 0.65)), (25, all(0.1, 0.1, 0.1)), (30, all(0.65, 0.6, 0.65))]);
        assert_eq!(d[0].rule, "high_once");
        assert_eq!((d[1].verdict, d[1].rule), (Verdict::Safe, "safe"));
        assert_eq!(d[2].rule, "high_once"); // streak started again, not SCAM
    }

    #[test]
    fn red_stays_red() {
        let (state, d) = replay(&cfg(), &[(20, all(0.9, 0.9, 0.9)), (25, all(0.0, 0.0, 0.0)), (99, [None, None, None])]);
        assert_eq!(d[0].verdict, Verdict::Scam);
        assert_eq!((d[1].verdict, d[1].rule), (Verdict::Scam, "red"));
        assert_eq!((d[2].verdict, d[2].rule), (Verdict::Scam, "red")); // even with no answers at all
        assert!(state.red);
        assert_eq!(d[2].action, ACTION_SCAM);
    }

    #[test]
    fn unsure_band() {
        let (_, d) = replay(&cfg(), &[(20, all(0.5, 0.5, 0.5))]);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Unsure, "unsure"));
    }

    #[test]
    fn window_forgets_old_lines() {
        let mut lines = vec![(20, all(0.9, 0.2, 0.2))];
        for i in 0..6 {
            lines.push((25 + i, all(0.1, 0.1, 0.1)));
        }
        let cfg = cfg_with(&[("AGG_W_URGENCY", "0.01")]); // keep urgency from making the call red
        let (state, d) = replay(&cfg, &lines);
        assert_eq!(d[5].recent[0], Some(0.9)); // 6 lines: the 0.9 is still in the window
        assert_eq!(d[6].recent[0], Some(0.1)); // 7th line: it has dropped out
        assert_eq!(state.window.len(), 6);
        assert_eq!(state.caller_lines, 7);
    }

    #[test]
    fn normal_call_stays_green() {
        let lines: Vec<(i64, LineScores)> =
            (0..10).map(|i| (i * 4, all(0.35, 0.3, 0.05))).collect();
        let (state, d) = replay(&cfg(), &lines);
        assert!(d.iter().all(|d| d.verdict == Verdict::Safe), "{d:?}");
        assert!(!state.red);
    }

    #[test]
    fn scripted_scam_turns_red_at_the_right_line() {
        let lines = [
            (0, all(0.1, 0.0, 0.2)),   // "Hello, is this Mr Tan?"
            (6, all(0.2, 0.1, 0.95)),  // "This is Inspector Lim from SPF" -> breaks warm-up, but money is low
            (20, all(0.6, 0.2, 0.3)),  // "Your account is linked to a money-laundering case, act now"
            (28, all(0.4, 0.95, 0.2)), // "Read me the OTP we just sent"  -> money rule
        ];
        let (_, d) = replay(&cfg(), &lines);
        assert_eq!(d[0].verdict, Verdict::Safe);
        assert_ne!(d[1].verdict, Verdict::Scam);
        assert_ne!(d[2].verdict, Verdict::Scam);
        assert_eq!((d[3].verdict, d[3].rule), (Verdict::Scam, "money_rule"));
        let signals: Vec<_> = d[3].reasons.iter().map(|r| r.signal).collect();
        assert_eq!(signals, ["money", "impersonation", "urgency"]); // strongest first
        assert_eq!(d[3].reasons[0].text, "Asked for your OTP or money");
    }

    #[test]
    fn phone_time_wins_over_aggregator_clock() {
        let cfg = cfg();
        // two lines that arrive 1 s apart, but the phone says they were spoken at 3 s and 40 s
        let (s, d1) = decide(CallState::default(), all(0.1, 0.1, 0.1), None, START, 3_000, &cfg);
        assert_eq!(s.started_at_ms, START - 3_000);
        assert!(d1.warming_up);
        let (_, d2) = decide(s, all(0.1, 0.1, 0.1), None, START + 1_000, 40_000, &cfg);
        assert!(!d2.warming_up);
        // without phone time: aggregator clock since the first line
        assert_eq!(elapsed(START, START + 16_000, 0), Duration::from_secs(16));
    }

    #[test]
    fn one_shot_has_no_warmup_or_streak() {
        let cfg = cfg();
        assert_eq!(decide_one_shot(all(0.9, 0.9, 0.9), None, &cfg).verdict, Verdict::Scam);
        assert_eq!(decide_one_shot(all(0.1, 0.1, 0.1), None, &cfg).verdict, Verdict::Safe);
        assert_eq!(decide_one_shot([Some(0.9), None, None], None, &cfg).rule, "quorum");
    }

    // ---------------------------------------------------------------- BERT second opinion (cascade, 7.4)

    /// Like replay, but line i gets bert[i] as BERT's answer (None = not asked / no answer).
    fn replay_bert(cfg: &Settings, lines: &[(i64, LineScores, Option<f32>)]) -> Vec<Decision> {
        let mut state = CallState::default();
        let mut out = vec![];
        for (secs, line, bert) in lines {
            let (s, d) = decide(state, *line, *bert, START + secs * 1000, secs * 1000, cfg);
            state = s;
            out.push(d);
        }
        out
    }

    #[test]
    fn bert_is_only_wanted_when_the_fast_vote_is_unsure_or_alarmed() {
        let cfg = cfg();
        let new = CallState::default();
        let t = START + 20_000;
        assert!(!wants_second_opinion(&new, all(0.1, 0.1, 0.1), t, 20_000, &cfg)); // clearly safe
        assert!(!wants_second_opinion(&new, all(0.5, 0.5, 0.5), START, 3_000, &cfg)); // warm-up: quiet anyway
        assert!(wants_second_opinion(&new, all(0.5, 0.5, 0.5), t, 20_000, &cfg)); // unsure band
        assert!(wants_second_opinion(&new, all(0.65, 0.6, 0.65), t, 20_000, &cfg)); // high
        assert!(wants_second_opinion(&new, all(0.2, 0.95, 0.6), t, 20_000, &cfg)); // money rule
        assert!(wants_second_opinion(&new, [None, Some(0.1), None], t, 20_000, &cfg)); // not enough data
        let red = CallState { red: true, started_at_ms: START, ..CallState::default() };
        assert!(!wants_second_opinion(&red, all(0.9, 0.9, 0.9), t, 20_000, &cfg)); // red stays red anyway
        assert!(one_shot_wants_second_opinion(all(0.5, 0.5, 0.5), &cfg));
        assert!(!one_shot_wants_second_opinion(all(0.1, 0.1, 0.1), &cfg));
    }

    #[test]
    fn asking_changes_nothing_when_bert_does_not_answer() {
        let cfg = cfg();
        let lines = [(20, all(0.65, 0.6, 0.65)), (25, all(0.1, 0.1, 0.1))];
        let (_, without) = replay(&cfg, &lines);
        let with_none = replay_bert(&cfg, &[(20, lines[0].1, None), (25, lines[1].1, None)]);
        assert_eq!(without, with_none);
        assert_eq!(without[0].fast, without[0].combined);
    }

    #[test]
    fn final_score_is_the_average_of_fast_vote_and_bert() {
        // fast 0.5 (UNSURE) + BERT 0.9 -> 0.7 (high once)
        let d = replay_bert(&cfg(), &[(20, all(0.5, 0.5, 0.5), Some(0.9))]);
        assert!((d[0].fast - 0.5).abs() < 1e-6);
        assert!((d[0].combined - 0.7).abs() < 1e-6);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Unsure, "high_once"));
        assert_eq!(d[0].bert, Some(0.9));
        assert_eq!(d[0].reasons[0].signal, "bert"); // strongest reason first
    }

    #[test]
    fn a_calm_second_opinion_turns_unsure_into_safe() {
        // the clinic call: fast 0.5 -> UNSURE on its own; BERT 0.1 -> 0.3 -> SAFE
        let d = replay_bert(&cfg(), &[(20, all(0.5, 0.5, 0.5), Some(0.1))]);
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Safe, "safe"));
        assert!(d[0].reasons.is_empty());
    }

    #[test]
    fn two_high_blended_updates_in_a_row_are_scam() {
        let d = replay_bert(&cfg(), &[(20, all(0.5, 0.5, 0.5), Some(0.9)), (25, all(0.5, 0.5, 0.5), Some(0.9))]);
        assert_eq!(d[0].rule, "high_once");
        assert_eq!((d[1].verdict, d[1].rule), (Verdict::Scam, "streak"));
    }

    #[test]
    fn bert_cannot_cancel_the_money_rule_unless_the_veto_is_switched_on() {
        // "This is DBS, your card bill of $50 is due": money 0.95, impersonation 0.6 -> money rule
        let line = all(0.2, 0.95, 0.6);
        let default = replay_bert(&cfg(), &[(20, line, Some(0.0))]); // veto off by default (recall first)
        assert_eq!((default[0].verdict, default[0].rule), (Verdict::Scam, "money_rule"));
        let on = cfg_with(&[("AGG_BERT_VETO", "0.2")]);
        let vetoed = replay_bert(&on, &[(20, line, Some(0.05))]);
        assert_ne!(vetoed[0].rule, "money_rule");
        assert_ne!(vetoed[0].verdict, Verdict::Scam);
        let kept = replay_bert(&on, &[(20, line, Some(0.3))]); // 0.3 >= veto 0.2
        assert_eq!((kept[0].verdict, kept[0].rule), (Verdict::Scam, "money_rule"));
    }

    #[test]
    fn bert_stands_in_when_too_few_workers_answer() {
        let d = replay_bert(&cfg(), &[(20, [None, Some(0.1), None], Some(0.8)), (25, [None, None, None], Some(0.8))]);
        assert!(!d[0].quorum_met);
        assert!((d[0].combined - 0.8).abs() < 1e-6); // BERT alone, not blended with a one-worker vote
        assert_eq!((d[0].verdict, d[0].rule), (Verdict::Unsure, "high_once")); // never red on one line
        assert_eq!((d[1].verdict, d[1].rule), (Verdict::Scam, "streak"));
        assert!(!d[0].reasons.iter().any(|r| r.signal == "quorum")); // BERT answered: not "not enough data"
        let calm = replay_bert(&cfg(), &[(20, [None, Some(0.1), None], Some(0.1))]);
        assert_eq!((calm[0].verdict, calm[0].rule), (Verdict::Safe, "safe"));
    }

    #[test]
    fn warmup_still_wins_unless_bert_is_very_sure() {
        let quiet = replay_bert(&cfg(), &[(3, [None, Some(0.1), None], Some(0.5))]);
        assert_eq!((quiet[0].verdict, quiet[0].rule), (Verdict::Safe, "warmup"));
        let loud = replay_bert(&cfg(), &[(3, [None, Some(0.1), None], Some(0.95))]);
        assert_ne!(loud[0].rule, "warmup");
    }

    #[test]
    fn bert_weight_is_configurable() {
        let cfg = cfg_with(&[("AGG_BERT_WEIGHT", "0.25")]);
        let d = replay_bert(&cfg, &[(20, all(0.5, 0.5, 0.5), Some(0.9))]);
        assert!((d[0].combined - 0.6).abs() < 1e-6); // 0.75 * 0.5 + 0.25 * 0.9
    }

    #[test]
    fn one_shot_uses_bert_too() {
        let cfg = cfg();
        let d = decide_one_shot(all(0.5, 0.5, 0.5), Some(0.1), &cfg);
        assert_eq!(d.verdict, Verdict::Safe);
        assert!((d.combined - 0.3).abs() < 1e-6);
        assert_eq!(decide_one_shot([None, None, None], Some(0.9), &cfg).verdict, Verdict::Scam);
    }

    #[test]
    fn verdict_names_round_trip() {
        for v in [Verdict::Safe, Verdict::Unsure, Verdict::Scam] {
            assert_eq!(Verdict::parse(v.as_str()), Some(v));
        }
    }
}
