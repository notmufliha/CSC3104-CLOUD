package com.example.scamdetect

import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.logic.DashboardStats
import com.example.scamdetect.logic.DashboardStats.Period
import com.example.scamdetect.logic.SampleHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The redesigned dashboard's numbers. The SAME expected values are asserted by the iOS app
 * (ios-app/ScamGuardTests/DashboardStatsTests.swift) on the same sample history, so both apps show the same numbers.
 */
class DashboardStatsTest {
    private val now = 1795435200000L          // 2026-11-23 12:00 UTC
    private val todayStart = 1795392000000L   // 2026-11-23 00:00 UTC
    private val utc = ZoneOffset.UTC
    private val h = SampleHistory.generate(now, todayStart)
    private fun stats(p: Period) = DashboardStats.compute(h.calls, h.labels, p, now, utc)

    private val otp = "Asked for your OTP or money"
    private val claims = "Claims to be police, a bank or the government"
    private val pressure = "Pressuring you to act now"
    private val whole = "The conversation as a whole sounds like a scam"

    @Test fun sampleHistoryIsDeterministic() {
        assertEquals(61, h.calls.size)
        assertEquals(407, h.points.size)
        assertEquals(11, h.labels.size)
        assertTrue(h.calls.all { it.id.startsWith(SampleHistory.ID_PREFIX) })
        assertEquals(h, SampleHistory.generate(now, todayStart))
        assertTrue(h.calls.all { it.endedAt <= now })
    }

    @Test fun lastSevenDays() {
        val s = stats(Period.WEEK)
        assertEquals(listOf(14, 7, 2, 5, 0), listOf(s.total, s.safe, s.unsure, s.scam, s.notChecked))
        assertEquals(50, s.safePercent)
        assertEquals(7, s.buckets.size)
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), s.buckets.map { it.daysAgo })
        assertEquals(listOf(3, 2, 1, 2, 3, 2, 1), s.buckets.map { it.total })
        assertEquals(0.4647, s.buckets[0].avgRisk!!, 1e-4)
        assertEquals(listOf(otp to 4, claims to 2, whole to 1), s.reasons)
        assertEquals(listOf("urgency" to 4, "money" to 4, "impersonation" to 3), s.signals)
        assertEquals(listOf(8, 10, 11, 12, 17, 18, 20), s.riskyHours.indices.filter { s.riskyHours[it] > 0 })
        assertEquals(7, s.riskyHours.sum())
        assertEquals(listOf(7, 2, 0), listOf(s.confirmed, s.calmed, s.raised))
        assertEquals(listOf("cloud" to 9, "hybrid" to 3, "on_device" to 2), s.privacy)
        assertEquals(2, s.markedFine)
        assertEquals(2, s.hungUpAfterWarning)
        assertEquals(97, s.avgLengthSeconds)
        assertEquals(14, s.lengthVsRisk.size)
    }

    @Test fun lastThirtyDays() {
        val s = stats(Period.MONTH)
        assertEquals(listOf(61, 36, 11, 12, 2), listOf(s.total, s.safe, s.unsure, s.scam, s.notChecked))
        assertEquals(59, s.safePercent)
        assertEquals(30, s.buckets.size)
        assertEquals(61, s.buckets.sumOf { it.total })
        assertEquals(listOf(otp to 12, claims to 7, pressure to 2, whole to 2), s.reasons)
        assertEquals(listOf("urgency" to 9, "money" to 13, "impersonation" to 10), s.signals)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 3, 1, 3, 3, 3, 2, 1, 0, 1, 1, 2, 0, 3, 0, 0, 0), s.riskyHours)
        assertEquals(listOf(23, 12, 0), listOf(s.confirmed, s.calmed, s.raised))
        assertEquals(listOf("cloud" to 38, "hybrid" to 13, "on_device" to 10), s.privacy)
        assertEquals(8, s.markedFine)
        assertEquals(3, s.hungUpAfterWarning)
        assertEquals(84, s.avgLengthSeconds)
        assertNull(s.buckets.firstOrNull { it.total == 0 }?.avgRisk)
    }

    @Test fun allTimeIsGroupedByWeek() {
        val s = stats(Period.ALL)
        assertTrue(s.weekly)
        assertEquals(61, s.total)
        assertEquals(listOf(34, 27, 20, 13, 6), s.buckets.map { it.daysAgo })
        assertEquals(listOf(5, 13, 14, 15, 14), s.buckets.map { it.total })
        assertEquals(0.4803, s.buckets.last().avgRisk!!, 1e-4)
    }

    @Test fun emptyPeriods() {
        val week = DashboardStats.compute(emptyList(), emptyList(), Period.WEEK, now, utc)
        assertTrue(week.isEmpty)
        assertEquals(0, week.safePercent)
        assertEquals(7, week.buckets.size)
        assertTrue(week.buckets.all { it.total == 0 && it.avgRisk == null })
        assertTrue(week.reasons.isEmpty())
        assertEquals(listOf(0, 0, 0), week.privacy.map { it.second })
        assertEquals(1, DashboardStats.compute(emptyList(), emptyList(), Period.ALL, now, utc).buckets.size)
        // a history that is older than the period is empty too
        val old = h.calls.map { it.copy(endedAt = it.endedAt - 60 * DashboardStats.DAY_MS) }
        assertTrue(DashboardStats.compute(old, emptyList(), Period.MONTH, now, utc).isEmpty)
    }

    @Test fun percentRoundsHalfUp() {
        assertEquals(33, DashboardStats.percent(1, 3))
        assertEquals(67, DashboardStats.percent(2, 3))
        assertEquals(50, DashboardStats.percent(1, 2))
        assertEquals(13, DashboardStats.percent(1, 8))
        assertEquals(0, DashboardStats.percent(0, 0))
    }

    @Test fun daysAndHoursUseTheLocalTimeZone() {
        val sg = ZoneId.of("Asia/Singapore")
        val lateUtc = todayStart - 30 * 60_000L       // 22 Nov 23:30 UTC = 23 Nov 07:30 in Singapore
        assertEquals(1, DashboardStats.daysAgo(lateUtc, now, utc))
        assertEquals(0, DashboardStats.daysAgo(lateUtc, now, sg))
        assertEquals(23, DashboardStats.hourOf(lateUtc, utc))
        assertEquals(7, DashboardStats.hourOf(lateUtc, sg))
    }

    @Test fun notCheckedCallsCountButAreNotWarnings() {
        val c = CallRecord("x", now - 60_000, now, "NOT CHECKED", "NOT CHECKED", "server not reached", null,
            emptyList(), "unavailable", null, "cloud", "server_not_reached")
        val s = DashboardStats.compute(listOf(c), emptyList(), Period.WEEK, now, utc)
        assertEquals(1, s.notChecked)
        assertEquals(0, s.riskyHours.sum())
        assertTrue(s.reasons.isEmpty())
        assertTrue(s.lengthVsRisk.isEmpty())
    }
}
