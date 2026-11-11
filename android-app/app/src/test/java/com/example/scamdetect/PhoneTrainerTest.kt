package com.example.scamdetect

import com.example.scamdetect.ondevice.OnDeviceScorer
import com.example.scamdetect.ondevice.PhoneTrainer
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp
import kotlin.random.Random

/**
 * Real-phone training on the JVM, with the hashing models the app downloads (backend/federated/phone_parity.py
 * writes them). Checks that training moves scores the right way, that clipping holds, and writes an upload that
 * backend/federated's inbox + bridge accept (test_upload.json, used by the Python end-to-end check).
 */
class PhoneTrainerTest {
    private val dir = File("../../backend/federated/data/phone_export")

    private val scam = PhoneTrainer.LabelledCall(1, listOf(
        "This is Officer Tan from the Singapore Police Force.",
        "Your bank account is linked to a money laundering case.",
        "You must transfer the money now or a warrant will be issued for your arrest.",
        "Read me the six digit OTP that was just sent to your phone.",
    ))
    private val falseAlarm = PhoneTrainer.LabelledCall(0, listOf(
        "Hi, this is the clinic calling to remind you of your appointment tomorrow.",
        "Please arrive 15 minutes early and bring your NRIC.",
        "If you need to reschedule, call us before 5pm today.",
    ))

    private fun scorer(): OnDeviceScorer {
        assumeTrue("run backend/federated/phone_parity.py first", File(dir, "urgency_en.json").isFile)
        return OnDeviceScorer.load { File(dir, it).bufferedReader() }
    }

    private fun decode(b64: String): FloatArray {
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(buf.remaining() / 4) { buf.getFloat() }
    }

    @Test fun trainingMovesScoresTheRightWay() {
        val s = scorer()
        val trainer = PhoneTrainer(s, lr = 5.0, epochs = 3, clip = 1e9, localNoise = 0.0, random = Random(1))
        val ex = trainer.examples(listOf(scam, falseAlarm))
        println("examples: " + ex.mapValues { (_, v) -> "${v.count { it.y == 1 }} positive, ${v.count { it.y == 0 }} negative" })
        assertTrue("the scam call should give positive examples", ex.values.sumOf { v -> v.count { it.y == 1 } } > 0)
        val updates = trainer.train(ex)
        assertTrue(updates.isNotEmpty())
        for (u in updates) {
            val model = s.model(u.signal)
            val w0 = model.weights()
            fun z(text: String, delta: Boolean) = s.trainingTexts(u.signal, text).maxOf { t ->
                model.features(t).entries.sumOf { (k, v) -> (w0[k] + if (delta) u.delta[k] else 0f) * v } +
                    model.intercept + if (delta) u.interceptDelta else 0.0
            }
            for (ex1 in ex.getValue(u.signal)) {
                val before = ex1.x.entries.sumOf { (k, v) -> w0[k] * v } + model.intercept
                val after = ex1.x.entries.sumOf { (k, v) -> (w0[k] + u.delta[k]) * v } + model.intercept + u.interceptDelta
                if (ex1.y == 1) assertTrue("${u.signal}: positive example should score higher", after > before)
                else assertTrue("${u.signal}: negative example should score lower", after < before)
            }
            println("${u.signal}: ${u.lines} lines; e.g. raw score of a scam line ${"%.3f".format(z(scam.lines[2], false))} -> ${"%.3f".format(z(scam.lines[2], true))}")
        }
    }

    @Test fun uploadIsClippedNoisedAndInTheInboxFormat() {
        val s = scorer()
        val trainer = PhoneTrainer(s, clip = 10.0, localNoise = 1.0, random = Random(7))
        val updates = trainer.train(trainer.examples(listOf(scam, falseAlarm)))
        val json = trainer.uploadJson(updates, "0f3c2a1b-1111-4222-8333-123456789abc",
            mapOf("urgency" to "v1", "money" to "v1", "impersonation" to "v1"))
        val doc = JsonParser.parseString(json).asJsonObject
        assertEquals("fl-update", doc["format"].asString)
        var norm2 = 0.0
        for ((signal, m) in doc["models"].asJsonObject.entrySet()) {
            val d = decode(m.asJsonObject["delta_b64"].asString)
            assertEquals(s.model(signal).featureCount, d.size)
            // noise on every weight: almost no slot is exactly 0, so the upload doesn't show which slots were used
            assertTrue("$signal: noise should cover the weights", d.count { it == 0f } < d.size / 100)
            norm2 += d.sumOf { (it * it).toDouble() }
        }
        val norm = kotlin.math.sqrt(norm2)
        println("upload: ${json.length / 1e6} MB JSON, change + noise norm %.2f (clip 10, noise 10)".format(norm))
        assertTrue("clipped change + noise of the same size", norm < 10.0 * 1.5 + 10.0)
        File(dir, "test_upload.json").writeText(json)
    }

    @Suppress("unused") private fun sigmoid(z: Double) = 1 / (1 + exp(-z))
}
