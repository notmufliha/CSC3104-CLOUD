package com.example.scamdetect.logic

import com.example.scamdetect.logic.DashboardStats.Period
import com.example.scamdetect.logic.DashboardStats.Stats

/**
 * Every sentence the dashboard, Insights and History screens show, in plain words (no "verdict", "signal",
 * "UNSURE"…). Pure, and kept word for word in step with ios-app/ScamGuard/Logic/PlainWords.swift; both apps test
 * the same sentences.
 */
object PlainWords {
    fun verdict(v: String) = when (v) {
        "SAFE" -> "Safe"
        "UNSURE" -> "Be careful"
        "SCAM" -> "Scam"
        else -> "Not checked"
    }

    fun periodPhrase(p: Period) = when (p) {
        Period.WEEK -> "this week"
        Period.MONTH -> "this month"
        Period.ALL -> "so far"
    }

    /** The hero card's big line. */
    fun hero(scams: Int, total: Int, p: Period): String = when {
        total == 0 -> if (p == Period.ALL) "No calls yet" else "No calls ${periodPhrase(p)}"
        scams == 0 -> "No scam calls ${periodPhrase(p)} 🎉"
        scams == 1 -> "1 scam call caught ${periodPhrase(p)}"
        else -> "$scams scam calls caught ${periodPhrase(p)}"
    }

    /** Name of a warning sign (urgency / money / impersonation). */
    fun signalName(s: String) = when (s) {
        "urgency" -> "Pressure to act fast"
        "money" -> "Talk of money or codes"
        "impersonation" -> "Pretending to be official"
        else -> s
    }

    /** "Most warnings …" for the most common reason code. */
    fun reasonSentence(code: String?) = when (code) {
        Reasons.MONEY -> "Most warnings asked for your OTP or money"
        Reasons.IMPERSONATION -> "Most warnings came from fake police, banks or officials"
        Reasons.URGENCY -> "Most warnings pushed you to act fast"
        Reasons.BERT -> "Most warnings sounded like a scam overall"
        else -> "Most warnings had several small warning signs"
    }

    /** The main dashboard's "Top warning sign" line; null when nothing was flagged. */
    fun topWarning(s: Stats): String? = s.reasons.firstOrNull()?.let { reasonSentence(Reasons.codeForText(it.first)) }

    // ---- one-line takeaways above each Insights chart

    fun overTime(s: Stats): String =
        if (s.scam == 0) "No scam calls in this period" else "${DashboardStats.percent(s.scam, s.total)}% of calls were scams"

    fun reasons(s: Stats): String = topWarning(s) ?: "No calls were flagged"

    fun signals(s: Stats): String {
        val top = s.signals.maxByOrNull { it.second }
        return if (top == null || top.second == 0) "No warning signs heard" else "${signalName(top.first)} came up most often"
    }

    fun trend(s: Stats): String {
        val vals = s.buckets.mapNotNull { it.avgRisk }
        if (vals.size < 2) return "Not enough calls to see a trend"
        val half = vals.size / 2
        val diff = vals.drop(half).average() - vals.take(half).average()
        return when {
            diff > 0.05 -> "Risk is going up"
            diff < -0.05 -> "Risk is going down"
            else -> "Risk is staying about the same"
        }
    }

    fun partOfDay(hour: Int) = when (hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    fun hours(s: Stats): String {
        if (s.riskyHours.sum() == 0) return "No risky calls in this period"
        val peak = s.riskyHours.indices.maxBy { s.riskyHours[it] }
        return "Most risky calls happen in the ${partOfDay(peak)}"
    }

    fun doubleCheck(s: Stats): String = when {
        s.confirmed + s.calmed == 0 -> "No warnings to double-check"
        s.calmed == 0 -> "Every warning was confirmed"
        s.calmed == 1 -> "1 warning turned out safe after a second look"
        else -> "${s.calmed} warnings turned out safe after a second look"
    }

    fun where(s: Stats): String {
        val top = s.privacy.maxByOrNull { it.second }
        if (top == null || top.second == 0) return "No calls in this period"
        return when (top.first) {
            "hybrid" -> "Most calls were checked on this phone with server help"
            "on_device" -> "Most calls were checked on this phone"
            else -> "Most calls were checked by the ScamGuard server"
        }
    }

    fun answers(s: Stats): String = when {
        s.markedFine + s.hungUpAfterWarning == 0 -> "No answers from you yet"
        s.markedFine == 1 -> "You marked 1 warning as fine"
        else -> "You marked ${s.markedFine} warnings as fine"
    }

    fun length(s: Stats): String =
        if (s.total == 0) "No calls in this period" else "An average call lasted ${DashboardStats.duration(s.avgLengthSeconds)}"

    // ---- lists

    /** "Just now", "12 min ago", "3 h ago", "Yesterday", "4 days ago"; null = older (show the date). */
    fun timeAgo(endedAtMs: Long, nowMs: Long, daysAgo: Int): String? {
        val minutes = (nowMs - endedAtMs) / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            daysAgo <= 0 -> "${minutes / 60} h ago"
            daysAgo == 1 -> "Yesterday"
            daysAgo < 7 -> "$daysAgo days ago"
            else -> null
        }
    }

    /** History's section for a call. */
    fun historyGroup(daysAgo: Int) = when {
        daysAgo <= 0 -> "Today"
        daysAgo < 7 -> "This week"
        else -> "Earlier"
    }

    /** The call detail page's "Checked …" line. */
    fun checkedWhere(privacyId: String) = when (privacyId) {
        "hybrid" -> "Checked on this phone with server help"
        "on_device" -> "Checked on this phone"
        else -> "Checked by the ScamGuard server"
    }
}
