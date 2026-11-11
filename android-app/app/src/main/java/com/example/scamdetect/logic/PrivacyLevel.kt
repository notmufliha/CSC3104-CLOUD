package com.example.scamdetect.logic

/**
 * Workstream G: where a call's sentences are scored. Cloud is the default and the main demo; the other two are
 * optional privacy modes.
 *
 * `id` is what is saved (settings, and each dashboard row's CallRecord.privacyLevel), so never rename one.
 * `available` = built in this version of the app. A level that isn't built yet can't be picked, and a saved one is
 * read back as CLOUD, so the app never claims a privacy level it doesn't deliver.
 */
enum class PrivacyLevel(
    val id: String,
    val title: String,
    /** One line for the Settings screen. */
    val explanation: String,
    /** Short label for the in-call badge and the dashboard. */
    val badge: String,
    val available: Boolean,
) {
    CLOUD(
        "cloud", "1. Cloud (default)",
        "Every caller sentence is checked by the ScamGuard server. Nothing is stored there.",
        "Cloud", available = true,
    ),
    HYBRID(
        "hybrid", "2. Hybrid",
        "Checked on this phone. Only at unsure moments, the caller's last 5 sentences go to the server, with numbers and names removed. English calls only.",
        "Hybrid", available = true,    // G.3
    ),
    ON_DEVICE(
        "on_device", "3. On-device",
        "Everything is checked on this phone, even in airplane mode. Nothing about the call leaves it. English calls only.",
        "On-device", available = true,  // G.2
    );

    /** Caller lines are scored by the on-device models (levels 2 and 3). */
    val scoresOnPhone: Boolean get() = this != CLOUD

    /**
     * Level 1 only: the server scores every line, keeps the call's memory, gets the end message, and the user can
     * share a report. Levels 2 and 3 send none of that (level 2 sends only masked windows to POST /v1/verify).
     */
    val cloudCall: Boolean get() = this == CLOUD

    companion object {
        val DEFAULT = CLOUD

        /** A saved id -> the level to use. Unknown, missing or not-yet-built levels fall back to Cloud. */
        fun fromId(id: String?): PrivacyLevel = entries.firstOrNull { it.id == id && it.available } ?: DEFAULT

        /** A dashboard row's id -> its badge, for rows saved by any version (old rows say "cloud"). */
        fun badgeFor(id: String): String = entries.firstOrNull { it.id == id }?.badge ?: id
    }
}
