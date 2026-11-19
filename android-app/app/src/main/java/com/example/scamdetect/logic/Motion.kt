package com.example.scamdetect.logic

/**
 * Motion and haptics, one set of rules for both apps (ios-app Logic/Motion.swift). With "reduce motion" on (Android:
 * "Remove animations", animator scale 0; iOS: Reduce Motion) nothing scales, slides or flashes: things fade or simply
 * appear.
 */
object Motion {
    /** Big buttons shrink a little while pressed. */
    fun pressScale(reduce: Boolean): Float = if (reduce) 1f else 0.97f

    /** The red warning comes in with a short scale + fade, or a plain fade with reduce motion. */
    fun warningStartScale(reduce: Boolean): Float = if (reduce) 1f else 0.92f
    fun warningEnterMs(reduce: Boolean): Int = if (reduce) 0 else 280

    /** The red warning's edge flash (the only flashing in the app) is off with reduce motion. */
    fun warningFlash(reduce: Boolean): Boolean = !reduce

    /** Risk bar colour / fill changes. */
    fun riskBarMs(reduce: Boolean): Int = if (reduce) 0 else 450

    enum class Haptic { LIGHT, WARNING, ERROR }

    /** Light for taps, warning for "Be careful", error for Scam. */
    fun hapticFor(level: Risk.Level): Haptic? = when (level) {
        Risk.Level.RED -> Haptic.ERROR
        Risk.Level.YELLOW -> Haptic.WARNING
        else -> null
    }
}
