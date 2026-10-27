package com.example.scamdetect.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Gateway URL + call history, stored in SharedPreferences. History keeps at most 100 rows. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("scamguard", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyType = object : TypeToken<List<HistoryEntry>>() {}.type

    var gatewayUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit { putString(KEY_URL, GatewayClient.normalize(value)) }

    fun history(): List<HistoryEntry> {
        val json = sp.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<HistoryEntry>>(json, historyType).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(entry: HistoryEntry) {
        val updated = (listOf(entry) + history()).take(MAX_HISTORY) // newest first
        sp.edit { putString(KEY_HISTORY, gson.toJson(updated)) }
    }

    fun clearHistory() = sp.edit { remove(KEY_HISTORY) }

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8080/" // emulator -> laptop's localhost
        private const val KEY_URL = "gateway_url"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 100
    }
}
