package com.example.scamdetect.data

import android.content.Context
import androidx.core.content.edit
import com.example.scamdetect.logic.PrivacyLevel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Gateway URL and settings, in SharedPreferences. (Call history moved to the Room database, data/db/.) */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("scamguard", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyType = object : TypeToken<List<HistoryEntry>>() {}.type

    var gatewayUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit { putString(KEY_URL, GatewayClient.normalize(value)) }

    /** Workstream B.7: "Save the riskiest sentence" on the dashboard. Off by default: no transcript is kept. */
    var saveRiskiestSentence: Boolean
        get() = sp.getBoolean(KEY_SAVE_SENTENCE, false)
        set(value) = sp.edit { putBoolean(KEY_SAVE_SENTENCE, value) }

    /** Workstream G: where calls are scored. Cloud unless the user picked an available level. */
    var privacyLevel: PrivacyLevel
        get() = PrivacyLevel.fromId(sp.getString(KEY_PRIVACY_LEVEL, null))
        set(value) = sp.edit { putString(KEY_PRIVACY_LEVEL, value.id) }

    /** Workstream G.13: "Help improve detection (federated learning)". Off by default: labels only, no sentences. */
    var helpImproveDetection: Boolean
        get() = sp.getBoolean(KEY_HELP_IMPROVE, false)
        set(value) = sp.edit { putBoolean(KEY_HELP_IMPROVE, value) }

    /**
     * Workstream G (real-phone training): a random id made once per install, so a newer upload replaces this phone's
     * older one in the inbox. Not linked to the user, the phone number or the device.
     */
    val flPhoneId: String
        get() = sp.getString(KEY_FL_PHONE_ID, null) ?: java.util.UUID.randomUUID().toString().also { id ->
            sp.edit { putString(KEY_FL_PHONE_ID, id) }
        }

    /**
     * The old History (before the Room dashboard), or null if there is none (never had one, or already moved).
     * Read once by CallHistory.migrateLegacy, which then calls removeLegacyHistory.
     */
    fun legacyHistory(): List<HistoryEntry>? {
        val json = sp.getString(KEY_HISTORY, null) ?: return null
        return try {
            gson.fromJson<List<HistoryEntry>>(json, historyType).orEmpty()
        } catch (e: Exception) {
            emptyList()   // unreadable: nothing to move, and the key is removed all the same
        }
    }

    fun removeLegacyHistory() = sp.edit { remove(KEY_HISTORY) }

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8080/" // emulator -> laptop's localhost
        private const val KEY_URL = "gateway_url"
        private const val KEY_HISTORY = "history"
        private const val KEY_SAVE_SENTENCE = "save_riskiest_sentence"
        private const val KEY_PRIVACY_LEVEL = "privacy_level"
        private const val KEY_HELP_IMPROVE = "help_improve_detection"
        private const val KEY_FL_PHONE_ID = "fl_phone_id"
    }
}
