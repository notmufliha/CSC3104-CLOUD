package com.example.scamdetect.logic

import com.example.scamdetect.R
import com.example.scamdetect.logic.DashboardStats.Period
import com.example.scamdetect.logic.DashboardStats.Stats

/**
 * Every sentence the dashboard, Insights and History screens show, in plain words (no "verdict", "signal",
 * "UNSURE"…), in plain words (string resources, via Tr). Kept in step with
 * ios-app/ScamGuard/Logic/PlainWords.swift; both apps test the same sentences.
 */
object PlainWords {
    fun verdict(v: String) = Tr.s(when (v) {
        "SAFE" -> R.string.risk_safe
        "UNSURE" -> R.string.risk_careful
        "SCAM" -> R.string.risk_scam
        else -> R.string.risk_not_checked
    })

    fun periodPhrase(p: Period) = Tr.s(when (p) {
        Period.WEEK -> R.string.pw_period_week
        Period.MONTH -> R.string.pw_period_month
        Period.ALL -> R.string.pw_period_all
    })

    fun periodLabel(p: Period) = Tr.s(when (p) {
        Period.WEEK -> R.string.period_week
        Period.MONTH -> R.string.period_month
        Period.ALL -> R.string.period_all
    })

    /** The hero card's big line. */
    fun hero(scams: Int, total: Int, p: Period): String = when {
        total == 0 -> if (p == Period.ALL) Tr.s(R.string.pw_hero_none_all) else Tr.s(R.string.pw_hero_none, periodPhrase(p))
        scams == 0 -> Tr.s(R.string.pw_hero_no_scams, periodPhrase(p))
        scams == 1 -> Tr.s(R.string.pw_hero_one, periodPhrase(p))
        else -> Tr.s(R.string.pw_hero_many, scams.toString(), periodPhrase(p))
    }

    /** Name of a warning sign (urgency / money / impersonation). */
    fun signalName(s: String) = when (s) {
        "urgency" -> Tr.s(R.string.pw_sig_urgency)
        "money" -> Tr.s(R.string.pw_sig_money)
        "impersonation" -> Tr.s(R.string.pw_sig_impersonation)
        else -> s
    }

    /** "Most warnings …" for the most common reason code. */
    fun reasonSentence(code: String?) = Tr.s(when (code) {
        Reasons.MONEY -> R.string.pw_reason_money
        Reasons.IMPERSONATION -> R.string.pw_reason_impersonation
        Reasons.URGENCY -> R.string.pw_reason_urgency
        Reasons.BERT -> R.string.pw_reason_bert
        else -> R.string.pw_reason_several
    })

    /** The main dashboard's "Top warning sign" line; null when nothing was flagged. */
    fun topWarning(s: Stats): String? = s.reasons.firstOrNull()?.let { reasonSentence(Reasons.codeForText(it.first)) }

    // ---- one-line takeaways above each Insights chart

    fun overTime(s: Stats): String =
        if (s.scam == 0) Tr.s(R.string.pw_over_none) else Tr.s(R.string.pw_over_pct, DashboardStats.percent(s.scam, s.total).toString())

    fun reasons(s: Stats): String = topWarning(s) ?: Tr.s(R.string.pw_no_flagged)

    fun signals(s: Stats): String {
        val top = s.signals.maxByOrNull { it.second }
        return if (top == null || top.second == 0) Tr.s(R.string.pw_no_signs) else Tr.s(R.string.pw_sign_most, signalName(top.first))
    }

    fun trend(s: Stats): String {
        val vals = s.buckets.mapNotNull { it.avgRisk }
        if (vals.size < 2) return Tr.s(R.string.pw_trend_few)
        val half = vals.size / 2
        val diff = vals.drop(half).average() - vals.take(half).average()
        return Tr.s(when {
            diff > 0.05 -> R.string.pw_trend_up
            diff < -0.05 -> R.string.pw_trend_down
            else -> R.string.pw_trend_same
        })
    }

    fun partOfDay(hour: Int) = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    fun hours(s: Stats): String {
        if (s.riskyHours.sum() == 0) return Tr.s(R.string.pw_hours_none)
        val peak = s.riskyHours.indices.maxBy { s.riskyHours[it] }
        return Tr.s(when (partOfDay(peak)) {
            "morning" -> R.string.pw_hours_morning
            "afternoon" -> R.string.pw_hours_afternoon
            "evening" -> R.string.pw_hours_evening
            else -> R.string.pw_hours_night
        })
    }

    fun doubleCheck(s: Stats): String = when {
        s.confirmed + s.calmed == 0 -> Tr.s(R.string.pw_double_none)
        s.calmed == 0 -> Tr.s(R.string.pw_double_all)
        s.calmed == 1 -> Tr.s(R.string.pw_double_one)
        else -> Tr.s(R.string.pw_double_many, s.calmed.toString())
    }

    fun where(s: Stats): String {
        val top = s.privacy.maxByOrNull { it.second }
        if (top == null || top.second == 0) return Tr.s(R.string.pw_none_period)
        return Tr.s(when (top.first) {
            "hybrid" -> R.string.pw_where_hybrid
            "on_device" -> R.string.pw_where_on_device
            else -> R.string.pw_where_cloud
        })
    }

    fun answers(s: Stats): String = when {
        s.markedFine + s.hungUpAfterWarning == 0 -> Tr.s(R.string.pw_answers_none)
        s.markedFine == 1 -> Tr.s(R.string.pw_answers_one)
        else -> Tr.s(R.string.pw_answers_many, s.markedFine.toString())
    }

    fun length(s: Stats): String =
        if (s.total == 0) Tr.s(R.string.pw_none_period) else Tr.s(R.string.pw_length, DashboardStats.duration(s.avgLengthSeconds))

    // ---- lists

    /** "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago"; null = older (show the date). */
    fun timeAgo(endedAtMs: Long, nowMs: Long, daysAgo: Int): String? {
        val minutes = (nowMs - endedAtMs) / 60_000
        return when {
            minutes < 1 -> Tr.s(R.string.pw_just_now)
            minutes < 60 -> Tr.s(R.string.pw_min_ago, minutes.toString())
            daysAgo <= 0 -> Tr.s(R.string.pw_h_ago, (minutes / 60).toString())
            daysAgo == 1 -> Tr.s(R.string.pw_yesterday)
            daysAgo < 7 -> Tr.s(R.string.pw_days_ago, daysAgo.toString())
            else -> null
        }
    }

    /** History's section for a call. */
    fun historyGroup(daysAgo: Int) = Tr.s(when {
        daysAgo <= 0 -> R.string.pw_today
        daysAgo < 7 -> R.string.pw_this_week
        else -> R.string.pw_earlier
    })

    /** The call detail page's "Checked …" line (no technical words). */
    fun checkedWhere(privacyId: String) = Tr.s(when (privacyId) {
        "hybrid" -> R.string.pw_checked_hybrid
        "on_device" -> R.string.pw_checked_on_device
        else -> R.string.pw_checked_cloud
    })
}
