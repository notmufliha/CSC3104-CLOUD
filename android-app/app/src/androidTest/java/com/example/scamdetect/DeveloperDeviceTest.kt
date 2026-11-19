package com.example.scamdetect

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.data.AppReset
import com.example.scamdetect.data.CallHistory
import com.example.scamdetect.data.Dev
import com.example.scamdetect.data.ModelUpdater
import com.example.scamdetect.data.PinStore
import com.example.scamdetect.data.db.CallDatabase
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ImplicitLabel
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.logic.DevLine
import com.example.scamdetect.logic.DevSession
import com.example.scamdetect.logic.Diagnostics
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.PlaybackSpeed
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.ui.CallViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Developer view on a device, with the app's real settings file, database, PIN store and model folder.
 * NOTE: these tests run in the app's own data folder, so they reset the app on this device (as "Reset app" does).
 */
@RunWith(AndroidJUnit4::class)
class DeveloperDeviceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sp get() = app.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
    private val prefs = AppPrefs(app)
    private val history = CallHistory(app, prefs)
    private val db = CallDatabase.get(app)
    private val pins = PinStore(app)

    private fun call(id: String) = CallRecord(
        id = id, startedAt = 1_000, endedAt = 61_000, finalVerdict = "SCAM", liveVerdict = "SCAM", topReason = "OTP",
        callScore = 0.8f, signalsFired = listOf("money"), review = "bert", riskiestSentence = null,
    )

    private suspend fun counts() = listOf(
        db.calls().calls().first().size, db.calls().pointCount(), db.labels().labels().size, db.labels().sentenceCount(),
    )

    /** Every file the app keeps (name, size), except the instrumentation's own and the test runner's. */
    private fun files(): Map<String, Long> =
        File(app.applicationInfo.dataDir).walkTopDown().filter { it.isFile }
            .filterNot { it.path.contains("/cache/") || it.path.contains("/code_cache/") || it.name.endsWith("-journal") }
            .associate { it.path to it.length() }

    @Before @After fun clean() = runBlocking {
        AppReset.clearAll(app, history, prefs, pins)
        db.deleteHistory()
    }

    @Test fun resetAppToFirstLaunchEmptiesEverything() = runBlocking {
        // a used phone: settings, history (all 4 tables), a PIN, a downloaded model, logs in memory
        prefs.privacyLevel = PrivacyLevel.HYBRID
        prefs.gatewayUrl = "http://192.168.1.23:8080/"
        prefs.familyPhone = "91234567"
        prefs.simpleMode = true
        prefs.developerUnlocked = true
        Onboarding.markCompleted(prefs)
        history.save(call("c1"), listOf(ScorePoint("c1", 1, 1f, 0.8f, 0.1f, 0.9f, 0.2f)))
        db.labels().insertLabel(ImplicitLabel(callId = "c1", label = 1, source = "hung_up", time = 5))
        db.labels().insertSentences(listOf(com.example.scamdetect.data.db.LabelSentence("c1", 1, "masked")))
        pins.set("2468")
        File(ModelUpdater.dir(app), "urgency_en.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        Dev.log.recording = true
        Dev.log.add(1, "GET", "/healthz", 200, 3, "", "{}")
        assertEquals(listOf(1, 1, 1, 1), counts())

        AppReset.clearAll(app, history, prefs, pins)

        assertTrue("prefs: ${sp.all}", sp.all.isEmpty())
        assertEquals(listOf(0, 0, 0, 0), counts())
        assertFalse(pins.isSet.first())
        assertFalse(ModelUpdater.dir(app).exists())
        assertTrue(Dev.log.entries().isEmpty())
        assertFalse(Dev.log.recording)
        // bundled models and scripts are untouched
        assertTrue(app.assets.list("models")!!.contains("urgency_en.json"))
        assertTrue(app.assets.list("scripts")!!.isNotEmpty())
        // the next start is a first launch
        assertEquals(Onboarding.Launch.SHOW_SETUP, Onboarding.atLaunch(prefs.onboardingCompleted, prefs.hasAnySetting(), hasHistory = false))
    }

    @Test fun resetAppLandsOnTheFirstSetupScreen() {
        prefs.developerUnlocked = true
        Onboarding.markCompleted(prefs)
        runBlocking { history.save(call("c2"), emptyList()) }
        lateinit var vm: CallViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = CallViewModel(app) }
        waitFor { vm.gate.value == CallViewModel.Gate.APP }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.resetApp() }
        waitFor { vm.gate.value == CallViewModel.Gate.SETUP }
        assertEquals(Onboarding.Step.WHO, vm.setupStep.value)
        assertEquals(null, vm.setupChoices.value.path)
        assertFalse(vm.devUnlocked.value)
        assertTrue(runBlocking { counts() } == listOf(0, 0, 0, 0))
        assertTrue(sp.all.isEmpty())
    }

    @Test fun resetSetupOnlyKeepsHistoryAndSettings() {
        prefs.developerUnlocked = true
        prefs.privacyLevel = PrivacyLevel.HYBRID
        Onboarding.markCompleted(prefs)
        runBlocking { history.save(call("c3"), emptyList()) }
        lateinit var vm: CallViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = CallViewModel(app) }
        waitFor { vm.gate.value == CallViewModel.Gate.APP }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.resetOnboarding() }
        waitFor { vm.gate.value == CallViewModel.Gate.SETUP }
        assertEquals(Onboarding.Step.WHO, vm.setupStep.value)
        assertEquals(1, runBlocking { db.calls().calls().first().size })
        assertEquals(PrivacyLevel.HYBRID, prefs.privacyLevel)
        assertTrue(prefs.developerUnlocked)
    }

    @Test fun theDeveloperViewWritesNothingButItsFlag() = runBlocking {
        prefs.privacyLevel = PrivacyLevel.CLOUD
        prefs.textSize = AppPrefs.TEXT_LARGE
        Onboarding.markCompleted(prefs)
        history.save(call("c4"), emptyList())
        val prefsBefore = sp.all.toMap()
        val countsBefore = counts()
        val filesBefore = files()

        val dev = DevSession(prefs, Dev.log)
        var taps = 0
        repeat(7) { taps = dev.tap(taps).count }
        dev.setOverlay(true)
        dev.setSpeed(PlaybackSpeed.X4)
        Dev.log.add(1, "POST", "/v1/utterance", 200, 30, """{"text":"my OTP is 123456"}""", """{"verdict":"SCAM"}""")
        Dev.log.startCall("c9", "cloud")
        Dev.log.addLine("c9", DevLine(1, 0, 0.1f, 0.9f, 0.1f, 0.8f, null, "off", null, "SCAM", "SCAM", "cloud", 50))
        Diagnostics.text(emptyList(), emptyList(), Dev.log.lastCall())
        dev.setOverlay(false)
        dev.historyDeleted()

        assertEquals(prefsBefore + (AppPrefs.KEY_DEV_UNLOCKED to true), sp.all.toMap())
        assertEquals(countsBefore, counts())
        val after = files()
        val changed = (filesBefore.keys + after.keys).filter { filesBefore[it] != after[it] }
        assertTrue("only the settings file may change: $changed", changed.all { it.endsWith("shared_prefs/${AppPrefs.FILE}.xml") })
    }

    private fun waitFor(timeoutMs: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }
}
