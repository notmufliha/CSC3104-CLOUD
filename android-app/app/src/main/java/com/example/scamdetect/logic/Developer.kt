package com.example.scamdetect.logic

import com.example.scamdetect.data.ScriptLine
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/*
 * The hidden Developer view (demos and debugging). Pure logic, no Android, kept in step with
 * ios-app/ScamGuard/Logic/Developer.swift; both apps test the same rules.
 *
 * Privacy: nothing here is written to disk or sent anywhere. The log and the last call live in memory only (cleared
 * when the app closes, by "Delete my history" and by locking the view). Only the "unlocked" flag is saved. Caller
 * text is always masked (logic/Mask.kt) before it is kept. Nothing here changes detection, thresholds or what the
 * server is sent.
 */

/** Settings → Help & about → tap the version number 7 times. */
object DevUnlock {
    const val TAPS = 7

    enum class Outcome { NONE, STEPS_LEFT, UNLOCKED, ALREADY }

    /** count = taps so far on this visit; left = taps still needed (for STEPS_LEFT). */
    data class Tap(val count: Int, val outcome: Outcome, val left: Int = 0)

    /** One tap. From the 4th tap on it says how many are left (as Android's own developer options do). */
    fun tap(count: Int, unlocked: Boolean): Tap {
        if (unlocked) return Tap(0, Outcome.ALREADY)
        val n = count + 1
        return when {
            n >= TAPS -> Tap(0, Outcome.UNLOCKED)
            n >= TAPS - 3 -> Tap(n, Outcome.STEPS_LEFT, TAPS - n)
            else -> Tap(n, Outcome.NONE)
        }
    }

    /** Where the "unlocked" flag lives (AppPrefs on the phone, a map in tests). */
    interface Store {
        var developerUnlocked: Boolean
    }
}

/**
 * The Developer view's state for one run of the app: the saved "unlocked" flag, and in memory the overlay switch and
 * the playback speed. Locking forgets the logs and puts both back. Delete my history forgets the logs too.
 */
class DevSession(private val store: DevUnlock.Store, val log: DevLog) {
    var overlay = false
        private set
    var speed = PlaybackSpeed.X1
        private set
    val unlocked: Boolean get() = store.developerUnlocked

    init { log.recording = store.developerUnlocked }

    /** One tap on the version number; the 7th unlocks. */
    fun tap(count: Int): DevUnlock.Tap = DevUnlock.tap(count, unlocked).also {
        if (it.outcome == DevUnlock.Outcome.UNLOCKED) setUnlocked(true)
    }

    fun setUnlocked(on: Boolean) {
        store.developerUnlocked = on
        log.recording = on
        if (!on) {
            log.clear()
            overlay = false
            speed = PlaybackSpeed.X1
        }
    }

    fun setOverlay(on: Boolean) { if (unlocked) overlay = on }
    fun setSpeed(s: PlaybackSpeed) { if (unlocked) speed = s }

    /** "Delete my history": the in-memory log goes too. */
    fun historyDeleted() = log.clear()

    /** "Reset app to first launch" cleared the store: forget the rest as well. */
    fun wasReset() {
        log.clear()
        log.recording = false
        overlay = false
        speed = PlaybackSpeed.X1
    }
}

/** "Call playback speed" (Developer view → Tools). In memory only; back to 1× when the view is locked. */
enum class PlaybackSpeed(val id: String, val factor: Int, val label: String) {
    X1("1x", 1, "1×"), X2("2x", 2, "2×"), X4("4x", 4, "4×"), INSTANT("instant", 0, "Instant");

    /** The small tag on the in-call screen; null at 1×. */
    val tag: String? get() = when (this) {
        X1 -> null
        INSTANT -> "Instant"
        else -> "$label speed"
    }
}

object Playback {
    /** Instant still waits this long between lines, so the screen and the requests stay in order. */
    const val INSTANT_GAP_MS = 150L

    /** A script line's wait before it appears, at this speed. */
    fun gap(delayMs: Long, speed: PlaybackSpeed): Long = when (speed) {
        PlaybackSpeed.INSTANT -> INSTANT_GAP_MS
        else -> delayMs / speed.factor
    }

    /**
     * Plays the lines in order: wait (scaled), show the line, then the end. Both call paths (cloud and on the phone)
     * run through this, so every speed ends the same way (the end message is sent by [onEnd]). [sleep] is
     * kotlinx's delay in the app and a recorder in tests.
     */
    suspend fun play(
        lines: List<ScriptLine>,
        speed: PlaybackSpeed,
        defaultDelayMs: Long,
        sleep: suspend (Long) -> Unit,
        onLine: suspend (index: Int, line: ScriptLine) -> Unit,
        onEnd: suspend () -> Unit,
    ) {
        lines.forEachIndexed { i, line ->
            sleep(gap(line.delayMs ?: defaultDelayMs, speed))
            onLine(i, line)
        }
        onEnd()
    }
}

/** The live score overlay: only while Developer view is unlocked, the toggle is on, the call is on and no red warning. */
object DevOverlay {
    fun visible(enabled: Boolean, unlocked: Boolean, inCall: Boolean, warningOpen: Boolean): Boolean =
        enabled && unlocked && inCall && !warningOpen
}

