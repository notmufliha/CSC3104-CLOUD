package com.example.scamdetect.logic

import androidx.annotation.StringRes
import com.example.scamdetect.R

/**
 * What the red warning and the yellow "Be careful" banner say, in plain words: one sentence for what happened and
 * one for what to do. No scores, no percentages. Pure; the same choices and keys as iOS Logic/Warnings.swift.
 */
object Warnings {
    enum class Kind { OTP, MONEY, OFFICIAL, RUSH, GENERAL, SEVERAL, EARLIER }

    /** Every reason code a call can be saved with (logic/Reasons.kt), for tests and the review checklist. */
    val REASON_CODES = listOf(
        Reasons.MONEY, Reasons.IMPERSONATION, Reasons.URGENCY, Reasons.BERT, Reasons.SEVERAL, Reasons.EARLIER,
        Reasons.NONE, Reasons.SERVER_NOT_REACHED, Reasons.NO_LINES,
    )

    private fun saidOtp(flaggedTexts: List<String>): Boolean {
        val all = flaggedTexts.joinToString(" ").lowercase()
        return "otp" in all || "one-time" in all || "验证码" in all
    }

    /**
     * The warning's reason. Strongest first, as on the old banner: money (OTP when the caller said it), then a fake
     * official, then rushing. With no signal lit (the whole-call model, or several small signs) the reason code
     * decides.
     */
    fun kind(lit: List<String>, flaggedTexts: List<String>, reasonCode: String? = null): Kind {
        val otp = saidOtp(flaggedTexts)
        return when {
            "money" in lit -> if (otp) Kind.OTP else Kind.MONEY
            "impersonation" in lit -> Kind.OFFICIAL
            "urgency" in lit -> Kind.RUSH
            else -> when (reasonCode) {
                Reasons.MONEY -> if (otp) Kind.OTP else Kind.MONEY
                Reasons.IMPERSONATION -> Kind.OFFICIAL
                Reasons.URGENCY -> Kind.RUSH
                Reasons.SEVERAL -> Kind.SEVERAL
                Reasons.EARLIER -> Kind.EARLIER
                else -> Kind.GENERAL
            }
        }
    }

    /** What happened (red warning). */
    @StringRes fun message(k: Kind): Int = when (k) {
        Kind.OTP -> R.string.warn_msg_otp
        Kind.MONEY -> R.string.warn_msg_money
        Kind.OFFICIAL -> R.string.warn_msg_official
        Kind.RUSH -> R.string.warn_msg_rush
        Kind.GENERAL -> R.string.warn_msg_general
        Kind.SEVERAL -> R.string.warn_msg_several
        Kind.EARLIER -> R.string.warn_msg_earlier
    }

    /** What to do (red warning, and the spoken warning). */
    @StringRes fun doLine(k: Kind): Int = when (k) {
        Kind.OTP -> R.string.warn_do_otp
        Kind.MONEY -> R.string.warn_do_money
        else -> R.string.warn_do_details
    }

    /** The yellow "Be careful" banner's line. */
    @StringRes fun careful(k: Kind): Int = when (k) {
        Kind.OTP, Kind.MONEY -> R.string.careful_msg_money
        Kind.OFFICIAL -> R.string.careful_msg_official
        Kind.RUSH -> R.string.careful_msg_rush
        else -> R.string.careful_msg_general
    }

    /** Read aloud: "This may be a scam. Do not give your OTP. Hang up now." in the given wording. */
    fun spoken(k: Kind, t: Txt = Tr.txt): String =
        t.get(R.string.warn_spoken, arrayOf(t.get(doLine(k), emptyArray())))

    /** The message to family: opens the SMS app, the user taps Send. */
    fun familyMessage(t: Txt = Tr.txt): String = t.get(R.string.sms_family, emptyArray())
}
