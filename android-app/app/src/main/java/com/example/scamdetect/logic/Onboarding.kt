package com.example.scamdetect.logic

import com.example.scamdetect.R

/**
 * First-launch setup: when it shows, which screens it has, and what it saves. Pure (no Android), kept in step with
 * ios-app/ScamGuard/Logic/Onboarding.swift; both apps test the same rules and wording.
 */
object Onboarding {
    /** Bump when the setup gains a screen that existing users should see. Saved with the "completed" flag. */
    const val VERSION = 2

    enum class Step { WHO, WELCOME, RECOMMENDED, PRIVACY, SERVER, DATA, HELPER, FAMILY, PIN, HANDOVER, DONE }

    /**
     * Which way through the setup, from the first screen ("Who will use this phone?"); null = not chosen yet.
     * STANDARD ("Me"): the full app and its 5-screen setup. SIMPLE ("Me, but simpler"): Simple mode, Welcome and
     * "Use recommended settings"; CUSTOM is its "Choose myself". HELPER ("An older family member"): Simple mode set
     * up by a helper: text size and read-aloud, the family contact, an optional PIN, then "Hand the phone to ...".
     */
    enum class Path { STANDARD, SIMPLE, CUSTOM, HELPER }

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
    fun steps(c: Choices): List<Step> {
        val server = Step.SERVER.takeIf { c.privacy != PrivacyLevel.ON_DEVICE }
        return when (c.path) {
            null -> listOf(Step.WHO)
            Path.STANDARD -> listOfNotNull(Step.WHO, Step.WELCOME, Step.PRIVACY, server, Step.DATA, Step.DONE)
            Path.SIMPLE -> listOf(Step.WHO, Step.WELCOME, Step.RECOMMENDED)
            Path.CUSTOM -> listOfNotNull(Step.WHO, Step.WELCOME, Step.RECOMMENDED, Step.PRIVACY, server, Step.DATA, Step.DONE)
            Path.HELPER -> listOf(Step.WHO, Step.HELPER, Step.FAMILY, Step.PIN, Step.HANDOVER)
        }
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
        /** null until "Who will use this phone?" is answered (nothing is pre-selected). */
        val path: Path? = null,
        val textSize: String = AppStyle.textSize(simple = false),
        val readAloud: Boolean = AppStyle.readAloud(simple = false),
        /** The user picked the text size / read-aloud themselves: switching modes then keeps it. */
        val textSizeChosen: Boolean = false,
        val readAloudChosen: Boolean = false,
        val familyName: String = "",
        val familyPhone: String = "",
        /** A 4-digit PIN for Advanced settings and "For family" (helper setup only); "" = none. */
        val pin: String = "",
    ) {
        /** Simple mode: every way except "Me" (and Skip on the first screen). */
        val simple: Boolean get() = path != null && path != Path.STANDARD
    }

    /** What Skip saves on a first launch: Standard mode, recommended settings (Cloud, both data switches off). */
    fun defaults(currentUrl: String) = Choices(serverUrl = currentUrl)

    /** An answer to "Who will use this phone?": the way through, and that mode's text size and read-aloud. */
    fun choose(c: Choices, path: Path): Choices {
        val simple = path != Path.STANDARD
        return c.copy(
            path = path,
            textSize = if (c.textSizeChosen) c.textSize else AppStyle.textSize(simple),
            readAloud = if (c.readAloudChosen) c.readAloud else AppStyle.readAloud(simple),
        )
    }

    /** Skip before answering the first question = Standard mode. */
    fun answered(c: Choices): Choices = if (c.path == null) choose(c, Path.STANDARD) else c

