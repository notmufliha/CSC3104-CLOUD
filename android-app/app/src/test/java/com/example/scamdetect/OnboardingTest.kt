package com.example.scamdetect

import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.Onboarding.Launch
import com.example.scamdetect.logic.Onboarding.Server
import com.example.scamdetect.logic.Onboarding.Step
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as ios-app/ScamGuardTests/OnboardingTests.swift. */
class OnboardingTest {
    /** AppPrefs stand-in: a map, so "never written" can be told apart from false. */
    private class FakeStore(val saved: MutableMap<String, Any> = mutableMapOf()) : Onboarding.Store {
        override var privacyLevel: PrivacyLevel
            get() = PrivacyLevel.fromId(saved["privacy_level"] as String?)
            set(v) { saved["privacy_level"] = v.id }
        override var gatewayUrl: String
            get() = saved["gateway_url"] as String? ?: DEFAULT_URL
            set(v) { saved["gateway_url"] = v }
        override var saveRiskiestSentence: Boolean
            get() = saved["save_riskiest_sentence"] as Boolean? ?: false
            set(v) { saved["save_riskiest_sentence"] = v }
        override var helpImproveDetection: Boolean
            get() = saved["help_improve_detection"] as Boolean? ?: false
            set(v) { saved["help_improve_detection"] = v }
        override var onboardingCompleted: Boolean?
            get() = saved["onboarding_completed"] as Boolean?
            set(v) { if (v == null) saved.remove("onboarding_completed") else saved["onboarding_completed"] = v }
        override var onboardingVersion: Int
            get() = saved["onboarding_version"] as Int? ?: 0
            set(v) { saved["onboarding_version"] = v }
        override var textSize: String
            get() = saved["text_size"] as String? ?: "large"
            set(v) { saved["text_size"] = v }
        override var readAloud: Boolean
            get() = saved["read_aloud"] as Boolean? ?: true
            set(v) { saved["read_aloud"] = v }
        override var simpleMode: Boolean?
            get() = saved["simple_mode"] as Boolean?
            set(v) { if (v == null) saved.remove("simple_mode") else saved["simple_mode"] = v }
        override var simpleModeOffered: Boolean
            get() = saved["simple_mode_offered"] as Boolean? ?: false
            set(v) { saved["simple_mode_offered"] = v }
        override var familyName: String
            get() = saved["family_name"] as String? ?: ""
            set(v) { saved["family_name"] = v }
        override var familyPhone: String
            get() = saved["family_phone"] as String? ?: ""
            set(v) { saved["family_phone"] = v }
        /** Only keys an earlier version could have saved (as AppPrefs.EXISTING_KEYS). */
        override fun hasAnySetting() = saved.keys.any { it in listOf("gateway_url", "save_riskiest_sentence", "privacy_level", "help_improve_detection") }

        fun launch(hasHistory: Boolean = false) = Onboarding.atLaunch(onboardingCompleted, hasAnySetting(), hasHistory)
    }

    companion object { const val DEFAULT_URL = "http://10.0.2.2:8080/" }

    // ---- when the setup shows

    @Test fun firstLaunchShowsSetup() {
        assertEquals(Launch.SHOW_SETUP, FakeStore().launch())
    }

