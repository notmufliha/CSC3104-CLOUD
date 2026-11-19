package com.example.scamdetect.logic

import com.example.scamdetect.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The summary screen's "What to do now" card (after a Scam or "Be careful" call) and the short summary it copies before
 * opening ScamShield's report page. Pure, kept in step with ios-app Logic/AfterCall.swift.
 *
 * The summary holds the date and time, the result, the reason in plain words and, only if the user lets ScamGuard keep
 * the riskiest sentence, the caller's flagged lines masked by logic/Mask.kt. It goes to the clipboard only: nothing is
 * uploaded.
 */
object AfterCall {
    /** Shown for a Scam call, and for a "Be careful" call unless those warnings are turned off. Never for safe calls. */
    fun shows(level: Risk.Level, showCareful: Boolean): Boolean =
        level == Risk.Level.RED || (level == Risk.Level.YELLOW && showCareful)

    /** "19 Nov 2026, 2:45 PM" (the same on iOS). */
    fun whenText(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).apply { timeZone = zone }.format(Date(timeMs))

    /**
     * The text copied for the ScamShield report. [lines] are the caller's flagged lines (raw); they are masked here and
     * left out entirely unless [includeLines] (the "Save the riskiest sentence" setting) is on.
     */
    fun reportSummary(timeMs: Long, result: String, reason: String, lines: List<String>, includeLines: Boolean,
                      zone: TimeZone = TimeZone.getDefault()): String = buildList {
        add(Tr.s(R.string.copy_title))
        add(Tr.s(R.string.copy_when, whenText(timeMs, zone)))
        add(Tr.s(R.string.copy_result, result))
        add(Tr.s(R.string.copy_why, reason))
        val masked = if (includeLines) lines.map { Mask.mask(it).trim() }.filter { it.isNotEmpty() }.distinct() else emptyList()
        if (masked.isNotEmpty()) {
            add(Tr.s(R.string.copy_lines))
            masked.forEach { add("- \"$it\"") }
        }
        add(Tr.s(R.string.copy_footer))
    }.joinToString("\n")

    /** The block-a-number steps for this platform. */
    val blockSteps = listOf(R.string.block_android_1, R.string.block_android_2, R.string.block_android_3, R.string.block_android_4)

    /** "If I already shared details": what to do, in order. Only 1799 has a number to call. */
    val sharedSteps = listOf(R.string.shared_bank, R.string.shared_passwords, R.string.shared_1799)
}
