package com.example.scamdetect.ondevice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.LongBuffer
import java.text.Normalizer
import kotlin.math.exp

/**
 * Workstream G.4: MobileBERT on the phone, the level-3 second opinion (the on-phone stand-in for the cloud's BERT).
 * Training and export are Goutham's; this is the loader. Until a model is bundled the app says "MobileBERT not
 * installed" and level 3 runs on the small models only.
 *
 * Files, in assets/models/mobilebert/ (see android-app/tools/README.md for the exact contract):
 *   model.onnx         fine-tuned MobileBERT, ONNX (INT8), inputs input_ids / attention_mask / token_type_ids
 *   vocab.txt          its WordPiece vocabulary
 *   bert_config.json   optional: version, max_len, temperature, scam_index, max_turns, lower_case, model_file
 *
 * Scoring follows the server's BERT (backend/workers/bert/predict.py + bert_text.py) step by step: the last 5
 * caller lines are normalised (NFKC, whitespace collapsed) and joined with " | ", tokenised with left truncation
 * (the newest line is always read), and score = softmax(logits / temperature)[scam_index].
 */
class MobileBert private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
    val config: Config,
    val modelBytes: Long,
) : AutoCloseable {

    data class Config(
        val version: String = "unknown",
        @SerializedName("max_len") val maxLen: Int = 128,
        val temperature: Double = 1.0,
        @SerializedName("scam_index") val scamIndex: Int = 1,
        @SerializedName("max_turns") val maxTurns: Int = 5,
        @SerializedName("lower_case") val lowerCase: Boolean = true,
        @SerializedName("model_file") val modelFile: String = "model.onnx",
    )

    private val inputs = session.inputNames

    /** P(scam) for the caller's recent lines, oldest first. Empty -> 0. */
    fun score(lines: List<String>): Float {
        val text = window(lines, config.maxTurns)
        if (text.isEmpty()) return 0f
        val ids = tokenizer.encode(text, config.maxLen)
        val shape = longArrayOf(1, ids.size.toLong())
        val feed = buildMap {
            if ("input_ids" in inputs) put("input_ids", ids)
            if ("attention_mask" in inputs) put("attention_mask", LongArray(ids.size) { 1 })
            if ("token_type_ids" in inputs) put("token_type_ids", LongArray(ids.size))
        }.mapValues { (_, v) -> OnnxTensor.createTensor(env, LongBuffer.wrap(v), shape) }
        try {
            session.run(feed).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                return softmax(logits, config.temperature)[config.scamIndex].toFloat()
            }
        } finally {
            feed.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    /** Whether the model is there, for the Settings screen and the benchmark. */
    sealed interface Status {
        data class Installed(val model: MobileBert) : Status
        /** No model file bundled (the normal case until Goutham's export exists), or it failed to load. */
        data class NotInstalled(val reason: String) : Status
    }

    companion object {
        const val DIR = "models/mobilebert"

        /** bert_text.make_window: NFKC, whitespace collapsed, empty lines dropped, the last `maxTurns`, " | ". */
        fun window(lines: List<String>, maxTurns: Int = 5): String = lines
            .map { WS.replace(Normalizer.normalize(it, Normalizer.Form.NFKC), " ").trim() }
            .filter { it.isNotEmpty() }
            .takeLast(maxTurns)
            .joinToString(" | ")
        private val WS = Regex("\\s+")

        fun softmax(logits: FloatArray, temperature: Double): DoubleArray {
            val z = logits.map { it / temperature }
            val max = z.max()
            val e = z.map { exp(it - max) }
            val sum = e.sum()
            return e.map { it / sum }.toDoubleArray()
        }

        /** Loads from any file source; NotInstalled when model.onnx or vocab.txt is missing. */
        fun load(open: (String) -> java.io.InputStream?): Status {
            val cfg = open("bert_config.json")?.bufferedReader()?.use { Gson().fromJson(it, Config::class.java) } ?: Config()
            val modelBytes = open(cfg.modelFile)?.use { it.readBytes() }
                ?: return Status.NotInstalled("MobileBERT not installed (no ${cfg.modelFile})")
            val vocab = open("vocab.txt")?.bufferedReader()?.use { r -> WordPieceTokenizer.fromVocabLines(r.lineSequence().toList().asSequence(), cfg.lowerCase) }
                ?: return Status.NotInstalled("MobileBERT not installed (no vocab.txt)")
            return try {
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
                Status.Installed(MobileBert(env, env.createSession(modelBytes, opts), vocab, cfg, modelBytes.size.toLong()))
            } catch (e: Exception) {
                Status.NotInstalled("MobileBERT could not be loaded: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
        }

        private val lock = Mutex()
        @Volatile private var cached: Status? = null

        /** From the app's assets, once. */
        suspend fun get(context: Context): Status = cached ?: lock.withLock {
            cached ?: withContext(Dispatchers.Default) {
                val assets = context.applicationContext.assets
                load { name ->
                    try { assets.open("$DIR/$name") } catch (e: FileNotFoundException) { null }
                }
            }.also { cached = it }
        }
    }
}