/** The plain connection status normal users see (Settings, Simple Home): never a URL, IP or error code. */
object ConnectionStatus {
    enum class State { CONNECTED, CANT_CONNECT, CHECKING, NOT_NEEDED }

    fun of(privacy: PrivacyLevel, connection: Boolean?): State = when {
        privacy == PrivacyLevel.ON_DEVICE -> State.NOT_NEEDED     // checked on the phone, no server
        connection == true -> State.CONNECTED
        connection == false -> State.CANT_CONNECT
        else -> State.CHECKING
    }
}

/** Learning-loop test scripts (file number 90 and up, e.g. scam_en_90_…): shown only in Developer view. */
object DevScripts {
    fun isTestCall(fileName: String): Boolean =
        (fileName.removeSuffix(".json").split("_").getOrNull(2)?.toIntOrNull() ?: 0) >= 90
}

/** Masks caller text inside a request / reply before the Developer view keeps it. */
object DevMask {
    /** Keys whose string values are what a caller said (or a server echo of it). */
    val TEXT_KEYS = setOf("text", "lines", "transcript", "caller_lines", "riskiest_sentence", "sentence")

    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    /** JSON: masked and pretty-printed. Anything else: masked as plain text. Empty stays empty. */
    fun body(raw: String): String {
        if (raw.isBlank()) return ""
        val el = try { JsonParser.parseString(raw) } catch (e: Exception) { null }
        if (el == null || !(el.isJsonObject || el.isJsonArray)) return Mask.mask(raw)
        return pretty.toJson(walk(el, false))
    }

    private fun walk(e: JsonElement, mask: Boolean): JsonElement = when {
        e.isJsonObject -> JsonObject().also { o -> e.asJsonObject.entrySet().forEach { (k, v) -> o.add(k, walk(v, mask || k in TEXT_KEYS)) } }
        e.isJsonArray -> JsonArray().also { a -> e.asJsonArray.forEach { a.add(walk(it, mask)) } }
        mask && e.isJsonPrimitive && e.asJsonPrimitive.isString -> JsonPrimitive(Mask.mask(e.asString))
        else -> e
    }
}

/** One caller line of the last call, for the Developer view's table and the overlay. Numbers only, no text. */
data class DevLine(
    val seq: Long,
    /** When the line appeared, ms since the call started. */
    val tMs: Long,
    val urgency: Float?,
    val money: Float?,
    val impersonation: Float?,
    val combined: Float?,
    val bertScore: Float?,
    val bertStatus: String?,
    /** On the phone: the rule that decided (LocalRules); the cloud doesn't report one. */
    val rule: String?,
    /** The cloud's live_alert; on the phone the verdict is the live alert. */
    val liveAlert: String?,
    val verdict: String?,
    /** "cloud", "phone", "phone + cloud BERT", "phone (cloud BERT unavailable)", "phone + MobileBERT"… */
    val where: String,
    val latencyMs: Long,
)

/** The end-of-call reply (cloud only). */
data class DevEnd(val finalVerdict: String?, val callScore: Float?, val review: String?, val topWindow: List<Long>)

data class DevCall(
    val callId: String,
    val privacy: String,
    val lines: List<DevLine> = emptyList(),
    val endedBy: String? = null,
    val end: DevEnd? = null,
)

/** One request / reply, masked. */
data class DevEntry(
    val timeMs: Long,
    val method: String,
    val path: String,
    val status: Int,
    val ms: Long,
    val request: String,
    val response: String,
)

data class DevHealth(val ok: Boolean, val message: String, val timeMs: Long)

/**
 * The Developer view's memory: the last 20 requests / replies, the last call, the server's model versions from its
 * last reply and the last /healthz. In memory only. [recording] is on while the view is unlocked; nothing is kept
 * otherwise.
 */
class DevLog(val capacity: Int = CAPACITY) {
    @Volatile var recording: Boolean = false

    private val entries = ArrayDeque<DevEntry>()
    private var call: DevCall? = null
    private var modelVersions: Map<String, String> = emptyMap()
    private var health: DevHealth? = null

    /** Masks both bodies, keeps the newest [capacity]. */
    @Synchronized
    fun add(timeMs: Long, method: String, path: String, status: Int, ms: Long, request: String, response: String) {
        if (!recording) return
        entries.addFirst(DevEntry(timeMs, method, path, status, ms, DevMask.body(request), DevMask.body(response)))
        while (entries.size > capacity) entries.removeLast()
    }

    /** Newest first. */
    @Synchronized fun entries(): List<DevEntry> = entries.toList()

    @Synchronized fun startCall(callId: String, privacy: String) { if (recording) call = DevCall(callId, privacy) }

    @Synchronized
    fun addLine(callId: String, line: DevLine) {
        val c = call ?: return
        if (!recording || c.callId != callId) return
        call = c.copy(lines = (c.lines.filter { it.seq != line.seq } + line).sortedBy { it.seq })
    }

