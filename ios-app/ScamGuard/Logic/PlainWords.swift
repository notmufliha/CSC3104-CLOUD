import Foundation

/// Every sentence the dashboard, Insights and History screens show, in plain words (no "verdict", "signal",
/// "UNSURE"…), in plain words (L.s). Kept in step with android-app logic/PlainWords.kt; both apps test the
/// same sentences.
nonisolated enum PlainWords {
    typealias Period = DashboardStats.Period
    typealias Stats = DashboardStats.Stats

    static func verdict(_ v: String) -> String {
        switch v {
        case "SAFE": return L.s(.risk_safe)
        case "UNSURE": return L.s(.risk_careful)
        case "SCAM": return L.s(.risk_scam)
        default: return L.s(.risk_not_checked)
        }
    }

    static func periodPhrase(_ p: Period) -> String {
        switch p {
        case .week: return L.s(.pw_period_week)
        case .month: return L.s(.pw_period_month)
        case .all: return L.s(.pw_period_all)
        }
    }

    static func periodLabel(_ p: Period) -> String {
        switch p {
        case .week: return L.s(.period_week)
        case .month: return L.s(.period_month)
        case .all: return L.s(.period_all)
        }
    }

    /// The hero card's big line.
    static func hero(_ scams: Int, _ total: Int, _ p: Period) -> String {
        if total == 0 { return p == .all ? L.s(.pw_hero_none_all) : L.s(.pw_hero_none, periodPhrase(p)) }
        if scams == 0 { return L.s(.pw_hero_no_scams, periodPhrase(p)) }
        if scams == 1 { return L.s(.pw_hero_one, periodPhrase(p)) }
        return L.s(.pw_hero_many, "\(scams)", periodPhrase(p))
    }

    /// Name of a warning sign (urgency / money / impersonation).
    static func signalName(_ s: String) -> String {
        switch s {
        case "urgency": return L.s(.pw_sig_urgency)
        case "money": return L.s(.pw_sig_money)
        case "impersonation": return L.s(.pw_sig_impersonation)
        default: return s
        }
    }

    /// "Most warnings …" for the most common reason code.
    static func reasonSentence(_ code: String?) -> String {
        switch code {
        case Reasons.money: return L.s(.pw_reason_money)
        case Reasons.impersonation: return L.s(.pw_reason_impersonation)
        case Reasons.urgency: return L.s(.pw_reason_urgency)
        case Reasons.bert: return L.s(.pw_reason_bert)
        default: return L.s(.pw_reason_several)
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
        s.scam == 0 ? L.s(.pw_over_none) : L.s(.pw_over_pct, "\(DashboardStats.percent(s.scam, s.total))")
    }

    static func reasons(_ s: Stats) -> String { topWarning(s) ?? L.s(.pw_no_flagged) }

    static func signals(_ s: Stats) -> String {
        guard let top = firstMax(s.signals), top.1 > 0 else { return L.s(.pw_no_signs) }
        return L.s(.pw_sign_most, signalName(top.0))
    }

    static func trend(_ s: Stats) -> String {
        let vals = s.buckets.compactMap(\.avgRisk)
        if vals.count < 2 { return L.s(.pw_trend_few) }
        let half = vals.count / 2
        let first = vals.prefix(half), second = vals.dropFirst(half)
        let diff = second.reduce(0, +) / Double(second.count) - first.reduce(0, +) / Double(first.count)
        if diff > 0.05 { return L.s(.pw_trend_up) }
        if diff < -0.05 { return L.s(.pw_trend_down) }
        return L.s(.pw_trend_same)
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
        if s.riskyHours.reduce(0, +) == 0 { return L.s(.pw_hours_none) }
        var peak = 0
        for i in s.riskyHours.indices where s.riskyHours[i] > s.riskyHours[peak] { peak = i }
        switch partOfDay(peak) {
        case "morning": return L.s(.pw_hours_morning)
        case "afternoon": return L.s(.pw_hours_afternoon)
        case "evening": return L.s(.pw_hours_evening)
        default: return L.s(.pw_hours_night)
        }
    }

    static func doubleCheck(_ s: Stats) -> String {
        if s.confirmed + s.calmed == 0 { return L.s(.pw_double_none) }
        if s.calmed == 0 { return L.s(.pw_double_all) }
        if s.calmed == 1 { return L.s(.pw_double_one) }
        return L.s(.pw_double_many, "\(s.calmed)")
    }

    static func whereChecked(_ s: Stats) -> String {
        guard let top = firstMax(s.privacy), top.1 > 0 else { return L.s(.pw_none_period) }
        switch top.0 {
        case "hybrid": return L.s(.pw_where_hybrid)
        case "on_device": return L.s(.pw_where_on_device)
        default: return L.s(.pw_where_cloud)
        }
    }

    static func answers(_ s: Stats) -> String {
        if s.markedFine + s.hungUpAfterWarning == 0 { return L.s(.pw_answers_none) }
        return s.markedFine == 1 ? L.s(.pw_answers_one) : L.s(.pw_answers_many, "\(s.markedFine)")
    }

    static func length(_ s: Stats) -> String {
        s.total == 0 ? L.s(.pw_none_period) : L.s(.pw_length, DashboardStats.duration(s.avgLengthSeconds))
    }

    // ---- lists

    /// "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago"; nil = older (show the date).
    static func timeAgo(_ endedAtMs: Int64, _ nowMs: Int64, _ daysAgo: Int) -> String? {
        let minutes = (nowMs - endedAtMs) / 60_000
        if minutes < 1 { return L.s(.pw_just_now) }
        if minutes < 60 { return L.s(.pw_min_ago, "\(minutes)") }
        if daysAgo <= 0 { return L.s(.pw_h_ago, "\(minutes / 60)") }
        if daysAgo == 1 { return L.s(.pw_yesterday) }
        if daysAgo < 7 { return L.s(.pw_days_ago, "\(daysAgo)") }
        return nil
    }

    /// History's section for a call.
    static func historyGroup(_ daysAgo: Int) -> String {
        L.s(daysAgo <= 0 ? .pw_today : daysAgo < 7 ? .pw_this_week : .pw_earlier)
    }

    /// The call detail page's "Checked …" line (no technical words).
    static func checkedWhere(_ privacyId: String) -> String {
        switch privacyId {
        case "hybrid": return L.s(.pw_checked_hybrid)
        case "on_device": return L.s(.pw_checked_on_device)
        default: return L.s(.pw_checked_cloud)
        }
    }
}
