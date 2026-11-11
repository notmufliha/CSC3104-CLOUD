package com.example.scamdetect.ondevice

import com.example.scamdetect.data.UtteranceResponse
import com.example.scamdetect.logic.Mask

/**
 * Workstream G.2 / G.3: one call scored on the phone. Each caller line goes through the three checks and the
 * aggregator's rules, and comes back in the same shape as the gateway's POST /v1/utterance answer, so the in-call
 * screen, chips, alert and dashboard work exactly as in the cloud.
 *
 *  - Level 3 (On-device): `answer`, or with MobileBERT installed (G.4) `answerWithSecondOpinion` asking it on the
 *    phone. Nothing touches the network.
 *  - Level 2 (Hybrid): `answerHybrid`. At unsure moments (the phone's own score >= 0.45) and on the spot check
 *    (every 5th caller line) the caller's last 5 lines, masked (logic/Mask.kt, Workstream H), are sent for a cloud
 *    BERT second opinion.
 * Either way the second opinion is blended in exactly as the aggregator does; if it doesn't come back (in time) the
 * phone's own answer stands. It is never asked once the call is already red (BERT can't change that), which for
 * Hybrid also means nothing more leaves the phone: a deliberate privacy choice.
 */
class OnDeviceCall(
    private val scorer: OnDeviceScorer,
    private val rules: LocalRules = LocalRules(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The caller's recent lines (raw, on the phone only), for the Hybrid window. */
    private val recentLines = ArrayDeque<String>()

    /** What happened with the second opinion (cloud BERT or MobileBERT) for one line. */
    data class SecondOpinion(
        /** Why it was asked: "unsure" (phone's score >= 0.45) or "spot_check" (every 5th caller line). */
        val why: String,
        /** What it read: the caller's last lines (Hybrid: masked, and exactly what left the phone). */
        val lines: List<String>,
        /** BERT's score, or null when it didn't answer (in time); then the phone's answer was kept. */
        val bertScore: Float?,
    )

    data class Answer(val response: UtteranceResponse, val decision: LocalRules.Decision, val second: SecondOpinion?)

    /** Level 3: everything on the phone. */
    fun answer(callId: String, seq: Long, text: String): UtteranceResponse {
        val start = System.nanoTime()
        val scores = scorer.score(text)
        remember(text)
        val d = rules.decide(lineOf(scores), clock())
        return response(callId, seq, scores, d, start)
    }

    /**
     * Level 2. `verify` sends the masked lines to the cloud and returns BERT's score, or null on timeout / error;
     * it is only called when the phone is unsure or on the spot check.
     */
    suspend fun answerHybrid(callId: String, seq: Long, text: String, verify: suspend (List<String>) -> Float?): Answer =
        answerWithSecondOpinion(callId, seq, text, Mask::mask, verify)

    /**
     * The shared rule for both: score on the phone, and only when the phone is unsure or on the spot check (and the
     * call isn't red yet) let `ask` read the caller's last 5 lines, each passed through `prepare` first.
     */
    suspend fun answerWithSecondOpinion(
        callId: String,
        seq: Long,
        text: String,
        prepare: (String) -> String,
        ask: suspend (List<String>) -> Float?,
    ): Answer {
        val start = System.nanoTime()
        val now = clock()
        val scores = scorer.score(text)
        remember(text)
        val line = lineOf(scores)
        val own = rules.peek(line, now)
        val why = when {
            own.rule == "red" -> null                                  // already red: nothing to gain, nothing sent
            own.fast >= ASK_AT -> "unsure"
            own.callerLines % SPOT_CHECK_EVERY == 0 -> "spot_check"
            else -> null
        }
        val second = why?.let {
            val lines = recentLines.map(prepare)
            SecondOpinion(it, lines, ask(lines))
        }
        val d = rules.decide(line, now, bert = second?.bertScore)
        return Answer(response(callId, seq, scores, d, start), d, second)
    }

    private fun remember(text: String) {
        recentLines.addLast(text)
        while (recentLines.size > WINDOW_LINES) recentLines.removeFirst()
    }

    private fun lineOf(scores: Map<String, Float>) = LocalRules.SIGNALS.map { scores[it] }

    private fun response(callId: String, seq: Long, scores: Map<String, Float>, d: LocalRules.Decision, startNanos: Long) =
        UtteranceResponse(
            callId = callId,
            seq = seq,
            scored = true,
            duplicate = false,
            verdict = d.verdict,
            combinedScore = d.combined,
            language = "en",
            scores = scores,
            missing = emptyList(),
            callScores = LocalRules.SIGNALS.zip(d.recent).mapNotNull { (s, v) -> v?.let { s to it } }.toMap(),
            callerLines = d.callerLines,
            latencyMs = (System.nanoTime() - startNanos) / 1_000_000,
        )

    companion object {
        /** G.3: ask the cloud when the phone's own score is at least this (the UNSURE threshold)... */
        const val ASK_AT = 0.45f
        /** ...or on every 5th caller line (spot check). */
        const val SPOT_CHECK_EVERY = 5
        /** The caller's last 5 lines go to the cloud. */
        const val WINDOW_LINES = 5
        /** How long the phone waits for the cloud before keeping its own answer. */
        const val VERIFY_TIMEOUT_MS = 1_500L

        /**
         * The on-device models are English only. Same 30% rule as the gateway's language detection: a line with
         * more than 30% Chinese characters is Chinese; a script marked "zh" is Chinese throughout.
         */
        fun isEnglish(scriptLang: String?, text: String): Boolean {
            if (scriptLang == "zh") return false
            val chinese = text.count { it in '一'..'鿿' }
            return chinese * 10 <= text.length * 3
        }
    }
}
