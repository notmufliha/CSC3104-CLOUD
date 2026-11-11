package com.example.scamdetect.logic

/**
 * Workstream G.12: labels the phone can infer from what the user did, kept only in the local Room database (for a
 * later on-phone training step, G.6–G.8, which is not built here). Nothing is uploaded.
 *
 * label 1 = likely scam, 0 = false alarm.
 *
 * Not recorded: "Block number". It only exists for real phone calls, and the app only plays simulated calls.
 */
object ImplicitLabels {
    /** Hung up this soon after the red alert first appeared -> likely scam. */
    const val HANG_UP_WINDOW_MS = 10_000L
    /** End-of-call review at least this sure either way -> use its answer as the label. */
    const val CONFIDENT_SCAM = 0.9f
    const val CONFIDENT_SAFE = 0.1f

    const val SOURCE_HUNG_UP = "hung_up_after_alert"
    const val SOURCE_FINE = "this_was_fine"
    const val SOURCE_REVIEW = "final_review"

    data class Label(val label: Int, val source: String)

    /**
     * Labels known when the call ends.
     * @param hungUp the user ended the call (Hang up / Hang up now), not the script running out
     * @param alertAt when the red alert first showed (ms), null if it never did
     * @param review the end-of-call answer; only a real review (`reviewed`) counts, never the live-verdict fallback
     */
    fun atEnd(hungUp: Boolean, alertAt: Long?, endedAt: Long, review: FinalReview?): List<Label> = buildList {
        if (hungUp && alertAt != null && endedAt - alertAt in 0..HANG_UP_WINDOW_MS) add(Label(1, SOURCE_HUNG_UP))
        val score = review?.callScore?.takeIf { review.reviewed }
        when {
            score == null -> Unit
            score > CONFIDENT_SCAM -> add(Label(1, SOURCE_REVIEW))
            score < CONFIDENT_SAFE -> add(Label(0, SOURCE_REVIEW))
        }
    }

    /** "This was fine" on the summary: a false alarm, but only if the call ended with a warning. */
    fun thisWasFine(summaryLevel: Risk.Level): Label? =
        if (summaryLevel == Risk.Level.RED || summaryLevel == Risk.Level.YELLOW) Label(0, SOURCE_FINE) else null
}
