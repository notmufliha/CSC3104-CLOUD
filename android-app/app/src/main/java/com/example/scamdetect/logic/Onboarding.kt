package com.example.scamdetect.logic

import com.example.scamdetect.R

/**
 * First-launch setup: when it shows, which screens it has, and what it saves. Pure (no Android), kept in step with
 * ios-app/ScamGuard/Logic/Onboarding.swift; both apps test the same rules and wording.
 */
object Onboarding {
    /** Bump when the setup gains a screen that existing users should see. Saved with the "completed" flag. */
    const val VERSION = 1

    enum class Step { WELCOME, RECOMMENDED, PRIVACY, SERVER, DATA, HELPER, FAMILY, PIN, DONE }

    /**
     * Which way through the setup. SIMPLE (the default): Welcome, "Use recommended settings", family
     * contact (3 screens). CUSTOM ("Choose myself"): the full choices. HELPER ("Set up for a family member"): text
     * size, Simple mode and read-aloud for them, the contact, an optional PIN.
     */
    enum class Path { SIMPLE, CUSTOM, HELPER }

    /** What the app does when it starts. */
    enum class Launch {
        /** First launch, or the setup was reset / never finished: show it. */
        SHOW_SETUP,
        /** An install from before the setup existed (it already has settings or history): never show it. */
        EXISTING_USER,
        /** Setup was finished (or skipped) before. */
        DONE,
    }

    /**
     * completed: the saved flag, null when it was never written. A reset writes false, so a reset install with old
     * settings still shows the setup; only a missing flag counts settings or history as "already set up".
     */
    fun atLaunch(completed: Boolean?, hasSettings: Boolean, hasHistory: Boolean): Launch = when {
        completed == true -> Launch.DONE
        completed == false -> Launch.SHOW_SETUP
        hasSettings || hasHistory -> Launch.EXISTING_USER
        else -> Launch.SHOW_SETUP
    }

    /** The screens, in order, for the way chosen. On-device needs no server, so that screen is left out. */
    fun steps(c: Choices): List<Step> = when (c.path) {
        Path.SIMPLE -> listOf(Step.WELCOME, Step.RECOMMENDED, Step.FAMILY)
        Path.CUSTOM -> listOfNotNull(Step.WELCOME, Step.RECOMMENDED, Step.PRIVACY,
            Step.SERVER.takeIf { c.privacy != PrivacyLevel.ON_DEVICE }, Step.DATA, Step.FAMILY, Step.DONE)
        Path.HELPER -> listOf(Step.WELCOME, Step.HELPER, Step.FAMILY, Step.PIN, Step.DONE)
    }

    fun next(step: Step, c: Choices): Step? = steps(c).let { s -> s.getOrNull(s.indexOf(step) + 1) }

    fun previous(step: Step, c: Choices): Step? = steps(c).let { s -> s.getOrNull(s.indexOf(step) - 1) }

    /** Where the server screen left things. */
    enum class Server { NOT_CHECKED, CONNECTED, CANT_REACH, LATER }

    data class Choices(
        val privacy: PrivacyLevel = PrivacyLevel.DEFAULT,
        val serverUrl: String,
        val server: Server = Server.NOT_CHECKED,
        val saveSentence: Boolean = false,
        val helpImprove: Boolean = false,
        val path: Path = Path.SIMPLE,
        val textSize: String = "large",
        /** Simple mode: on for every new install (an install from before it keeps the full app). */
        val simple: Boolean = true,
        val readAloud: Boolean = true,
        val familyName: String = "",
        val familyPhone: String = "",
        /** A 4-digit PIN for Advanced settings and "For family" (helper setup only); "" = none. */
        val pin: String = "",
    )

    /** What Skip saves on a first launch: recommended settings (Cloud, both data switches off, Simple mode on). */
    fun defaults(currentUrl: String) = Choices(serverUrl = currentUrl)

    /** "Run setup again" starts from what is saved now, so Skip keeps it. */
    fun fromSaved(store: Store) = Choices(
        privacy = store.privacyLevel,
        serverUrl = store.gatewayUrl,
        saveSentence = store.saveRiskiestSentence,
        helpImprove = store.helpImproveDetection,
        textSize = store.textSize,
        simple = simpleModeAtLaunch(store.simpleMode),
        readAloud = store.readAloud,
        familyName = store.familyName,
        familyPhone = store.familyPhone,
    )

    /** "Use recommended settings": the most accurate checking, nothing kept, warnings read aloud. */
    fun recommended(c: Choices) = c.copy(privacy = PrivacyLevel.CLOUD, saveSentence = false, helpImprove = false,
        readAloud = true, path = Path.SIMPLE)

