package com.example.scamdetect.logic

import com.example.scamdetect.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Share weekly summary": a plain note for the family about the last 7 days, built from the history on this phone.
 * It never holds phone numbers, anything the caller said, URLs or addresses: only counts and plain reasons. Shared
 * through the system share sheet (the user picks the app); nothing is uploaded. Same rules as ios-app
 * Logic/WeeklySummary.swift.
 */
object WeeklySummary {
    /** One saved call, as much as the summary needs. */
    data class Call(val endedAt: Long, val verdict: String, val reasonCode: String?)

    /** The last 7 days: today and the 6 before, from the start of the first day to now. */
    data class Week(val startMs: Long, val endMs: Long, val label: String)

    fun week(nowMs: Long, zone: ZoneId): Week {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val first = today.minusDays(6)
        return Week(first.atStartOfDay(zone).toInstant().toEpochMilli(), nowMs, label(first, today))
    }

    /** "12–18 Nov", or "28 Oct – 3 Nov" across a month. */
    fun label(first: LocalDate, last: LocalDate): String {
        val month = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
        return if (first.month == last.month) "${first.dayOfMonth}–${last.dayOfMonth} ${last.format(month)}"
        else "${first.dayOfMonth} ${first.format(month)} – ${last.dayOfMonth} ${last.format(month)}"
    }

    /** A scam's reason code -> the plain words used in the note. */
    fun reasonWords(code: String?): Int = when (code) {
        Reasons.MONEY -> R.string.weekly_r_money
        Reasons.IMPERSONATION -> R.string.weekly_r_imp
        Reasons.URGENCY -> R.string.weekly_r_urgency
        else -> R.string.weekly_r_other
    }

    fun text(calls: List<Call>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val w = week(nowMs, zone)
        val inWeek = calls.filter { it.endedAt in w.startMs..w.endMs && it.verdict in CHECKED }
        val head = Tr.s(R.string.weekly_head, w.label)
        val end = Tr.s(R.string.weekly_end)
        if (inWeek.isEmpty()) return "$head ${Tr.s(R.string.weekly_none)} $end"
        val scams = inWeek.filter { it.verdict == "SCAM" }
        val careful = inWeek.count { it.verdict == "UNSURE" }
        val parts = mutableListOf(head)
        parts += if (inWeek.size == 1) Tr.s(R.string.weekly_calls_one) else Tr.s(R.string.weekly_calls_many, inWeek.size.toString())
        if (scams.isEmpty()) parts += Tr.s(R.string.weekly_scam_zero)
        else {
            // most frequent reason first, each once
            val reasons = scams.groupingBy { reasonWords(it.reasonCode) }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { ORDER.indexOf(it.key) })
                .joinToString("; ") { Tr.s(it.key) }
            parts += if (scams.size == 1) Tr.s(R.string.weekly_scam_one, reasons) else Tr.s(R.string.weekly_scam_many, scams.size.toString(), reasons)
        }
        if (careful > 0) parts += if (careful == 1) Tr.s(R.string.weekly_careful_one) else Tr.s(R.string.weekly_careful_many, careful.toString())
        parts += end
        return parts.joinToString(" ")
    }

    /** Calls that were actually checked (a call that couldn't be checked isn't counted). */
    private val CHECKED = setOf("SAFE", "UNSURE", "SCAM")
    private val ORDER = listOf(R.string.weekly_r_money, R.string.weekly_r_imp, R.string.weekly_r_urgency, R.string.weekly_r_other)
}

/**
 * The optional "Weekly reminder" (off by default): a local notification once a week, no server. Notification
 * permission is asked only when the user turns it on; never at start or when turning it off.
 */
object WeeklyReminder {
    enum class Action { ASK_PERMISSION, SCHEDULE, CANCEL, NOTHING }

    /** The switch was flipped. */
    fun onToggle(on: Boolean, permitted: Boolean): Action = when {
        !on -> Action.CANCEL
        permitted -> Action.SCHEDULE
        else -> Action.ASK_PERMISSION
    }

    /** The permission question was answered: on and scheduled, or off again. */
    fun afterPermission(granted: Boolean): Pair<Boolean, Action> = if (granted) true to Action.SCHEDULE else false to Action.NOTHING

    /** App start: re-arm a reminder that is on (only if notifications are still allowed); never asks. */
    fun atLaunch(savedOn: Boolean, permitted: Boolean): Action = if (savedOn && permitted) Action.SCHEDULE else Action.NOTHING
}
