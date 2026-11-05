package com.example.scamdetect.data

import com.google.gson.annotations.SerializedName

/** One line of a call script (a .json file in assets/scripts/), the format Workstream 6 writes. */
data class ScriptLine(
    val speaker: String? = null,          // "caller" or "receiver"
    val text: String? = null,
    @SerializedName("delay_ms") val delayMs: Long? = null, // wait before this line appears
) {
    val isCaller: Boolean
        get() = speaker?.trim()?.lowercase() in setOf("caller", "speaker 1", "speaker1", "speaker_1")
}

/** A whole script plus what we read from its file name, e.g. scam_en_01_police.json. */
data class CallScript(
    val fileName: String,
    val kind: String,        // "scam" | "normal" | "pasted"
    val lang: String?,       // "en" | "zh" | null (let the gateway detect)
    val title: String,
    val lines: List<ScriptLine>,
)

/** Body of POST /v1/utterance. Gson leaves out null fields, so lang = null means "detect". */
data class UtteranceRequest(
    @SerializedName("call_id") val callId: String,
    val seq: Long,
    val speaker: String,
    val text: String,
    val lang: String? = null,
)

/** Answer from POST /v1/utterance. Everything nullable: Gson does not respect Kotlin defaults. */
data class UtteranceResponse(
    @SerializedName("call_id") val callId: String?,
    val seq: Long?,
    val scored: Boolean?,
    val duplicate: Boolean?,
    val verdict: String?,                                   // SAFE | UNSURE | SCAM
    @SerializedName("combined_score") val combinedScore: Float?,
    val language: String?,
    val scores: Map<String, Float?>?,                       // this line only
    val missing: List<String>?,
    // Best per signal over the call's recent caller lines. The gateway sends "recent_scores"; the old name
    // "call_scores" (in the first app guide) is still read, so chips light up with either.
    @SerializedName(value = "recent_scores", alternate = ["call_scores"]) val callScores: Map<String, Float>?,
    @SerializedName("caller_lines") val callerLines: Int?,
    @SerializedName("latency_ms") val latencyMs: Long?,
    /** Workstream B.6: true = the call had already ended, the line was not scored. Treat as "no change". */
    val ignored: Boolean? = null,
    /** Why it was ignored ("call_ended"); null otherwise. */
    val reason: String? = null,
)

/** Workstream B: one caller line in the end message, for the end-of-call review (not stored by the server). */
data class EndLine(val seq: Long, val text: String)

/** Body of POST /v1/call/end. */
data class EndCallRequest(
    @SerializedName("call_id") val callId: String,
    @SerializedName("ended_by") val endedBy: String,       // script_finished | user_hung_up | user_discarded
    @SerializedName("last_seq") val lastSeq: Long,
    @SerializedName("caller_lines") val callerLines: List<EndLine>,
    val lang: String? = null,                               // null = the gateway detects it from the lines
    val event: String = "end",
)

/** Answer from POST /v1/call/end. Everything nullable: Gson does not respect Kotlin defaults. */
data class EndCallResponse(
    @SerializedName("call_id") val callId: String?,
    @SerializedName("final_verdict") val finalVerdict: String?,   // SAFE | UNSURE | SCAM | UNKNOWN
    @SerializedName("call_score") val callScore: Float?,
    @SerializedName("live_verdict") val liveVerdict: String?,
    val review: String?,                                          // "bert" | "fallback"
    @SerializedName("top_window_seqs") val topWindowSeqs: List<Long>?,
    val reason: String?,
    @SerializedName("ended_by") val endedBy: String?,
    @SerializedName("already_ended") val alreadyEnded: Boolean?,
    @SerializedName("latency_ms") val latencyMs: Long?,
    val error: String?,                                           // set when the server fell back (aggregator down)
)

/** Workstream H.3: one caller line in a report, already masked on the phone (logic/Mask.kt). */
data class ReportLine(val seq: Long, val text: String)

/** Body of POST /v1/report: sent only after the user agreed on the consent screen. */
data class ReportRequest(
    @SerializedName("call_id") val callId: String,
    val kind: String,                                       // "scam" (Report this call) | "fine" (This was fine)
    @SerializedName("app_verdict") val appVerdict: String,
    val language: String,                                   // "en" | "zh"
    val consent: Boolean,
    val lines: List<ReportLine>,
)

/** Answer from POST /v1/report (from the review service, through the gateway). */
data class ReportResponse(
    @SerializedName("report_id") val reportId: Long?,
    @SerializedName("lines_stored") val linesStored: Int?,
    @SerializedName("expires_at") val expiresAt: Long?,
)

/** One row of the OLD History (SharedPreferences). Only read to move it into Room once (CallHistory.migrateLegacy). */
data class HistoryEntry(
    val timeMillis: Long,
    val verdict: String,
    val topReason: String,
    val combinedScore: Float?,
)