    /** The settings the setup reads and writes: AppPrefs on the phone, a map in tests. */
    interface Store {
        var privacyLevel: PrivacyLevel
        var gatewayUrl: String
        var saveRiskiestSentence: Boolean
        var helpImproveDetection: Boolean
        /** null = never written. */
        var onboardingCompleted: Boolean?
        var onboardingVersion: Int
        var textSize: String
        var readAloud: Boolean
        /** null = never written (an install from before Simple mode). */
        var simpleMode: Boolean?
        var simpleModeOffered: Boolean
        var familyName: String
        var familyPhone: String
        /** Any setting saved by an earlier version of the app (or old history kept in settings). */
        fun hasAnySetting(): Boolean
    }

    /**
     * Saves the choices and marks the setup done. The server address is kept as it was for On-device (no server
     * screen) and for "Set up later". The PIN is saved separately (secure storage), by the caller.
     */
    fun apply(c: Choices, store: Store) {
        store.privacyLevel = c.privacy
        if (c.privacy != PrivacyLevel.ON_DEVICE && c.server != Server.LATER) store.gatewayUrl = c.serverUrl
        store.saveRiskiestSentence = c.saveSentence
        store.helpImproveDetection = c.helpImprove
        store.textSize = c.textSize
        store.readAloud = c.readAloud
        store.simpleMode = c.simple
        store.simpleModeOffered = true
        store.familyName = c.familyName.trim()
        store.familyPhone = c.familyPhone.trim()
        markCompleted(store)
    }

    fun markCompleted(store: Store) {
        store.onboardingCompleted = true
        store.onboardingVersion = VERSION
    }

    /** Debug "Reset setup": the next start shows the setup again, even with settings saved. */
    fun reset(store: Store) {
        store.onboardingCompleted = false
    }

    // ---- Simple mode for installs from before it

    /** New installs save Simple mode in the setup; an install from before it (nothing saved) keeps the full app. */
    fun simpleModeAtLaunch(saved: Boolean?): Boolean = saved ?: false

    /** An existing user is offered Simple mode once (a card on Home), never switched over by itself. */
    fun offerSimpleMode(saved: Boolean?, offered: Boolean): Boolean = saved == null && !offered

    /** "Step 2 of 5", for screen readers on the progress dots: (index, count). */
    fun progress(s: Step, c: Choices): Pair<Int, Int> = steps(c).let { all -> (all.indexOf(s) + 1) to all.size }
}

/**
 * The guided tour. Simple mode: 3 coach marks (the practice call button, the red warning, its buttons). The full app:
 * 7 coach marks over real screens. The call steps use a pretend call (nothing starts, nothing is sent).
 */
object Tour {
    enum class Place { HOME, CALL, DASHBOARD, SETTINGS }

    enum class Target(val id: String) {
        PRACTICE_CALL("tour.practiceCall"),
        SCAM_CALL("tour.scamCall"),
        PICK_SCRIPT("tour.pickScript"),
        RISK_BAR("tour.riskBar"),
        WARNING_CHIPS("tour.warningChips"),
        RED_WARNING("tour.redWarning"),
        WARNING_BUTTONS("tour.warningButtons"),
        DASHBOARD_HERO("tour.dashboardHero"),
        PRIVACY_ROW("tour.privacyRow"),
    }

    /** title / line are string resources. */
    data class Step(val target: Target, val place: Place, @androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val line: Int)

    val simpleSteps = listOf(
        Step(Target.PRACTICE_CALL, Place.HOME, R.string.tour_practice_title, R.string.tour_practice_line),
        Step(Target.RED_WARNING, Place.CALL, R.string.tour_red_title, R.string.tour_red_line),
        Step(Target.WARNING_BUTTONS, Place.CALL, R.string.tour_buttons_title, R.string.tour_buttons_line),
    )

    val fullSteps = listOf(
        Step(Target.SCAM_CALL, Place.HOME, R.string.tour_scam_title, R.string.tour_scam_line),
        Step(Target.PICK_SCRIPT, Place.HOME, R.string.tour_pick_title, R.string.tour_pick_line),
        Step(Target.RISK_BAR, Place.CALL, R.string.tour_bar_title, R.string.tour_bar_line),
        Step(Target.WARNING_CHIPS, Place.CALL, R.string.tour_chips_title, R.string.tour_chips_line),
        Step(Target.RED_WARNING, Place.CALL, R.string.tour_red_title, R.string.tour_red_line_full),
        Step(Target.DASHBOARD_HERO, Place.DASHBOARD, R.string.tour_dash_title, R.string.tour_dash_line),
        Step(Target.PRIVACY_ROW, Place.SETTINGS, R.string.tour_privacy_title, R.string.tour_privacy_line),
    )

    fun steps(simple: Boolean) = if (simple) simpleSteps else fullSteps

    fun isLast(index: Int, simple: Boolean) = index == steps(simple).size - 1
}
