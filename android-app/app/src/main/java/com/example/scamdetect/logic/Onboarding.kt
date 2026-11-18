package com.example.scamdetect.logic

/**
 * First-launch setup: when it shows, which screens it has, and what it saves. Pure (no Android), kept in step with
 * ios-app/ScamGuard/Logic/Onboarding.swift; both apps test the same rules and wording.
 */
object Onboarding {
    /** Bump when the setup gains a screen that existing users should see. Saved with the "completed" flag. */
    const val VERSION = 1

    enum class Step { WELCOME, PRIVACY, SERVER, DATA, DONE }

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

    /** The screens, in order. On-device needs no server, so that screen is left out. */
    fun steps(privacy: PrivacyLevel): List<Step> =
        Step.entries.filter { it != Step.SERVER || privacy != PrivacyLevel.ON_DEVICE }

    fun next(step: Step, privacy: PrivacyLevel): Step? = steps(privacy).let { s -> s.getOrNull(s.indexOf(step) + 1) }

    fun previous(step: Step, privacy: PrivacyLevel): Step? = steps(privacy).let { s -> s.getOrNull(s.indexOf(step) - 1) }

    /** Where the server screen left things. */
    enum class Server { NOT_CHECKED, CONNECTED, CANT_REACH, LATER }

    data class Choices(
        val privacy: PrivacyLevel = PrivacyLevel.DEFAULT,
        val serverUrl: String,
        val server: Server = Server.NOT_CHECKED,
        val saveSentence: Boolean = false,
        val helpImprove: Boolean = false,
    )

    /** What Skip saves on a first launch: Cloud, the current server address, both data switches off. */
    fun defaults(currentUrl: String) = Choices(serverUrl = currentUrl)

    /** "Run setup again" starts from what is saved now, so Skip keeps it. */
    fun fromSaved(store: Store) = Choices(
        privacy = store.privacyLevel,
        serverUrl = store.gatewayUrl,
        saveSentence = store.saveRiskiestSentence,
        helpImprove = store.helpImproveDetection,
    )

    /** The settings the setup reads and writes: AppPrefs on the phone, a map in tests. */
    interface Store {
        var privacyLevel: PrivacyLevel
        var gatewayUrl: String
        var saveRiskiestSentence: Boolean
        var helpImproveDetection: Boolean
        /** null = never written. */
        var onboardingCompleted: Boolean?
        var onboardingVersion: Int
        /** Any setting saved by an earlier version of the app (or old history kept in settings). */
        fun hasAnySetting(): Boolean
    }

    /**
     * Saves the choices and marks the setup done. The server address is kept as it was for On-device (no server
     * screen) and for "Set up later".
     */
    fun apply(c: Choices, store: Store) {
        store.privacyLevel = c.privacy
        if (c.privacy != PrivacyLevel.ON_DEVICE && c.server != Server.LATER) store.gatewayUrl = c.serverUrl
        store.saveRiskiestSentence = c.saveSentence
        store.helpImproveDetection = c.helpImprove
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

    // ---- wording (the same on iOS)

    fun privacyTitle(p: PrivacyLevel) = when (p) {
        PrivacyLevel.CLOUD -> "Cloud"
        PrivacyLevel.HYBRID -> "Hybrid"
        PrivacyLevel.ON_DEVICE -> "On-device"
    }

    fun privacyTag(p: PrivacyLevel) = when (p) {
        PrivacyLevel.CLOUD -> "Most accurate"
        PrivacyLevel.HYBRID -> "Balanced"
        PrivacyLevel.ON_DEVICE -> "Most private"
    }

    /** What leaves the phone, in one line. */
    fun privacyLine(p: PrivacyLevel) = when (p) {
        PrivacyLevel.CLOUD -> "What the caller says goes to the server."
        PrivacyLevel.HYBRID -> "Only unsure moments go, names removed."
        PrivacyLevel.ON_DEVICE -> "Nothing leaves this phone."
    }

    fun serverLabel(c: Choices): String = when {
        c.privacy == PrivacyLevel.ON_DEVICE -> "Not needed"
        c.server == Server.CONNECTED -> "Connected ✓"
        c.server == Server.CANT_REACH -> "Can't reach"
        c.server == Server.LATER -> "Set up later"
        else -> "Not checked"
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** The "You're all set" summary, label to value. */
    fun summary(c: Choices): List<Pair<String, String>> = listOf(
        "Privacy" to privacyTitle(c.privacy),
        "Server" to serverLabel(c),
        "Save riskiest sentence" to onOff(c.saveSentence),
        "Help improve detection" to onOff(c.helpImprove),
    )

    /** Screen titles and their one line, by step. */
    fun title(s: Step) = when (s) {
        Step.WELCOME -> "Welcome to ScamGuard"
        Step.PRIVACY -> "Choose your privacy"
        Step.SERVER -> "Connect to the server"
        Step.DATA -> "Your data"
        Step.DONE -> "You're all set"
    }

    fun line(s: Step) = when (s) {
        Step.WELCOME -> "ScamGuard warns you during calls if the caller might be a scammer."
        Step.PRIVACY -> "Pick where calls are checked."
        Step.SERVER -> "ScamGuard needs its server to check calls."
        Step.DATA -> "Your call history stays on this phone."
        Step.DONE -> "Change these later in Settings."
    }

    /** One line before the local-network question (iOS asks on Test connection; Android 17+ asks there too). */
    const val NETWORK_HEADS_UP = "Your phone may ask to find devices on your network. Tap Allow."

    /** "Step 2 of 5", for screen readers on the progress dots. */
    fun progress(s: Step, privacy: PrivacyLevel): String {
        val all = steps(privacy)
        return "Step ${all.indexOf(s) + 1} of ${all.size}"
    }
}

/** The guided tour: seven coach marks over real screens (the call steps use a pretend call, nothing is sent). */
object Tour {
    enum class Place { HOME, CALL, DASHBOARD, SETTINGS }

    enum class Target(val id: String, val spoken: String) {
        SCAM_CALL("tour.scamCall", "Simulate scam call button"),
        PICK_SCRIPT("tour.pickScript", "Choose a call script button"),
        RISK_BAR("tour.riskBar", "Risk bar"),
        WARNING_CHIPS("tour.warningChips", "Warning signs"),
        RED_WARNING("tour.redWarning", "Red warning"),
        DASHBOARD_HERO("tour.dashboardHero", "Dashboard summary"),
        PRIVACY_ROW("tour.privacyRow", "Privacy level"),
    }

    data class Step(val target: Target, val place: Place, val title: String, val line: String)

    val steps = listOf(
        Step(Target.SCAM_CALL, Place.HOME, "Try a demo call", "Plays a pretend scam call."),
        Step(Target.PICK_SCRIPT, Place.HOME, "Pick a call", "Choose which pretend call to play."),
        Step(Target.RISK_BAR, Place.CALL, "The risk bar", "Shows how risky the call is."),
        Step(Target.WARNING_CHIPS, Place.CALL, "Warning signs", "Warning signs it heard light up."),
        Step(Target.RED_WARNING, Place.CALL, "Red warning", "Hang up when you see this."),
        Step(Target.DASHBOARD_HERO, Place.DASHBOARD, "Your dashboard", "Your safety at a glance."),
        Step(Target.PRIVACY_ROW, Place.SETTINGS, "Privacy level", "Change privacy anytime."),
    )

    fun progress(index: Int) = "${index + 1} of ${steps.size}"

    fun isLast(index: Int) = index == steps.size - 1

    /** The bubble's main button. */
    fun nextLabel(index: Int) = if (isLast(index)) "Done" else "Next"
}
