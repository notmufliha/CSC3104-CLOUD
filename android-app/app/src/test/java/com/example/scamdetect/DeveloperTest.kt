package com.example.scamdetect

import com.example.scamdetect.data.ScriptLine
import com.example.scamdetect.logic.ConnectionStatus
import com.example.scamdetect.logic.DevCall
import com.example.scamdetect.logic.DevEnd
import com.example.scamdetect.logic.DevFormat
import com.example.scamdetect.logic.DevLine
import com.example.scamdetect.logic.DevLog
import com.example.scamdetect.logic.DevMask
import com.example.scamdetect.logic.DevModels
import com.example.scamdetect.logic.DevOverlay
import com.example.scamdetect.logic.DevScripts
import com.example.scamdetect.logic.DevSession
import com.example.scamdetect.logic.DevUnlock
import com.example.scamdetect.logic.Diagnostics
import com.example.scamdetect.logic.Playback
import com.example.scamdetect.logic.PlaybackSpeed
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.ui.SettingsPage
import com.example.scamdetect.ui.connectionStatusRes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hidden Developer view (logic/Developer.kt). Same cases as ios-app ScamGuardTests/DeveloperTests.swift. */
class DeveloperTest {
    /** A settings file in a map: what the Developer view may write is visible as a diff. */
    private class MapStore(val map: MutableMap<String, Any> = mutableMapOf()) : DevUnlock.Store {
        override var developerUnlocked: Boolean
            get() = map["developer_view_unlocked"] as? Boolean ?: false
            set(v) { map["developer_view_unlocked"] = v }
    }

    private fun session(store: MapStore = MapStore()) = DevSession(store, DevLog())

    // ---- 7 taps unlock, the switch locks

    @Test fun sevenTapsUnlock() {
        val s = session()
        var count = 0
        val outcomes = (1..7).map { s.tap(count).also { t -> count = t.count }.outcome }
        assertEquals(List(3) { DevUnlock.Outcome.NONE } + List(3) { DevUnlock.Outcome.STEPS_LEFT } + DevUnlock.Outcome.UNLOCKED, outcomes)
        assertTrue(s.unlocked)
        assertTrue(s.log.recording)
        assertEquals(DevUnlock.Outcome.ALREADY, s.tap(0).outcome)
    }

    @Test fun tapsSayHowManyAreLeft() {
        assertEquals(3, DevUnlock.tap(3, false).left)
        assertEquals(2, DevUnlock.tap(4, false).left)
        assertEquals(1, DevUnlock.tap(5, false).left)
        assertEquals(DevUnlock.Tap(0, DevUnlock.Outcome.UNLOCKED), DevUnlock.tap(6, false))
    }

    @Test fun sixTapsAreNotEnough() {
        val s = session()
        var count = 0
        repeat(6) { count = s.tap(count).count }
        assertFalse(s.unlocked)
        assertFalse(s.log.recording)
    }

    @Test fun lockingHidesItAgainAndForgetsEverything() {
        val store = MapStore()
        val s = session(store)
        s.setUnlocked(true)
        s.setOverlay(true)
        s.setSpeed(PlaybackSpeed.X4)
        s.log.add(1, "GET", "/healthz", 200, 3, "", """{"status":"ok"}""")
        s.setUnlocked(false)
        assertFalse(store.developerUnlocked)
        assertFalse(s.overlay)
        assertEquals(PlaybackSpeed.X1, s.speed)
        assertTrue(s.log.entries().isEmpty())
        assertFalse(s.log.recording)
        // locked: the overlay and the speed can't be changed, and nothing is logged
        s.setOverlay(true); s.setSpeed(PlaybackSpeed.INSTANT)
        s.log.add(2, "GET", "/healthz", 200, 3, "", "")
        assertFalse(s.overlay)
        assertEquals(PlaybackSpeed.X1, s.speed)
        assertTrue(s.log.entries().isEmpty())
    }

