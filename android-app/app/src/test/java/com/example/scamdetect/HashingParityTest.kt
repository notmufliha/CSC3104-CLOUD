package com.example.scamdetect

import com.example.scamdetect.ondevice.OnDeviceScorer
import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Workstream G.10 parity: the federated (hashing) models, as the app downloads them, must score every parity
 * sentence the same as Python (backend/federated/phone_parity.py wrote the files and expected scores). Skipped if
 * those files haven't been generated on this machine (they are rebuilt, not committed: about 8 MB).
 */
class HashingParityTest {
    companion object {
        private val dir = File("../../backend/federated/data/phone_export")
        private var scorer: OnDeviceScorer? = null
        private var cases: List<com.google.gson.JsonObject> = emptyList()
        private var tolerance = 0.001

        @BeforeClass @JvmStatic fun load() {
            if (!File(dir, "parity_expected.json").isFile) return
            scorer = OnDeviceScorer.load { File(dir, it).bufferedReader() }
            val doc = JsonParser.parseString(File(dir, "parity_expected.json").readText()).asJsonObject
            tolerance = doc["tolerance"].asDouble
            cases = doc["cases"].asJsonArray.map { it.asJsonObject }
        }
    }

    private fun check(signal: String, score: (String) -> Double) {
        assumeTrue("run backend/federated/phone_parity.py first", scorer != null)
        assertTrue(scorer!!.models.all { it.isHashing })
        var worst = 0.0
        val failures = mutableListOf<String>()
        for (c in cases) {
            val text = c["text"].asString
            val want = c[signal].asDouble
            val got = score(text)
            worst = maxOf(worst, abs(got - want))
            if (abs(got - want) > tolerance) failures += "\"$text\": python $want, kotlin $got"
        }
        println("$signal (hashing): ${cases.size} sentences, largest difference ${"%.7f".format(worst)}")
        assertTrue("$signal: ${failures.size} mismatches\n" + failures.take(10).joinToString("\n"), failures.isEmpty())
    }

    @Test fun urgencyMatchesPython() = check("urgency") { scorer!!.urgency(it) }
    @Test fun moneyMatchesPython() = check("money") { scorer!!.money(it) }
    @Test fun impersonationMatchesPython() = check("impersonation") { scorer!!.impersonation(it) }
}
