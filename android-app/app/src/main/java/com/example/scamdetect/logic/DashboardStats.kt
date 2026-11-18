package com.example.scamdetect.logic

import com.example.scamdetect.R

import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ImplicitLabel
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The dashboard's numbers for one period, computed from the phone's own database only. Pure (no Room, no Compose),
 * and kept line for line in step with ios-app/ScamGuard/Logic/DashboardStats.swift: both apps' tests check the same
 * numbers on the same sample history (SampleHistory).
 */
object DashboardStats {
    enum class Period(val days: Int?, val label: String) { WEEK(7, "7 days"), MONTH(30, "30 days"), ALL(null, "All") }

    const val DAY_MS = 86_400_000L
    private val WARNINGS = setOf("SCAM", "UNSURE")

    /** One bar of "Calls over time": a day (7 / 30 days) or a week (All). `daysAgo` = its first day, 0 = today. */
    data class Bucket(
        val daysAgo: Int,
        val safe: Int,
        val unsure: Int,
        val scam: Int,
        val notChecked: Int,
        /** Average call risk score of the bucket's calls (0-1), null when none had one. */
        val avgRisk: Double?,
    ) {
        val total get() = safe + unsure + scam + notChecked
    }

    data class Stats(
        val total: Int,
        val safe: Int,
        val unsure: Int,
        val scam: Int,
        val notChecked: Int,
        /** Whole percent of calls that ended SAFE (rounded half up); 0 when there are no calls. */
        val safePercent: Int,
        /** Oldest first. Days for 7 / 30 days, weeks for All. */
        val buckets: List<Bucket>,
        val weekly: Boolean,
        /** Why calls were flagged: (shown wording, count), at most 5, most common first. */
        val reasons: List<Pair<String, Int>>,
        /** How many calls each warning sign fired in, in Risk.SIGNALS order. */
        val signals: List<Pair<String, Int>>,
        /** Warning calls (SCAM / UNSURE) per hour of the day, 0-23. */
        val riskyHours: List<Int>,
        /** Live warnings that the end-of-call check confirmed / found safe; live SAFE calls it raised to a warning. */
        val confirmed: Int,
        val calmed: Int,
        val raised: Int,
        /** Calls per privacy level, in PrivacyLevel order (ids). */
        val privacy: List<Pair<String, Int>>,
        /** Labels the user gave in this period. */
        val markedFine: Int,
        val hungUpAfterWarning: Int,
        /** (call length in seconds, call risk score) for calls with a score. */
        val lengthVsRisk: List<Pair<Int, Double>>,
        val avgLengthSeconds: Int,
    ) {
        val isEmpty get() = total == 0
    }

    /** Calendar days between the call's local date and today's (0 = today, 1 = yesterday; negative = future). */
    fun daysAgo(timeMs: Long, nowMs: Long, zone: ZoneId): Int {
        val day = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(day, today).toInt()
    }

    fun hourOf(timeMs: Long, zone: ZoneId): Int = Instant.ofEpochMilli(timeMs).atZone(zone).hour

    fun inPeriod(daysAgo: Int, period: Period): Boolean = period.days?.let { daysAgo in 0 until it } ?: true

    /** Whole percent, rounded half up, without floating point (so both apps agree exactly). */
    fun percent(part: Int, total: Int): Int = if (total == 0) 0 else (part * 200 + total) / (2 * total)

