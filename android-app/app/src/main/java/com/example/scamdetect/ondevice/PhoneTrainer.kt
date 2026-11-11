package com.example.scamdetect.ondevice

import com.google.gson.Gson
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Workstream G, real phones: train the downloaded (hashing) models on this phone's own labelled calls, and turn the
 * result into an upload that carries only the change to the models' numbers.
 *
 * Where the examples come from (G.12's implicit labels, kept only while "Help improve detection" is on):
 *  - a call labelled SCAM (hung up within 10 s of a red alert, or a very sure end-of-call review): every caller line
 *    a model already scored >= [POSITIVE] becomes a positive example for that model. It strengthens what caught it.
 *  - a call labelled FALSE ALARM ("This was fine"): every line a model scored >= [NEGATIVE] becomes a negative
 *    example for that model. It teaches the model not to raise that alarm again.
 *  Other lines say nothing about a signal and are left out.
 *
 * Training is the server's fedcore.local_train, line for line: weighted log loss + L2, mini-batch gradient descent
 * from the current weights, balanced by this phone's own counts. Then, before anything leaves the phone:
 *  1. change = new weights - downloaded weights (all three models together)
 *  2. clip: scale the whole change down to norm [clip] (G.8), so no phone can move the shared model much
 *  3. noise: add Gaussian noise to EVERY weight, total size = localNoise x clip, so the upload doesn't show which
 *     words (slots) this phone's calls contained
 * The upload has no text, no labels and no phone number: just numbers, a random install id and the line counts.
 */
class PhoneTrainer(
    private val scorer: OnDeviceScorer,
    private val lr: Double = 1.0,           // the server's weekly-round setting (server.py WEEKLY_LR / WEEKLY_EPOCHS)
    private val epochs: Int = 1,
    private val l2: Double = 1e-5,
    private val batch: Int = 32,
    private val clip: Double = 10.0,
    private val localNoise: Double = 1.0,
    private val random: Random = Random.Default,
) {
    data class LabelledCall(val label: Int, val lines: List<String>)
    data class Example(val x: Map<Int, Double>, val y: Int)
    class ModelUpdate(val signal: String, val lines: Int, val delta: FloatArray, val interceptDelta: Double)

    /** Per signal, the training rows this phone's labelled calls give. */
    fun examples(calls: List<LabelledCall>): Map<String, List<Example>> = SIGNALS.associateWith { signal ->
        val model = scorer.model(signal)
        calls.flatMap { call ->
            call.lines.flatMap { line ->
                val s = scorer.score(signal, line)
                val y = when {
                    call.label == 1 && s >= POSITIVE -> 1
                    call.label == 0 && s >= NEGATIVE -> 0
                    else -> return@flatMap emptyList()
                }
                scorer.trainingTexts(signal, line).map { Example(model.features(it), y) }.filter { it.x.isNotEmpty() }
            }
        }
    }

    /** Train every hashing model that has examples; returns the clipped, noised changes (empty if nothing to learn). */
    fun train(examples: Map<String, List<Example>>): List<ModelUpdate> {
        val raw = SIGNALS.mapNotNull { signal ->
            val ex = examples[signal].orEmpty()
            val model = scorer.model(signal)
            if (ex.isEmpty() || !model.isHashing) return@mapNotNull null
            val w0 = model.weights()
            val (w, b) = localTrain(w0.copyOf(), model.intercept, ex)
            Triple(signal, ex.size, DoubleArray(w.size) { w[it] - w0[it] } to (b - model.intercept))
        }
        if (raw.isEmpty()) return emptyList()
        // 2. clip the whole change (all models together), like fedcore.clip_update
        val norm = sqrt(raw.sumOf { (_, _, d) -> d.first.sumOf { it * it } + d.second * d.second })
        val scale = if (norm > clip) clip / norm else 1.0
        // 3. noise on every weight of every uploaded model; total noise size ~ localNoise x clip
        val dims = raw.sumOf { it.third.first.size + 1 }
        val sd = localNoise * clip / sqrt(dims.toDouble())
        return raw.map { (signal, lines, d) ->
            ModelUpdate(signal, lines, FloatArray(d.first.size) { (d.first[it] * scale + sd * gaussian()).toFloat() },
                d.second * scale + sd * gaussian())
        }
    }

    /** fedcore.local_train in Kotlin. */
    private fun localTrain(w: DoubleArray, b0: Double, ex: List<Example>): Pair<DoubleArray, Double> {
        var b = b0
        val pos = ex.count { it.y == 1 }
        val n = ex.size
        val cw = if (pos == 0 || pos == n) doubleArrayOf(1.0, 1.0)
                 else doubleArrayOf(n / (2.0 * (n - pos)), n / (2.0 * pos))
        val steps = (n + batch - 1) / batch
        repeat(epochs) {
            val order = ex.indices.shuffled(random)
            for (s in order.indices step batch) {
                val idx = order.subList(s, minOf(s + batch, n))
                val grad = HashMap<Int, Double>()
                var gb = 0.0
                for (i in idx) {
                    val e = ex[i]
                    var z = b
                    for ((k, v) in e.x) z += w[k] * v
                    val err = (1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0))) - e.y) * cw[e.y]
                    for ((k, v) in e.x) grad[k] = (grad[k] ?: 0.0) + err * v
                    gb += err
                }
                for ((k, g) in grad) w[k] -= lr * g / idx.size
                b -= lr * gb / idx.size
            }
            val shrink = Math.pow(1.0 - lr * l2, steps.toDouble())
            for (k in w.indices) w[k] *= shrink
        }
        return w to b
    }

    private fun gaussian(): Double {   // Box-Muller
        val u = random.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * random.nextDouble())
    }

    /** The upload (backend/federated/inbox.py, format "fl-update" v1). [baseVersions]: signal -> "v3". */
    fun uploadJson(updates: List<ModelUpdate>, phoneId: String, baseVersions: Map<String, String>): String =
        Gson().toJson(mapOf(
            "format" to "fl-update", "format_version" to 1, "phone" to phoneId,
            "clip" to clip, "local_noise" to localNoise,
            "models" to updates.associate { u ->
                u.signal to mapOf("base" to baseVersions.getValue(u.signal), "lines" to u.lines,
                    "delta_b64" to encode(u.delta), "intercept_delta" to u.interceptDelta)
            },
        ))

    companion object {
        val SIGNALS = listOf("urgency", "money", "impersonation")
        const val POSITIVE = 0.5
        const val NEGATIVE = 0.3

        fun encode(v: FloatArray): String {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return Base64.getEncoder().encodeToString(buf.array())
        }
    }
}
