package com.example.scamdetect

import com.example.scamdetect.data.ScriptLine
import com.example.scamdetect.ondevice.OnDeviceCall
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File

/** Workstream G.2: whole demo calls scored on the phone, line by line with the script's own timing. */
class OnDeviceCallTest {
    companion object {
        private lateinit var scorer: OnDeviceScorer
        @BeforeClass @JvmStatic fun load() {
            scorer = OnDeviceScorer.load { File("src/main/assets/models/$it").bufferedReader() }
        }
    }

    private val listType = object : TypeToken<List<ScriptLine>>() {}.type

    /** The verdict at the end of a script, the way the app plays it (delays included, receiver lines skipped). */
    private fun play(file: File): String? {
        var now = 1_700_000_000_000L
        val call = OnDeviceCall(scorer, clock = { now })
        var verdict: String? = null
        val lines: List<ScriptLine> = Gson().fromJson(file.readText(), listType)
        lines.filter { !it.text.isNullOrBlank() }.forEachIndexed { i, line ->
            now += line.delayMs ?: 1_500
            if (line.isCaller) {
                val answer = call.answer("test", (i + 1).toLong(), line.text!!)
                if (verdict != "SCAM") verdict = answer.verdict   // the app's bar: red stays red
            }
        }
        return verdict
    }

    @Test fun englishDemoCallsGetAVerdictOnThePhone() {
        val scripts = File("src/main/assets/scripts").listFiles { f -> f.name.contains("_en_") }!!.sortedBy { it.name }
        assertEquals(10, scripts.size)
        val results = scripts.associate { it.name to play(it) }
        results.forEach { (name, v) -> println("on-device  ${v?.padEnd(6)}  $name") }
        assertTrue(results.values.all { it in setOf("SAFE", "UNSURE", "SCAM") })
        // every scam script raises a warning on the phone, as it does in the cloud
        results.filterKeys { it.startsWith("scam_") }.forEach { (name, v) -> assertTrue("$name: $v", v == "SCAM" || v == "UNSURE") }
    }

    @Test fun answerHasTheGatewaysShape() {
        val a = OnDeviceCall(scorer, clock = { 0L }).answer("c1", 3, "This is the police, transfer the money to the safe account now.")
        assertEquals("c1", a.callId)
        assertEquals(3L, a.seq)
        assertEquals(setOf("urgency", "money", "impersonation"), a.scores!!.keys)
        assertEquals(a.scores!!.keys, a.callScores!!.keys)
        assertEquals(1, a.callerLines)
        assertTrue(a.verdict in setOf("SAFE", "UNSURE", "SCAM"))
    }

    @Test fun englishOnly() {
        assertTrue(OnDeviceCall.isEnglish("en", "Read me the OTP"))
        assertTrue(OnDeviceCall.isEnglish(null, "Read me the OTP"))
        assertFalse(OnDeviceCall.isEnglish("zh", "Read me the OTP"))
        assertFalse(OnDeviceCall.isEnglish(null, "请把验证码告诉我"))
        assertTrue(OnDeviceCall.isEnglish(null, "Your DBS 银行 account is fine, thank you very much"))
    }

    // ------------------------------------------------------------ G.3 Hybrid

    private class Played(val verdict: String?, val asked: List<Pair<Int, OnDeviceCall.SecondOpinion>>, val callerLines: Int)

    /** A script in Hybrid mode; `cloud` stands in for POST /v1/verify (null = no answer in time / server off). */
    private fun playHybrid(file: File, cloud: (List<String>) -> Float?): Played = runBlocking {
        var now = 1_700_000_000_000L
        val call = OnDeviceCall(scorer, clock = { now })
        var verdict: String? = null
        val asked = mutableListOf<Pair<Int, OnDeviceCall.SecondOpinion>>()
        var n = 0
        val lines: List<ScriptLine> = Gson().fromJson(file.readText(), listType)
        lines.filter { !it.text.isNullOrBlank() }.forEachIndexed { i, line ->
            now += line.delayMs ?: 1_500
            if (line.isCaller) {
                n += 1
                val a = call.answerHybrid("test", (i + 1).toLong(), line.text!!) { sent -> cloud(sent) }
                a.second?.let { asked += n to it }
                if (verdict != "SCAM") verdict = a.response.verdict
            }
        }
        Played(verdict, asked, n)
    }

    private fun script(name: String) = File("src/main/assets/scripts/$name")

