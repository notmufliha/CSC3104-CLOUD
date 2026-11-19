package com.example.scamdetect

import com.example.scamdetect.logic.AlertTone
import com.example.scamdetect.logic.Reasons
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Warnings
import com.example.scamdetect.logic.Warnings.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/** Phase 1: the red warning's and yellow banner's wording, the spoken warning, the alert tone. Same cases on iOS. */
class WarningsTest {
    @Before fun english() = TestStrings.install()

    @Test fun strongestReasonFirst() {
        assertEquals(Kind.OTP, Warnings.kind(listOf("urgency", "money"), listOf("Read me the OTP you just received")))
        assertEquals(Kind.MONEY, Warnings.kind(listOf("money", "impersonation"), listOf("Transfer the money now")))
        assertEquals(Kind.OFFICIAL, Warnings.kind(listOf("impersonation", "urgency"), emptyList()))
        assertEquals(Kind.RUSH, Warnings.kind(listOf("urgency"), emptyList()))
        assertEquals(Kind.OTP, Warnings.kind(listOf("money"), listOf("请告诉我验证码")))
    }

    @Test fun reasonCodeDecidesWhenNoSignalIsLit() {
        assertEquals(Kind.MONEY, Warnings.kind(emptyList(), emptyList(), Reasons.MONEY))
        assertEquals(Kind.OFFICIAL, Warnings.kind(emptyList(), emptyList(), Reasons.IMPERSONATION))
        assertEquals(Kind.RUSH, Warnings.kind(emptyList(), emptyList(), Reasons.URGENCY))
        assertEquals(Kind.SEVERAL, Warnings.kind(emptyList(), emptyList(), Reasons.SEVERAL))
        assertEquals(Kind.EARLIER, Warnings.kind(emptyList(), emptyList(), Reasons.EARLIER))
        assertEquals(Kind.GENERAL, Warnings.kind(emptyList(), emptyList(), Reasons.BERT))
        assertEquals(Kind.GENERAL, Warnings.kind(emptyList(), emptyList(), null))
    }

    @Test fun englishWording() {
        assertEquals("This may be a scam. Do not give your OTP. Hang up now.", Warnings.spoken(Kind.OTP))
        assertEquals("This may be a scam. Do not send any money. Hang up now.", Warnings.spoken(Kind.MONEY))
        assertEquals("This may be a scam. Do not give any personal details. Hang up now.", Warnings.spoken(Kind.OFFICIAL))
        assertEquals("They asked for your OTP. Banks and police never do this.", TestStrings.get(Warnings.message(Kind.OTP)))
        assertEquals("They talked about money or codes. Don't share them.", TestStrings.get(Warnings.careful(Kind.MONEY)))
        assertEquals("ScamGuard: I may be on a scam call. Please call me.", Warnings.familyMessage())
    }

    /** The spoken warning for every reason code: filled in, with its "do" line. */
    @Test fun spokenTextPerReasonCode() {
        for (code in Warnings.REASON_CODES) {
            val kind = Warnings.kind(emptyList(), emptyList(), code)
            val text = Warnings.spoken(kind)
            assertTrue("$code empty", text.isNotBlank())
            assertFalse("$code placeholder left: $text", text.contains("%") || text.contains("{"))
            assertTrue("$code has no 'do' line", text.contains(TestStrings.get(Warnings.doLine(kind))))
        }
    }

    /** reason code -> the app's wording, for every code; each one different. */
    @Test fun reasonLabelForEveryCode() {
        val seen = mutableSetOf<String>()
        for (code in Warnings.REASON_CODES) {
            val label = Reasons.label(code)
            assertNotNull(code, label)
            assertTrue("$code blank", label!!.isNotBlank())
            assertTrue("$code not unique", seen.add(label))
        }
    }

    /** The server's wording maps back to the code, so a stored reason is shown in the app's wording. */
    @Test fun storedServerWordingIsShownInTheAppsWording() {
        assertEquals(TestStrings.get(R.string.reason_money), Reasons.localized("Asked for your OTP or money"))
        assertEquals(TestStrings.get(R.string.reason_money), Reasons.localized("asking for your OTP"))
        // a wording the app doesn't know: shown as the server sent it
        assertEquals("Asked to install a remote-access app", Reasons.localized("Asked to install a remote-access app"))
    }

    /** Message, action and yellow line exist for every kind, with no scores or technical words. */
    @Test fun warningWordingIsPlain() {
        val banned = listOf("BERT", "verdict", "gateway", "model", "server", "score")
        for (k in Kind.entries) {
            listOf(Warnings.message(k), Warnings.doLine(k), Warnings.careful(k)).forEach { id ->
                val text = TestStrings.get(id)
                assertTrue("$k", text.isNotBlank())
                assertFalse("$k has numbers or %: $text", text.contains("%") || text.contains("{"))
            }
            val text = TestStrings.get(Warnings.message(k)) + " " + TestStrings.get(Warnings.doLine(k))
            banned.forEach { w -> assertFalse("$k: $w in $text", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) }
        }
    }

    @Test fun familyMessage() {
        assertTrue(Warnings.familyMessage().startsWith("ScamGuard"))
    }

    @Test fun beCarefulOffShowsYellowAsListening() {
        assertEquals(Risk.Level.LISTENING, Risk.shown(Risk.Level.YELLOW, showCareful = false))
        assertEquals(Risk.Level.YELLOW, Risk.shown(Risk.Level.YELLOW, showCareful = true))
        for (l in Risk.Level.entries.filter { it != Risk.Level.YELLOW }) assertEquals(l, Risk.shown(l, false))
    }

    @Test fun alertToneIsThreeSoftEdgedBeeps() {
        val pcm = AlertTone.pcm()
        val beep = AlertTone.SAMPLE_RATE * AlertTone.BEEP_MS / 1000
        val gap = AlertTone.SAMPLE_RATE * AlertTone.GAP_MS / 1000
        assertEquals(3 * beep + 2 * gap, pcm.size)
        assertTrue(abs(pcm.first().toInt()) < 50 && abs(pcm.last().toInt()) < 50)       // no click at the ends
        assertTrue(pcm.maxOf { abs(it.toInt()) } <= (Short.MAX_VALUE * AlertTone.AMPLITUDE).toInt() + 1)
        assertTrue((beep until beep + gap).all { pcm[it] == 0.toShort() })               // silence between beeps
        assertEquals(1060, AlertTone.durationMs)
    }
}
