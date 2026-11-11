package com.example.scamdetect.ondevice

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.Reader
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Workstream G.2: one exported English model (assets/models/<name>_en.json, written by tools/export_models.py):
 * TF-IDF vectoriser(s) -> logistic regression -> optional Platt calibration. Reproduces scikit-learn's
 * TfidfVectorizer.transform + LogisticRegression.decision_function + predict_proba in plain Kotlin.
 * Text must already be cleaned the way the worker cleans it (see TextCleaning.kt).
 *
 * Workstream G.10, format 2: a vectoriser can instead be a HASHING one (the federated models, downloaded from the
 * model registry; backend/federated/export_phone.py). Same n-grams, but a token's slot is
 * abs(murmurhash3_32(token)) % n_features instead of a word-list lookup, with a frozen idf per slot.
 */
class LinearTextModel private constructor(private val doc: Doc) {
    val name: String get() = doc.name
    val version: String get() = doc.version
    val threshold: Double get() = doc.threshold
    val orgTypes: Map<String, List<String>> get() = doc.orgTypes.orEmpty()
    /** money_en v2: the model was trained on brand-masked text (money_norm.clean_text(mask_brands=True)). */
    val maskBrands: Boolean get() = doc.maskBrands == true
    val brandTypes: Map<String, List<String>> get() = doc.brandTypes.orEmpty()
    val featureCount: Int get() = parts.sumOf { it.size }
    /** Format 2 = hashing (federated, downloaded); format 1 = TF-IDF word list (built into the app). */
    val isHashing: Boolean get() = doc.format == 2

    private val parts = doc.vectorizers.map { Part(it) }

    /** Probability (0-1) for one cleaned text. */
    fun proba(cleaned: String): Double {
        val z = decision(cleaned)
        val cal = doc.calibration
        return sigmoid(if (cal != null) cal.a * z + cal.b else z)
    }

    /** The logistic regression's raw score (before the sigmoid / calibration). */
    fun decision(cleaned: String): Double = doc.intercept + parts.sumOf { it.dot(cleaned) }

    private class Part(v: Vectorizer) {
        private val analyzer = v.analyzer
        private val minN = v.ngramRange[0]
        private val maxN = v.ngramRange[1]
        private val lowercase = v.lowercase
        private val sublinear = v.sublinearTf
        private val stopWords = v.stopWords.toHashSet()
        private val token = Py.re(v.tokenPattern.removePrefix("(?u)"))   // (?u) is Python's default anyway
        private val hashSlots = v.nFeatures
        private val index: HashMap<String, Int>? = v.terms?.let { terms ->
            HashMap<String, Int>(terms.size * 2).apply { terms.forEachIndexed { i, t -> put(t, i) } }
        }
        private val idf: DoubleArray? = v.idf
        private val idfDefault = v.idfDefault ?: 0.0
        private val idfSeen: HashMap<Int, Double>? = v.idfIndex?.let { ix ->
            val values = v.idfValue ?: error("model file is inconsistent: idf_index without idf_value")
            HashMap<Int, Double>(ix.size * 2).apply { ix.forEachIndexed { k, slot -> put(slot, values[k]) } }
        }
        private val coef: DoubleArray = v.coef ?: decodeFloats(v.coefB64 ?: error("model file has no weights"))
        val size get() = coef.size

        init {
            require(analyzer == "word" || analyzer == "char_wb") { "unsupported analyzer $analyzer" }
            if (hashSlots != null) require(coef.size == hashSlots) { "model file is inconsistent: ${coef.size} weights for $hashSlots slots" }
            else require(v.terms?.size == coef.size && idf?.size == coef.size) { "model file is inconsistent" }
        }

        private fun slotOf(gram: String): Int? = if (hashSlots != null) Murmur3.slot(gram, hashSlots) else index!![gram]
        private fun idfOf(i: Int): Double = idf?.get(i) ?: (idfSeen?.get(i) ?: idfDefault)

