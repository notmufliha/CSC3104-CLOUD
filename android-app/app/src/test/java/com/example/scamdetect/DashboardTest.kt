package com.example.scamdetect

import com.example.scamdetect.data.HistoryEntry
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.Converters
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Workstream B.7: the dashboard's numbers (no Room, no Android). */
class DashboardTest {
    private fun call(id: String, verdict: String, reason: String) = CallRecord(
        id = id, startedAt = 0, endedAt = 0, finalVerdict = verdict, liveVerdict = verdict, topReason = reason,
        callScore = null, signalsFired = emptyList(), review = "fallback", riskiestSentence = null,
    )

    private fun point(seq: Long, u: Float?, m: Float?, i: Float?, combined: Float? = 0.5f, t: Float = seq.toFloat()) =
        ScorePoint("c", seq, t, combined, u, m, i)

    @Test fun topReasonsCountOnlyWarnings() {
        val calls = listOf(
            call("1", "SCAM", "Asked for your OTP or money"),
            call("2", "SCAM", "Asked for your OTP or money"),
            call("3", "UNSURE", "Pressuring you to act now"),
            call("4", "SAFE", "no warning signs"),
            call("5", "SAFE", "no warning signs"),
            call("6", "NOT CHECKED", "server not reached"),
            call("7", "UNSURE", "Claims to be police, a bank or the government"),
            call("8", "SCAM", "Claims to be police, a bank or the government"),
            call("9", "UNSURE", "Asked for your OTP or money"),
        )
        assertEquals(
            listOf(
                "Asked for your OTP or money" to 3,
                "Claims to be police, a bank or the government" to 2,
                "Pressuring you to act now" to 1,
            ),
            Dashboard.topReasons(calls),
        )
        assertEquals(emptyList<Pair<String, Int>>(), Dashboard.topReasons(listOf(call("1", "SAFE", "no warning signs"))))
    }

    @Test fun riskiestLineIsTheHighestSingleSignal() {
        val pts = listOf(point(1, 0.2f, 0.1f, 0.3f), point(3, 0.4f, 0.95f, 0.1f), point(5, 0.9f, null, 0.2f))
        assertEquals(3L, Dashboard.riskiestSeq(pts))
        assertEquals(5L, Dashboard.riskiestSeq(listOf(point(1, 0.7f, null, null), point(5, 0.7f, null, null)))) // tie: later
        assertNull(Dashboard.riskiestSeq(listOf(point(1, 0.3f, 0.2f, 0.1f))))   // nothing reached SIGNAL_ON
        assertNull(Dashboard.riskiestSeq(listOf(point(1, null, null, null))))
        assertEquals(0.5f, Risk.SIGNAL_ON)
    }

    @Test fun legacyHistoryBecomesCallRecordsWithFixedIds() {
        val old = listOf(
            HistoryEntry(1_700_000_000_000, "SCAM", "asking for your OTP", 0.8f),
            HistoryEntry(1_699_000_000_000, "NOT CHECKED", "server not reached", null),
        )
        val rows = Dashboard.fromLegacy(old)
        assertEquals(listOf("migrated-1700000000000-0", "migrated-1699000000000-1"), rows.map { it.id })
        assertEquals(rows, Dashboard.fromLegacy(old))                       // same ids again: a rerun adds nothing
        assertEquals("SCAM", rows[0].finalVerdict)
        assertEquals(0.8f, rows[0].callScore)
        assertEquals("migrated", rows[0].review)
        assertNull(rows[0].riskiestSentence)
        assertNull(rows[1].liveVerdict)
    }

    @Test fun chartSkipsLinesWithoutAScoreAndKeepsTimeOrder() {
        val pts = listOf(point(3, 0.1f, 0.1f, 0.1f, combined = 0.7f, t = 9f), point(1, 0.1f, 0.1f, 0.1f, combined = 0.2f, t = 2f),
            point(5, null, null, null, combined = null, t = 12f), point(7, 0.1f, 0.1f, 0.1f, combined = 1.4f, t = 15f))
        assertEquals(listOf(2f to 0.2f, 9f to 0.7f, 15f to 1f), Dashboard.chart(pts))
    }

    @Test fun savedVerdictColours() {
        assertEquals(Risk.Level.RED, Dashboard.level("SCAM"))
        assertEquals(Risk.Level.YELLOW, Dashboard.level("UNSURE"))
        assertEquals(Risk.Level.GREEN, Dashboard.level("SAFE"))
        assertEquals(Risk.Level.ERROR, Dashboard.level("NOT CHECKED"))
    }

    @Test fun signalListConverter() {
        val c = Converters()
        assertEquals("urgency,money", c.fromList(listOf("urgency", "money")))
        assertEquals(listOf("urgency", "money"), c.toList("urgency,money"))
        assertEquals(emptyList<String>(), c.toList(""))
    }
}
