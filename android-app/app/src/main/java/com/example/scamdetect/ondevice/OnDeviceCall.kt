package com.example.scamdetect.ondevice

import com.example.scamdetect.data.UtteranceResponse
import com.example.scamdetect.logic.Mask

/**
 * Workstream G.2 / G.3: one call scored on the phone. Each caller line goes through the three checks and the
 * aggregator's rules, and comes back in the same shape as the gateway's POST /v1/utterance answer, so the in-call
 * screen, chips, alert and dashboard work exactly as in the cloud.
 *
 *  - Level 3 (On-device): `answer` (no BERT at all: the rules see BERT as "off", like AGG_MODE=fast), or with
 *    MobileBERT installed (G.4) `answerWithSecondOpinion` asking it on the phone. Nothing touches the network.
 *  - Level 2 (Hybrid): `answerHybrid`. The caller's last 5 lines, masked (logic/Mask.kt, Workstream H), are sent
 *    for a cloud BERT second opinion.
 * When the second opinion is asked, as the aggregator does it (policy.rs wants_second_opinion + bert.rs MIN_LINES):
 *  - only once the call has at least 3 caller lines (fewer: BERT status "no_context", as in the cloud);
 *  - when the phone's own score is >= 0.45 (the brief's rule, also during the warm-up), or the answer without BERT
 *    is decided by a rule BERT can change, or on the spot check (every 5th caller line);
 *  - on a red call: MobileBERT yes (it can confirm or, after 2 disagreements, clear the red, like the cloud);
 *    Hybrid never, so nothing more leaves the phone once a call is red (a deliberate privacy choice; the red then
 *    stays, as the aggregator keeps a red call red while BERT is unavailable).
 * Its score is blended in exactly as the aggregator does; if it doesn't come back (in time) the phone's own answer
 * stands (status "timeout"/"error", which lets two very strong signals still turn the call red).
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
        /** Why it was asked: "unsure" (the phone is unsure or alarmed), "spot_check" (every 5th line) or "red". */
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
        answerWithSecondOpinion(callId, seq, text, Mask::mask, askWhenRed = false, ask = verify)

    /**
     * The shared rule (see the class comment): score on the phone, and only when the second opinion is wanted let
     * `ask` read the caller's last 5 lines, each passed through `prepare` first.
     */
    suspend fun answerWithSecondOpinion(
        callId: String,
        seq: Long,
        text: String,
        prepare: (String) -> String,
        askWhenRed: Boolean = true,
        ask: suspend (List<String>) -> Float?,
    ): Answer {
        val start = System.nanoTime()
        val now = clock()
        val scores = scorer.score(text)
        remember(text)
        val line = lineOf(scores)
        val own = rules.peek(line, now)
        val wanted = when {
            rules.red -> if (askWhenRed) "red" else null
            own.fast >= ASK_AT || own.rule in LocalRules.SECOND_OPINION_RULES -> "unsure"
            rules.isSpotCheck() -> "spot_check"
            else -> null
        }
        val enoughLines = rules.callerLines + 1 >= MIN_LINES
        val second = wanted?.takeIf { enoughLines }?.let {
            val lines = recentLines.map(prepare)
            SecondOpinion(it, lines, ask(lines))
        }
        val status = when {
            second == null && wanted != null && !enoughLines -> "no_context"
            second == null -> "skipped"
            second.bertScore != null -> "ok"
            else -> "error"
        }
        val d = rules.decide(line, now, bert = second?.bertScore, bertStatus = status)
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
        /** G.3: ask when the phone's own score is at least this (the UNSURE threshold), among other reasons. */
        const val ASK_AT = 0.45f
        /** BERT is asked only once the call has this many caller lines (aggregator bert.rs MIN_LINES). */
        const val MIN_LINES = 3
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
