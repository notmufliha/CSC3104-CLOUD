package com.example.scamdetect.logic

import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ImplicitLabel
import com.example.scamdetect.data.db.ScorePoint

/**
 * Debug builds only (Settings → Help & about → Load sample history): about 30 days of made-up calls, so the dashboard
 * can be demoed and screenshotted. Every id starts with "sample-"; "Delete my history" removes them like any call.
 *
 * Deterministic and integer-only (a fixed linear congruential generator), and kept line for line in step with
 * ios-app/ScamGuard/Logic/SampleHistory.swift, so both apps build exactly the same history from the same seed.
 */
object SampleHistory {
    const val ID_PREFIX = "sample-"
    private const val HOUR = 3_600_000L
    private const val MINUTE = 60_000L

    data class History(val calls: List<CallRecord>, val points: List<ScorePoint>, val labels: List<ImplicitLabel>)

    private class Rng(seed: Long) {
        private var s = seed
        fun next(): Int {
            s = (s * 1103515245L + 12345L) and 0x7fffffffL
            return s.toInt()
        }
        fun int(n: Int): Int = next() % n
    }

    /** `todayStartMs` = local midnight today; calls later than `nowMs` are left out. */
    fun generate(nowMs: Long, todayStartMs: Long, seed: Long = 42): History {
        val rng = Rng(seed)
        val calls = mutableListOf<CallRecord>()
        val points = mutableListOf<ScorePoint>()
        val labels = mutableListOf<ImplicitLabel>()
        for (d in 0 until 30) {
            val n = 1 + rng.int(3)
            for (k in 0 until n) {
                val hour = 8 + rng.int(14)
                val minute = rng.int(60)
                val endedAt = todayStartMs - d * DashboardStats.DAY_MS + hour * HOUR + minute * MINUTE
                val durationS = 30 + rng.int(121)
                val roll = rng.int(100)
                val final = when {
                    roll < 55 -> "SAFE"
                    roll < 75 -> "UNSURE"
                    roll < 95 -> "SCAM"
                    else -> "NOT CHECKED"
                }
                val p = rng.int(100)
                val privacy = when {
                    p < 60 -> "cloud"
                    p < 85 -> "hybrid"
                    else -> "on_device"
                }
                val lines = 4 + rng.int(6)
                val sig = mutableListOf<String>()
                if (final == "SCAM") {
                    if (rng.int(100) < 70) sig += "money"
                    if (rng.int(100) < 60) sig += "impersonation"
                    if (rng.int(100) < 50) sig += "urgency"
                    if (sig.isEmpty()) sig += "money"
                } else if (final == "UNSURE") {
                    sig += Risk.SIGNALS[rng.int(3)]
                }
                var live = final
                if (final == "SAFE" && rng.int(100) < 15) live = "UNSURE"
                val id = "$ID_PREFIX$d-$k"
                var callScore: Float? = null
                if (final != "NOT CHECKED") {
                    for (i in 0 until lines) {
                        val base = when (final) {
                            "SAFE" -> 50 + rng.int(250)
                            "UNSURE" -> 250 + rng.int(250)
                            else -> minOf(990, 200 + i * 700 / (lines - 1) + rng.int(100))
                        }
                        val sc = Risk.SIGNALS.map { s -> (if (s in sig) 500 + rng.int(450) else rng.int(300)) / 1000f }
                        val combined = base / 1000f
                        points += ScorePoint(
                            callId = id, seq = (2 * i + 1).toLong(),
                            secondsIntoCall = ((i + 1) * durationS).toFloat() / lines,
                            combined = combined, urgency = sc[0], money = sc[1], impersonation = sc[2],
                        )
                        callScore = combined
                    }
                }
                val code = when (final) {
                    "SAFE" -> Reasons.NONE
                    "NOT CHECKED" -> Reasons.SERVER_NOT_REACHED
                    "UNSURE" -> sig[0]
                    else -> if (rng.int(100) < 20) Reasons.BERT else listOf("money", "impersonation", "urgency").first { it in sig }
                }
                calls += CallRecord(
                    id = id,
                    startedAt = endedAt - durationS * 1000L,
                    endedAt = endedAt,
                    finalVerdict = final,
                    liveVerdict = live,
                    topReason = Reasons.text(code).orEmpty(),
                    callScore = callScore,
                    signalsFired = Risk.SIGNALS.filter { it in sig },
                    review = when {
                        final == "NOT CHECKED" -> "unavailable"
                        privacy == "cloud" -> "bert"
                        else -> "on_device"
                    },
                    riskiestSentence = null,
                    privacyLevel = privacy,
                    reasonCode = code,
                )
                if (final == "SCAM" && rng.int(100) < 40) {
                    labels += ImplicitLabel(callId = id, label = 1, source = ImplicitLabels.SOURCE_HUNG_UP, time = endedAt)
                }
                if (final == "SAFE" && live == "UNSURE" && rng.int(100) < 50) {
                    labels += ImplicitLabel(callId = id, label = 0, source = ImplicitLabels.SOURCE_FINE, time = endedAt)
                }
            }
        }
        // a call later today than "now" hasn't happened yet
        val keep = calls.filter { it.endedAt <= nowMs }.map { it.id }.toSet()
        return History(calls.filter { it.id in keep }, points.filter { it.callId in keep }, labels.filter { it.callId in keep })
    }
}
