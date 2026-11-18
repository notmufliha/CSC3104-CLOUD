package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri

/** The activity behind a (possibly wrapped) context. */
tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

const val SCAMSHIELD_HELPLINE = "1799"

/** Opens the phone app with the number filled in (never calls by itself). false = no phone app. */
fun dial(context: Context, number: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.filter { it.isDigit() || it == '+' }, null)))
    true
} catch (e: ActivityNotFoundException) {
    false
}

/** Opens the messages app with the text filled in; the user taps Send (no SMS permission). false = no app. */
fun textMessage(context: Context, phone: String, body: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone.filter { it.isDigit() || it == '+' }, null))
            .putExtra("sms_body", body)
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}
