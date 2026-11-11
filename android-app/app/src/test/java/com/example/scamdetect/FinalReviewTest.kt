package com.example.scamdetect

import com.example.scamdetect.data.EndCallRequest
import com.example.scamdetect.data.EndCallResponse
import com.example.scamdetect.data.EndLine
import com.example.scamdetect.data.UtteranceResponse
import com.example.scamdetect.logic.FinalReview
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Summary
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Workstream B.5: the end-of-call answer -> what the summary shows. JSON names as in docs/workstream-b-contract.md. */
class FinalReviewTest {
    private val gson = Gson()

    private fun end(json: String): EndCallResponse = gson.fromJson(json, EndCallResponse::class.java)

    @Test fun fallbackAnswerIsNotReviewed() {
        val r = FinalReview.from(end("""{"call_id":"c1","final_verdict":"SCAM","call_score":0.8,"live_verdict":"SCAM",
            "review":"fallback","top_window_seqs":[],"reason":"Asked for your OTP or money","ended_by":"user_hung_up",
            "already_ended":false,"latency_ms":12}"""))
        assertEquals("SCAM", r.finalVerdict)
        assertFalse(r.reviewed)                      // -> "Final review unavailable"
        assertEquals("Asked for your OTP or money", r.reason)
        assertTrue(r.topWindowSeqs.isEmpty())
    }

    @Test fun reviewedAnswerCarriesTheRiskyLines() {
        val r = FinalReview.from(end("""{"final_verdict":"SCAM","review":"bert","top_window_seqs":[9,11,13]}"""))
        assertTrue(r.reviewed)
        assertEquals(setOf(9L, 11L, 13L), r.topWindowSeqs)
    }

    @Test fun unknownOrMissingMeansNoFinalVerdict() {
        assertNull(FinalReview.from(end("""{"final_verdict":"UNKNOWN","review":"fallback"}""")).finalVerdict)
        assertFalse(FinalReview.from(end("""{"final_verdict":"UNKNOWN","review":"bert"}""")).reviewed)
        assertEquals(FinalReview.UNAVAILABLE, FinalReview.from(null))   // request failed / 3 s passed
    }

    @Test fun summaryLevelUsesTheFinalVerdictButNeverLooksCalmerThanTheLiveOneOnFallback() {
        val fallbackSafe = FinalReview("SAFE", "", reviewed = false, topWindowSeqs = emptySet())
        assertEquals(Risk.Level.YELLOW, Summary.level(fallbackSafe, "UNSURE", null))
        assertEquals(Risk.Level.RED, Summary.level(FinalReview.UNAVAILABLE, "SCAM", null))
        assertEquals(Risk.Level.GREEN, Summary.level(FinalReview.UNAVAILABLE, "SAFE", null))
        // a line that could not be checked beats SAFE, as on the bar
        assertEquals(Risk.Level.ERROR, Summary.level(fallbackSafe, "SAFE", "Could not reach the server"))
        // no summary answer yet and no verdict at all
        assertEquals(Risk.Level.LISTENING, Summary.level(null, null, null))
        // the end-of-call review is the final word when it answered
        val reviewedScam = FinalReview("SCAM", "", reviewed = true, topWindowSeqs = setOf(3L))
        assertEquals(Risk.Level.RED, Summary.level(reviewedScam, "UNSURE", null))
    }

    @Test fun utteranceReadsRecentScoresAndTheOldName() {
        val now = gson.fromJson("""{"verdict":"UNSURE","recent_scores":{"money":0.9}}""", UtteranceResponse::class.java)
        assertEquals(0.9f, now.callScores!!["money"])
        val old = gson.fromJson("""{"verdict":"UNSURE","call_scores":{"money":0.7}}""", UtteranceResponse::class.java)
        assertEquals(0.7f, old.callScores!!["money"])
    }

    @Test fun utteranceIgnoredAfterTheEnd() {
        val late = gson.fromJson("""{"verdict":"SCAM","scored":false,"ignored":true,"reason":"call_ended"}""",
            UtteranceResponse::class.java)
        assertEquals(true, late.ignored)
        assertEquals("call_ended", late.reason)
        val normal = gson.fromJson("""{"verdict":"SAFE","ignored":false}""", UtteranceResponse::class.java)
        assertEquals(false, normal.ignored)
    }

    @Test fun endRequestUsesTheContractNames() {
        val json = JsonParser.parseString(gson.toJson(
            EndCallRequest("c1", "script_finished", 16, listOf(EndLine(1, "hello"), EndLine(3, "OTP please")), "en")
        )).asJsonObject
        assertEquals("c1", json["call_id"].asString)
        assertEquals("end", json["event"].asString)
        assertEquals("script_finished", json["ended_by"].asString)
        assertEquals(16, json["last_seq"].asInt)
        assertEquals(3, json["caller_lines"].asJsonArray[1].asJsonObject["seq"].asInt)
        assertEquals("en", json["lang"].asString)
        val noLang = JsonParser.parseString(gson.toJson(EndCallRequest("c1", "user_discarded", 0, emptyList())))
        assertFalse(noLang.asJsonObject.has("lang"))   // Gson leaves out null: the gateway detects the language
    }
}
