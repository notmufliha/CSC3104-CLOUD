package com.example.scamdetect

import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.Onboarding.Launch
import com.example.scamdetect.logic.Onboarding.Server
import com.example.scamdetect.logic.Onboarding.Step
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Tour
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
        override fun hasAnySetting() = saved.keys.any { !it.startsWith("onboarding") }

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

    @Test fun serverStepSkippedForOnDevice() {
        assertEquals(listOf(Step.WELCOME, Step.PRIVACY, Step.SERVER, Step.DATA, Step.DONE), Onboarding.steps(PrivacyLevel.CLOUD))
        assertEquals(listOf(Step.WELCOME, Step.PRIVACY, Step.SERVER, Step.DATA, Step.DONE), Onboarding.steps(PrivacyLevel.HYBRID))
        assertEquals(listOf(Step.WELCOME, Step.PRIVACY, Step.DATA, Step.DONE), Onboarding.steps(PrivacyLevel.ON_DEVICE))
        assertEquals(Step.DATA, Onboarding.next(Step.PRIVACY, PrivacyLevel.ON_DEVICE))
        assertEquals(Step.SERVER, Onboarding.next(Step.PRIVACY, PrivacyLevel.CLOUD))
        assertEquals(Step.PRIVACY, Onboarding.previous(Step.DATA, PrivacyLevel.ON_DEVICE))
        assertNull(Onboarding.previous(Step.WELCOME, PrivacyLevel.CLOUD))
        assertNull(Onboarding.next(Step.DONE, PrivacyLevel.CLOUD))
        assertEquals("Step 3 of 4", Onboarding.progress(Step.DATA, PrivacyLevel.ON_DEVICE))
        assertEquals("Step 3 of 5", Onboarding.progress(Step.SERVER, PrivacyLevel.HYBRID))
    }

    // ---- wording (identical on iOS)

    @Test fun summaryWording() {
        val c = Onboarding.Choices(PrivacyLevel.CLOUD, DEFAULT_URL, Server.CONNECTED, saveSentence = true)
        assertEquals(
            listOf("Privacy" to "Cloud", "Server" to "Connected ✓", "Save riskiest sentence" to "On", "Help improve detection" to "Off"),
            Onboarding.summary(c),
        )
        assertEquals("Can't reach", Onboarding.serverLabel(c.copy(server = Server.CANT_REACH)))
        assertEquals("Set up later", Onboarding.serverLabel(c.copy(server = Server.LATER)))
        assertEquals("Not checked", Onboarding.serverLabel(c.copy(server = Server.NOT_CHECKED)))
        assertEquals("Not needed", Onboarding.serverLabel(c.copy(privacy = PrivacyLevel.ON_DEVICE, server = Server.CONNECTED)))
        assertEquals("ScamGuard warns you during calls if the caller might be a scammer.", Onboarding.line(Step.WELCOME))
        assertEquals("Most private", Onboarding.privacyTag(PrivacyLevel.ON_DEVICE))
    }

    @Test fun tourStepsAreShortAndInOrder() {
        assertEquals(7, Tour.steps.size)
        Tour.steps.forEach { s ->
            assertTrue("title too long: ${s.title}", s.title.split(" ").size <= 4)
            assertFalse(s.line.contains("\n"))
            listOf("BERT", "verdict", "gateway").forEach { w -> assertFalse(s.line.contains(w, ignoreCase = true)) }
        }
        assertEquals(
            listOf(Tour.Place.HOME, Tour.Place.HOME, Tour.Place.CALL, Tour.Place.CALL, Tour.Place.CALL, Tour.Place.DASHBOARD, Tour.Place.SETTINGS),
            Tour.steps.map { it.place },
        )
        assertEquals("2 of 7", Tour.progress(1))
        assertEquals("Next", Tour.nextLabel(5))
        assertEquals("Done", Tour.nextLabel(6))
        assertEquals("Hang up when you see this.", Tour.steps[4].line)
    }
}
