import Foundation

/// "Share weekly summary": a plain note for the family about the last 7 days, built from the history on this phone.
/// It never holds phone numbers, anything the caller said, URLs or addresses: only counts and plain reasons. Shared
/// through the system share sheet (the user picks the app); nothing is uploaded. Same rules as android-app
/// logic/WeeklySummary.kt.
nonisolated enum WeeklySummary {
    /// One saved call, as much as the summary needs.
    struct Call: Equatable, Sendable {
        let endedAt: Int64
        let verdict: String
        let reasonCode: String?
    }

    /// The last 7 days: today and the 6 before, from the start of the first day to now.
    struct Week: Equatable, Sendable {
        let startMs: Int64
        let endMs: Int64
        let label: String
    }

    static func week(nowMs: Int64, zone: TimeZone) -> Week {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let now = Date(timeIntervalSince1970: Double(nowMs) / 1000)
        let today = cal.startOfDay(for: now)
        let first = cal.date(byAdding: .day, value: -6, to: today)!
        return Week(startMs: Int64(first.timeIntervalSince1970 * 1000), endMs: nowMs, label: label(first, today, cal))
    }

    /// "12–18 Nov", or "28 Oct – 3 Nov" across a month.
    static func label(_ first: Date, _ last: Date, _ cal: Calendar) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = cal.timeZone
        f.dateFormat = "MMM"
        let d1 = cal.component(.day, from: first), d2 = cal.component(.day, from: last)
        if cal.component(.month, from: first) == cal.component(.month, from: last) { return "\(d1)–\(d2) \(f.string(from: last))" }
        return "\(d1) \(f.string(from: first)) – \(d2) \(f.string(from: last))"
    }

    /// A scam's reason code -> the plain words used in the note.
    static func reasonWords(_ code: String?) -> K {
        switch code {
        case Reasons.money: return .weekly_r_money
        case Reasons.impersonation: return .weekly_r_imp
        case Reasons.urgency: return .weekly_r_urgency
        default: return .weekly_r_other
        }
    }

    private static let checked: Set<String> = ["SAFE", "UNSURE", "SCAM"]
    private static let order: [K] = [.weekly_r_money, .weekly_r_imp, .weekly_r_urgency, .weekly_r_other]

    static func text(_ calls: [Call], nowMs: Int64, zone: TimeZone = .current) -> String {
        let w = week(nowMs: nowMs, zone: zone)
        let inWeek = calls.filter { $0.endedAt >= w.startMs && $0.endedAt <= w.endMs && checked.contains($0.verdict) }
        let head = L.s(.weekly_head, w.label)
        let end = L.s(.weekly_end)
        if inWeek.isEmpty { return "\(head) \(L.s(.weekly_none)) \(end)" }
        let scams = inWeek.filter { $0.verdict == "SCAM" }
        let careful = inWeek.filter { $0.verdict == "UNSURE" }.count
        var parts = [head]
        parts.append(inWeek.count == 1 ? L.s(.weekly_calls_one) : L.s(.weekly_calls_many, "\(inWeek.count)"))
        if scams.isEmpty {
            parts.append(L.s(.weekly_scam_zero))
        } else {
            // most frequent reason first, each once
            var counts: [K: Int] = [:]
            for s in scams { counts[reasonWords(s.reasonCode), default: 0] += 1 }
            let reasons = counts.sorted { a, b in
                a.value != b.value ? a.value > b.value : (order.firstIndex(of: a.key) ?? 9) < (order.firstIndex(of: b.key) ?? 9)
            }.map { L.s($0.key) }.joined(separator: "; ")
            parts.append(scams.count == 1 ? L.s(.weekly_scam_one, reasons) : L.s(.weekly_scam_many, "\(scams.count)", reasons))
        }
        if careful > 0 { parts.append(careful == 1 ? L.s(.weekly_careful_one) : L.s(.weekly_careful_many, "\(careful)")) }
        parts.append(end)
        return parts.joined(separator: " ")
    }
}

/// The optional "Weekly reminder" (off by default): a local notification once a week, no server. Notification
/// permission is asked only when the user turns it on; never at start or when turning it off.
nonisolated enum WeeklyReminder {
    enum Action: Equatable, Sendable { case askPermission, schedule, cancel, nothing }

    /// The switch was flipped.
    static func onToggle(on: Bool, permitted: Bool) -> Action { !on ? .cancel : permitted ? .schedule : .askPermission }

    /// The permission question was answered: on and scheduled, or off again.
    static func afterPermission(granted: Bool) -> (on: Bool, action: Action) { granted ? (true, .schedule) : (false, .nothing) }

    /// App start: re-arm a reminder that is on (only if notifications are still allowed); never asks.
    static func atLaunch(savedOn: Bool, permitted: Bool) -> Action { savedOn && permitted ? .schedule : .nothing }
}
