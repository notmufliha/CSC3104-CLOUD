package com.example.scamdetect

import com.example.scamdetect.data.HistoryEntry
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.Reasons
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.ondevice.LocalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Workstream G: one reason wording on the dashboard, whichever privacy level decided the call. */
class ReasonsTest {
    private fun call(id: String, reason: String, code: String?, verdict: String = "SCAM") = CallRecord(
        id = id, startedAt = 0, endedAt = 1, finalVerdict = verdict, liveVerdict = verdict, topReason = reason,
        callScore = 0.8f, signalsFired = emptyList(), review = "fallback", riskiestSentence = null, reasonCode = code,
    )

    @Test fun aWarningIsCodedByItsStrongestSignalLikeTheAggregator() {
        val scores = mapOf("urgency" to 0.55f, "money" to 0.92f, "impersonation" to 0.7f)
        assertEquals(Reasons.MONEY, Reasons.codeFor(Risk.Level.RED, scores, null))
        assertEquals(Reasons.IMPERSONATION, Reasons.codeFor(Risk.Level.YELLOW, mapOf("impersonation" to 0.6f, "urgency" to 0.5f), null))
        assertEquals(Reasons.SEVERAL, Reasons.codeFor(Risk.Level.YELLOW, mapOf("urgency" to 0.4f), null))   // nothing >= 0.5
    }

    @Test fun theServersEndOfCallReasonWins() {
        val scores = mapOf("urgency" to 0.9f)
        assertEquals(Reasons.MONEY, Reasons.codeFor(Risk.Level.RED, scores, "Asked for your OTP or money"))
        assertEquals(Reasons.EARLIER, Reasons.codeFor(Risk.Level.RED, scores, "Warning signs earlier in the call"))
        assertEquals(Reasons.URGENCY, Reasons.codeFor(Risk.Level.RED, scores, "a reason the app doesn't know"))   // fall back
    }

    @Test fun calmAndErrorLevels() {
        assertEquals(Reasons.NONE, Reasons.codeFor(Risk.Level.GREEN, emptyMap(), null))
        assertEquals(Reasons.SERVER_NOT_REACHED, Reasons.codeFor(Risk.Level.ERROR, emptyMap(), null))
        assertEquals(Reasons.NO_LINES, Reasons.codeFor(Risk.Level.LISTENING, emptyMap(), null))
    }

    @Test fun shownInTheServersWording() {
        LocalRules.REASON_TEXT.forEach { (code, text) -> assertEquals(text, Reasons.text(code)) }
        assertEquals("Asked for your OTP or money", Reasons.display(call("a", "asking for your OTP", Reasons.MONEY)))
        assertEquals("whatever was saved", Reasons.display(call("b", "whatever was saved", null)))
        assertNull(Reasons.text("not-a-code"))
    }

    @Test fun oldWordingsMapToTheSameCode() {
        listOf("asking for your OTP", "asking you to transfer money", "Asked for your OTP or money", " ASKED FOR YOUR OTP OR MONEY ")
            .forEach { assertEquals(it, Reasons.MONEY, Reasons.codeForText(it)) }
        assertEquals(Reasons.IMPERSONATION, Reasons.codeForText("pretending to be an official (police, bank, government)"))
        assertNull(Reasons.codeForText("no idea"))
    }

    @Test fun topReasonsCountOneWordingTogether() {
        val calls = listOf(
            call("1", "asking for your OTP", Reasons.MONEY),                          // on the phone
            call("2", "Asked for your OTP or money", Reasons.MONEY),                  // cloud
            call("3", "pretending to be an official (police, bank, government)", Reasons.IMPERSONATION),
            call("4", "no warning signs", Reasons.NONE, verdict = "SAFE"),            // not a warning
        )
        assertEquals(
            listOf("Asked for your OTP or money" to 2, "Claims to be police, a bank or the government" to 1),
            Dashboard.topReasons(calls),
        )
    }

    @Test fun theOldHistoryGetsCodesToo() {
        val rows = Dashboard.fromLegacy(listOf(HistoryEntry(1_700_000_000_000, "SCAM", "asking for your OTP", 0.8f)))
        assertEquals(Reasons.MONEY, rows.single().reasonCode)
    }
}
