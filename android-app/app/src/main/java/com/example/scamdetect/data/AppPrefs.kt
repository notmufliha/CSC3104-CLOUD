package com.example.scamdetect.data

import android.content.Context
import androidx.core.content.edit
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.PrivacyLevel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Gateway URL and settings, in SharedPreferences. (Call history moved to the Room database, data/db/.) */
class AppPrefs(context: Context) : Onboarding.Store {
    private val sp = context.getSharedPreferences("scamguard", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyType = object : TypeToken<List<HistoryEntry>>() {}.type

    override var gatewayUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit { putString(KEY_URL, GatewayClient.normalize(value)) }

    /** Workstream B.7: "Save the riskiest sentence" on the dashboard. Off by default: no transcript is kept. */
    override var saveRiskiestSentence: Boolean
        get() = sp.getBoolean(KEY_SAVE_SENTENCE, false)
        set(value) = sp.edit { putBoolean(KEY_SAVE_SENTENCE, value) }

    /** Workstream G: where calls are scored. Cloud unless the user picked an available level. */
    override var privacyLevel: PrivacyLevel
        get() = PrivacyLevel.fromId(sp.getString(KEY_PRIVACY_LEVEL, null))
        set(value) = sp.edit { putString(KEY_PRIVACY_LEVEL, value.id) }

    /** Workstream G.13: "Help improve detection (federated learning)". Off by default: labels only, no sentences. */
    override var helpImproveDetection: Boolean
        get() = sp.getBoolean(KEY_HELP_IMPROVE, false)
        set(value) = sp.edit { putBoolean(KEY_HELP_IMPROVE, value) }

    /** First-launch setup finished (or skipped); null = never written (a new install, or one from before setup). */
    override var onboardingCompleted: Boolean?
        get() = if (sp.contains(KEY_ONBOARDING_DONE)) sp.getBoolean(KEY_ONBOARDING_DONE, false) else null
        set(value) = sp.edit { if (value == null) remove(KEY_ONBOARDING_DONE) else putBoolean(KEY_ONBOARDING_DONE, value) }

    /** The setup version that was finished (Onboarding.VERSION). */
    override var onboardingVersion: Int
        get() = sp.getInt(KEY_ONBOARDING_VERSION, 0)
        set(value) = sp.edit { putInt(KEY_ONBOARDING_VERSION, value) }

    // ---- elderly-friendly settings (new keys only; the same names and defaults on iOS)

    /** "large" (default) or "xlarge". */
    override var textSize: String
        get() = sp.getString(KEY_TEXT_SIZE, TEXT_LARGE) ?: TEXT_LARGE
        set(value) = sp.edit { putString(KEY_TEXT_SIZE, value) }

    /** "Read warnings aloud". On by default. */
    override var readAloud: Boolean
        get() = sp.getBoolean(KEY_READ_ALOUD, true)
        set(value) = sp.edit { putBoolean(KEY_READ_ALOUD, value) }

    /** "Show 'Be careful' warnings". On by default; off = only red warnings show (calls are still saved). */
    var showBeCareful: Boolean
        get() = sp.getBoolean(KEY_SHOW_CAREFUL, true)
        set(value) = sp.edit { putBoolean(KEY_SHOW_CAREFUL, value) }

    /** Simple mode; null = never written (an install from before Simple mode: it keeps the full app). */
    override var simpleMode: Boolean?
        get() = if (sp.contains(KEY_SIMPLE)) sp.getBoolean(KEY_SIMPLE, true) else null
        set(value) = sp.edit { if (value == null) remove(KEY_SIMPLE) else putBoolean(KEY_SIMPLE, value) }

    /** An existing user was offered Simple mode once (yes or no). */
    override var simpleModeOffered: Boolean
        get() = sp.getBoolean(KEY_SIMPLE_OFFERED, false)
        set(value) = sp.edit { putBoolean(KEY_SIMPLE_OFFERED, value) }

    /** The trusted family contact, on this phone only. Empty = none. */
    override var familyName: String
        get() = sp.getString(KEY_FAMILY_NAME, "") ?: ""
        set(value) = sp.edit { putString(KEY_FAMILY_NAME, value.trim()) }

    override var familyPhone: String
        get() = sp.getString(KEY_FAMILY_PHONE, "") ?: ""
        set(value) = sp.edit { putString(KEY_FAMILY_PHONE, value.trim()) }

    /** Any setting an earlier version saved, or its old history: then this install is not a first launch. */
    override fun hasAnySetting(): Boolean = EXISTING_KEYS.any { sp.contains(it) }

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
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
        private const val KEY_ONBOARDING_VERSION = "onboarding_version"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_READ_ALOUD = "read_aloud"
        private const val KEY_SHOW_CAREFUL = "show_be_careful"
        private const val KEY_SIMPLE = "simple_mode"
        private const val KEY_SIMPLE_OFFERED = "simple_mode_offered"
        private const val KEY_FAMILY_NAME = "family_name"
        private const val KEY_FAMILY_PHONE = "family_phone"
        const val TEXT_LARGE = "large"
        const val TEXT_XLARGE = "xlarge"
        private val EXISTING_KEYS = listOf(KEY_URL, KEY_HISTORY, KEY_SAVE_SENTENCE, KEY_PRIVACY_LEVEL, KEY_HELP_IMPROVE, KEY_FL_PHONE_ID)
    }
}
