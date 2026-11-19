package com.example.scamdetect

import com.example.scamdetect.logic.AppStyle
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.Onboarding.Path
import com.example.scamdetect.logic.Onboarding.Launch
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
        // Not saved yet: the app style's default, as AppPrefs does.
        override var textSize: String
            get() = saved["text_size"] as String? ?: AppStyle.textSize(AppStyle.simpleAtLaunch(simpleMode))
            set(v) { saved["text_size"] = v }
        override var readAloud: Boolean
            get() = saved["read_aloud"] as Boolean? ?: AppStyle.readAloud(AppStyle.simpleAtLaunch(simpleMode))
            set(v) { saved["read_aloud"] = v }
        override var textSizeChosen: Boolean
            get() = saved["text_size_chosen"] as Boolean? ?: false
            set(v) { saved["text_size_chosen"] = v }
        override var readAloudChosen: Boolean
            get() = saved["read_aloud_chosen"] as Boolean? ?: false
            set(v) { saved["read_aloud_chosen"] = v }
        override var simpleMode: Boolean?
            get() = saved["simple_mode"] as Boolean?
            set(v) { if (v == null) saved.remove("simple_mode") else saved["simple_mode"] = v }
        override var appStyleTipShown: Boolean
            get() = saved["app_style_tip_shown"] as Boolean? ?: false
            set(v) { saved["app_style_tip_shown"] = v }
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
        Onboarding.apply(Onboarding.defaults(), s)
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
        val s = FakeStore(mutableMapOf("gateway_url" to "http://mac:8080/"))
        Onboarding.apply(Onboarding.Choices(PrivacyLevel.HYBRID, saveSentence = true, helpImprove = true), s)
        val replay = Onboarding.fromSaved(s)
        assertEquals(PrivacyLevel.HYBRID, replay.privacy)
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
        Onboarding.apply(Onboarding.defaults(), s)
        assertEquals(PrivacyLevel.CLOUD, s.privacyLevel)
        assertEquals(DEFAULT_URL, s.gatewayUrl)
        assertFalse(s.saveRiskiestSentence)
        assertFalse(s.helpImproveDetection)
        assertEquals(Launch.DONE, s.launch())
    }

    @Test fun choicesAreSaved() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.Choices(PrivacyLevel.HYBRID, saveSentence = true, helpImprove = false), s)
        assertEquals(PrivacyLevel.HYBRID, s.privacyLevel)
        assertTrue(s.saveRiskiestSentence)
        assertFalse(s.helpImproveDetection)
    }

    /** Developer view: the setup has no server screen and never changes the saved (or default) address. */
    @Test fun setupKeepsTheSavedAddressSilently() {
        for (level in PrivacyLevel.entries) for (path in Path.entries) {
            val s = FakeStore(mutableMapOf("gateway_url" to "http://old/"))
            Onboarding.apply(c(path, level), s)
            assertEquals("http://old/", s.gatewayUrl)
            assertEquals(Launch.DONE, s.launch())
            assertFalse("$path $level", Onboarding.steps(c(path, level)).any { it.name.contains("SERVER") })
        }
        val fresh = FakeStore()
        Onboarding.apply(Onboarding.defaults(), fresh)
        assertEquals(DEFAULT_URL, fresh.gatewayUrl)
    }

    /** Without the server screen every way through still reaches its last screen and finishes. */
    @Test fun setupWithoutTheServerScreenStillCompletes() {
        for (path in Path.entries) for (level in PrivacyLevel.entries) {
            val choices = c(path, level)
            var step: Step? = Step.WHO
            var last = step
            var guard = 0
            while (step != null && guard++ < 20) { last = step; step = Onboarding.next(step, choices) }
            assertEquals("$path", if (path == Path.SIMPLE) Step.RECOMMENDED else if (path == Path.HELPER) Step.HANDOVER else Step.DONE, last)
            val s = FakeStore()
            Onboarding.apply(choices, s)
            assertEquals(Launch.DONE, s.launch())
        }
    }

    // ---- "Who will use this phone?" and the three ways through

    private fun c(path: Path, privacy: PrivacyLevel = PrivacyLevel.CLOUD) =
        Onboarding.choose(Onboarding.defaults().copy(privacy = privacy), path)

    @Test fun theFirstQuestionComesFirstWithNothingPicked() {
        val start = Onboarding.defaults()
        assertNull(start.path)
        assertFalse(start.simple)
        assertEquals(listOf(Step.WHO), Onboarding.steps(start))
        for (p in Path.entries) assertEquals(Step.WHO, Onboarding.steps(c(p)).first())
    }

    @Test fun meIsStandardWithTheFullSetup() {
        assertEquals(listOf(Step.WHO, Step.WELCOME, Step.PRIVACY, Step.DATA, Step.DONE), Onboarding.steps(c(Path.STANDARD)))
        assertEquals(listOf(Step.WHO, Step.WELCOME, Step.PRIVACY, Step.DATA, Step.DONE), Onboarding.steps(c(Path.STANDARD, PrivacyLevel.ON_DEVICE)))
        val s = FakeStore()
        Onboarding.apply(c(Path.STANDARD, PrivacyLevel.HYBRID), s)
        assertEquals(false, s.simpleMode)
        assertEquals("normal", s.textSize)
        assertFalse("read aloud is off in Standard mode", s.readAloud)
        assertEquals(PrivacyLevel.HYBRID, s.privacyLevel)
        assertFalse(Tour.steps(AppStyle.simpleAtLaunch(s.simpleMode)) == Tour.simpleSteps)   // the 7-step tour
    }

    @Test fun meButSimplerIsSimpleModeWithThreeScreensAndNoFamilySetup() {
        assertEquals(listOf(Step.WHO, Step.WELCOME, Step.RECOMMENDED), Onboarding.steps(c(Path.SIMPLE)))
        assertEquals(3 to 3, Onboarding.progress(Step.RECOMMENDED, c(Path.SIMPLE)))
        assertFalse(Step.FAMILY in Onboarding.steps(c(Path.SIMPLE)) || Step.PIN in Onboarding.steps(c(Path.SIMPLE)))
        val s = FakeStore()
        Onboarding.apply(Onboarding.recommended(c(Path.SIMPLE, PrivacyLevel.ON_DEVICE)), s)
        assertEquals(true, s.simpleMode)
        assertEquals("large", s.textSize)
        assertTrue(s.readAloud)
        assertEquals(PrivacyLevel.CLOUD, s.privacyLevel)
        assertEquals("", s.familyPhone)
        // "Choose myself": the detailed screens, still no family setup
        assertEquals(listOf(Step.WHO, Step.WELCOME, Step.RECOMMENDED, Step.PRIVACY, Step.DATA, Step.DONE),
            Onboarding.steps(c(Path.CUSTOM, PrivacyLevel.ON_DEVICE)))
        assertTrue(c(Path.CUSTOM).simple)
    }

    @Test fun familyPathGoesThroughContactAndPinAndEndsSimple() {
        assertEquals(listOf(Step.WHO, Step.HELPER, Step.FAMILY, Step.PIN, Step.HANDOVER), Onboarding.steps(c(Path.HELPER)))
        assertEquals(Step.FAMILY, Onboarding.next(Step.HELPER, c(Path.HELPER)))
        assertEquals(Step.HANDOVER, Onboarding.next(Step.PIN, c(Path.HELPER)))
        assertNull(Onboarding.next(Step.HANDOVER, c(Path.HELPER)))
        val s = FakeStore()
        Onboarding.apply(c(Path.HELPER).copy(textSize = "xlarge", textSizeChosen = true, readAloud = false, readAloudChosen = true,
            familyName = " Mei Ling ", familyPhone = " 9123 4567 ", pin = "2468"), s)
        assertEquals(true, s.simpleMode)
        assertEquals("xlarge", s.textSize)
        assertFalse(s.readAloud)
        assertEquals("Mei Ling", s.familyName)
        assertEquals("9123 4567", s.familyPhone)
        assertFalse("the PIN is never kept with the settings", s.saved.values.any { it == "2468" })
        assertEquals(Tour.simpleSteps, Tour.steps(AppStyle.simpleAtLaunch(s.simpleMode)))
    }

    @Test fun skipIsStandardWithRecommendedSettings() {
        val s = FakeStore()
        Onboarding.apply(Onboarding.defaults(), s)             // Skip on "Who will use this phone?"
        assertEquals(false, s.simpleMode)
        assertEquals("normal", s.textSize)
        assertFalse(s.readAloud)
        assertEquals(PrivacyLevel.CLOUD, s.privacyLevel)
        assertFalse(s.saveRiskiestSentence || s.helpImproveDetection)
        assertFalse("a new user gets no tip about the choice they just made", AppStyle.showTip(false, s.appStyleTipShown))
    }

    @Test fun changingTheAnswerResetsOnlyTheDefaults() {
        val simpler = c(Path.SIMPLE)
        assertEquals("large", simpler.textSize)
        assertTrue(simpler.readAloud)
        val back = Onboarding.choose(simpler, Path.STANDARD)
        assertEquals("normal", back.textSize)
        assertFalse(back.readAloud)
        // a size the user picked stays
        val picked = Onboarding.choose(simpler.copy(textSize = "xlarge", textSizeChosen = true), Path.STANDARD)
        assertEquals("xlarge", picked.textSize)
    }

    @Test fun runSetupAgainStartsAtTheQuestionAndKeepsTheRest() {
        val s = FakeStore()
        Onboarding.apply(c(Path.HELPER, PrivacyLevel.HYBRID).copy(familyName = "Mei Ling", familyPhone = "91234567"), s)
        val replay = Onboarding.fromSaved(s)
        assertNull("nothing picked", replay.path)
        assertEquals(listOf(Step.WHO), Onboarding.steps(replay))
        assertEquals("91234567", replay.familyPhone)
        assertEquals(PrivacyLevel.HYBRID, replay.privacy)
    }

    // ---- switching later (Settings → App style)

    @Test fun switchingKeepsDataAndOnlyChangesDefaultsTheUserLeftAlone() {
        val s = FakeStore()
        Onboarding.apply(c(Path.HELPER, PrivacyLevel.HYBRID).copy(familyName = "Mei Ling", familyPhone = "91234567", saveSentence = true), s)
        s.textSize = "xlarge"; s.textSizeChosen = true            // the user picked Extra large in Settings
        AppStyle.switchTo(simple = false, s)
        assertEquals(false, s.simpleMode)
        assertEquals("xlarge", s.textSize)
        assertFalse("read aloud follows Standard (not set by the user)", s.readAloud)
        assertEquals("91234567", s.familyPhone)
        assertEquals(PrivacyLevel.HYBRID, s.privacyLevel)
        assertTrue(s.saveRiskiestSentence)
        assertEquals(Launch.DONE, s.launch())
        AppStyle.switchTo(simple = true, s)
        assertTrue(s.readAloud)
        assertEquals("xlarge", s.textSize)
    }

    @Test fun leavingSimpleModeAsksForThePinWhenSet() {
        assertTrue(AppStyle.needsPin(fromSimple = true, toSimple = false, pinSet = true))
        assertFalse(AppStyle.needsPin(fromSimple = true, toSimple = false, pinSet = false))
        assertFalse("turning Simple on never asks", AppStyle.needsPin(fromSimple = false, toSimple = true, pinSet = true))
        assertFalse(AppStyle.needsPin(fromSimple = true, toSimple = true, pinSet = true))
    }

    // ---- existing installs

    @Test fun upgradeKeepsTheModeAndShowsTheTipOnce() {
        // set up before Simple mode existed: Standard, phone-size text, tip once
        val old = FakeStore(mutableMapOf("privacy_level" to "hybrid"))
        assertEquals(Launch.EXISTING_USER, old.launch())
        Onboarding.markCompleted(old)
        assertFalse(AppStyle.simpleAtLaunch(old.simpleMode))
        assertEquals("normal", old.textSize)
        assertTrue(AppStyle.showTip(AppStyle.simpleAtLaunch(old.simpleMode), old.appStyleTipShown))
        old.appStyleTipShown = true                                         // "Got it"
        assertFalse(AppStyle.showTip(false, old.appStyleTipShown))
        // already in Simple mode: stays Simple, no tip, no new question
        val simple = FakeStore(mutableMapOf("simple_mode" to true, "onboarding_completed" to true, "onboarding_version" to 1))
        assertEquals(Launch.DONE, simple.launch())
        assertTrue(AppStyle.simpleAtLaunch(simple.simpleMode))
        assertEquals("large", simple.textSize)
        assertFalse(AppStyle.showTip(true, simple.appStyleTipShown))
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
