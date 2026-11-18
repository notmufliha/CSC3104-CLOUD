import Foundation

/// A value copy of one ImplicitLabel.
nonisolated struct LabelRow: Equatable, Sendable {
    var callId: String
    var label: Int
    var source: String
    var time: Int64
}

/// The dashboard's numbers for one period, from the phone's own database only. Kept line for line in step with
/// android-app logic/DashboardStats.kt: both apps' tests check the same numbers on the same sample history.
nonisolated enum DashboardStats {
    enum Period: String, CaseIterable, Identifiable, Sendable {
        case week, month, all
        var id: String { rawValue }
        var days: Int? {
            switch self {
            case .week: return 7
            case .month: return 30
            case .all: return nil
            }
        }
        var label: String {
            switch self {
            case .week: return L.s(.period_week)
            case .month: return L.s(.period_month)
            case .all: return L.s(.period_all)
            }
        }
    }

    static let dayMs: Int64 = 86_400_000
    private static let warnings: Set<String> = ["SCAM", "UNSURE"]

    /// One bar of "Calls over time": a day (7 / 30 days) or a week (All). `daysAgo` = its first day, 0 = today.
    struct Bucket: Equatable, Sendable {
        var daysAgo: Int
        var safe: Int
        var unsure: Int
        var scam: Int
        var notChecked: Int
        /// Average call risk score of the bucket's calls (0-1), nil when none had one.
        var avgRisk: Double?
        var total: Int { safe + unsure + scam + notChecked }
    }

    struct Stats: Sendable {
        var total: Int
        var safe: Int
        var unsure: Int
        var scam: Int
        var notChecked: Int
        var safePercent: Int
        var buckets: [Bucket]
        var weekly: Bool
        var reasons: [(String, Int)]
        var signals: [(String, Int)]
        var riskyHours: [Int]
        var confirmed: Int
        var calmed: Int
        var raised: Int
        var privacy: [(String, Int)]
        var markedFine: Int
        var hungUpAfterWarning: Int
        var lengthVsRisk: [(Int, Double)]
        var avgLengthSeconds: Int
        var isEmpty: Bool { total == 0 }
    }

    private static func calendar(_ tz: TimeZone) -> Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = tz
        return c
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    /// Calendar days between the call's local date and today's (0 = today, 1 = yesterday; negative = future).
    static func daysAgo(_ timeMs: Int64, _ nowMs: Int64, _ tz: TimeZone) -> Int {
        let c = calendar(tz)
        return c.dateComponents([.day], from: c.startOfDay(for: date(timeMs)), to: c.startOfDay(for: date(nowMs))).day ?? 0
    }

    static func hourOf(_ timeMs: Int64, _ tz: TimeZone) -> Int { calendar(tz).component(.hour, from: date(timeMs)) }

    static func inPeriod(_ daysAgo: Int, _ period: Period) -> Bool {
        guard let d = period.days else { return true }
        return daysAgo >= 0 && daysAgo < d
    }

    /// Whole percent, rounded half up, without floating point (so both apps agree exactly).
    static func percent(_ part: Int, _ total: Int) -> Int { total == 0 ? 0 : (part * 200 + total) / (2 * total) }

    static func compute(_ allCalls: [CallRow], _ allLabels: [LabelRow], _ period: Period, nowMs: Int64, tz: TimeZone) -> Stats {
        let calls = allCalls.filter { inPeriod(daysAgo($0.endedAt, nowMs, tz), period) }
        let labels = allLabels.filter { inPeriod(daysAgo($0.time, nowMs, tz), period) }
        func count(_ v: String) -> Int { calls.filter { $0.finalVerdict == v }.count }
        let safe = count("SAFE"), unsure = count("UNSURE"), scam = count("SCAM")
        let notChecked = calls.count - safe - unsure - scam

        // ---- buckets: days, or weeks for All
        let weekly = period.days == nil
        let dayIdx = calls.map { max(daysAgo($0.endedAt, nowMs, tz), 0) }
        let n = period.days ?? ((dayIdx.max() ?? 0) / 7 + 1)
        let width = weekly ? 7 : 1
        let buckets: [Bucket] = (0..<n).map { b in
            let back = n - 1 - b
            let members = calls.indices.filter { dayIdx[$0] / width == back }.map { calls[$0] }
            let scores = members.compactMap { $0.callScore.map(Double.init) }
            return Bucket(
                daysAgo: back * width + (width - 1),
                safe: members.filter { $0.finalVerdict == "SAFE" }.count,
                unsure: members.filter { $0.finalVerdict == "UNSURE" }.count,
                scam: members.filter { $0.finalVerdict == "SCAM" }.count,
                notChecked: members.filter { !["SAFE", "UNSURE", "SCAM"].contains($0.finalVerdict) }.count,
                avgRisk: scores.isEmpty ? nil : scores.reduce(0, +) / Double(scores.count))
        }

        let warned = calls.filter { warnings.contains($0.finalVerdict) }
        var reasonCounts: [String: Int] = [:]
        for c in warned {
            let r = c.displayReason
            if !r.trimmingCharacters(in: .whitespaces).isEmpty { reasonCounts[r, default: 0] += 1 }
        }
        let reasons = reasonCounts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(5).map { ($0.key, $0.value) }
        let signals = Risk.signals.map { s in (s, calls.filter { $0.signalsFired.contains(s) }.count) }
        var hours = [Int](repeating: 0, count: 24)
        for c in warned { hours[hourOf(c.endedAt, tz)] += 1 }

        let liveWarn = calls.filter { warnings.contains($0.liveVerdict ?? "") }
        let confirmed = liveWarn.filter { warnings.contains($0.finalVerdict) }.count
        let calmed = liveWarn.filter { $0.finalVerdict == "SAFE" }.count
        let raised = calls.filter { $0.liveVerdict == "SAFE" && warnings.contains($0.finalVerdict) }.count

        let privacy = PrivacyLevel.allCases.map { l in (l.rawValue, calls.filter { $0.privacyLevel == l.rawValue }.count) }
        let lengths = calls.map { Int(max((($0.endedAt - $0.startedAt) / 1000), 0)) }
        let lengthVsRisk = calls.indices.compactMap { i in calls[i].callScore.map { (lengths[i], Double($0)) } }

        return Stats(
            total: calls.count, safe: safe, unsure: unsure, scam: scam, notChecked: notChecked,
            safePercent: percent(safe, calls.count),
            buckets: buckets, weekly: weekly,
            reasons: reasons, signals: signals, riskyHours: hours,
            confirmed: confirmed, calmed: calmed, raised: raised,
            privacy: privacy,
            markedFine: labels.filter { $0.source == ImplicitLabels.sourceFine }.count,
            hungUpAfterWarning: labels.filter { $0.source == ImplicitLabels.sourceHungUp }.count,
            lengthVsRisk: lengthVsRisk,
            avgLengthSeconds: lengths.isEmpty ? 0 : lengths.reduce(0, +) / lengths.count)
    }

    /// The call detail page's one-line "What happened" (same wording on Android).
    static func whatHappened(_ finalVerdict: String, _ liveVerdict: String?, _ reason: String) -> String {
        var r = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        while r.hasSuffix(".") { r.removeLast() }
        if let f = r.first { r = f.lowercased() + r.dropFirst() }
        switch finalVerdict {
        case "SCAM": return r.isEmpty ? L.s(.ds_what_scam) : L.s(.ds_what_scam_r, r)
        case "UNSURE": return r.isEmpty ? L.s(.ds_what_off) : L.s(.ds_what_off_r, r)
        case "SAFE":
            return warnings.contains(liveVerdict ?? "") ? L.s(.ds_what_calmed) : L.s(.ds_what_safe)
        default: return L.s(.ds_what_unchecked)
        }
    }

    /// "1 min 24 s" / "45 s".
    static func duration(_ seconds: Int) -> String {
        seconds >= 60 ? L.s(.pw_dur_min, "\(seconds / 60)", "\(seconds % 60)") : L.s(.pw_dur_s, "\(seconds)")
    }
}
