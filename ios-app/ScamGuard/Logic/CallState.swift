import Foundation

/// One chat bubble. flags = signals this line scored high on; pending = waiting for an answer;
/// failed = this caller line could not be checked.
nonisolated struct ChatLine: Identifiable, Equatable, Sendable {
    let seq: Int
    let isCaller: Bool
    let text: String
    var flags: [String] = []
    var pending = false
    var failed = false
    /// Hybrid / MobileBERT: a note when this line's window got a second opinion ("checked with cloud").
    var cloudNote: String?

    var id: Int { seq }
}

nonisolated enum Phase: Sendable { case idle, ringing, inCall, ended }

/// Port of Android's CallUiState, plus the summary text rules (with the two Android bugs fixed, see below).
nonisolated struct CallUiState: Equatable, Sendable {
    var script: CallScript?
    var phase: Phase = .idle
    var lines: [ChatLine] = []
    var verdict: String?
    var combinedScore: Float?
    var callScores: [String: Float] = [:]
    var gatewayError: String?
    var scamAlert = false
    /// Waiting (up to 3 s) for the end-of-call answer; the summary shows "Final review…".
    var reviewing = false
    /// The end-of-call answer, once it arrived (or .unavailable / .onDeviceReview).
    var finalReview: FinalReview?
    /// The privacy level this call runs at, fixed when it is answered.
    var privacyLevel: PrivacyLevel = .defaultLevel
    /// Level 1: the server's suggested action from the newest answer (policy.rs action_for).
    var serverAction: String?
    /// The full-screen red warning is showing. Only the user closes it (a button), even after the call ends.
    var warningOpen = false
    /// The yellow "Be careful" tap was given in this call (once per call).
    var carefulAlerted = false
    /// Developer view: "2× speed" etc. on the call screen when the playback isn't 1×; nil normally.
    var speedTag: String?

    /// What the red warning / yellow banner say (no scores): the strongest reason heard so far.
    var warningKind: Warnings.Kind {
        Warnings.kind(lit, flaggedTexts, reasonCode: Reasons.codeForText(finalReview?.reason))
    }

    /// The live level: the in-call bar.
    var level: Risk.Level { Risk.level(verdict, gatewayError) }
    /// What the summary and the saved history row lead with: the final verdict, else the live one.
    var summaryLevel: Risk.Level { Summary.level(finalReview, verdict, gatewayError) }
    var lit: [String] { Risk.litSignals(callScores) }
    var flaggedTexts: [String] { lines.filter { !$0.flags.isEmpty }.map(\.text) }
    var banner: String? { Risk.banner(level, lit, flaggedTexts) }

    /// The reason code saved with the call (the dashboard's).
    var reasonCode: String { Reasons.codeFor(summaryLevel, callScores, finalReview?.reason) }

    /// The summary's "reason. action" line for a warning; nil when the call didn't end with one.
    ///
    /// Android bug not copied: at levels 2-3 Android showed its own wording ("asking for your OTP") while the
    /// dashboard showed the server's ("Asked for your OTP or money"). Here every level uses the reason code's
    /// wording, as the dashboard does. Level 1 shows the server's end-of-call reason (the same wording, or
    /// "Warning signs earlier in the call") and the server's action.
    var summaryReason: String? {
        let level = summaryLevel
        guard level == .red || level == .yellow else { return nil }
        let serverReason = finalReview?.reason.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let reason: String
        if privacyLevel.cloudCall && !serverReason.isEmpty && Reasons.codeForText(serverReason) == nil {
            reason = serverReason            // a wording the app doesn't know yet: shown as the server sent it
        } else {
            reason = Reasons.text(reasonCode) ?? serverReason
        }
        let action: String
        if privacyLevel.cloudCall, level == self.level, let a = serverAction, !a.isEmpty {
            action = a
        } else {
            action = LocalRules.actionFor(level) ?? Risk.action(lit)
        }
        return "\(reason). \(action)"
    }

    /// Android bug not copied: "What triggered it" is hidden when the final verdict is SAFE.
    var showsTriggers: Bool { summaryLevel != .green }

    /// The caller lines "What triggered it" shows: flagged lines and the end-of-call review's riskiest window.
    var triggerLines: [ChatLine] {
        let risky = finalReview?.topWindowSeqs ?? []
        return lines.filter { $0.isCaller && (!$0.flags.isEmpty || risky.contains($0.seq)) }
    }

    /// "During the call: <live>" plus how the end was checked.
    var duringCallLine: String {
        let live = Theme.levelLabel(level)
        if finalReview?.onDevice == true { return L.s(.sum_during_phone, live) }
        if finalReview?.reviewed == true { return L.s(.sum_during, live) }
        return L.s(.sum_during_unavailable, live)
    }
}