    @Test fun theUnlockedFlagIsTheOnlySettingWritten() {
        val store = MapStore(mutableMapOf("privacy_level" to "cloud", "text_size" to "large"))
        val before = store.map.toMap()
        val s = session(store)
        var count = 0
        repeat(7) { count = s.tap(count).count }
        s.setOverlay(true)
        s.setSpeed(PlaybackSpeed.INSTANT)
        s.log.add(1, "POST", "/v1/utterance", 200, 40, """{"text":"hello"}""", "{}")
        s.log.startCall("c1", "cloud")
        s.log.addLine("c1", line(1))
        s.log.endCall("c1", "script_finished", null)
        s.log.setModelVersions(mapOf("urgency" to "v5"))
        Diagnostics.text(emptyList(), emptyList(), s.log.lastCall())
        assertEquals(before + ("developer_view_unlocked" to true), store.map)
    }

    // ---- the in-memory log

    @Test fun theLogKeepsTheNewest20() {
        val log = DevLog().apply { recording = true }
        repeat(25) { i -> log.add(i.toLong(), "POST", "/v1/utterance", 200, i.toLong(), """{"seq":$i}""", "{}") }
        val e = log.entries()
        assertEquals(20, e.size)
        assertEquals(DevLog.CAPACITY, e.size)
        assertEquals(24L, e.first().timeMs)     // newest first
        assertEquals(5L, e.last().timeMs)
    }

    @Test fun deleteMyHistoryClearsTheLog() {
        val s = session()
        s.setUnlocked(true)
        s.log.add(1, "GET", "/healthz", 200, 3, "", "")
        s.log.startCall("c1", "cloud")
        s.log.addLine("c1", line(1))
        s.historyDeleted()
        assertTrue(s.log.entries().isEmpty())
        assertNull(s.log.lastCall())
        assertTrue("still unlocked", s.unlocked)
    }

    @Test fun nothingIsKeptWhileLocked() {
        val log = DevLog()
        log.add(1, "GET", "/healthz", 200, 3, "", "")
        log.startCall("c1", "cloud")
        log.addLine("c1", line(1))
        log.setModelVersions(mapOf("urgency" to "v5"))
        assertTrue(log.entries().isEmpty())
        assertNull(log.lastCall())
        assertTrue(log.modelVersions().isEmpty())
    }

    @Test fun lastCallKeepsLinesInOrderAndTheEnd() {
        val log = DevLog().apply { recording = true }
        log.startCall("c1", "cloud")
        log.addLine("c1", line(3)); log.addLine("c1", line(1)); log.addLine("c1", line(3))
        log.addLine("other", line(9))                       // a late answer for another call
        log.endCall("c1", "user_hung_up", DevEnd("SCAM", 0.91f, "bert", listOf(1, 3)))
        val c = log.lastCall()!!
        assertEquals(listOf(1L, 3L), c.lines.map { it.seq })
        assertEquals("user_hung_up", c.endedBy)
        assertEquals("SCAM", c.end?.finalVerdict)
        assertEquals(3L, log.latestLine("c1")?.seq)
    }

    // ---- masking

    private val callerText = "This is Officer Tan from the police. Your NRIC S1234567D, call 9123 4567, OTP 482913, pay \$5,000 to bob@mail.com"

    @Test fun theLogMasksCallerText() {
        val log = DevLog().apply { recording = true }
        val request = """{"call_id":"c-42","seq":3,"speaker":"caller","text":"$callerText","lang":"en"}"""
        val end = """{"call_id":"c-42","caller_lines":[{"seq":3,"text":"$callerText"}]}"""
        val verify = """{"lines":["$callerText","OTP 111222"],"lang":"en"}"""
        log.add(1, "POST", "/v1/utterance", 200, 10, request, """{"verdict":"SCAM","reasons":[{"signal":"money","text":"$callerText"}]}""")
        log.add(2, "POST", "/v1/call/end", 200, 10, end, "{}")
        log.add(3, "POST", "/v1/verify", 200, 10, verify, """{"score":0.9}""")
        log.add(4, "POST", "/v1/utterance", 0, 10, "not json: $callerText", "IOException: $callerText")
        val all = log.entries().joinToString("\n") { it.request + "\n" + it.response }
        for (secret in listOf("S1234567D", "9123 4567", "482913", "5,000", "bob@mail.com", "Tan", "111222")) {
            assertFalse("$secret leaked", all.contains(secret))
        }
        assertTrue(all.contains("[NUMBER]"))
        assertTrue(all.contains("[EMAIL_ADDRESS]"))
        assertTrue(all.contains("[PERSON_NAME]"))
        assertTrue("ids and numbers that aren't caller text stay", all.contains("\"c-42\"") && all.contains("\"seq\": 3"))
    }