    @Test fun hybridSendsOnlyMaskedWindowsAtUnsureMomentsOrTheSpotCheck() {
        val sent = mutableListOf<List<String>>()
        val p = playHybrid(script("scam_en_11_bank_otp.json")) { sent += it; 0.9f }
        assertTrue("asked at least once", p.asked.isNotEmpty())
        p.asked.forEach { (n, c) ->
            assertTrue(c.why == "unsure" || (c.why == "spot_check" && n % 5 == 0))
            assertTrue(c.lines.size in 1..5)
            assertTrue("no digits leave the phone: ${c.lines}", c.lines.none { l -> l.any { it.isDigit() } })
        }
        assertEquals(p.asked.map { it.second.lines }, sent)   // exactly what the cloud got
        assertEquals("SCAM", p.verdict)
    }

    @Test fun whatIsSentIsMaskedWithTheMaskFromH() {
        var now = 0L
        val call = OnDeviceCall(scorer, clock = { now })
        val raw = listOf(
            "Good afternoon, this is Officer Tan from the Singapore Police Force.",
            "Your NRIC S1234567D is linked to a money laundering case.",
            "Call me back on 9123 4567 or email tan@spf-help.com right away.",
            "You must transfer $5,000 to the safe account within 30 minutes.",
            "Read me the 6 digit OTP now or you will be arrested today.",
            "Don't tell your family, this is confidential.",
        )
        val sent = mutableListOf<Pair<Int, List<String>>>()   // (line index, what left the phone)
        runBlocking {
            raw.forEachIndexed { i, text -> now += 20_000; call.answerHybrid("c", i.toLong(), text) { s -> sent += i to s; null } }
        }
        assertTrue(sent.isNotEmpty())
        // each window = the caller's last (up to) 5 lines up to and including this one, masked by H's Mask
        sent.forEach { (i, lines) ->
            assertEquals(raw.subList(0, i + 1).takeLast(5).map { com.example.scamdetect.logic.Mask.mask(it) }, lines)
        }
        val all = sent.flatMap { it.second }.joinToString(" ")
        listOf("Tan", "S1234567D", "9123", "tan@spf-help.com", "5,000", "6 digit").forEach { assertFalse("'$it' left the phone", it in all) }
    }

    @Test fun withTheServerOffHybridStillWarnsFromThePhone() {
        val scams = File("src/main/assets/scripts").listFiles { f -> f.name.startsWith("scam_en_") }!!
        for (f in scams) {
            val offline = playHybrid(f) { null }       // every cloud check fails
            assertEquals("${f.name}: same as on-device", play(f), offline.verdict)
            assertTrue("${f.name}: ${offline.verdict}", offline.verdict == "SCAM" || offline.verdict == "UNSURE")
        }
    }

    @Test fun aCalmCallOnlyAsksOnTheSpotCheck() {
        val p = playHybrid(script("normal_en_14_family_call.json")) { 0.05f }
        assertTrue(p.asked.all { it.second.why == "spot_check" || it.second.why == "unsure" })
        assertEquals(p.callerLines / 5, p.asked.count { it.second.why == "spot_check" })
        println("family call: ${p.callerLines} caller lines, cloud asked ${p.asked.size}x (${p.asked.map { it.second.why }}), verdict ${p.verdict}")
    }

    @Test fun aCalmSecondOpinionCanCalmANormalCall() {
        val results = File("src/main/assets/scripts").listFiles { f -> f.name.startsWith("normal_en_") }!!.sortedBy { it.name }
            .associate { it.name to (play(it) to playHybrid(it) { 0.05f }.verdict) }
        results.forEach { (name, v) -> println("normal  on-device ${v.first?.padEnd(6)}  hybrid(BERT calm) ${v.second?.padEnd(6)}  $name") }
        val rank = mapOf("SAFE" to 0, "UNSURE" to 1, "SCAM" to 2)
        results.values.forEach { (local, hybrid) -> assertTrue(rank.getValue(hybrid!!) <= rank.getValue(local!!)) }
    }

    @Test fun nothingIsSentOnceTheCallIsRed() {
        val p = playHybrid(script("scam_en_11_bank_otp.json")) { 0.99f }
        var red = false
        // replay to find when it went red: after that no line may have been sent
        var now = 1_700_000_000_000L
        val call = OnDeviceCall(scorer, clock = { now })
        val lines: List<ScriptLine> = Gson().fromJson(script("scam_en_11_bank_otp.json").readText(), listType)
        var n = 0
        runBlocking {
            lines.filter { !it.text.isNullOrBlank() }.forEach { line ->
                now += line.delayMs ?: 1_500
                if (!line.isCaller) return@forEach
                n += 1
                val a = call.answerHybrid("t", n.toLong(), line.text!!) { 0.99f }
                if (red) assertNull("line $n sent after red", a.second)
                if (a.response.verdict == "SCAM") red = true
            }
        }
        assertTrue(red)
        assertTrue(p.asked.size < p.callerLines)
    }
}
