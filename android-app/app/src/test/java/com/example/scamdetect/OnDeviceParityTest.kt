package com.example.scamdetect

import com.example.scamdetect.ondevice.ImpersonationText
import com.example.scamdetect.ondevice.MoneyText
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.example.scamdetect.ondevice.UrgencyText
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Workstream G.2 parity, on the JVM: the Kotlin cleaning + exported models against the workers' own Python
 * (tools/parity_cases.py wrote the expected cleaned text and score for each of 300 sentences). Every score must be
 * within 0.01 and every cleaned text identical. The same check runs on the phone: OnDeviceParityDeviceTest.
 */
class OnDeviceParityTest {
    companion object {
        private lateinit var scorer: OnDeviceScorer
        private lateinit var cases: List<com.google.gson.JsonObject>
        private var tolerance = 0.01

        @BeforeClass @JvmStatic fun load() {
            scorer = OnDeviceScorer.load { File("src/main/assets/models/$it").bufferedReader() }
            val doc = JsonParser.parseString(File("src/androidTest/assets/parity/parity_cases.json").readText()).asJsonObject
            tolerance = doc["tolerance"].asDouble
            cases = doc["cases"].asJsonArray.map { it.asJsonObject }
        }
    }

    private fun check(signal: String, clean: (String) -> String, score: (String) -> Double) {
        var worst = 0.0
        var worstText = ""
        val failures = mutableListOf<String>()
        for (c in cases) {
            val text = c["text"].asString
            val want = c[signal].asJsonObject
            val gotClean = clean(text)
            if (gotClean != want["clean"].asString) failures += "clean  \"$text\"\n   python: ${want["clean"].asString}\n   kotlin: $gotClean"
            val d = abs(score(text) - want["score"].asDouble)
            if (d > worst) { worst = d; worstText = text }
            if (d > tolerance) failures += "score  \"$text\": python ${want["score"].asDouble}, kotlin ${score(text)}"
        }
        println("$signal: ${cases.size} sentences, largest difference ${"%.6f".format(worst)} (\"${worstText.take(60)}\")")
        assertTrue("$signal: ${failures.size} mismatches\n" + failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test fun atLeast50Sentences() = assertTrue(cases.size >= 50)

    @Test fun urgencyMatchesPython() = check("urgency", UrgencyText::normalize, scorer::urgency)

    @Test fun moneyMatchesPython() = check("money", { MoneyText.clean(it, scorer.moneyBrands) }, scorer::money)

    @Test fun impersonationMatchesPython() {
        val prep = ImpersonationText(scorer.models[2].orgTypes)
        check("impersonation", prep::prep, scorer::impersonation)
    }
}
