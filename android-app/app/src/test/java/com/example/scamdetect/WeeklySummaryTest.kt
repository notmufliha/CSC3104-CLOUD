package com.example.scamdetect

import com.example.scamdetect.data.WeeklyReminderScheduler
import com.example.scamdetect.logic.WeeklyReminder
import com.example.scamdetect.logic.WeeklySummary
import com.example.scamdetect.logic.WeeklySummary.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Share weekly summary" (logic/WeeklySummary.kt). Same cases as ios-app WeeklySummaryTests.swift. */
class WeeklySummaryTest {
    @Before fun strings() = TestStrings.install()

    private val sg = ZoneId.of("Asia/Singapore")
    private fun at(d: Int, h: Int = 12, month: Int = 11) = ZonedDateTime.of(2026, month, d, h, 0, 0, 0, sg).toInstant().toEpochMilli()
    private val now = at(18, 20)   // Wed 18 Nov 2026, 8 pm

    @Test fun countsReasonsAndDateRange() {
        val calls = listOf(
            Call(at(18), "SCAM", "money"), Call(at(17), "SCAM", "impersonation"), Call(at(16), "SCAM", "money"),
            Call(at(15), "UNSURE", "urgency"), Call(at(14), "SAFE", "none"), Call(at(13), "SAFE", "none"),
            Call(at(12, 0), "SAFE", "none"),            // 12 Nov 00:00: the first day of the week, still in
            Call(at(12), "NOT CHECKED", "server_not_reached"),   // not checked: not counted
            Call(at(11), "SCAM", "money"),              // 8 days ago: out
        )
        assertEquals(
            "ScamGuard weekly summary (12–18 Nov): 7 calls checked. 3 scam calls warned (asked for money or a code; " +
                "pretended to be an official). 1 'be careful'. Stay safe! Call 1799 if unsure.",
            WeeklySummary.text(calls, now, sg))
    }

    @Test fun singularsAndNoScams() {
        assertEquals("ScamGuard weekly summary (12–18 Nov): 1 call checked. No scam calls. Stay safe! Call 1799 if unsure.",
            WeeklySummary.text(listOf(Call(at(16), "SAFE", "none")), now, sg))
        assertEquals("ScamGuard weekly summary (12–18 Nov): 2 calls checked. 1 scam call warned (rushed you). 1 'be careful'. Stay safe! Call 1799 if unsure.",
            WeeklySummary.text(listOf(Call(at(16), "SCAM", "urgency"), Call(at(15), "UNSURE", "money")), now, sg))
        assertTrue(WeeklySummary.text(listOf(Call(at(16), "SCAM", "bert")), now, sg).contains("(sounded like a scam)"))
    }

    @Test fun emptyWeek() {
        assertEquals("ScamGuard weekly summary (12–18 Nov): no calls checked this week. Stay safe! Call 1799 if unsure.",
            WeeklySummary.text(emptyList(), now, sg))
        assertEquals(WeeklySummary.text(emptyList(), now, sg), WeeklySummary.text(listOf(Call(at(1), "SCAM", "money")), now, sg))
    }

    @Test fun dateRangeAcrossMonthsAndYears() {
        assertEquals("28 Oct – 3 Nov", WeeklySummary.label(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3)))
        assertEquals("29 Dec – 4 Jan", WeeklySummary.label(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)))
        assertEquals("28 Oct – 3 Nov", WeeklySummary.week(at(3, 9), sg).label)
    }

    @Test fun noNumbersSentencesOrUrls() {
        // even if a saved reason text held a number, a URL or a caller sentence, only the plain phrases are used
        val text = WeeklySummary.text(listOf(Call(at(16), "SCAM", "Call 91234567 at www.scam.com, my OTP is 123456")), now, sg)
        val withoutDates = text.replace("12–18 Nov", "").replace("1799", "")
        assertFalse(text, Regex("""https?://|www\.|\.com|@""").containsMatchIn(text))
        assertFalse(text, Regex("""\d{3,}""").containsMatchIn(withoutDates))
        assertFalse(text.contains("OTP is"))
        assertTrue(text.contains("sounded like a scam"))
    }

    @Test fun theReminderAsksForPermissionOnlyWhenTurnedOn() {
        assertEquals(WeeklyReminder.Action.ASK_PERMISSION, WeeklyReminder.onToggle(on = true, permitted = false))
        assertEquals(WeeklyReminder.Action.SCHEDULE, WeeklyReminder.onToggle(on = true, permitted = true))
        assertEquals(WeeklyReminder.Action.CANCEL, WeeklyReminder.onToggle(on = false, permitted = false))
        assertEquals(WeeklyReminder.Action.CANCEL, WeeklyReminder.onToggle(on = false, permitted = true))
        // the app starting never asks
        for (on in listOf(true, false)) for (p in listOf(true, false)) assertTrue(WeeklyReminder.atLaunch(on, p) != WeeklyReminder.Action.ASK_PERMISSION)
        assertEquals(WeeklyReminder.Action.SCHEDULE, WeeklyReminder.atLaunch(savedOn = true, permitted = true))
        assertEquals(WeeklyReminder.Action.NOTHING, WeeklyReminder.atLaunch(savedOn = false, permitted = true))
        // the answer: on and scheduled, or stays off
        assertEquals(true to WeeklyReminder.Action.SCHEDULE, WeeklyReminder.afterPermission(granted = true))
        assertEquals(false to WeeklyReminder.Action.NOTHING, WeeklyReminder.afterPermission(granted = false))
    }

    @Test fun theReminderIsOnSundayMorning() {
        val next = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(WeeklyReminderScheduler.nextTrigger(now, sg)), sg)
        assertEquals(java.time.DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(22, next.dayOfMonth)
        assertEquals(10, next.hour)
        // Sunday after 10:00 -> next Sunday
        assertEquals(29, ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(WeeklyReminderScheduler.nextTrigger(at(22, 11), sg)), sg).dayOfMonth)
    }
}
