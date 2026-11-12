package com.example.scamdetect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scamdetect.ondevice.MobileBert
import com.example.scamdetect.ondevice.OnDeviceCall
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import kotlin.math.abs

/**
 * Workstream G.4 on the phone, with the DUMMY model from tools/mobilebert_fixtures.py (same interface as the real
 * MobileBERT export, meaningless scores): ONNX Runtime Mobile loads it, and window -> tokenizer -> model ->
 * softmax(logits / T) gives the score Python's onnxruntime gave, for every window.
 */
@RunWith(AndroidJUnit4::class)
class MobileBertDeviceTest {
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun loadDummy(): MobileBert {
        val status = MobileBert.load { name ->
            try { testAssets.open("mobilebert_dummy/$name") } catch (e: FileNotFoundException) { null }
        }
        assertTrue("dummy model should load: $status", status is MobileBert.Status.Installed)
        return (status as MobileBert.Status.Installed).model
    }

    @Test fun dummyModelScoresMatchPythonOnnxRuntime() {
        val model = loadDummy()
        assertEquals("DUMMY-not-a-real-model", model.config.version)
        val cases = testAssets.open("mobilebert_dummy/cases.json").bufferedReader().use { JsonParser.parseString(it.readText()) }
            .asJsonObject["cases"].asJsonArray.map { it.asJsonObject }
        var worst = 0.0
        val failures = cases.mapNotNull { c ->
            val lines = c["lines"].asJsonArray.map { it.asString }
            val got = model.score(lines).toDouble()
            val d = abs(got - c["score"].asDouble)
            worst = maxOf(worst, d)
            if (d > 1e-4) "\"${c["window"].asString.take(60)}\": python ${c["score"].asDouble}, phone $got" else null
        }
        println("G4-DUMMY ${cases.size} windows, largest difference ${"%.7f".format(worst)}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        model.close()
    }

    /** The real model in assets/models/mobilebert/ (notebooks/mobilebert_g4.ipynb) loads and gives a probability. */
    @Test fun theAppItselfLoadsTheBundledModel() = runBlocking {
        val status = MobileBert.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assertTrue("the bundled MobileBERT should load: $status", status is MobileBert.Status.Installed)
        val model = (status as MobileBert.Status.Installed).model
        assertTrue("version ${model.config.version}", model.config.version.startsWith("mobilebert-"))
        val p = model.score(listOf(
            "This is the police. Your bank account is linked to money laundering.",
            "Transfer all your savings to the safe account now and read me the OTP.",
        ))
        println("G4-REAL ${model.config.version}: police/OTP window scores $p")
        assertTrue("score $p", p in 0f..1f)
    }

    /** Level 3 with a second opinion: asked only at unsure moments / the spot check, its score blended in. */
    @Test fun levelThreeAsksMobileBertLikeHybridAsksTheCloud() = runBlocking {
        val model = loadDummy()
        val scorer = OnDeviceScorer.get(InstrumentationRegistry.getInstrumentation().targetContext)
        var now = 0L
        val call = OnDeviceCall(scorer, clock = { now })
        val lines = listOf(
            "Good afternoon, this is Officer Tan from the Singapore Police Force.",
            "Your bank account has been linked to a money laundering investigation.",
            "Please read me the six digit OTP that was just sent to your phone.",
        )
        val asked = lines.mapIndexedNotNull { i, text ->
            now += 20_000
            call.answerWithSecondOpinion("t", i.toLong(), text, { it }) { w -> model.score(w) }.second
        }
        assertTrue(asked.isNotEmpty())
        asked.forEach { assertTrue(it.bertScore != null && it.lines.size <= 5) }
        model.close()
    }
}
