package com.example.scamdetect.logic

import com.example.scamdetect.data.HistoryEntry
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ScorePoint

/** Workstream B.7: the dashboard's numbers, kept apart from Room and Compose so they can be unit-tested. */
object Dashboard {
    private val WARNINGS = setOf("SCAM", "UNSURE")

    /** The aggregator's default thresholds (AGG_UNSURE_THRESHOLD / AGG_SCAM_THRESHOLD), drawn on the chart. */
    const val UNSURE_LINE = 0.45f
    const val SCAM_LINE = 0.6f

    /** A saved verdict -> the colour level used everywhere else ("NOT CHECKED" -> grey). */
    fun level(verdict: String): Risk.Level =
        if (verdict in setOf("SAFE", "UNSURE", "SCAM")) Risk.level(verdict, null) else Risk.Level.ERROR

    /** The chart's points: (seconds into the call, combined score 0-1), in time order; lines with no score left out. */
    fun chart(points: List<ScorePoint>): List<Pair<Float, Float>> =
        points.mapNotNull { p -> p.combined?.let { p.secondsIntoCall to it.coerceIn(0f, 1f) } }.sortedBy { it.first }

    /** "Most common warning reasons": the top `n` reasons of calls that ended with a warning, with counts. */
    fun topReasons(calls: List<CallRecord>, n: Int = 3): List<Pair<String, Int>> =
        calls.asSequence()
            .filter { it.finalVerdict in WARNINGS && it.topReason.isNotBlank() }
            .groupingBy { it.topReason }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(n)
            .map { it.key to it.value }

    /** A line's strongest signal score (null = no check answered). */
    fun lineMax(p: ScorePoint): Float? = listOfNotNull(p.urgency, p.money, p.impersonation).maxOrNull()

    /**
     * The seq of the riskiest caller line: the highest single-signal score, the later line on a tie. Null when no
     * line reached Risk.SIGNAL_ON, so a calm call never has a "riskiest sentence" to save.
     */
    fun riskiestSeq(points: List<ScorePoint>): Long? =
        points.mapNotNull { p -> lineMax(p)?.let { p.seq to it } }
            .filter { it.second >= Risk.SIGNAL_ON }
            .maxWithOrNull(compareBy<Pair<Long, Float>> { it.second }.thenBy { it.first })
            ?.first

    /**
     * The old SharedPreferences History rows as CallRecords. Ids are fixed per row, so running the move twice
     * (e.g. the app stopped half-way) inserts nothing new.
     */
    fun fromLegacy(entries: List<HistoryEntry>): List<CallRecord> = entries.mapIndexed { i, e ->
        CallRecord(
            id = "migrated-${e.timeMillis}-$i",
            startedAt = e.timeMillis,
            endedAt = e.timeMillis,
            finalVerdict = e.verdict,
            liveVerdict = null,
            topReason = e.topReason,
            callScore = e.combinedScore,
            signalsFired = emptyList(),
            review = "migrated",
            riskiestSentence = null,
        )
    }
}
