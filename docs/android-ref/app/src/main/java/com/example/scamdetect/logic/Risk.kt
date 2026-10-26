package com.example.scamdetect.logic

/** Turns gateway numbers into colours, chips and the words on the banner. */
object Risk {
    val SIGNALS = listOf("urgency", "money", "impersonation")

    /** A chip lights up when that signal's best score in the call reaches this. Tune with the team. */
    const val SIGNAL_ON = 0.5f

    enum class Level { LISTENING, GREEN, YELLOW, RED, ERROR }

    fun level(verdict: String?, error: String?): Level = when (verdict) {
        "SAFE" -> Level.GREEN
        "UNSURE" -> Level.YELLOW
        "SCAM" -> Level.RED
        else -> if (error != null) Level.ERROR else Level.LISTENING
    }

    fun litSignals(callScores: Map<String, Float>): List<String> =
        SIGNALS.filter { (callScores[it] ?: 0f) >= SIGNAL_ON }

    fun chipLabel(signal: String) = when (signal) {
        "urgency" -> "Urgency"
        "money" -> "Money / OTP"
        "impersonation" -> "Impersonation"
        else -> signal
    }

    /** Strongest reason first: money, then impersonation, then urgency. */
    fun reason(lit: List<String>, flaggedTexts: List<String>): String {
        val all = flaggedTexts.joinToString(" ").lowercase()
        return when {
            "money" in lit && ("otp" in all || "one-time" in all || "验证码" in all) -> "asking for your OTP"
            "money" in lit -> "asking you to transfer money"
            "impersonation" in lit -> "pretending to be an official (police, bank, government)"
            "urgency" in lit -> "pressuring you to act right now"
            else -> "several small warning signs together"
        }
    }

    fun action(lit: List<String>): String = when {
        "money" in lit -> "Hang up and call your bank on the number on your card."
        "impersonation" in lit -> "Hang up and call the agency back on its official number."
        else -> "Don't share personal details. Real organisations give you time to check."
    }

    fun banner(level: Level, lit: List<String>, flaggedTexts: List<String>): String? = when (level) {
        Level.RED -> "Likely scam: ${reason(lit, flaggedTexts)}. ${action(lit)}"
        Level.YELLOW -> "Be careful: ${reason(lit, flaggedTexts)}. ${action(lit)}"
        Level.ERROR -> "Could not check this call. The server can't be reached — this does NOT mean it is safe."
        else -> null
    }
}
