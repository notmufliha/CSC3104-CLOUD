import Foundation

/// Port of android-app logic/Reasons.kt: one wording for a call's top reason. Each saved call keeps a reason CODE
/// (CallRecord.reasonCode); the dashboard and (on iOS) the summary show the server's wording for it
/// (backend/aggregator/src/policy.rs reason_text), whichever privacy level decided the call.
nonisolated enum Reasons {
    static let money = "money"
    static let impersonation = "impersonation"
    static let urgency = "urgency"
    static let bert = "bert"
    /// A warning with no single signal above the reason threshold.
    static let several = "several"
    /// The end-of-call answer pointed at warnings earlier in the call (end.rs REASON_EARLIER).
    static let earlier = "earlier"
    static let none = "none"
    static let serverNotReached = "server_not_reached"
    static let noLines = "no_lines"

    static let allCodes = [money, impersonation, urgency, bert, several, earlier, none, serverNotReached, noLines]

    private static let textByCode: [String: String] = LocalRules.reasonText.merging([
        several: "Several small warning signs together",
        earlier: "Warning signs earlier in the call",
        none: "No warning signs",
        serverNotReached: "Server not reached",
        noLines: "Ended before any line was checked",
    ]) { a, _ in a }

    /// The server's wording for a code; nil for an unknown code.
    static func text(_ code: String?) -> String? {
        guard let code else { return nil }
        return textByCode[code]
    }

    /// What the dashboard shows for a saved call: the code's wording, else the text saved with it.
    static func display(reasonCode: String?, topReason: String) -> String {
        text(reasonCode) ?? topReason
    }

    /// Every wording a reason has had (the server's, the app's own from Risk, the old History's), lowercase.
    private static let knownTexts: [String: String] = {
        var m: [String: String] = [:]
        for (code, text) in textByCode { m[text.lowercased()] = code }
        m["asking for your otp"] = money
        m["asking you to transfer money"] = money
        m["pretending to be an official (police, bank, government)"] = impersonation
        m["pressuring you to act right now"] = urgency
        m["several small warning signs together"] = several
        m["no warning signs"] = none
        m["server not reached"] = serverNotReached
        m["ended before any line was checked"] = noLines
        return m
    }()

    /// A reason text (any known wording, any case) -> its code; nil if unknown.
    static func codeForText(_ text: String?) -> String? {
        guard let text else { return nil }
        return knownTexts[text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()]
    }

    /// The code for a call that ended at `level`. Warnings: the end-of-call answer's reason if the server gave one,
    /// else the strongest signal that fired (the aggregator's choice), else "several".
    static func codeFor(_ level: Risk.Level, _ recentScores: [String: Float], _ serverReason: String?) -> String {
        switch level {
        case .red, .yellow:
            if let c = codeForText(serverReason) { return c }
            var best: (String, Float)?
            for s in Risk.signals {
                let v = recentScores[s] ?? 0
                // maxByOrNull keeps the first of equal maxima
                if v >= Risk.signalOn && (best == nil || v > best!.1) { best = (s, v) }
            }
            return best?.0 ?? several
        case .green: return none
        case .error: return serverNotReached
        case .listening: return noLines
        }
    }
}