    @Synchronized
    fun endCall(callId: String, endedBy: String, end: DevEnd?) {
        val c = call ?: return
        if (!recording || c.callId != callId) return
        call = c.copy(endedBy = endedBy, end = end ?: c.end)
    }

    @Synchronized fun lastCall(): DevCall? = call

    /** The newest caller line of the last call (the overlay). */
    @Synchronized fun latestLine(callId: String): DevLine? = call?.takeIf { it.callId == callId }?.lines?.maxByOrNull { it.seq }

    @Synchronized fun setModelVersions(v: Map<String, String>) { if (recording && v.isNotEmpty()) modelVersions = modelVersions + v }
    @Synchronized fun modelVersions(): Map<String, String> = modelVersions

    @Synchronized fun setHealth(h: DevHealth) { if (recording) health = h }
    @Synchronized fun health(): DevHealth? = health

    @Synchronized
    fun clear() {
        entries.clear()
        call = null
        modelVersions = emptyMap()
        health = null
    }

    companion object {
        const val CAPACITY = 20
    }
}

/** Number formats shared by the table, the overlay and "Copy diagnostics" (same on iOS). */
object DevFormat {
    fun score(v: Float?): String = v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-"
    fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    fun time(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    /** One row of the table: "#3  12.4s  U 0.12 M 0.98 I 0.40  C 0.79  BERT 0.91 ok  rule red_quorum  live SCAM  SCAM  cloud 182ms" */
    fun line(l: DevLine): String =
        "#${l.seq}  ${seconds(l.tMs)}  U ${score(l.urgency)} M ${score(l.money)} I ${score(l.impersonation)}  " +
            "C ${score(l.combined)}  BERT ${score(l.bertScore)} ${l.bertStatus ?: "-"}  rule ${l.rule ?: "-"}  " +
            "live ${l.liveAlert ?: "-"}  ${l.verdict ?: "-"}  ${l.where} ${l.latencyMs}ms"

    val TABLE_HEADER = listOf("seq", "t", "U", "M", "I", "comb", "BERT", "status", "rule", "live", "verdict", "where", "ms")

    fun tableRow(l: DevLine): List<String> = listOf(
        "${l.seq}", seconds(l.tMs), score(l.urgency), score(l.money), score(l.impersonation), score(l.combined),
        score(l.bertScore), l.bertStatus ?: "-", l.rule ?: "-", l.liveAlert ?: "-", l.verdict ?: "-", l.where, "${l.latencyMs}",
    )

    /** The Developer view's table: a header and one row per caller line, columns lined up (monospace). */
    fun table(lines: List<DevLine>): String {
        val rows = listOf(TABLE_HEADER) + lines.map(::tableRow)
        val widths = TABLE_HEADER.indices.map { c -> rows.maxOf { it[c].length } }
        return rows.joinToString("\n") { r -> r.mapIndexed { c, v -> v.padEnd(widths[c]) }.joinToString("  ").trimEnd() }
    }

    fun end(e: DevEnd): String =
        "final ${e.finalVerdict ?: "-"}  score ${score(e.callScore)}  review ${e.review ?: "-"}  top window ${e.topWindow.joinToString(",").ifEmpty { "-" }}"
}

/**
 * "Copy diagnostics": sections 1-3 (connection, models, last call) as one text. It holds no caller text at all:
 * the last call is numbers, verdicts and rule names only.
 */
object Diagnostics {
    fun text(connection: List<Pair<String, String>>, models: List<Pair<String, String>>, call: DevCall?): String = buildString {
        appendLine("ScamGuard diagnostics")
        appendLine()
        appendLine("1. Connection")
        connection.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("2. Models")
        models.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        appendLine("3. Last call")
        if (call == null) appendLine("none") else {
            appendLine("call_id: ${call.callId}")
            appendLine("privacy: ${call.privacy}")
            appendLine("ended_by: ${call.endedBy ?: "-"}")
            call.lines.forEach { appendLine(DevFormat.line(it)) }
            call.end?.let { appendLine("end: " + DevFormat.end(it)) }
        }
    }.trimEnd()
}

/** The models section: what is bundled (manifest.json) and what was downloaded (installed.json). */
object DevModels {
    /** "urgency_en: urgency_en_v5  sha256 677c3f…" per bundled model. */
    fun bundled(manifestJson: String?): List<Pair<String, String>> = runCatching {
        val models = JsonParser.parseString(manifestJson ?: return emptyList()).asJsonObject["models"].asJsonObject
        models.entrySet().sortedBy { it.key }.map { (name, v) ->
            val o = v.asJsonObject
            "bundled $name" to "${o["version"]?.asString ?: "?"}  sha256 ${o["sha256"]?.asString ?: "?"}"
        }
    }.getOrDefault(emptyList())

    fun downloaded(installedJson: String?): List<Pair<String, String>> = runCatching {
        val o = JsonParser.parseString(installedJson ?: return emptyList()).asJsonObject
        o.entrySet().sortedBy { it.key }.map { (name, v) ->
            val e = v.asJsonObject
            "downloaded $name" to "${e["version"].asString}  sha256 ${e["sha256"].asString}"
        }
    }.getOrDefault(emptyList())
}
