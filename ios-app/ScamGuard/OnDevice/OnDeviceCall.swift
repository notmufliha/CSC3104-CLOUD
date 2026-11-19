import Foundation

/// Port of android-app ondevice/OnDeviceCall.kt (G.2 / G.3): one call scored on the phone. Each caller line goes
/// through the three checks and the aggregator's rules, and comes back in the same shape as the gateway's
/// POST /v1/utterance answer, so the in-call screen, chips, alert and dashboard work exactly as in the cloud.
///
///  - Level 3 (On-device): `answer` (no BERT: the rules see it as "off"), or with MobileBERT installed (G.4)
///    `answerWithSecondOpinion` asking it on the phone. Nothing touches the network.
///  - Level 2 (Hybrid): `answerHybrid`. The caller's last 5 lines, masked (Mask), are sent for a cloud BERT opinion.
/// The second opinion is asked as the aggregator does it:
///  - only once the call has at least 3 caller lines (fewer: status "no_context");
///  - when the phone's own score is >= 0.45, or the answer without BERT is decided by a rule BERT can change, or on
///    the spot check (every 5th caller line);
///  - on a red call: MobileBERT yes; Hybrid never, so nothing more leaves the phone once a call is red.
/// If it doesn't come back (in time) the phone's own answer stands (status "error").
///
/// Not thread-safe: one call's lines are handled one at a time, in order (the view model chains them).
nonisolated final class OnDeviceCall: @unchecked Sendable {
    private let scorer: OnDeviceScorer
    private let rules: LocalRules
    private let clock: @Sendable () -> Int64
    /// The caller's recent lines (raw, on the phone only), for the second-opinion window.
    private var recentLines: [String] = []

    /// What happened with the second opinion (cloud BERT or MobileBERT) for one line.
    struct SecondOpinion: Equatable, Sendable {
        /// "unsure" (the phone is unsure or alarmed), "spot_check" (every 5th line) or "red".
        let why: String
        /// What it read: the caller's last lines (Hybrid: masked, and exactly what left the phone).
        let lines: [String]
        /// Its score, or nil when it didn't answer (in time); then the phone's answer was kept.
        let bertScore: Float?
    }

    struct Answer: Sendable {
        let response: UtteranceResponse
        let decision: LocalRules.Decision
        let second: SecondOpinion?
    }

    /// G.3: ask when the phone's own score is at least this (the UNSURE threshold), among other reasons.
    static let askAt: Float = 0.45
    /// BERT is asked only once the call has this many caller lines (bert.rs MIN_LINES).
    static let minLines = 3
    /// The caller's last 5 lines go to the second opinion.
    static let windowLines = 5
    /// How long the phone waits for the cloud before keeping its own answer.
    static let verifyTimeout = 1.5

    init(_ scorer: OnDeviceScorer, rules: LocalRules = LocalRules(),
         clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.scorer = scorer
        self.rules = rules
        self.clock = clock
    }

    /// Level 3: everything on the phone.
    @concurrent
    func answer(callId: String, seq: Int, text: String) async -> UtteranceResponse {
        answerNow(callId: callId, seq: seq, text: text)
    }

    /// Synchronous level-3 answer (tests and `answer`).
    func answerNow(callId: String, seq: Int, text: String) -> UtteranceResponse {
        let start = DispatchTime.now().uptimeNanoseconds
        let scores = scorer.score(text)
        remember(text)
        let d = rules.decide(lineOf(scores), nowMs: clock())
        return response(callId, seq, scores, d, start, bertStatus: "off")
    }

    /// Level 2. `verify` sends the masked lines to the cloud and returns BERT's score, or nil on timeout / error.
    @concurrent
    func answerHybrid(callId: String, seq: Int, text: String,
                      verify: @escaping @Sendable ([String]) async -> Float?) async -> Answer {
        await answerWithSecondOpinion(callId: callId, seq: seq, text: text, prepare: { Mask.mask($0) },
                                      askWhenRed: false, ask: verify)
    }

    /// The shared rule: score on the phone, and only when the second opinion is wanted let `ask` read the caller's
    /// last 5 lines, each passed through `prepare` first.
    @concurrent
    func answerWithSecondOpinion(callId: String, seq: Int, text: String,
                                 prepare: @escaping @Sendable (String) -> String,
                                 askWhenRed: Bool = true,
                                 ask: @escaping @Sendable ([String]) async -> Float?) async -> Answer {
        let start = DispatchTime.now().uptimeNanoseconds
        let now = clock()
        let scores = scorer.score(text)
        remember(text)
        let line = lineOf(scores)
        let own = rules.peek(line, nowMs: now)
        let wanted: String?
        if rules.red { wanted = askWhenRed ? "red" : nil }
        else if own.fast >= Self.askAt || LocalRules.secondOpinionRules.contains(own.rule) { wanted = "unsure" }
        else if rules.isSpotCheck() { wanted = "spot_check" }
        else { wanted = nil }
        let enoughLines = rules.callerLines + 1 >= Self.minLines
        var second: SecondOpinion?
        if let wanted, enoughLines {
            let lines = recentLines.map(prepare)
            second = SecondOpinion(why: wanted, lines: lines, bertScore: await ask(lines))
        }
        let status: String
        if second == nil && wanted != nil && !enoughLines { status = "no_context" }
        else if second == nil { status = "skipped" }
        else if second?.bertScore != nil { status = "ok" }
        else { status = "error" }
        let d = rules.decide(line, nowMs: now, bert: second?.bertScore, bertStatus: status)
        return Answer(response: response(callId, seq, scores, d, start, bertStatus: status), decision: d, second: second)
    }

    private func remember(_ text: String) {
        recentLines.append(text)
        if recentLines.count > Self.windowLines { recentLines.removeFirst(recentLines.count - Self.windowLines) }
    }

    private func lineOf(_ scores: [String: Float]) -> [Float?] { LocalRules.signals.map { scores[$0] } }

    private func response(_ callId: String, _ seq: Int, _ scores: [String: Float], _ d: LocalRules.Decision,
                          _ startNanos: UInt64, bertStatus: String) -> UtteranceResponse {
        var recent: [String: Float] = [:]
        for (s, v) in zip(LocalRules.signals, d.recent) { if let v { recent[s] = v } }
        let reasons = d.reasons.map { ServerReason(signal: $0.signal, score: $0.score, text: $0.text) }
        let level = Risk.level(d.verdict, nil)
        var r = UtteranceResponse(
            callId: callId, seq: seq, scored: true, duplicate: false, verdict: d.verdict, combinedScore: d.combined,
            language: "en", scores: scores.mapValues { Optional($0) }, missing: [], callScores: recent,
            callerLines: d.callerLines, latencyMs: Int((DispatchTime.now().uptimeNanoseconds - startNanos) / 1_000_000),
            reasons: reasons, action: LocalRules.actionFor(level) ?? "")
        // For the Developer view only
        r.liveAlert = d.verdict
        r.fastScore = d.fast
        r.bertScore = d.bert
        r.bertStatus = bertStatus
        r.rule = d.rule
        return r
    }

    /// The on-device models are English only. Same 30% rule as the gateway's language detection: a line with more
    /// than 30% Chinese characters is Chinese; a script marked "zh" is Chinese throughout.
    static func isEnglish(_ scriptLang: String?, _ text: String) -> Bool {
        if scriptLang == "zh" { return false }
        let chinese = Py.countUTF16(text) { (0x4E00...0x9FFF).contains($0) }
        return chinese * 10 <= text.utf16.count * 3
    }
}
