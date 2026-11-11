package com.example.scamdetect

import com.example.scamdetect.logic.FinalReview
import com.example.scamdetect.logic.ImplicitLabels
import com.example.scamdetect.logic.ImplicitLabels.Label
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream G.12 (labels inferred on the phone) and the G privacy-level setting. */
class ImplicitLabelsTest {
    private val alert = 100_000L
    private fun reviewed(score: Float) = FinalReview("SCAM", "", reviewed = true, topWindowSeqs = emptySet(), callScore = score)

    @Test fun hangingUpWithin10sOfTheRedAlertIsLikelyScam() {
        assertEquals(listOf(Label(1, ImplicitLabels.SOURCE_HUNG_UP)), ImplicitLabels.atEnd(true, alert, alert + 4_000, null))
        assertEquals(listOf(Label(1, ImplicitLabels.SOURCE_HUNG_UP)), ImplicitLabels.atEnd(true, alert, alert + 10_000, null))
    }

    @Test fun noHangUpLabelWhenLateNoAlertOrTheScriptEnded() {
        assertTrue(ImplicitLabels.atEnd(true, alert, alert + 10_001, null).isEmpty())   // too late
        assertTrue(ImplicitLabels.atEnd(true, null, alert, null).isEmpty())             // never went red
        assertTrue(ImplicitLabels.atEnd(false, alert, alert + 2_000, null).isEmpty())   // the script finished
    }

    @Test fun aVerySureReviewBecomesTheLabel() {
        assertEquals(listOf(Label(1, ImplicitLabels.SOURCE_REVIEW)), ImplicitLabels.atEnd(false, null, 0, reviewed(0.95f)))
        assertEquals(listOf(Label(0, ImplicitLabels.SOURCE_REVIEW)), ImplicitLabels.atEnd(false, null, 0, reviewed(0.05f)))
        assertTrue(ImplicitLabels.atEnd(false, null, 0, reviewed(0.9f)).isEmpty())       // not > 0.9
        assertTrue(ImplicitLabels.atEnd(false, null, 0, reviewed(0.5f)).isEmpty())
    }

    @Test fun theLiveVerdictFallbackIsNeverUsedAsALabel() {
        val fallback = FinalReview("SCAM", "", reviewed = false, topWindowSeqs = emptySet(), callScore = 0.99f)
        assertTrue(ImplicitLabels.atEnd(false, null, 0, fallback).isEmpty())
        assertTrue(ImplicitLabels.atEnd(false, null, 0, FinalReview.UNAVAILABLE).isEmpty())
    }

    @Test fun bothRulesCanFireForOneCall() {
        assertEquals(
            listOf(Label(1, ImplicitLabels.SOURCE_HUNG_UP), Label(1, ImplicitLabels.SOURCE_REVIEW)),
            ImplicitLabels.atEnd(true, alert, alert + 3_000, reviewed(0.97f)),
        )
    }

    @Test fun thisWasFineCountsOnlyAfterAWarning() {
        assertEquals(Label(0, ImplicitLabels.SOURCE_FINE), ImplicitLabels.thisWasFine(Risk.Level.RED))
        assertEquals(Label(0, ImplicitLabels.SOURCE_FINE), ImplicitLabels.thisWasFine(Risk.Level.YELLOW))
        assertNull(ImplicitLabels.thisWasFine(Risk.Level.GREEN))
        assertNull(ImplicitLabels.thisWasFine(Risk.Level.ERROR))
    }

    @Test fun finalReviewKeepsTheCallScore() {
        val r = FinalReview.from(
            com.example.scamdetect.data.EndCallResponse(
                "c", "SCAM", 0.93f, "SCAM", "bert", listOf(3L), "asked for your OTP", "user_hung_up", false, 400, null,
            )
        )
        assertEquals(0.93f, r.callScore!!, 1e-6f)
    }

    @Test fun privacyLevelDefaultsToCloudAndNeverReturnsAnUnbuiltLevel() {
        assertEquals(PrivacyLevel.CLOUD, PrivacyLevel.fromId(null))
        assertEquals(PrivacyLevel.CLOUD, PrivacyLevel.fromId("nonsense"))
        assertEquals(PrivacyLevel.CLOUD, PrivacyLevel.fromId("cloud"))
        PrivacyLevel.entries.forEach { level ->
            assertEquals(if (level.available) level else PrivacyLevel.CLOUD, PrivacyLevel.fromId(level.id))
        }
        assertEquals("Cloud", PrivacyLevel.badgeFor("cloud"))
        assertEquals("On-device", PrivacyLevel.badgeFor("on_device"))   // a saved row always gets its badge
    }
}