    @Test fun maskedBodiesArePrettyPrinted() {
        val out = DevMask.body("""{"a":1,"text":"call 91234567"}""")
        assertTrue(out, out.contains("\n  \"a\": 1"))
        assertTrue(out.contains("[PHONE_NUMBER]"))
        assertEquals("", DevMask.body(""))
    }

    @Test fun copyDiagnosticsHasNoCallerText() {
        val log = DevLog().apply { recording = true }
        log.add(1, "POST", "/v1/utterance", 200, 10, """{"text":"$callerText"}""", "{}")
        log.startCall("c1", "hybrid")
        log.addLine("c1", line(1))
        log.endCall("c1", "script_finished", DevEnd("SCAM", 0.8f, "bert", listOf(1)))
        val text = Diagnostics.text(
            listOf("gateway URL" to "http://10.0.2.2:8080/", "privacy level" to "hybrid"),
            listOf("bundled urgency_en" to "urgency_en_v5"),
            log.lastCall(),
        )
        for (secret in listOf("S1234567D", "482913", "bob@mail.com", "Officer Tan", "police")) assertFalse(secret, text.contains(secret))
        assertTrue(text.contains("1. Connection") && text.contains("2. Models") && text.contains("3. Last call"))
        assertTrue(text.contains("call_id: c1"))
        assertTrue(text.contains("final SCAM"))
        assertFalse("section 4 (raw replies) is not in the copy", text.contains("utterance"))
    }

    // ---- overlay

    @Test fun overlayHidesWhileTheRedWarningShows() {
        assertTrue(DevOverlay.visible(enabled = true, unlocked = true, inCall = true, warningOpen = false))
        assertFalse(DevOverlay.visible(enabled = true, unlocked = true, inCall = true, warningOpen = true))
        assertFalse(DevOverlay.visible(enabled = false, unlocked = true, inCall = true, warningOpen = false))
        assertFalse(DevOverlay.visible(enabled = true, unlocked = false, inCall = true, warningOpen = false))
        assertFalse(DevOverlay.visible(enabled = true, unlocked = true, inCall = false, warningOpen = false))
    }

    // ---- playback speed

    @Test fun eachSpeedScalesTheDelays() {
        assertEquals(1_500L, Playback.gap(1_500, PlaybackSpeed.X1))
        assertEquals(750L, Playback.gap(1_500, PlaybackSpeed.X2))
        assertEquals(375L, Playback.gap(1_500, PlaybackSpeed.X4))
        assertEquals(Playback.INSTANT_GAP_MS, Playback.gap(1_500, PlaybackSpeed.INSTANT))
        assertEquals(150L, Playback.gap(9_000, PlaybackSpeed.INSTANT))
        assertNull(PlaybackSpeed.X1.tag)
        assertEquals("2× speed", PlaybackSpeed.X2.tag)
        assertEquals("4× speed", PlaybackSpeed.X4.tag)
        assertEquals("Instant", PlaybackSpeed.INSTANT.tag)
    }

    @Test fun everySpeedKeepsTheOrderAndEndsTheCall() = runBlocking {
        val lines = listOf(ScriptLine("receiver", "Hello?", 0), ScriptLine("caller", "a", 2_000), ScriptLine("caller", "b", 1_000),
            ScriptLine("receiver", "ok", null), ScriptLine("caller", "c", 4_000))
        for (speed in PlaybackSpeed.entries) {
            val events = mutableListOf<String>()
            Playback.play(lines, speed, 1_500, sleep = { events += "sleep $it" }, onLine = { i, _ -> events += "line $i" }) { events += "end" }
            val expectedGaps = listOf(0L, 2_000L, 1_000L, 1_500L, 4_000L).map { Playback.gap(it, speed) }
            assertEquals("$speed", expectedGaps.indices.flatMap { listOf("sleep ${expectedGaps[it]}", "line $it") } + "end", events)
        }
    }

