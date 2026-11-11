package com.example.scamdetect.ondevice

/**
 * Workstream G.2: the aggregator's decision rules (backend/aggregator/src/policy.rs `decide`), on the phone, so
 * levels 2 and 3 give the same SAFE / UNSURE / SCAM, chips and reasons as the cloud. A BERT second opinion (G.3
 * Hybrid's /v1/verify, later G.4's MobileBERT) is passed in and blended exactly as the aggregator does. The numbers
 * are the aggregator's defaults, which are also what docker-compose.yml and demo/k8s/04-aggregator.yaml set
 * (Workstream A thresholds).
 *
 * Rules, in order: 1. add the line to the window (last 6 caller lines; each signal's recent score is its highest
 * there); 2. already red -> SCAM; 3. fewer than 2 checks answered -> UNSURE "not enough data"; 4. combined =
 * weighted average of the recent scores; 5. warm-up (first 15 s, nothing >= 0.9) -> SAFE; 6. money rule: money
 * >= 0.7 while another signal >= 0.5 -> SCAM; 7. combined >= 0.6 twice in a row -> SCAM (once -> UNSURE);
 * 8. combined >= 0.45 -> UNSURE; 9. else SAFE.
 */
class LocalRules(private val cfg: Config = Config()) {
    data class Config(
        val weights: List<Float> = listOf(1f / 3, 1f / 3, 1f / 3),   // urgency, money, impersonation
        val scamThreshold: Float = 0.6f,
        val unsureThreshold: Float = 0.45f,
        val windowLines: Int = 6,
        val warmupMs: Long = 15_000,
        val warmupOverride: Float = 0.9f,
        val scamStreak: Int = 2,
        val moneyRule: Float = 0.7f,
        val moneyRuleOther: Float = 0.5f,
        val moneyRuleLines: Int = 6,
        val reasonThreshold: Float = 0.5f,
        val minQuorum: Int = 2,
        /** final = (1 - w) * fast + w * BERT, when BERT answered (AGG_BERT_WEIGHT). */
        val bertWeight: Float = 0.5f,
        /** BERT below this cancels the money rule; 0 = off, the deployed default (AGG_BERT_VETO). */
        val bertVeto: Float = 0f,
    )

    data class Reason(val signal: String, val score: Float, val text: String)

    data class Decision(
        val verdict: String,
        /** The score the verdict was decided on: `fast`, or its blend with BERT when BERT answered. */
        val combined: Float,
        /** The checks' weighted vote alone, before BERT: the phone's own score. */
        val fast: Float,
        val bert: Float?,
        /** Each signal's highest score in the window (null = none). */
        val recent: List<Float?>,
        val reasons: List<Reason>,
        val warmingUp: Boolean,
        val quorumMet: Boolean,
        val callerLines: Int,
        /** Which rule decided: red, quorum, warmup, money_rule, streak, high_once, unsure, safe. */
        val rule: String,
    )

    // ------------------------------------------------------------ the call's memory (one LocalRules per call)
    private data class Memory(
        val startedAtMs: Long = 0,
        val window: List<List<Float?>> = emptyList(),
        val streak: Int = 0,
        val red: Boolean = false,
        val callerLines: Int = 0,
    )

    private var memory = Memory()

    /**
     * Applies one caller line (scores in SIGNALS order; null = that check didn't answer). `callTimeMs` = how far
     * into the call the phone says this line is; 0 = unknown, then the clock runs from the first caller line, which
     * is what the cloud does for the app (it sends no call time). `bert` = BERT's score for the caller's recent
     * lines when it was asked and answered (G.3 Hybrid / G.4), null otherwise.
     */
    fun decide(line: List<Float?>, nowMs: Long, callTimeMs: Long = 0, bert: Float? = null): Decision {
        val (next, d) = step(memory, line, nowMs, callTimeMs, bert)
        memory = next
        return d
    }

    /**
     * What `decide` would answer for this line without BERT, changing nothing (the aggregator's
     * wants_second_opinion). G.3 uses it to see whether the phone is unsure before anything is sent.
     */
    fun peek(line: List<Float?>, nowMs: Long, callTimeMs: Long = 0): Decision = step(memory, line, nowMs, callTimeMs, null).second

