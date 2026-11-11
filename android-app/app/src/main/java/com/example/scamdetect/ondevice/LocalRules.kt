package com.example.scamdetect.ondevice

/**
 * Workstream G.2: the aggregator's decision rules on the phone, ported from backend/aggregator/src/policy.rs
 * (`decide_with_bert_status`, Workstream A's decision table, AGG_MODE=cascade), so levels 2 and 3 give the same
 * SAFE / UNSURE / SCAM, chips and reasons as the cloud. Numbers = the aggregator's defaults, which are also what
 * docker-compose.yml and demo/make_manifests.py deploy.
 *
 * Rules, in order (policy.rs):
 *  1. add the line to the window (last 6 caller lines; each signal's recent score = its highest there);
 *  2. a red call stays red while BERT is unavailable or confirms it; two BERT disagreements in a row clear it;
 *  3. fewer than 2 checks answered (and no BERT) -> UNSURE "not enough data";
 *  4. fast = weighted average of the recent scores; combined = fast, or blended 50/50 with BERT when it answered;
 *  5. warm-up (first 15 s, nothing >= 0.9, BERT included) -> SAFE;
 *  6/7. an alarmed vote (money rule, or fast >= 0.6, or BERT >= 0.6) turns red only if BERT confirms it (>= 0.6),
 *     or, when BERT is unavailable ("off" / "timeout" / "error"), two signals are >= 0.8 ("strong_fallback");
 *  8. combined >= 0.45, or an alarmed vote BERT didn't confirm -> UNSURE;
 *  9. otherwise SAFE.
 *
 * BERT status, as the aggregator reports it: "ok" (answered), "off" (no BERT at all: level 3 without MobileBERT,
 * like AGG_MODE=fast), "no_context" (fewer than 3 caller lines), "skipped" (not needed), "timeout" / "error".
 * Only "off" / "timeout" / "error" let the strong-signal fallback turn a call red without BERT.
 */
class LocalRules(private val cfg: Config = Config()) {
    data class Config(
        val weights: List<Float> = listOf(1f / 3, 1f / 3, 1f / 3),   // urgency, money, impersonation
        val scamThreshold: Float = 0.6f,
        val unsureThreshold: Float = 0.45f,
        val bertScamThreshold: Float = 0.6f,
        val fallbackStrongSignal: Float = 0.8f,
        val redDisagreementsToClear: Int = 2,
        val windowLines: Int = 6,
        val warmupMs: Long = 15_000,
        val warmupOverride: Float = 0.9f,
        val moneyRule: Float = 0.7f,
        val moneyRuleOther: Float = 0.5f,
        val moneyRuleLines: Int = 6,
        val reasonThreshold: Float = 0.5f,
        val minQuorum: Int = 2,
        /** final = (1 - w) * fast + w * BERT, when BERT answered (AGG_BERT_WEIGHT). */
        val bertWeight: Float = 0.5f,
        /** Cascade: also ask BERT on every Nth caller line (AGG_BERT_SPOT_EVERY); 0 = off. */
        val bertSpotEvery: Int = 5,
        /** BERT needs this many caller lines to say anything useful (bert.rs MIN_LINES). */
        val bertMinLines: Int = 3,
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
        /** Which rule decided (policy.rs names): red_*, quorum, warmup, bert_confirmed, strong_fallback, unsure, safe. */
        val rule: String,
    )

    // ------------------------------------------------------------ the call's memory (one LocalRules per call)
    private data class Memory(
        val startedAtMs: Long = 0,
        val window: List<List<Float?>> = emptyList(),
        val streak: Int = 0,
        val red: Boolean = false,
        val redDisagreements: Int = 0,
        val callerLines: Int = 0,
    )

    private var memory = Memory()

    /** Caller lines decided so far. */
    val callerLines: Int get() = memory.callerLines

    /** The call is red right now. */
    val red: Boolean get() = memory.red

    /**
     * Applies one caller line (scores in SIGNALS order; null = that check didn't answer). `callTimeMs` = how far
     * into the call the phone says this line is; 0 = unknown, then the clock runs from the first caller line, which
     * is what the cloud does for the app (it sends no call time). `bert` / `bertStatus`: see the class comment.
     */
    fun decide(
        line: List<Float?>,
        nowMs: Long,
        callTimeMs: Long = 0,
        bert: Float? = null,
        bertStatus: String = if (bert != null) "ok" else "off",
    ): Decision {
        val (next, d) = step(memory, line, nowMs, callTimeMs, bert, bertStatus)
        memory = next
        return d
    }

