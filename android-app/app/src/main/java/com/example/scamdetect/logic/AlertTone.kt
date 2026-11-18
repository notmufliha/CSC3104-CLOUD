package com.example.scamdetect.logic

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The red warning's alert sound: three short 880 Hz beeps, as 16-bit mono PCM. Made in code (no sound file), the
 * same samples on iOS (Logic/Warnings.swift AlertTone), so both apps sound the same.
 */
object AlertTone {
    const val SAMPLE_RATE = 22_050
    const val BEEPS = 3
    const val BEEP_MS = 260
    const val GAP_MS = 140
    const val FREQ = 880.0
    const val AMPLITUDE = 0.7

    /** Whole sound in ms (beeps and the gaps between them). */
    val durationMs: Int get() = BEEPS * BEEP_MS + (BEEPS - 1) * GAP_MS

    fun pcm(): ShortArray {
        val beep = SAMPLE_RATE * BEEP_MS / 1000
        val gap = SAMPLE_RATE * GAP_MS / 1000
        val ramp = SAMPLE_RATE / 100            // 10 ms fade in / out: no clicks
        val out = ShortArray(BEEPS * beep + (BEEPS - 1) * gap)
        var at = 0
        repeat(BEEPS) { b ->
            for (i in 0 until beep) {
                val env = min(1.0, min(i, beep - 1 - i).toDouble() / ramp)
                out[at + i] = (sin(2 * PI * FREQ * i / SAMPLE_RATE) * AMPLITUDE * env * Short.MAX_VALUE).toInt().toShort()
            }
            at += beep + if (b < BEEPS - 1) gap else 0
        }
        return out
    }
}