    /** policy.rs `decide`, as a pure function of the memory before this line. */
    private fun step(before: Memory, line: List<Float?>, nowMs: Long, callTimeMs: Long, bert: Float?): Pair<Memory, Decision> {
        require(line.size == SIGNALS.size)
        val startedAt = if (before.startedAtMs == 0L) nowMs - maxOf(callTimeMs, 0) else before.startedAtMs
        val window = (before.window + listOf(line)).takeLast(cfg.windowLines)
        val callerLines = before.callerLines + 1
        val recent = highs(window)
        val fast = combined(recent)
        val answered = line.count { it != null }
        val quorumMet = answered >= cfg.minQuorum
        val warmingUp = (if (callTimeMs > 0) callTimeMs else nowMs - startedAt) < cfg.warmupMs
        // 4. blend: with a quorum, (1 - w) * fast + w * BERT; without one, BERT stands in alone
        val combined = when {
            bert == null -> fast
            quorumMet -> (1 - cfg.bertWeight) * fast + cfg.bertWeight * bert
            else -> bert
        }
        val overrideHit = recent.any { it != null && it >= cfg.warmupOverride } || (bert != null && bert >= cfg.warmupOverride)
        val vetoed = bert != null && bert < cfg.bertVeto

        var streak = before.streak
        val (verdict, rule) = when {
            before.red -> "SCAM" to "red"
            !quorumMet && bert == null -> "UNSURE" to "quorum"
            warmingUp && !overrideHit -> { streak = 0; "SAFE" to "warmup" }
            quorumMet && moneyRule(window) && !vetoed -> "SCAM" to "money_rule"
            combined >= cfg.scamThreshold -> {
                streak += 1
                if (streak >= cfg.scamStreak) "SCAM" to "streak" else "UNSURE" to "high_once"
            }
            combined >= cfg.unsureThreshold -> { streak = 0; "UNSURE" to "unsure" }
            else -> { streak = 0; "SAFE" to "safe" }
        }
        val after = Memory(startedAt, window, streak, before.red || verdict == "SCAM", callerLines)

        val reasons = (if (verdict == "SAFE") emptyList() else allReasons(recent, bert)).toMutableList()
        if (rule == "quorum") {
            reasons += Reason("quorum", answered.toFloat(), "Not enough data: only $answered of ${SIGNALS.size} checks answered")
        }
        return after to Decision(verdict, combined, fast, bert, recent, reasons, warmingUp, quorumMet, callerLines, rule)
    }

    private fun highs(lines: Collection<List<Float?>>): List<Float?> = SIGNALS.indices.map { i ->
        lines.mapNotNull { it[i] }.maxOrNull()
    }

    /** Weighted average over the signals that have a score; a missing one shares its weight out. */
    private fun combined(recent: List<Float?>): Float {
        var sum = 0f
        var weight = 0f
        recent.forEachIndexed { i, s -> if (s != null) { sum += cfg.weights[i] * s; weight += cfg.weights[i] } }
        return if (weight > 0f) sum / weight else 0f
    }

    private fun moneyRule(window: List<List<Float?>>): Boolean {
        val recent = highs(window.takeLast(cfg.moneyRuleLines))
        val m = SIGNALS.indexOf("money")
        val moneyHigh = recent[m]?.let { it >= cfg.moneyRule } == true
        val otherHigh = recent.withIndex().any { (i, s) -> i != m && s != null && s >= cfg.moneyRuleOther }
        return moneyHigh && otherHigh
    }

    /** Signals (and BERT) at or above the reason threshold, strongest first. */
    private fun allReasons(recent: List<Float?>, bert: Float?): List<Reason> {
        val out = SIGNALS.indices
            .mapNotNull { i -> recent[i]?.takeIf { it >= cfg.reasonThreshold }?.let { Reason(SIGNALS[i], it, REASON_TEXT.getValue(SIGNALS[i])) } }
        val withBert = if (bert != null && bert >= cfg.reasonThreshold) out + Reason("bert", bert, REASON_TEXT.getValue("bert")) else out
        return withBert.sortedByDescending { it.score }
    }

    companion object {
        val SIGNALS = listOf("urgency", "money", "impersonation")
        private val REASON_TEXT = mapOf(
            "money" to "Asked for your OTP or money",
            "impersonation" to "Claims to be police, a bank or the government",
            "urgency" to "Pressuring you to act now",
            "bert" to "The conversation as a whole sounds like a scam",
        )
    }
}
