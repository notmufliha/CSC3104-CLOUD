package com.example.scamdetect

import com.example.scamdetect.logic.DashboardStats
import com.example.scamdetect.logic.DashboardStats.Period
import com.example.scamdetect.logic.PlainWords
import com.example.scamdetect.logic.SampleHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/**
 * The dashboard's plain-word sentences. The SAME sentences are asserted by the iOS app
 * (ios-app/ScamGuardTests/PlainWordsTests.swift) on the same sample history.
 */
class PlainWordsTest {
    private val now = 1795435200000L
    private val h = SampleHistory.generate(now, 1795392000000L)
    private fun stats(p: Period) = DashboardStats.compute(h.calls, h.labels, p, now, ZoneOffset.UTC)
    private val empty = DashboardStats.compute(emptyList(), emptyList(), Period.WEEK, now, ZoneOffset.UTC)

    @Test fun heroSentenceForNoneOneAndMany() {
        assertEquals("No calls this week", PlainWords.hero(0, 0, Period.WEEK))
        assertEquals("No calls this month", PlainWords.hero(0, 0, Period.MONTH))
        assertEquals("No calls yet", PlainWords.hero(0, 0, Period.ALL))
        assertEquals("No scam calls this week 🎉", PlainWords.hero(0, 5, Period.WEEK))
        assertEquals("1 scam call caught this month", PlainWords.hero(1, 5, Period.MONTH))
        assertEquals("2 scam calls caught this week", PlainWords.hero(2, 9, Period.WEEK))
        assertEquals("12 scam calls caught so far", PlainWords.hero(12, 61, Period.ALL))
    }

    @Test fun sampleWeek() {
        val s = stats(Period.WEEK)
        assertEquals("5 scam calls caught this week", PlainWords.hero(s.scam, s.total, Period.WEEK))
        assertEquals("Most warnings asked for your OTP or money", PlainWords.topWarning(s))
        assertEquals("36% of calls were scams", PlainWords.overTime(s))
        assertEquals("Pressure to act fast came up most often", PlainWords.signals(s))
        assertEquals("Risk is going down", PlainWords.trend(s))
        assertEquals("Most risky calls happen in the morning", PlainWords.hours(s))
        assertEquals("2 warnings turned out safe after a second look", PlainWords.doubleCheck(s))
        assertEquals("Most calls were checked by the ScamGuard server", PlainWords.where(s))
        assertEquals("You marked 2 warnings as fine", PlainWords.answers(s))
        assertEquals("An average call lasted 1 min 37 s", PlainWords.length(s))
    }

    @Test fun sampleMonthAndAll() {
        val m = stats(Period.MONTH)
        assertEquals("12 scam calls caught this month", PlainWords.hero(m.scam, m.total, Period.MONTH))
        assertEquals("20% of calls were scams", PlainWords.overTime(m))
        assertEquals("Talk of money or codes came up most often", PlainWords.signals(m))
        assertEquals("Risk is staying about the same", PlainWords.trend(m))
        assertEquals("12 warnings turned out safe after a second look", PlainWords.doubleCheck(m))
        val a = stats(Period.ALL)
        assertEquals("12 scam calls caught so far", PlainWords.hero(a.scam, a.total, Period.ALL))
        assertEquals("Risk is going down", PlainWords.trend(a))
    }

    @Test fun emptyPeriodSentences() {
        assertNull(PlainWords.topWarning(empty))
        assertEquals("No calls were flagged", PlainWords.reasons(empty))
        assertEquals("No scam calls in this period", PlainWords.overTime(empty))
        assertEquals("No warning signs heard", PlainWords.signals(empty))
        assertEquals("Not enough calls to see a trend", PlainWords.trend(empty))
        assertEquals("No risky calls in this period", PlainWords.hours(empty))
        assertEquals("No warnings to double-check", PlainWords.doubleCheck(empty))
        assertEquals("No calls in this period", PlainWords.where(empty))
        assertEquals("No answers from you yet", PlainWords.answers(empty))
        assertEquals("No calls in this period", PlainWords.length(empty))
    }

    @Test fun plainWordsForResultsAndPlaces() {
        assertEquals(listOf("Safe", "Be careful", "Scam", "Not checked"), listOf("SAFE", "UNSURE", "SCAM", "NOT CHECKED").map(PlainWords::verdict))
        assertEquals("Checked on this phone", PlainWords.checkedWhere("on_device"))
        assertEquals("Checked on this phone with server help", PlainWords.checkedWhere("hybrid"))
        assertEquals("Checked by the ScamGuard server", PlainWords.checkedWhere("cloud"))
        assertEquals(listOf("morning", "afternoon", "evening", "night", "night"), listOf(8, 13, 19, 23, 3).map(PlainWords::partOfDay))
        assertEquals("Most warnings came from fake police, banks or officials", PlainWords.reasonSentence("impersonation"))
        assertEquals("Most warnings had several small warning signs", PlainWords.reasonSentence(null))
    }

    @Test fun timeAgoAndHistoryGroups() {
        val min = 60_000L
        assertEquals("Just now", PlainWords.timeAgo(now - 20_000, now, 0))
        assertEquals("12 min ago", PlainWords.timeAgo(now - 12 * min, now, 0))
        assertEquals("3 h ago", PlainWords.timeAgo(now - 190 * min, now, 0))
        assertEquals("Yesterday", PlainWords.timeAgo(now - 20 * 60 * min, now, 1))
        assertEquals("4 days ago", PlainWords.timeAgo(now - 4 * 1440 * min, now, 4))
        assertNull(PlainWords.timeAgo(now - 9 * 1440 * min, now, 9))
        assertEquals(listOf("Today", "This week", "This week", "Earlier"), listOf(0, 1, 6, 7).map(PlainWords::historyGroup))
    }
}
