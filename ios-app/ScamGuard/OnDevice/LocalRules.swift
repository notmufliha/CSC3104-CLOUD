import Foundation

/// Port of android-app ondevice/LocalRules.kt, itself a port of backend/aggregator/src/policy.rs
/// (`decide_with_bert_status`, cascade mode), so levels 2 and 3 give the same SAFE / UNSURE / SCAM, chips and
/// reasons as the cloud.
///
/// Rules, in order:
///  1. add the line to the window (last 6 caller lines; each signal's recent score = its highest there);
///  2. a red call stays red while BERT is unavailable or confirms it; two BERT disagreements in a row clear it;
///  3. fewer than 2 checks answered (and no BERT) -> UNSURE "not enough data";
///  4. fast = weighted average of the recent scores; combined = fast, or blended 50/50 with BERT when it answered;
///  5. warm-up (first 15 s, nothing >= 0.9, BERT included) -> SAFE;
///  6/7. an alarmed vote (money rule, or fast >= 0.6, or BERT >= 0.6) turns red only if BERT confirms it (>= 0.6),
///     or, when BERT is unavailable ("off" / "timeout" / "error"), two signals are >= 0.8 ("strong_fallback");
///  8. combined >= 0.45, or an alarmed vote BERT didn't confirm -> UNSURE;
///  9. otherwise SAFE.
nonisolated final class LocalRules {
    struct Config: Sendable {
        var weights: [Float] = [1.0 / 3, 1.0 / 3, 1.0 / 3]   // urgency, money, impersonation
        var scamThreshold: Float = 0.6
        var unsureThreshold: Float = 0.45
        var bertScamThreshold: Float = 0.6
        var fallbackStrongSignal: Float = 0.8
        var redDisagreementsToClear = 2
        var windowLines = 6
        var warmupMs: Int64 = 15_000
        var warmupOverride: Float = 0.9
        var moneyRule: Float = 0.7
        var moneyRuleOther: Float = 0.5
        var moneyRuleLines = 6
        var reasonThreshold: Float = 0.5
        var minQuorum = 2
        /// final = (1 - w) * fast + w * BERT, when BERT answered (AGG_BERT_WEIGHT).
        var bertWeight: Float = 0.5
        /// Cascade: also ask BERT on every Nth caller line (AGG_BERT_SPOT_EVERY); 0 = off.
        var bertSpotEvery = 5
        /// BERT needs this many caller lines to say anything useful (bert.rs MIN_LINES).
        var bertMinLines = 3
    }

    struct Reason: Equatable, Sendable {
        let signal: String
        let score: Float
        let text: String
    }

    struct Decision: Equatable, Sendable {
        let verdict: String
        /// The score the verdict was decided on: `fast`, or its blend with BERT when BERT answered.
        let combined: Float
        /// The checks' weighted vote alone, before BERT.
        let fast: Float
        let bert: Float?
        /// Each signal's highest score in the window (nil = none).
        let recent: [Float?]
        let reasons: [Reason]
        let warmingUp: Bool
        let quorumMet: Bool
        let callerLines: Int
        /// Which rule decided (policy.rs names).
        let rule: String
    }

    private struct Memory {
        var startedAtMs: Int64 = 0
        var window: [[Float?]] = []
        var streak = 0
        var red = false
        var redDisagreements = 0
        var callerLines = 0
    }

    private let cfg: Config
    private var memory = Memory()

    init(_ cfg: Config = Config()) { self.cfg = cfg }

    /// Caller lines decided so far.
    var callerLines: Int { memory.callerLines }

    /// The call is red right now.
    var red: Bool { memory.red }

    /// Applies one caller line (scores in `signals` order; nil = that check didn't answer). `callTimeMs` = how far
    /// into the call this line is; 0 = unknown, then the clock runs from the first caller line.
    @discardableResult
    func decide(_ line: [Float?], nowMs: Int64, callTimeMs: Int64 = 0, bert: Float? = nil, bertStatus: String? = nil) -> Decision {
        let (next, d) = step(memory, line, nowMs, callTimeMs, bert, bertStatus ?? (bert != nil ? "ok" : "off"))
        memory = next
        return d
    }

    /// policy.rs `wants_second_opinion`. Changes nothing.
    func wantsSecondOpinion(_ line: [Float?], nowMs: Int64, callTimeMs: Int64 = 0) -> Bool {
        memory.red || isSpotCheck() || Self.secondOpinionRules.contains(peek(line, nowMs: nowMs, callTimeMs: callTimeMs).rule)
    }

    /// This line (the next one to be counted) is due a spot check.
    func isSpotCheck() -> Bool {
        cfg.bertSpotEvery > 0 && !memory.red && (memory.callerLines + 1) % cfg.bertSpotEvery == 0
    }

    /// What `decide` would answer for this line without BERT ("off"), changing nothing.
    func peek(_ line: [Float?], nowMs: Int64, callTimeMs: Int64 = 0) -> Decision {
        step(memory, line, nowMs, callTimeMs, nil, "off").1
    }

    private func step(_ before: Memory, _ line: [Float?], _ nowMs: Int64, _ callTimeMs: Int64, _ bert: Float?,
                      _ bertStatus: String) -> (Memory, Decision) {
        precondition(line.count == Self.signals.count)
        let startedAt = before.startedAtMs == 0 ? nowMs - max(callTimeMs, 0) : before.startedAtMs
        let window = Array((before.window + [line]).suffix(cfg.windowLines))
        let callerLines = before.callerLines + 1
        let recent = highs(window)
        let fast = combined(recent)
        let answered = line.filter { $0 != nil }.count
        let quorumMet = answered >= cfg.minQuorum
        let warmingUp = (callTimeMs > 0 ? callTimeMs : nowMs - startedAt) < cfg.warmupMs
        let combinedScore: Float
        if let bert {
            combinedScore = quorumMet ? (1 - cfg.bertWeight) * fast + cfg.bertWeight * bert : bert
        } else {
            combinedScore = fast
        }
        let overrideHit = recent.contains { ($0 ?? -1) >= cfg.warmupOverride } || (bert.map { $0 >= cfg.warmupOverride } ?? false)
        let bertUnavailable = ["off", "timeout", "error"].contains(bertStatus)
        let bertConfirms = bert.map { $0 >= cfg.bertScamThreshold } ?? false
        let redCandidate = (!warmingUp || overrideHit) && (quorumMet || bert != nil) &&
            ((quorumMet && moneyRule(window)) || fast >= cfg.scamThreshold || bertConfirms)
        let strongFallback = recent.filter { ($0 ?? -1) >= cfg.fallbackStrongSignal }.count >= 2

        var streak = before.streak
        var red = before.red
        var disagreements = before.redDisagreements
        let verdict: String
        let rule: String
        if before.red && bert == nil {
            (verdict, rule) = ("SCAM", "red_bert_unavailable")
        } else if before.red {
            if bertConfirms {
                disagreements = 0
                (verdict, rule) = ("SCAM", "red_bert_confirmed")
            } else {
                disagreements += 1
                if disagreements < cfg.redDisagreementsToClear {
                    (verdict, rule) = ("SCAM", "red_bert_disagrees")
                } else {
                    red = false; disagreements = 0
                    (verdict, rule) = ("UNSURE", "red_cleared")
                }
            }
        } else if !quorumMet && bert == nil {
            (verdict, rule) = ("UNSURE", "quorum")
        } else if warmingUp && !overrideHit {
            streak = 0
            (verdict, rule) = ("SAFE", "warmup")
        } else if redCandidate && bertConfirms {
            (verdict, rule) = ("SCAM", "bert_confirmed")
        } else if redCandidate && bert == nil && bertUnavailable && strongFallback {
            (verdict, rule) = ("SCAM", "strong_fallback")
        } else if combinedScore >= cfg.unsureThreshold || redCandidate {
            streak = 0
            (verdict, rule) = ("UNSURE", "unsure")
        } else {
            streak = 0
            (verdict, rule) = ("SAFE", "safe")
        }
        if verdict == "SCAM" { red = true } else { red = false; disagreements = 0 }
        let after = Memory(startedAtMs: startedAt, window: window, streak: streak, red: red,
                           redDisagreements: disagreements, callerLines: callerLines)

        var reasons = verdict == "SAFE" ? [] : allReasons(recent, bert)
        if rule == "quorum" {
            reasons.append(Reason(signal: "quorum", score: Float(answered),
                                  text: "Not enough data: only \(answered) of \(Self.signals.count) checks answered"))
        }
        return (after, Decision(verdict: verdict, combined: combinedScore, fast: fast, bert: bert, recent: recent,
                                reasons: reasons, warmingUp: warmingUp, quorumMet: quorumMet,
                                callerLines: callerLines, rule: rule))
    }

    private func highs(_ lines: [[Float?]]) -> [Float?] {
        Self.signals.indices.map { i in lines.compactMap { $0[i] }.max() }
    }

    /// Weighted average over the signals that have a score; a missing one shares its weight out.
    private func combined(_ recent: [Float?]) -> Float {
        var sum: Float = 0
        var weight: Float = 0
        for (i, s) in recent.enumerated() {
            if let s { sum += cfg.weights[i] * s; weight += cfg.weights[i] }
        }
        return weight > 0 ? sum / weight : 0
    }

    private func moneyRule(_ window: [[Float?]]) -> Bool {
        let recent = highs(Array(window.suffix(cfg.moneyRuleLines)))
        let m = Self.signals.firstIndex(of: "money")!
        let moneyHigh = (recent[m] ?? -1) >= cfg.moneyRule
        let otherHigh = recent.enumerated().contains { i, s in i != m && (s ?? -1) >= cfg.moneyRuleOther }
        return moneyHigh && otherHigh
    }

    /// Signals (and BERT) at or above the reason threshold, strongest first (stable on ties, like sortedByDescending).
    private func allReasons(_ recent: [Float?], _ bert: Float?) -> [Reason] {
        var out: [Reason] = Self.signals.indices.compactMap { i in
            guard let v = recent[i], v >= cfg.reasonThreshold else { return nil }
            return Reason(signal: Self.signals[i], score: v, text: Self.reasonText[Self.signals[i]]!)
        }
        if let bert, bert >= cfg.reasonThreshold {
            out.append(Reason(signal: "bert", score: bert, text: Self.reasonText["bert"]!))
        }
        return out.enumerated().sorted { a, b in
            a.element.score != b.element.score ? a.element.score > b.element.score : a.offset < b.offset
        }.map(\.element)
    }

    static let signals = ["urgency", "money", "impersonation"]
    /// policy.rs SECOND_OPINION_RULES: rules whose answer BERT can change.
    static let secondOpinionRules: Set<String> = ["quorum", "money_rule", "high_once", "streak", "unsure", "strong_fallback"]
    /// policy.rs reason_text: the server's wording, which the app also shows (Reasons).
    static let reasonText: [String: String] = [
        "money": "Asked for your OTP or money",
        "impersonation": "Claims to be police, a bank or the government",
        "urgency": "Pressuring you to act now",
        "bert": "The conversation as a whole sounds like a scam",
    ]
    /// policy.rs ACTION_SCAM / ACTION_UNSURE (action_for): the server's suggested action per verdict.
    static let actionScam = "Hang up now. Call the organisation back on its official number, or ScamShield on 1799."
    static let actionUnsure = "Don't share any codes or transfer money. Hang up and check with the official number."

    static func actionFor(_ level: Risk.Level) -> String? {
        switch level {
        case .red: return actionScam
        case .yellow: return actionUnsure
        default: return nil
        }
    }
}