    @Test fun instantStillWaitsBetweenLines() = runBlocking {
        val gaps = mutableListOf<Long>()
        Playback.play(List(5) { ScriptLine("caller", "x", 0) }, PlaybackSpeed.INSTANT, 1_500, { gaps += it }, { _, _ -> }) {}
        assertTrue(gaps.all { it == Playback.INSTANT_GAP_MS })
    }

    // ---- what normal users see

    @Test fun theUserFacingStatusShowsNoAddress() {
        TestStrings.install()
        assertEquals(ConnectionStatus.State.CONNECTED, ConnectionStatus.of(PrivacyLevel.CLOUD, true))
        assertEquals(ConnectionStatus.State.CANT_CONNECT, ConnectionStatus.of(PrivacyLevel.HYBRID, false))
        assertEquals(ConnectionStatus.State.CHECKING, ConnectionStatus.of(PrivacyLevel.CLOUD, null))
        assertEquals(ConnectionStatus.State.NOT_NEEDED, ConnectionStatus.of(PrivacyLevel.ON_DEVICE, false))
        assertEquals("Connected ✓", TestStrings.get(connectionStatusRes(ConnectionStatus.State.CONNECTED)))
        assertEquals("Can't connect to ScamGuard", TestStrings.get(connectionStatusRes(ConnectionStatus.State.CANT_CONNECT)))
        assertEquals("Ask the person who set up this phone for help", TestStrings.get(R.string.conn_fail_line))
        val shown = ConnectionStatus.State.entries.map { TestStrings.get(connectionStatusRes(it)) } + TestStrings.get(R.string.conn_fail_line)
        for (t in shown) {
            assertFalse(t, Regex("""https?://|\d|:\d|\.\d|/api|localhost|error""", RegexOption.IGNORE_CASE).containsMatchIn(t))
        }
    }

    @Test fun serverSettingsAreOnlyInTheDeveloperView() {
        val pages = SettingsPage.entries.map { it.name }
        assertFalse(pages.any { it.contains("SERVER") || it.contains("MODELS") })
        assertTrue("DEVELOPER" in pages)
    }

    @Test fun learningLoopCallsAreTestCalls() {
        assertTrue(DevScripts.isTestCall("scam_en_90_new_scam_test_A_report_this.json"))
        assertTrue(DevScripts.isTestCall("scam_en_91_new_scam_test_B_same_scam_new_words.json"))
        assertFalse(DevScripts.isTestCall("scam_en_11_bank_otp.json"))
        assertFalse(DevScripts.isTestCall("normal_zh_14_family_call.json"))
    }

    // ---- formats

    @Test fun tableLinesUpAndModelsAreRead() {
        val t = DevFormat.table(listOf(line(1), line(12))).lines()
        assertEquals(3, t.size)
        assertTrue(t[0].startsWith("seq"))
        assertEquals(t[1].indexOf("cloud"), t[2].indexOf("cloud"))
        val bundled = DevModels.bundled("""{"models":{"urgency_en":{"version":"urgency_en_v5","sha256":"abc"}}}""")
        assertEquals(listOf("bundled urgency_en" to "urgency_en_v5  sha256 abc"), bundled)
        assertEquals(emptyList<Pair<String, String>>(), DevModels.downloaded(null))
        assertEquals("downloaded money_en_phone" to "v3  sha256 def",
            DevModels.downloaded("""{"money_en_phone":{"version":"v3","sha256":"def","installed_at":1}}""").single())
    }

    private fun line(seq: Long) = DevLine(seq, seq * 1000, 0.1f, 0.9f, 0.2f, 0.7f, 0.95f, "ok", null, "SCAM", "SCAM", "cloud", 120)

    @Suppress("unused") private val keepImport = DevCall("x", "cloud")
}
