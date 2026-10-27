package com.example.scamdetect.data

import com.google.gson.annotations.SerializedName

/** One line of a call script (assets/scripts/*.json), the format Workstream 6 writes. */
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
    @SerializedName("call_scores") val callScores: Map<String, Float>?, // best per signal, whole call
    @SerializedName("caller_lines") val callerLines: Int?,
    @SerializedName("latency_ms") val latencyMs: Long?,
)

/** One row of the History screen. No transcript, no number. */
data class HistoryEntry(
    val timeMillis: Long,
    val verdict: String,
    val topReason: String,
    val combinedScore: Float?,
)
