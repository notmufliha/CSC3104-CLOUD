package com.example.scamdetect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scamdetect.ondevice.ImpersonationText
import com.example.scamdetect.ondevice.MoneyText
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.example.scamdetect.ondevice.UrgencyText
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Workstream G.2 parity, on the phone: Android's regex engine (ICU) and Unicode tables are not the JVM's, so the
 * JVM test (OnDeviceParityTest) alone doesn't prove the phone matches Python. Same 300 sentences, same 0.01 limit;
 * the models are the real ones the app loads from its assets.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceParityDeviceTest {
    @Test fun everyScoreWithin001OfPython() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val scorer = OnDeviceScorer.get(inst.targetContext)
        val doc = inst.context.assets.open("parity/parity_cases.json").bufferedReader().use { JsonParser.parseString(it.readText()) }.asJsonObject
        val tolerance = doc["tolerance"].asDouble
        val cases = doc["cases"].asJsonArray.map { it.asJsonObject }
        val prep = ImpersonationText(scorer.models[2].orgTypes)
        val checks = listOf(
            Triple("urgency", UrgencyText::normalize, scorer::urgency),
            Triple("money", { t: String -> MoneyText.clean(t, scorer.moneyBrands) }, scorer::money),
            Triple("impersonation", prep::prep, scorer::impersonation),
        )
        val failures = mutableListOf<String>()
        for ((signal, clean, score) in checks) {
            var worst = 0.0
            for (c in cases) {
                val text = c["text"].asString
                val want = c[signal].asJsonObject
                val got = score(text)
                val d = abs(got - want["score"].asDouble)
                worst = maxOf(worst, d)
                if (d > tolerance) failures += "$signal score \"$text\": python ${want["score"].asDouble}, phone $got"
                if (clean(text) != want["clean"].asString) failures += "$signal clean \"$text\": python \"${want["clean"].asString}\", phone \"${clean(text)}\""
            }
            println("G2-PARITY $signal: ${cases.size} sentences on the phone, largest difference ${"%.6f".format(worst)}")
        }
        assertTrue("${failures.size} mismatches on the phone\n" + failures.take(15).joinToString("\n"), failures.isEmpty())
    }
}