        /** tf-idf (sublinear, l2-normalised) of the text, dotted with this part's weights. */
        fun dot(text: String): Double {
            val doc = if (lowercase) Py.lower(text) else text
            val counts = HashMap<Int, Int>()
            for (gram in if (analyzer == "word") wordNgrams(doc) else charWbNgrams(doc)) {
                val i = slotOf(gram) ?: continue
                counts[i] = (counts[i] ?: 0) + 1
            }
            if (counts.isEmpty()) return 0.0
            var norm = 0.0
            var dot = 0.0
            for ((i, c) in counts) {
                val w = (if (sublinear) ln(c.toDouble()) + 1.0 else c.toDouble()) * idfOf(i)
                norm += w * w
                dot += w * coef[i]
            }
            return if (norm > 0) dot / sqrt(norm) else 0.0
        }

        /** sklearn _word_ngrams: tokens minus stop words, then all n-grams joined by a space. */
        private fun wordNgrams(doc: String): List<String> {
            val tokens = token.findAll(doc).map { it.value }.filter { it !in stopWords }.toList()
            val out = ArrayList<String>(tokens.size * (maxN - minN + 1))
            for (n in minN..minOf(maxN, tokens.size)) {
                for (i in 0..tokens.size - n) out += if (n == 1) tokens[i] else tokens.subList(i, i + n).joinToString(" ")
            }
            return out
        }

        /** sklearn _char_wb_ngrams: per whitespace-separated word, padded with one space each side (code points). */
        private fun charWbNgrams(doc: String): List<String> {
            val out = ArrayList<String>()
            for (word in Py.split(doc)) {
                val w = (" $word ").codePoints().toArray()
                val len = w.size
                for (n in minN..maxN) {
                    var offset = 0
                    out += String(w, offset, minOf(n, len))
                    while (offset + n < len) {
                        offset += 1
                        out += String(w, offset, n)
                    }
                    if (offset == 0) break   // the word is shorter than n: longer n-grams would repeat it
                }
            }
            return out
        }
    }

    private class Doc(
        val format: Int,
        val name: String,
        val version: String,
        val threshold: Double,
        val vectorizers: List<Vectorizer>,
        val intercept: Double,
        val calibration: Calibration?,
        @SerializedName("org_types") val orgTypes: Map<String, List<String>>?,
        @SerializedName("mask_brands") val maskBrands: Boolean?,
        @SerializedName("brand_types") val brandTypes: Map<String, List<String>>?,
    )

    private class Vectorizer(
        val analyzer: String,
        @SerializedName("ngram_range") val ngramRange: List<Int>,
        val lowercase: Boolean,
        @SerializedName("token_pattern") val tokenPattern: String,
        @SerializedName("stop_words") val stopWords: List<String>,
        @SerializedName("sublinear_tf") val sublinearTf: Boolean,
        // format 1 (word list)
        val terms: List<String>?,
        val idf: DoubleArray?,
        val coef: DoubleArray?,
        // format 2 (hashing)
        @SerializedName("n_features") val nFeatures: Int?,
        @SerializedName("idf_default") val idfDefault: Double?,
        @SerializedName("idf_index") val idfIndex: IntArray?,
        @SerializedName("idf_value") val idfValue: DoubleArray?,
        @SerializedName("coef_b64") val coefB64: String?,
    )

    private class Calibration(val a: Double, val b: Double)

    companion object {
        const val FORMAT = 1
        const val FORMAT_HASHING = 2

        fun load(reader: Reader): LinearTextModel {
            val doc = Gson().fromJson(reader, Doc::class.java)
            require(doc.format == FORMAT || doc.format == FORMAT_HASHING) {
                "model file format ${doc.format}, this app reads $FORMAT and $FORMAT_HASHING"
            }
            return LinearTextModel(doc)
        }

        /** float32 little-endian, base64 (export_phone.py) -> doubles. */
        private fun decodeFloats(b64: String): DoubleArray {
            val buf = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(b64)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return DoubleArray(buf.remaining() / 4) { buf.getFloat().toDouble() }
        }

        private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))
    }
}
