package com.example.scamdetect

import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.ScriptRepository
import com.example.scamdetect.logic.Mask
import com.example.scamdetect.ondevice.LocalRules
import com.example.scamdetect.ondevice.MobileBert
import com.example.scamdetect.ondevice.OnDeviceCall
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Workstream G.5: the per-level benchmark for the report. Run on any device (emulator now, Galaxy S25 later):
 *
 *   adb logcat -c
 *   bash gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.scamdetect.BenchmarkTest \
 *     -Pandroid.testInstrumentationRunnerArguments.serverUrl=http://10.0.2.2:8080/
 *   adb logcat -d -s G5:I -v raw > benchmark.md        # the report (Gradle uninstalls the app after the run)
 *
 * Measures: model file sizes; time per sentence (median / p95 over the 200 robocall sentences) for the small
 * models and MobileBERT (if installed); app memory (PSS) while scoring; accuracy per level on
 *   (a) the robocall set (backend/workers/urgency_en/eval/robocall_gold.csv): 200 single sentences, label = the
 *       URGENCY label (provisional, partly AI-labelled), so it measures "warns on urgent robocall sentences";
 *   (b) the demo calls (app/src/main/assets/scripts: Workstream 6's 14 + H's 2 learning-loop tests): call-level scam detection. Correct = a
 *       scam call ends with a warning (UNSURE/SCAM), a normal call ends SAFE.
 * Levels 1 (Cloud) and 2 (Hybrid) need the server (serverUrl); if it doesn't answer they are left out.
 * Level 3 = the small models only (plus MobileBERT once a model is bundled).
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private val gson = Gson()
    private val out = mutableListOf<String>()
    private fun log(line: String = "") { out += line; Log.i("G5", line); println("G5| $line") }

    private val serverUrl = (InstrumentationRegistry.getArguments().getString("serverUrl") ?: "http://10.0.2.2:8080/").trimEnd('/') + "/"

    // ------------------------------------------------------------ helpers
    private fun percentile(xs: List<Double>, p: Double): Double {
        if (xs.isEmpty()) return Double.NaN
        val s = xs.sorted()
        return s[((p / 100.0) * (s.size - 1)).let { Math.round(it).toInt() }]
    }
    private fun ms(xs: List<Double>) = if (xs.isEmpty()) "n/a" else "%.1f / %.1f ms".format(percentile(xs, 50.0), percentile(xs, 95.0))
    private fun pssMb(): Double = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024.0
    private fun heapMb(): Double = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1048576.0 }

    private fun http(method: String, path: String, body: Any?, timeoutMs: Int): Pair<Int, JsonObject?> = try {
        val c = URL(serverUrl + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(gson.toJson(body).toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        code to runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
    } catch (e: Exception) {
        -1 to null
    }

    /** Hybrid's cloud call, as the app makes it: masked lines, 1.5 s limit; null = no (usable) answer. */
    private fun verify(masked: List<String>): Float? {
        val (code, body) = http("POST", "v1/verify", mapOf("lines" to masked, "lang" to "en"), OnDeviceCall.VERIFY_TIMEOUT_MS.toInt())
        return if (code == 200) body?.get("score")?.asFloat else null
    }

    private data class Prf(val tp: Int, val fp: Int, val fn: Int, val tn: Int) {
        val precision get() = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val recall get() = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val f1 get() = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        val accuracy get() = (tp + tn).toDouble() / (tp + fp + fn + tn)
        override fun toString() = "F1 %.3f (P %.3f, R %.3f, acc %.3f; TP %d FP %d FN %d TN %d)".format(f1, precision, recall, accuracy, tp, fp, fn, tn)
    }
    private fun prf(pairs: List<Pair<Boolean, Boolean>>) = Prf(  // (warned, label)
        pairs.count { it.first && it.second }, pairs.count { it.first && !it.second },
        pairs.count { !it.first && it.second }, pairs.count { !it.first && !it.second })

    private data class CallResult(val script: CallScript, val verdict: String, val sentWindows: Int = 0, val callerLines: Int = 0) {
        val isScam get() = script.kind == "scam"
        /** Workstream H's learning-loop tests: a scam every model misses until retrained (should end SAFE today). */
        val learningLoopTest get() = "new_scam_test" in script.fileName
        val correct get() = if (isScam) verdict == "SCAM" || verdict == "UNSURE" else verdict == "SAFE"
    }
    private fun callSummary(rs: List<CallResult>): String {
        val en = rs.filter { it.script.lang != "zh" }
        val checked = rs.filter { it.verdict != "NOT CHECKED" }
        val usual = rs.filter { !it.learningLoopTest }
        return "${rs.count { it.correct }}/${rs.size} correct (English ${en.count { it.correct }}/${en.size}" +
            (if (checked.size < rs.size) "; ${rs.size - checked.size} Chinese calls not checked" else "") +
            "; without H's 2 learning-loop tests ${usual.count { it.correct }}/${usual.size})"
    }

    // ------------------------------------------------------------ the benchmark
    // (the 14 + 2 demo calls: main added H's two learning-loop test scams, scam_en_90/91, in 2026-11)
    @Test fun benchmark() = runBlocking {
        val started = System.currentTimeMillis()
        log("# Benchmark (Workstream G.5)")
        log()
        log("- Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}" +
            if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true) || Build.HARDWARE.contains("ranchu")) " (emulator)" else "")
        log("- Run: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())}; server $serverUrl")

        // sizes
        val sizes = OnDeviceScorer.FILES.associateWith { f -> app.assets.open("${OnDeviceScorer.DIR}/$f").use { it.readBytes().size.toLong() } }
        val manifest = app.assets.open("${OnDeviceScorer.DIR}/manifest.json").bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
        val versions = manifest["models"].asJsonObject.entrySet().joinToString { (k, v) -> "$k ${v.asJsonObject["version"].asString}" }
        val smallMb = sizes.values.sum() / 1e6
        log("- Small models (bundled, assets/models/manifest.json): $versions")

        // memory + load time of a fresh copy of the small models
        System.gc(); val pss0 = pssMb(); val heap0 = heapMb()
        val t0 = System.nanoTime()
        val scorer = OnDeviceScorer.load { f -> app.assets.open("${OnDeviceScorer.DIR}/$f").bufferedReader() }
        val loadMs = (System.nanoTime() - t0) / 1e6
        val pss1 = pssMb(); val heap1 = heapMb()

        // robocall sentences
        val roboDoc = inst.context.assets.open("benchmark/robocall.json").bufferedReader().use { JsonParser.parseString(it.readText()).asJsonObject }
        val robo = roboDoc.getAsJsonArray("rows").map { it.asJsonObject }.map { it["text"].asString to (it["label"].asInt == 1) }

        // time per sentence, small models (all three checks), after a warm-up
        robo.take(20).forEach { scorer.score(it.first) }
        var pssPeak = pssMb()
        val smallTimes = robo.mapIndexed { i, (text, _) ->
            val s = System.nanoTime(); scorer.score(text); val d = (System.nanoTime() - s) / 1e6
            if (i % 25 == 0) pssPeak = maxOf(pssPeak, pssMb())
            d
        }
        pssPeak = maxOf(pssPeak, pssMb())
        val perModel = listOf<Pair<String, (String) -> Double>>("urgency" to scorer::urgency, "money" to scorer::money, "impersonation" to scorer::impersonation)
            .map { (n, f) -> n to robo.map { (t, _) -> val s = System.nanoTime(); f(t); (System.nanoTime() - s) / 1e6 } }

        // MobileBERT
        val mb = MobileBert.get(app)
        val mbModel = (mb as? MobileBert.Status.Installed)?.model
        val mbTimes = mbModel?.let { m -> robo.take(10).forEach { m.score(listOf(it.first)) }; robo.map { (t, _) -> val s = System.nanoTime(); m.score(listOf(t)); (System.nanoTime() - s) / 1e6 } }.orEmpty()
        val mbStatus = when (mb) { is MobileBert.Status.Installed -> "installed (${mb.model.config.version}, ${"%.1f".format(mb.model.modelBytes / 1e6)} MB)"; is MobileBert.Status.NotInstalled -> mb.reason }

        // ---- accuracy, level 3 (and 2) on robocall sentences: each sentence as one caller line, past the warm-up
        fun rulesFor() = LocalRules()
        val l3Robo = robo.map { (text, label) ->
            val sc = scorer.score(text)
            val d = rulesFor().decide(LocalRules.SIGNALS.map { sc[it] }, 1_000_000L, callTimeMs = 20_000)
            (d.verdict != "SAFE") to label
        }

        // Android 17+: private addresses (the laptop's gateway) need this runtime permission; a reinstall drops it
        if (Build.VERSION.SDK_INT >= 37) runCatching { inst.uiAutomation.grantRuntimePermission(app.packageName, LOCAL_NETWORK_PERMISSION) }
        val (healthCode, health) = http("GET", "healthz", null, 2_000)
        val serverUp = healthCode == 200 && health?.get("status")?.asString == "ok"
        log("- Server: ${if (serverUp) "up" else "not reachable: levels 1 and 2 left out"}; MobileBERT: $mbStatus")

        var l2Robo: List<Pair<Boolean, Boolean>>? = null
        var l1Robo: List<Pair<Boolean, Boolean>>? = null
        val l2Times = mutableListOf<Double>(); val l1Times = mutableListOf<Double>()
        if (serverUp) {
            // Hybrid asks BERT only once a call has 3 caller lines (like the aggregator), so a one-sentence "call" never
            // sends anything; the rules then see BERT as "no_context" when it would have been wanted, as the cloud does.
            l2Robo = robo.map { (text, label) ->
                val s = System.nanoTime()
                val sc = scorer.score(text)
                val line = LocalRules.SIGNALS.map { sc[it] }
                val rules = rulesFor()
                val wanted = rules.wantsSecondOpinion(line, 1_000_000L, 20_000) ||
                    rules.peek(line, 1_000_000L, 20_000).fast >= OnDeviceCall.ASK_AT
                val d = rules.decide(line, 1_000_000L, 20_000, null, if (wanted) "no_context" else "skipped")
                l2Times += (System.nanoTime() - s) / 1e6
                (d.verdict != "SAFE") to label
            }
            l1Robo = robo.map { (text, label) ->
                val id = "bench-${UUID.randomUUID()}"
                val s = System.nanoTime()
                val (_, r) = http("POST", "v1/utterance", mapOf("call_id" to id, "seq" to 1, "speaker" to "caller", "text" to text, "lang" to "en", "t_ms" to 20_000), 4_000)
                l1Times += (System.nanoTime() - s) / 1e6
                http("POST", "v1/call/end", mapOf("call_id" to id, "event" to "end", "ended_by" to "user_discarded", "last_seq" to 1, "caller_lines" to emptyList<Any>()), 4_000)
                ((r?.get("verdict")?.asString ?: "SAFE") != "SAFE") to label
            }
        }

        // ---- accuracy on the demo calls, played with their own timing
        val scripts = ScriptRepository(app).loadAll()
        fun playLocal(script: CallScript, ask: (suspend (List<String>) -> Float?)?): CallResult {
            var now = 1_700_000_000_000L
            val call = OnDeviceCall(scorer, clock = { now })
            var verdict = "LISTENING"; var notChecked = false; var sent = 0; var n = 0
            script.lines.forEachIndexed { i, line ->
                now += line.delayMs ?: 1_500
                if (!line.isCaller) return@forEachIndexed
                n++
                val text = line.text.orEmpty()
                if (!OnDeviceCall.isEnglish(script.lang, text)) { notChecked = true; return@forEachIndexed }
                verdict = if (ask == null) call.answer("b", (i + 1).toLong(), text).verdict!!
                else runBlocking { call.answerHybrid("b", (i + 1).toLong(), text, ask).also { if (it.second != null) sent++ }.response.verdict!! }
            }
            // the app: a line that couldn't be checked beats SAFE ("Could not check"), a warning always shows
            val final = if (notChecked && verdict !in setOf("SCAM", "UNSURE")) "NOT CHECKED" else verdict
            return CallResult(script, final, sent, n)
        }
        val l3Calls = scripts.map { playLocal(it, null) }
        val l2Calls = if (serverUp) scripts.map { playLocal(it) { lines -> verify(lines) } } else null
        val l1Calls = if (serverUp) scripts.map { script ->
            val id = "bench-${UUID.randomUUID()}"; var t = 0L; var verdict = "LISTENING"
            val callerLines = mutableListOf<Map<String, Any>>()
            script.lines.forEachIndexed { i, line ->
                t += line.delayMs ?: 1_500
                if (!line.isCaller) return@forEachIndexed
                callerLines += mapOf("seq" to i + 1, "text" to line.text.orEmpty())
                val body = mutableMapOf<String, Any>("call_id" to id, "seq" to i + 1, "speaker" to "caller", "text" to line.text.orEmpty(), "t_ms" to t)
                script.lang?.let { body["lang"] = it }
                val (_, r) = http("POST", "v1/utterance", body, 4_000)
                r?.get("verdict")?.takeIf { !it.isJsonNull }?.asString?.let { verdict = it }
            }
            val (_, end) = http("POST", "v1/call/end", mapOf("call_id" to id, "event" to "end", "ended_by" to "script_finished",
                "last_seq" to script.lines.size, "caller_lines" to callerLines), 4_000)
            CallResult(script, end?.get("final_verdict")?.asString?.takeIf { it in setOf("SAFE", "UNSURE", "SCAM") } ?: verdict)
        } else null

        // ---- report
        val l3RoboPrf = prf(l3Robo)
        log()
        log("| Level | F1 (robocall sentences, urgency labels) / correct calls (${scripts.size} demo calls) | Time per sentence (median / p95) | Model size on the phone | Data leaving the phone |")
        log("|---|---|---|---|---|")
        if (serverUp) {
            log("| 1. Cloud | ${"%.3f".format(prf(l1Robo!!).f1)} / ${l1Calls!!.count { it.correct }} of ${scripts.size} | ${ms(l1Times)} (round trip) | 0 MB | every caller sentence (not stored) |")
            val l2Lines = l2Calls!!.sumOf { it.callerLines }; val l2Win = l2Calls.sumOf { it.sentWindows }
            log("| 2. Hybrid | ${"%.3f".format(prf(l2Robo!!).f1)} / ${l2Calls.count { it.correct }} of ${scripts.size} | ${ms(l2Times)} (incl. cloud when asked) | ${"%.1f".format(smallMb)} MB | masked 5-line windows at unsure moments: $l2Win windows for $l2Lines caller lines (demo calls); none for one-sentence robocall checks (BERT waits for 3 lines) |")
        } else {
            log("| 1. Cloud | not run (server not reachable) | | 0 MB | every caller sentence (not stored) |")
            log("| 2. Hybrid | not run (server not reachable) | | ${"%.1f".format(smallMb)} MB | masked windows at unsure moments |")
        }
        log("| 3. On-device (small models${if (mbModel != null) " + MobileBERT" else " only"}) | ${"%.3f".format(l3RoboPrf.f1)} / ${l3Calls.count { it.correct }} of ${scripts.size} | ${ms(smallTimes)}${if (mbModel != null) "; MobileBERT ${ms(mbTimes)}" else ""} | ${"%.1f".format(smallMb)} MB${mbModel?.let { " + %.1f MB".format(it.modelBytes / 1e6) } ?: ""} | nothing |")
        log()
        log("## Details")
        log("- Model files: " + sizes.entries.joinToString { (f, b) -> "$f ${"%.2f".format(b / 1e6)} MB" } + "; MobileBERT: $mbStatus")
        log("- Load time of the three small models (fresh, from assets): ${"%.0f".format(loadMs)} ms")
        log("- Time per sentence, per check: " + perModel.joinToString { (n, xs) -> "$n ${ms(xs)}" })
        log("- App memory (PSS): before loading ${"%.0f".format(pss0)} MB, after loading ${"%.0f".format(pss1)} MB, peak while scoring ${"%.0f".format(pssPeak)} MB; Java heap ${"%.0f".format(heap0)} -> ${"%.0f".format(heap1)} MB")
        log("- Robocall set: ${robo.size} sentences, ${robo.count { it.second }} labelled 1 (urgent). Warned = verdict UNSURE or SCAM for the sentence on its own, past the 15 s warm-up.")
        log("  - Level 3: $l3RoboPrf")
        l2Robo?.let { log("  - Level 2: ${prf(it)}") }
        l1Robo?.let { log("  - Level 1: ${prf(it)}") }
        log("- Demo calls (correct = scam call ends UNSURE/SCAM, normal call ends SAFE):")
        log("  - Level 3: ${callSummary(l3Calls)}")
        l2Calls?.let { log("  - Level 2: ${callSummary(it)}") }
        l1Calls?.let { log("  - Level 1: ${callSummary(it)}") }
        log()
        log("| Call | Kind | Level 1 | Level 2 | Level 3 |")
        log("|---|---|---|---|---|")
        scripts.forEachIndexed { i, s ->
            log("| ${s.fileName.removeSuffix(".json")}${if ("new_scam_test" in s.fileName) " (H learning-loop test)" else ""} | ${s.kind} | ${l1Calls?.get(i)?.verdict ?: "-"} | ${l2Calls?.get(i)?.let { "${it.verdict} (${it.sentWindows} sent)" } ?: "-"} | ${l3Calls[i].verdict} |")
        }
        log()
        log("Benchmark took ${(System.currentTimeMillis() - started) / 1000} s.")

        File(app.filesDir, "benchmark.md").writeText(out.joinToString("\n") + "\n")
        assertTrue(smallTimes.size == robo.size && smallTimes.all { it > 0 })
    }
}