    /** "Run setup again" starts from what is saved now (Skip keeps it), at the first question, nothing picked. */
    fun fromSaved(store: Store) = Choices(
        privacy = store.privacyLevel,
        serverUrl = store.gatewayUrl,
        saveSentence = store.saveRiskiestSentence,
        helpImprove = store.helpImproveDetection,
        textSize = store.textSize,
        readAloud = store.readAloud,
        textSizeChosen = store.textSizeChosen,
        readAloudChosen = store.readAloudChosen,
        familyName = store.familyName,
        familyPhone = store.familyPhone,
    )

    /** "Use recommended settings" (Simple mode): the most accurate checking, nothing kept, warnings read aloud. */
    fun recommended(c: Choices) = c.copy(privacy = PrivacyLevel.CLOUD, saveSentence = false, helpImprove = false,
        readAloud = if (c.readAloudChosen) c.readAloud else true, path = Path.SIMPLE)

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
        var textSizeChosen: Boolean
        var readAloudChosen: Boolean
        /** null = never written (an install from before Simple mode: Standard). */
        var simpleMode: Boolean?
        /** The one-time "Try Simple mode in Settings → App style" tip on Home was shown and closed. */
        var appStyleTipShown: Boolean
        var familyName: String
        var familyPhone: String
        /** Any setting saved by an earlier version of the app (or old history kept in settings). */
        fun hasAnySetting(): Boolean
    }

    /**
     * Saves the choices and marks the setup done. The server address is kept as it was for On-device (no server
     * screen) and for "Set up later". The PIN is saved separately (secure storage), by the caller.
     */
    fun apply(choices: Choices, store: Store) {
        val c = answered(choices)
        store.privacyLevel = c.privacy
        if (c.privacy != PrivacyLevel.ON_DEVICE && c.server != Server.LATER) store.gatewayUrl = c.serverUrl
        store.saveRiskiestSentence = c.saveSentence
        store.helpImproveDetection = c.helpImprove
        store.simpleMode = c.simple
        store.textSize = c.textSize
        store.readAloud = c.readAloud
        store.textSizeChosen = c.textSizeChosen
        store.readAloudChosen = c.readAloudChosen
        store.appStyleTipShown = true            // they just chose: no tip about it
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

    /** "Step 2 of 5", for screen readers on the progress dots: (index, count). */
    fun progress(s: Step, c: Choices): Pair<Int, Int> = steps(c).let { all -> (all.indexOf(s) + 1) to all.size }
}

/**
 * The two app styles. Standard: the full app (dashboard, insights, history, all settings), the phone's own text size,
 * warnings not read aloud. Simple: Simple Home, "For family", short settings + Advanced, large text, read aloud.
 * Both get the same red warning, "Be careful" banner and plain wording. Same rules on iOS (Logic/Onboarding.swift).
 */
object AppStyle {
    fun textSize(simple: Boolean): String =
        if (simple) com.example.scamdetect.data.AppPrefs.TEXT_LARGE else com.example.scamdetect.data.AppPrefs.TEXT_NORMAL

    fun readAloud(simple: Boolean): Boolean = simple

    /** Saved mode; nothing saved = an install from before Simple mode, which keeps the full app. */
    fun simpleAtLaunch(saved: Boolean?): Boolean = saved ?: false

    /**
     * Settings → App style. Keeps the history, settings, contact and PIN; only the layout changes, and the text size
     * and read-aloud follow the new style unless the user set them.
     */
    fun switchTo(simple: Boolean, store: Onboarding.Store) {
        store.simpleMode = simple
        if (!store.textSizeChosen) store.textSize = textSize(simple)
        if (!store.readAloudChosen) store.readAloud = readAloud(simple)
        store.appStyleTipShown = true
    }

    /** Leaving Simple mode needs the PIN when one is set, so a senior can't switch by accident. */
    fun needsPin(fromSimple: Boolean, toSimple: Boolean, pinSet: Boolean): Boolean = fromSimple && !toSimple && pinSet

    /** The one-time Home tip for users who were set up before the "Who will use this phone?" question. */
    fun showTip(simple: Boolean, tipShown: Boolean): Boolean = !simple && !tipShown
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