    @Test fun completedNeverShowsAgain() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.defaults(DEFAULT_URL), s)
        assertEquals(true, s.onboardingCompleted)
        assertEquals(Onboarding.VERSION, s.onboardingVersion)
        assertEquals(Launch.DONE, s.launch())
        assertEquals(Launch.DONE, s.launch(hasHistory = true))
    }

    @Test fun existingUserUpgradeSkipsSetup() {
        // an install from before the setup: settings saved, no flag
        assertEquals(Launch.EXISTING_USER, FakeStore(mutableMapOf("privacy_level" to "hybrid")).launch())
        assertEquals(Launch.EXISTING_USER, FakeStore(mutableMapOf("gateway_url" to "http://x/")).launch())
        // no settings changed, but calls in the history
        assertEquals(Launch.EXISTING_USER, FakeStore().launch(hasHistory = true))
        // marked done, then it stays done
        val s = FakeStore(mutableMapOf("save_riskiest_sentence" to true))
        Onboarding.markCompleted(s)
        assertEquals(Launch.DONE, s.launch())
    }

    @Test fun resetShowsSetupEvenWithSettings() {
        val s = FakeStore(mutableMapOf("privacy_level" to "on_device"))
        Onboarding.markCompleted(s)
        Onboarding.reset(s)
        assertEquals(false, s.onboardingCompleted)
        assertEquals(Launch.SHOW_SETUP, s.launch(hasHistory = true))
    }

    @Test fun replayStartsFromSavedSettingsAndSkipKeepsThem() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.Choices(PrivacyLevel.HYBRID, "http://mac:8080/", Server.CONNECTED, true, true), s)
        val replay = Onboarding.fromSaved(s)
        assertEquals(PrivacyLevel.HYBRID, replay.privacy)
        assertEquals("http://mac:8080/", replay.serverUrl)
        assertTrue(replay.saveSentence && replay.helpImprove)
        Onboarding.apply(replay, s)   // Skip right away
        assertEquals(PrivacyLevel.HYBRID, s.privacyLevel)
        assertEquals("http://mac:8080/", s.gatewayUrl)
        assertTrue(s.saveRiskiestSentence && s.helpImproveDetection)
        assertEquals(Launch.DONE, s.launch())
    }

    // ---- what it saves

    @Test fun skipOnFirstLaunchSavesDefaults() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.defaults(s.gatewayUrl), s)
        assertEquals(PrivacyLevel.CLOUD, s.privacyLevel)
        assertEquals(DEFAULT_URL, s.gatewayUrl)
        assertFalse(s.saveRiskiestSentence)
        assertFalse(s.helpImproveDetection)
        assertEquals(Launch.DONE, s.launch())
    }

    @Test fun choicesAreSaved() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.Choices(PrivacyLevel.HYBRID, "https://10.0.2.2:8443/api/", Server.CONNECTED, true, false), s)
        assertEquals(PrivacyLevel.HYBRID, s.privacyLevel)
        assertEquals("https://10.0.2.2:8443/api/", s.gatewayUrl)
        assertTrue(s.saveRiskiestSentence)
        assertFalse(s.helpImproveDetection)
    }

    @Test fun setUpLaterAndOnDeviceKeepTheOldAddress() {
        val later = FakeStore(mutableMapOf("gateway_url" to "http://old/"))
        Onboarding.apply(Onboarding.Choices(PrivacyLevel.CLOUD, "http://typed-but-not-used/", Server.LATER), later)
        assertEquals("http://old/", later.gatewayUrl)
        val onDevice = FakeStore(mutableMapOf("gateway_url" to "http://old/"))
        Onboarding.apply(Onboarding.Choices(PrivacyLevel.ON_DEVICE, "http://other/"), onDevice)
        assertEquals("http://old/", onDevice.gatewayUrl)
        assertEquals(PrivacyLevel.ON_DEVICE, onDevice.privacyLevel)
    }

    // ---- which screens

    private fun c(path: Onboarding.Path, privacy: PrivacyLevel = PrivacyLevel.CLOUD) =
        Onboarding.defaults(DEFAULT_URL).copy(path = path, privacy = privacy)

    @Test fun simpleSetupIsThreeScreens() {
        assertEquals(listOf(Step.WELCOME, Step.RECOMMENDED, Step.FAMILY), Onboarding.steps(c(Onboarding.Path.SIMPLE)))
        assertNull(Onboarding.next(Step.FAMILY, c(Onboarding.Path.SIMPLE)))
        assertEquals(2 to 3, Onboarding.progress(Step.RECOMMENDED, c(Onboarding.Path.SIMPLE)))
    }

    @Test fun chooseMyselfKeepsTheFullChoicesAndSkipsServerForOnDevice() {
        assertEquals(listOf(Step.WELCOME, Step.RECOMMENDED, Step.PRIVACY, Step.SERVER, Step.DATA, Step.FAMILY, Step.DONE),
            Onboarding.steps(c(Onboarding.Path.CUSTOM)))
        assertEquals(listOf(Step.WELCOME, Step.RECOMMENDED, Step.PRIVACY, Step.DATA, Step.FAMILY, Step.DONE),
            Onboarding.steps(c(Onboarding.Path.CUSTOM, PrivacyLevel.ON_DEVICE)))
        assertEquals(Step.DATA, Onboarding.next(Step.PRIVACY, c(Onboarding.Path.CUSTOM, PrivacyLevel.ON_DEVICE)))
        assertEquals(Step.SERVER, Onboarding.next(Step.PRIVACY, c(Onboarding.Path.CUSTOM)))
        assertNull(Onboarding.previous(Step.WELCOME, c(Onboarding.Path.CUSTOM)))
        assertEquals(4 to 6, Onboarding.progress(Step.DATA, c(Onboarding.Path.CUSTOM, PrivacyLevel.ON_DEVICE)))
    }

    @Test fun familyHelperSetup() {
        assertEquals(listOf(Step.WELCOME, Step.HELPER, Step.FAMILY, Step.PIN, Step.DONE), Onboarding.steps(c(Onboarding.Path.HELPER)))
        val s = FakeStore()
        Onboarding.apply(c(Onboarding.Path.HELPER).copy(textSize = "xlarge", simple = true, readAloud = false,
            familyName = " Mei Ling ", familyPhone = " 9123 4567 ", pin = "2468"), s)
        assertEquals("xlarge", s.textSize)
        assertEquals(true, s.simpleMode)
        assertFalse(s.readAloud)
        assertEquals("Mei Ling", s.familyName)
        assertEquals("9123 4567", s.familyPhone)
        assertFalse("the PIN is never kept with the settings", s.saved.values.any { it == "2468" })
    }

    @Test fun recommendedSettings() {
        val r = Onboarding.recommended(c(Onboarding.Path.CUSTOM, PrivacyLevel.ON_DEVICE).copy(saveSentence = true, helpImprove = true, readAloud = false))
        assertEquals(PrivacyLevel.CLOUD, r.privacy)
        assertFalse(r.saveSentence || r.helpImprove)
        assertTrue(r.readAloud)
        assertEquals(Onboarding.Path.SIMPLE, r.path)
    }

    // ---- Simple mode: on for new installs, never forced on existing ones

    @Test fun simpleModeDefaults() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.defaults(DEFAULT_URL), s)          // a new install (finished or skipped)
        assertEquals(true, s.simpleMode)
        assertTrue(Onboarding.simpleModeAtLaunch(s.simpleMode))
        assertFalse(Onboarding.offerSimpleMode(s.simpleMode, s.simpleModeOffered))
        assertTrue(Onboarding.defaults(DEFAULT_URL).simple)
        assertTrue(Onboarding.defaults(DEFAULT_URL).readAloud)
        assertEquals("large", Onboarding.defaults(DEFAULT_URL).textSize)
    }

    @Test fun existingUsersKeepTheirModeAndAreOfferedSimpleModeOnce() {
        val s = FakeStore(mutableMapOf("privacy_level" to "hybrid"))       // installed before Simple mode
        assertEquals(Launch.EXISTING_USER, s.launch())
        Onboarding.markCompleted(s)
        assertNull(s.simpleMode)
        assertFalse("keeps the full app", Onboarding.simpleModeAtLaunch(s.simpleMode))
        assertTrue("offered once", Onboarding.offerSimpleMode(s.simpleMode, s.simpleModeOffered))
        s.simpleModeOffered = true                                           // "No, thanks"
        assertFalse(Onboarding.offerSimpleMode(s.simpleMode, s.simpleModeOffered))
        assertFalse(Onboarding.simpleModeAtLaunch(s.simpleMode))
        assertEquals(PrivacyLevel.HYBRID, s.privacyLevel)
        // a user who already chose keeps the choice
        assertFalse(Onboarding.offerSimpleMode(false, false))
        assertFalse(Onboarding.simpleModeAtLaunch(false))
    }

    // ---- wording (identical on iOS)

    @Test fun setupWording() {
        TestStrings.install()
        assertEquals("ScamGuard warns you during calls if the caller might be a scammer.", TestStrings.get(R.string.setup_welcome_line))
        assertEquals("Your calls stay private. We never record your voice.", TestStrings.get(R.string.privacy_promise))
        assertEquals("Most private", TestStrings.get(R.string.privacy_tag_on_device))
        assertEquals("Step 2 of 3", TestStrings.get(R.string.setup_step, "2", "3"))
    }

    @Test fun toursAreShortAndInOrder() {
        assertEquals(3, Tour.simpleSteps.size)
        assertEquals(listOf(Tour.Target.PRACTICE_CALL, Tour.Target.RED_WARNING, Tour.Target.WARNING_BUTTONS), Tour.simpleSteps.map { it.target })
        assertEquals(listOf(Tour.Place.HOME, Tour.Place.CALL, Tour.Place.CALL), Tour.simpleSteps.map { it.place })
        assertEquals(7, Tour.fullSteps.size)
        assertEquals(
            listOf(Tour.Place.HOME, Tour.Place.HOME, Tour.Place.CALL, Tour.Place.CALL, Tour.Place.CALL, Tour.Place.DASHBOARD, Tour.Place.SETTINGS),
            Tour.fullSteps.map { it.place },
        )
        (Tour.simpleSteps + Tour.fullSteps).forEach { s ->
            val title = TestStrings.get(s.title)
            val line = TestStrings.get(s.line)
            assertTrue("title too long: $title", title.split(" ").size <= 4)
            assertFalse(line.contains("\n"))
            listOf("BERT", "verdict", "gateway", "server").forEach { w -> assertFalse(line, line.contains(w, ignoreCase = true)) }
        }
        assertTrue(Tour.isLast(2, simple = true))
        assertFalse(Tour.isLast(2, simple = false))
        assertEquals("2 of 3", TestStrings.get(R.string.tour_progress, "2", "3"))
        assertEquals("Hang up when you see this.", TestStrings.get(Tour.fullSteps[4].line))
    }
}
