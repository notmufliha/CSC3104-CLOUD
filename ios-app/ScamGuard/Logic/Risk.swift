import Foundation

/// Port of android-app logic/Risk.kt: turns gateway numbers into colours, chips and the words on the banner.
nonisolated enum Risk {
    static let signals = ["urgency", "money", "impersonation"]

    /// A chip lights up when that signal's best score in the call reaches this.
    static let signalOn: Float = 0.5

    enum Level: String, Sendable, CaseIterable {
        case listening, green, yellow, red, error
    }

    /// A warning (UNSURE / SCAM) always stays on screen. An error beats SAFE: if a later line could not be
    /// checked, an earlier SAFE no longer covers the call, so we show "Could not check" instead of green.
    static func level(_ verdict: String?, _ error: String?) -> Level {
        if verdict == "SCAM" { return .red }
        if verdict == "UNSURE" { return .yellow }
        if error != nil { return .error }
        if verdict == "SAFE" { return .green }
        return .listening
    }

    static func litSignals(_ callScores: [String: Float]) -> [String] {
        signals.filter { (callScores[$0] ?? 0) >= signalOn }
    }

    /// A warning sign's short name.
    static func chipLabel(_ signal: String) -> String {
        switch signal {
        case "urgency": return L.s(.chip_urgency)
        case "money": return L.s(.chip_money)
        case "impersonation": return L.s(.chip_impersonation)
        default: return signal
        }
    }

    /// What the call screen shows for a level. With "Show 'Be careful' warnings" off, a yellow moment is shown as
    /// "Listening…": only red warnings reach the user (the call is still saved as "Be careful").
    static func shown(_ level: Level, showCareful: Bool) -> Level {
        level == .yellow && !showCareful ? .listening : level
    }

    /// Strongest reason first: money, then impersonation, then urgency.
    static func reason(_ lit: [String], _ flaggedTexts: [String]) -> String {
        let all = flaggedTexts.joined(separator: " ").lowercased()
        if lit.contains("money") && (all.contains("otp") || all.contains("one-time") || all.contains("验证码")) {
            return "asking for your OTP"
        }
        if lit.contains("money") { return "asking you to transfer money" }
        if lit.contains("impersonation") { return "pretending to be an official (police, bank, government)" }
        if lit.contains("urgency") { return "pressuring you to act right now" }
        return "several small warning signs together"
    }

    static func action(_ lit: [String]) -> String {
        if lit.contains("money") { return "Hang up and call your bank on the number on your card." }
        if lit.contains("impersonation") { return "Hang up and call the agency back on its official number." }
        return "Don't share personal details. Real organisations give you time to check."
    }

    static func banner(_ level: Level, _ lit: [String], _ flaggedTexts: [String]) -> String? {
        switch level {
        case .red: return "Likely scam: \(reason(lit, flaggedTexts)). \(action(lit))"
        case .yellow: return "Be careful: \(reason(lit, flaggedTexts)). \(action(lit))"
        case .error: return "Could not check this call. The server can't be reached — this does NOT mean it is safe."
        default: return nil
        }
    }

    /// The verdict word saved with a call: same rule as the bar (SCAM / UNSURE stick, SAFE only without an error).
    static func historyVerdict(_ level: Level) -> String {
        switch level {
        case .red: return "SCAM"
        case .yellow: return "UNSURE"
        case .green: return "SAFE"
        case .error, .listening: return notChecked
        }
    }

    static let notChecked = "NOT CHECKED"
}
