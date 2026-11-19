package com.example.scamdetect

import com.example.scamdetect.logic.AfterCall
import com.example.scamdetect.logic.Motion
import com.example.scamdetect.logic.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** The after-call card (logic/AfterCall.kt). Same cases as ios-app AfterCallTests.swift. */
class AfterCallTest {
    @Before fun strings() = TestStrings.install()

    private val sg = TimeZone.getTimeZone("Asia/Singapore")
    private val at = 1_795_070_700_000L   // 19 Nov 2026, 14:45 in Singapore

    @Test fun shownOnlyAfterScamOrBeCareful() {
        assertTrue(AfterCall.shows(Risk.Level.RED, showCareful = true))
        assertTrue(AfterCall.shows(Risk.Level.RED, showCareful = false))
        assertTrue(AfterCall.shows(Risk.Level.YELLOW, showCareful = true))
        assertFalse("Be careful warnings turned off", AfterCall.shows(Risk.Level.YELLOW, showCareful = false))
        for (l in listOf(Risk.Level.GREEN, Risk.Level.ERROR, Risk.Level.LISTENING)) assertFalse("$l", AfterCall.shows(l, true))
    }

    private val lines = listOf(
        "This is Officer Tan from the police, call me back at 9123 4567.",
        "Pay \$5,000 to account 123-456-789 or visit www.safe-account.com now. My email is tan@mail.com",
        "Read me the OTP 482913.",
    )

    @Test fun summaryIsMaskedWithNoNumberOrUrl() {
        val text = AfterCall.reportSummary(at, "Scam", "They asked for your one-time code.", lines, includeLines = true, zone = sg)
        assertTrue(text, text.startsWith("ScamGuard call summary\nWhen: 19 Nov 2026, 2:45 PM\nResult: Scam\nWhy: They asked for your one-time code."))
        assertTrue(text.contains("What the caller said (numbers and names removed):"))
        for (secret in listOf("9123", "4567", "5,000", "123-456", "482913", "www.", "safe-account", "@", "Tan ")) assertFalse(secret, text.contains(secret))
        assertFalse("no URL", Regex("""https?://|www\.|\.com""").containsMatchIn(text))
        // apart from the date and time, no digits at all
        assertFalse(Regex("""\d""").containsMatchIn(text.lines().drop(2).joinToString("\n")))
        assertTrue(text.contains("[NUMBER]") && text.contains("[PERSON_NAME]"))
        assertTrue(text.endsWith("From the ScamGuard app."))
    }

    @Test fun callerLinesOnlyWhenTheUserKeepsThem() {
        val text = AfterCall.reportSummary(at, "Be careful", "They rushed you.", lines, includeLines = false, zone = sg)
        assertFalse(text.contains("What the caller said"))
        assertFalse(text.contains("OTP"))
        assertEquals(5, text.lines().size)
    }

    @Test fun blockAndSharedSteps() {
        assertEquals(4, AfterCall.blockSteps.size)
        assertEquals("Tap the call, then tap Block.", TestStrings.get(AfterCall.blockSteps[2]))
        assertEquals(listOf("Call your bank on the number on your card. Ask them to block your card and stop payments.",
            "Change your passwords, starting with your bank and email.", "Call 1799 ScamShield Helpline for help."),
            AfterCall.sharedSteps.map { TestStrings.get(it) })
    }

    @Test fun reduceMotionIsRespected() {
        assertEquals(1f, Motion.pressScale(reduce = true))
        assertTrue(Motion.pressScale(reduce = false) < 1f)
        assertEquals(1f, Motion.warningStartScale(reduce = true))
        assertEquals(0, Motion.warningEnterMs(reduce = true))
        assertFalse(Motion.warningFlash(reduce = true))
        assertTrue(Motion.warningFlash(reduce = false))
        assertEquals(0, Motion.riskBarMs(reduce = true))
        assertEquals(Motion.Haptic.ERROR, Motion.hapticFor(Risk.Level.RED))
        assertEquals(Motion.Haptic.WARNING, Motion.hapticFor(Risk.Level.YELLOW))
        assertEquals(null, Motion.hapticFor(Risk.Level.GREEN))
    }
}
