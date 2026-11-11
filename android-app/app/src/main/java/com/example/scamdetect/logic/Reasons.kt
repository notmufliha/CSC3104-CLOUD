package com.example.scamdetect.logic

import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.ondevice.LocalRules

/**
 * Workstream G: one wording for a call's top reason. Each saved call keeps a reason CODE (CallRecord.reasonCode);
 * the dashboard shows the server's wording for it (backend/aggregator/src/policy.rs reason_text, also used by
 * LocalRules), whichever privacy level decided the call. Before this, cloud calls stored the server's text and
 * on-phone calls the app's own ("Asked for your OTP or money" vs "asking for your OTP"), so the same reason was
 * counted twice in "Most common warning reasons".
 */
object Reasons {
    const val MONEY = "money"
    const val IMPERSONATION = "impersonation"
    const val URGENCY = "urgency"
    const val BERT = "bert"
    /** A warning with no single signal above the reason threshold. */
    const val SEVERAL = "several"
    /** The end-of-call answer pointed at warnings earlier in the call (end.rs REASON_EARLIER). */
    const val EARLIER = "earlier"
    const val NONE = "none"
    const val SERVER_NOT_REACHED = "server_not_reached"
    const val NO_LINES = "no_lines"

    private val TEXT: Map<String, String> = LocalRules.REASON_TEXT + mapOf(
        SEVERAL to "Several small warning signs together",
        EARLIER to "Warning signs earlier in the call",
        NONE to "No warning signs",
        SERVER_NOT_REACHED to "Server not reached",
        NO_LINES to "Ended before any line was checked",
    )

    /** The server's wording for a code; null for an unknown code. */
    fun text(code: String?): String? = code?.let { TEXT[it] }

    /** What the dashboard shows for a saved call: the code's wording, else the text saved with it. */
    fun display(call: CallRecord): String = text(call.reasonCode) ?: call.topReason

    /**
     * Every wording a reason has had (the server's, the app's own from logic/Risk.kt, the old History's), lowercase,
     * so old texts can be turned into codes: by the 2 -> 3 migration and for a reason the server sends back.
     */
    private val KNOWN_TEXTS: Map<String, String> = buildMap {
        TEXT.forEach { (code, text) -> put(text.lowercase(), code) }
        put("asking for your otp", MONEY)
        put("asking you to transfer money", MONEY)
        put("pretending to be an official (police, bank, government)", IMPERSONATION)
        put("pressuring you to act right now", URGENCY)
        put("several small warning signs together", SEVERAL)
        put("no warning signs", NONE)
        put("server not reached", SERVER_NOT_REACHED)
        put("ended before any line was checked", NO_LINES)
    }

    /** A reason text (any known wording, any case) -> its code; null if unknown. */
    fun codeForText(text: String?): String? = text?.trim()?.lowercase()?.let { KNOWN_TEXTS[it] }

    /**
     * The code for a call that ended at `level`. Warnings: the end-of-call answer's reason if the server gave one,
     * else the strongest signal that fired (the same choice as the aggregator's live reason: the highest recent
     * score at or above the reason threshold), else "several".
     */
    fun codeFor(level: Risk.Level, recentScores: Map<String, Float>, serverReason: String?): String = when (level) {
        Risk.Level.RED, Risk.Level.YELLOW -> codeForText(serverReason)
            ?: Risk.SIGNALS.filter { (recentScores[it] ?: 0f) >= Risk.SIGNAL_ON }.maxByOrNull { recentScores.getValue(it) }
            ?: SEVERAL
        Risk.Level.GREEN -> NONE
        Risk.Level.ERROR -> SERVER_NOT_REACHED
        Risk.Level.LISTENING -> NO_LINES
    }

    /** SQL for the 2 -> 3 migration: `CASE lower(trim(topReason)) WHEN '...' THEN 'code' ... END` (unknown -> NULL). */
    val MIGRATION_CASE_SQL: String
        get() = "CASE lower(trim(topReason)) " +
            KNOWN_TEXTS.entries.joinToString(" ") { (t, c) -> "WHEN '${t.replace("'", "''")}' THEN '$c'" } + " END"
}
