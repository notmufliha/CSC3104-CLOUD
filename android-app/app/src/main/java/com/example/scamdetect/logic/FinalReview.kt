package com.example.scamdetect.logic

import com.example.scamdetect.data.EndCallResponse

/**
 * Workstream B.5: what the summary shows about the end of a call, built from POST /v1/call/end's answer (or
 * from nothing, when the request failed or took longer than the 3 s wait).
 *
 * finalVerdict = SAFE / UNSURE / SCAM, or null when the server didn't know the call (UNKNOWN) or wasn't reached.
 * reviewed = the end-of-call review (Workstream C) answered. When false the summary says "Final review
 * unavailable" and the verdict is the live one.
 */
data class FinalReview(
    val finalVerdict: String?,
    val reason: String,
    val reviewed: Boolean,
    /** Caller lines where "the conversation turned risky" (bubbles to highlight). */
    val topWindowSeqs: Set<Long>,
    /** False when the server never answered (request failed, or the 3 s wait ran out). */
    val answered: Boolean = true,
    /** G.12: the review's whole-call score (0-1); only meaningful when `reviewed`. */
    val callScore: Float? = null,
    /** G.2: privacy level 3, the call was checked on the phone only (no end message was sent). */
    val onDevice: Boolean = false,
) {
    companion object {
        private val KNOWN = setOf("SAFE", "UNSURE", "SCAM")

        /** Request failed, timed out, or no server: the live verdict is all there is. */
        val UNAVAILABLE =
            FinalReview(finalVerdict = null, reason = "", reviewed = false, topWindowSeqs = emptySet(), answered = false)

        /** Level 3: no server review by design; the live (on-phone) verdict is the final one. */
        val ON_DEVICE = FinalReview(
            finalVerdict = null, reason = "", reviewed = false, topWindowSeqs = emptySet(), answered = false, onDevice = true,
        )

        fun from(resp: EndCallResponse?): FinalReview {
            if (resp == null) return UNAVAILABLE
            val verdict = resp.finalVerdict?.takeIf { it in KNOWN }
            return FinalReview(
                finalVerdict = verdict,
                reason = resp.reason.orEmpty(),
                reviewed = resp.review == "bert" && verdict != null,
                topWindowSeqs = resp.topWindowSeqs.orEmpty().toSet(),
                callScore = resp.callScore,
            )
        }
    }

    /** How the dashboard records it: "bert" | "fallback" | "unavailable" | "on_device". */
    val reviewType: String get() = when {
        onDevice -> "on_device"
        reviewed -> "bert"
        answered -> "fallback"
        else -> "unavailable"
    }
}

object Summary {
    /**
     * The level the summary leads with: the final verdict when there is one, else the live one. The same rule as
     * the bar: a warning always wins, and a line that could not be checked beats SAFE (`gatewayError`).
     */
    fun level(final: FinalReview?, liveVerdict: String?, gatewayError: String?): Risk.Level =
        Risk.level(final?.finalVerdict ?: liveVerdict, gatewayError).let { finalLevel ->
            // Never show a calmer verdict than the live one just because the review fell back to an older state.
            val live = Risk.level(liveVerdict, gatewayError)
            if (final?.reviewed == true) finalLevel else maxOf(finalLevel, live, compareBy(::severity))
        }

    /** Higher = more alarming. ERROR sits between GREEN and YELLOW: "not checked" is never shown as safe. */
    fun severity(level: Risk.Level): Int = when (level) {
        Risk.Level.LISTENING -> 0
        Risk.Level.GREEN -> 1
        Risk.Level.ERROR -> 2
        Risk.Level.YELLOW -> 3
        Risk.Level.RED -> 4
    }
}
