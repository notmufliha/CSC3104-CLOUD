package com.example.scamdetect.data

import android.content.Context
import androidx.core.content.edit
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
    }
}