    fun compute(
        allCalls: List<CallRecord>,
        allLabels: List<ImplicitLabel>,
        period: Period,
        nowMs: Long,
        zone: ZoneId,
    ): Stats {
        val calls = allCalls.filter { inPeriod(daysAgo(it.endedAt, nowMs, zone), period) }
        val labels = allLabels.filter { inPeriod(daysAgo(it.time, nowMs, zone), period) }
        fun count(v: String) = calls.count { it.finalVerdict == v }
        val safe = count("SAFE")
        val unsure = count("UNSURE")
        val scam = count("SCAM")
        val notChecked = calls.size - safe - unsure - scam

        // ---- buckets: days, or weeks for All
        val weekly = period.days == null
        val dayIdx = calls.map { maxOf(daysAgo(it.endedAt, nowMs, zone), 0) }
        val n = period.days ?: ((dayIdx.maxOrNull() ?: 0) / 7 + 1)
        val width = if (weekly) 7 else 1
        val buckets = (0 until n).map { b ->
            // b = 0 is the oldest bucket; its first day is (n - 1 - b) buckets ago
            val back = n - 1 - b
            val members = calls.filterIndexed { i, _ -> dayIdx[i] / width == back }
            val scores = members.mapNotNull { it.callScore?.toDouble() }
            Bucket(
                daysAgo = back * width + (width - 1),
                safe = members.count { it.finalVerdict == "SAFE" },
                unsure = members.count { it.finalVerdict == "UNSURE" },
                scam = members.count { it.finalVerdict == "SCAM" },
                notChecked = members.count { it.finalVerdict !in setOf("SAFE", "UNSURE", "SCAM") },
                avgRisk = if (scores.isEmpty()) null else scores.sum() / scores.size,
            )
        }

        val warnings = calls.filter { it.finalVerdict in WARNINGS }
        val reasons = warnings.map { Reasons.display(it) }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(5).map { it.key to it.value }
        val signals = Risk.SIGNALS.map { s -> s to calls.count { s in it.signalsFired } }
        val hours = MutableList(24) { 0 }
        warnings.forEach { hours[hourOf(it.endedAt, zone)]++ }

        val liveWarn = calls.filter { it.liveVerdict in WARNINGS }
        val confirmed = liveWarn.count { it.finalVerdict in WARNINGS }
        val calmed = liveWarn.count { it.finalVerdict == "SAFE" }
        val raised = calls.count { it.liveVerdict == "SAFE" && it.finalVerdict in WARNINGS }

        val privacy = PrivacyLevel.entries.map { l -> l.id to calls.count { it.privacyLevel == l.id } }
        val lengths = calls.map { ((it.endedAt - it.startedAt) / 1000).coerceAtLeast(0).toInt() }
        val lengthVsRisk = calls.indices.mapNotNull { i -> calls[i].callScore?.let { lengths[i] to it.toDouble() } }

        return Stats(
            total = calls.size, safe = safe, unsure = unsure, scam = scam, notChecked = notChecked,
            safePercent = percent(safe, calls.size),
            buckets = buckets, weekly = weekly,
            reasons = reasons, signals = signals, riskyHours = hours,
            confirmed = confirmed, calmed = calmed, raised = raised,
            privacy = privacy,
            markedFine = labels.count { it.source == ImplicitLabels.SOURCE_FINE },
            hungUpAfterWarning = labels.count { it.source == ImplicitLabels.SOURCE_HUNG_UP },
            lengthVsRisk = lengthVsRisk,
            avgLengthSeconds = if (lengths.isEmpty()) 0 else lengths.sum() / lengths.size,
        )
    }

    /** The call detail page's one-line "What happened" (same wording on iOS). */
    fun whatHappened(finalVerdict: String, liveVerdict: String?, reason: String): String {
        val r = reason.trim().trimEnd('.').replaceFirstChar { it.lowercase() }
        return when {
            finalVerdict == "SCAM" -> if (r.isEmpty()) Tr.s(R.string.ds_what_scam) else Tr.s(R.string.ds_what_scam_r, r)
            finalVerdict == "UNSURE" -> if (r.isEmpty()) Tr.s(R.string.ds_what_off) else Tr.s(R.string.ds_what_off_r, r)
            finalVerdict == "SAFE" && liveVerdict in WARNINGS -> Tr.s(R.string.ds_what_calmed)
            finalVerdict == "SAFE" -> Tr.s(R.string.ds_what_safe)
            else -> Tr.s(R.string.ds_what_unchecked)
        }
    }

    /** "1 min 24 s" / "45 s". */
    fun duration(seconds: Int): String =
        if (seconds >= 60) Tr.s(R.string.pw_dur_min, (seconds / 60).toString(), (seconds % 60).toString()) else Tr.s(R.string.pw_dur_s, seconds.toString())
}
