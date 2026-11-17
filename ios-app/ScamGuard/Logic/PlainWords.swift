import Foundation

/// Every sentence the dashboard, Insights and History screens show, in plain words (no "verdict", "signal",
/// "UNSURE"…). Pure, and kept word for word in step with android-app logic/PlainWords.kt; both apps test the same
/// sentences.
nonisolated enum PlainWords {
    typealias Period = DashboardStats.Period
    typealias Stats = DashboardStats.Stats

    static func verdict(_ v: String) -> String {
        switch v {
        case "SAFE": return "Safe"
        case "UNSURE": return "Be careful"
        case "SCAM": return "Scam"
        default: return "Not checked"
        }
    }

    static func periodPhrase(_ p: Period) -> String {
        switch p {
        case .week: return "this week"
        case .month: return "this month"
        case .all: return "so far"
        }
    }

    /// The hero card's big line.
    static func hero(_ scams: Int, _ total: Int, _ p: Period) -> String {
        if total == 0 { return p == .all ? "No calls yet" : "No calls \(periodPhrase(p))" }
        if scams == 0 { return "No scam calls \(periodPhrase(p)) 🎉" }
        if scams == 1 { return "1 scam call caught \(periodPhrase(p))" }
        return "\(scams) scam calls caught \(periodPhrase(p))"
    }

    /// Name of a warning sign (urgency / money / impersonation).
    static func signalName(_ s: String) -> String {
        switch s {
        case "urgency": return "Pressure to act fast"
        case "money": return "Talk of money or codes"
        case "impersonation": return "Pretending to be official"
        default: return s
        }
    }

    /// "Most warnings …" for the most common reason code.
    static func reasonSentence(_ code: String?) -> String {
        switch code {
        case Reasons.money: return "Most warnings asked for your OTP or money"
        case Reasons.impersonation: return "Most warnings came from fake police, banks or officials"
        case Reasons.urgency: return "Most warnings pushed you to act fast"
        case Reasons.bert: return "Most warnings sounded like a scam overall"
        default: return "Most warnings had several small warning signs"
        }
    }

    /// The main dashboard's "Top warning sign" line; nil when nothing was flagged.
    static func topWarning(_ s: Stats) -> String? { s.reasons.first.map { reasonSentence(Reasons.codeForText($0.0)) } }

    /// The first pair with the largest count (Kotlin's maxByOrNull keeps the first of equal maxima).
    private static func firstMax(_ xs: [(String, Int)]) -> (String, Int)? {
        var best: (String, Int)?
        for x in xs where best == nil || x.1 > best!.1 { best = x }
        return best
    }

    // ---- one-line takeaways above each Insights chart

    static func overTime(_ s: Stats) -> String {
        s.scam == 0 ? "No scam calls in this period" : "\(DashboardStats.percent(s.scam, s.total))% of calls were scams"
    }

    static func reasons(_ s: Stats) -> String { topWarning(s) ?? "No calls were flagged" }

    static func signals(_ s: Stats) -> String {
        guard let top = firstMax(s.signals), top.1 > 0 else { return "No warning signs heard" }
        return "\(signalName(top.0)) came up most often"
    }

    static func trend(_ s: Stats) -> String {
        let vals = s.buckets.compactMap(\.avgRisk)
        if vals.count < 2 { return "Not enough calls to see a trend" }
        let half = vals.count / 2
        func avg(_ x: ArraySlice<Double>) -> Double { x.reduce(0, +) / Double(x.count) }
        let diff = avg(vals[half...]) - avg(vals[..<half])
        if diff > 0.05 { return "Risk is going up" }
        if diff < -0.05 { return "Risk is going down" }
        return "Risk is staying about the same"
    }

    static func partOfDay(_ hour: Int) -> String {
        switch hour {
        case 5...11: return "morning"
        case 12...16: return "afternoon"
        case 17...21: return "evening"
        default: return "night"
        }
    }

    static func hours(_ s: Stats) -> String {
        if s.riskyHours.reduce(0, +) == 0 { return "No risky calls in this period" }
        var peak = 0
        for h in s.riskyHours.indices where s.riskyHours[h] > s.riskyHours[peak] { peak = h }
        return "Most risky calls happen in the \(partOfDay(peak))"
    }

    static func doubleCheck(_ s: Stats) -> String {
        if s.confirmed + s.calmed == 0 { return "No warnings to double-check" }
        if s.calmed == 0 { return "Every warning was confirmed" }
        if s.calmed == 1 { return "1 warning turned out safe after a second look" }
        return "\(s.calmed) warnings turned out safe after a second look"
    }

    static func whereChecked(_ s: Stats) -> String {
        guard let top = firstMax(s.privacy), top.1 > 0 else { return "No calls in this period" }
        switch top.0 {
        case "hybrid": return "Most calls were checked on this phone with server help"
        case "on_device": return "Most calls were checked on this phone"
        default: return "Most calls were checked by the ScamGuard server"
        }
    }

    static func answers(_ s: Stats) -> String {
        if s.markedFine + s.hungUpAfterWarning == 0 { return "No answers from you yet" }
        return s.markedFine == 1 ? "You marked 1 warning as fine" : "You marked \(s.markedFine) warnings as fine"
    }

    static func length(_ s: Stats) -> String {
        s.total == 0 ? "No calls in this period" : "An average call lasted \(DashboardStats.duration(s.avgLengthSeconds))"
    }

    // ---- lists

    /// "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago"; nil = older (show the date).
    static func timeAgo(_ endedAtMs: Int64, _ nowMs: Int64, _ daysAgo: Int) -> String? {
        let minutes = (nowMs - endedAtMs) / 60_000
        if minutes < 1 { return "Just now" }
        if minutes < 60 { return "\(minutes) min ago" }
        if daysAgo <= 0 { return "\(minutes / 60) h ago" }
        if daysAgo == 1 { return "Yesterday" }
        if daysAgo < 7 { return "\(daysAgo) days ago" }
        return nil
    }

    /// History's section for a call.
    static func historyGroup(_ daysAgo: Int) -> String { daysAgo <= 0 ? "Today" : daysAgo < 7 ? "This week" : "Earlier" }

    /// The call detail page's "Checked …" line.
    static func checkedWhere(_ privacyId: String) -> String {
        switch privacyId {
        case "hybrid": return "Checked on this phone with server help"
        case "on_device": return "Checked on this phone"
        default: return "Checked by the ScamGuard server"
        }
    }
}
