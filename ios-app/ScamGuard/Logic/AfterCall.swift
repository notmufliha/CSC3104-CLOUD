import Foundation

/// The summary screen's "What to do now" card (after a Scam or "Be careful" call) and the short summary it copies before
/// opening ScamShield's report page. Pure, kept in step with android-app logic/AfterCall.kt.
///
/// The summary holds the date and time, the result, the reason in plain words and, only if the user lets ScamGuard keep
/// the riskiest sentence, the caller's flagged lines masked by Logic/Mask.swift. It goes to the clipboard only: nothing
/// is uploaded.
nonisolated enum AfterCall {
    /// Shown for a Scam call, and for a "Be careful" call unless those warnings are turned off. Never for safe calls.
    static func shows(_ level: Risk.Level, showCareful: Bool) -> Bool {
        level == .red || (level == .yellow && showCareful)
    }

    /// "19 Nov 2026, 2:45 PM" (the same on Android).
    static func whenText(_ timeMs: Int64, zone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = "d MMM yyyy, h:mm a"
        return f.string(from: Date(timeIntervalSince1970: Double(timeMs) / 1000))
    }

    /// The text copied for the ScamShield report. `lines` are the caller's flagged lines (raw); they are masked here and
    /// left out entirely unless `includeLines` (the "Save the riskiest sentence" setting) is on.
    static func reportSummary(timeMs: Int64, result: String, reason: String, lines: [String], includeLines: Bool,
                              zone: TimeZone = .current) -> String {
        var out = [L.s(.copy_title), L.s(.copy_when, whenText(timeMs, zone: zone)), L.s(.copy_result, result), L.s(.copy_why, reason)]
        var seen = Set<String>()
        let masked = includeLines
            ? lines.map { Mask.mask($0).trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty && seen.insert($0).inserted }
            : []
        if !masked.isEmpty {
            out.append(L.s(.copy_lines))
            out += masked.map { "- \"\($0)\"" }
        }
        out.append(L.s(.copy_footer))
        return out.joined(separator: "\n")
    }

    /// The block-a-number steps for this platform.
    static let blockSteps: [K] = [.block_ios_1, .block_ios_2, .block_ios_3, .block_ios_4]

    /// "If I already shared details": what to do, in order. Only 1799 has a number to call.
    static let sharedSteps: [K] = [.shared_bank, .shared_passwords, .shared_1799]
}