    /**
     * policy.rs `wants_second_opinion`: would the aggregator ask BERT about this line? Yes when the call is red,
     * on the spot check (every 5th caller line), or when the answer without BERT is decided by a rule BERT can
     * change (not enough data, alarmed, unsure, strong fallback). Changes nothing.
     */
    fun wantsSecondOpinion(line: List<Float?>, nowMs: Long, callTimeMs: Long = 0): Boolean =
        memory.red || isSpotCheck() || peek(line, nowMs, callTimeMs).rule in SECOND_OPINION_RULES

    /** This line (the next one to be counted) is due a spot check. */
    fun isSpotCheck(): Boolean = cfg.bertSpotEvery > 0 && !memory.red && (memory.callerLines + 1) % cfg.bertSpotEvery == 0

    /** What `decide` would answer for this line without BERT ("off"), changing nothing. */
    fun peek(line: List<Float?>, nowMs: Long, callTimeMs: Long = 0): Decision =
        step(memory, line, nowMs, callTimeMs, null, "off").second

    /** policy.rs `decide_with_bert_status` (cascade), as a pure function of the memory before this line. */
    private fun step(
        before: Memory, line: List<Float?>, nowMs: Long, callTimeMs: Long, bert: Float?, bertStatus: String,
    ): Pair<Memory, Decision> {
        require(line.size == SIGNALS.size)
        val startedAt = if (before.startedAtMs == 0L) nowMs - maxOf(callTimeMs, 0) else before.startedAtMs
        val window = (before.window + listOf(line)).takeLast(cfg.windowLines)
        val callerLines = before.callerLines + 1
        val recent = highs(window)
        val fast = combined(recent)
        val answered = line.count { it != null }
        val quorumMet = answered >= cfg.minQuorum
        val warmingUp = (if (callTimeMs > 0) callTimeMs else nowMs - startedAt) < cfg.warmupMs
        val combined = when {
            bert == null -> fast
            quorumMet -> (1 - cfg.bertWeight) * fast + cfg.bertWeight * bert
            else -> bert
        }
        val overrideHit = recent.any { it != null && it >= cfg.warmupOverride } || (bert != null && bert >= cfg.warmupOverride)
        val bertUnavailable = bertStatus in setOf("off", "timeout", "error")
        val bertConfirms = bert != null && bert >= cfg.bertScamThreshold
        val redCandidate = (!warmingUp || overrideHit) && (quorumMet || bert != null) &&
            (quorumMet && moneyRule(window) || fast >= cfg.scamThreshold || bertConfirms)
        val strongFallback = recent.count { it != null && it >= cfg.fallbackStrongSignal } >= 2

        var streak = before.streak
        var red = before.red
        var disagreements = before.redDisagreements
        val (verdict, rule) = when {
            before.red && bert == null -> "SCAM" to "red_bert_unavailable"
            before.red -> if (bertConfirms) {
                disagreements = 0
                "SCAM" to "red_bert_confirmed"
            } else {
                disagreements += 1
                if (disagreements < cfg.redDisagreementsToClear) "SCAM" to "red_bert_disagrees"
                else { red = false; disagreements = 0; "UNSURE" to "red_cleared" }
            }
            !quorumMet && bert == null -> "UNSURE" to "quorum"
            warmingUp && !overrideHit -> { streak = 0; "SAFE" to "warmup" }
            redCandidate && bertConfirms -> "SCAM" to "bert_confirmed"
            redCandidate && bert == null && bertUnavailable && strongFallback -> "SCAM" to "strong_fallback"
            combined >= cfg.unsureThreshold || redCandidate -> { streak = 0; "UNSURE" to "unsure" }
            else -> { streak = 0; "SAFE" to "safe" }
        }
        if (verdict == "SCAM") red = true else { red = false; disagreements = 0 }
        val after = Memory(startedAt, window, streak, red, disagreements, callerLines)

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
        /** policy.rs SECOND_OPINION_RULES: rules whose answer BERT can change. */
        val SECOND_OPINION_RULES = setOf("quorum", "money_rule", "high_once", "streak", "unsure", "strong_fallback")
        /** policy.rs reason_text: the server's wording, which the app also shows (logic/Reasons.kt). */
        val REASON_TEXT = mapOf(
            "money" to "Asked for your OTP or money",
            "impersonation" to "Claims to be police, a bank or the government",
            "urgency" to "Pressuring you to act now",
            "bert" to "The conversation as a whole sounds like a scam",
        )
    }
}
