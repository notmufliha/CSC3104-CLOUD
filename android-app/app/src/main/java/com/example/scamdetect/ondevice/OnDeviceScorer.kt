package com.example.scamdetect.ondevice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Reader

/**
 * Workstream G.2: the three English checks, run on the phone. Each signal is scored the way its worker scores
 * a caller line (the gateway sends each worker one line), so the numbers match the server's:
 *   urgency        highest probability over the line's sentences and pairs of sentences (predict.score)
 *   money          highest calibrated probability over the line's sentences (MoneyModel.score_chunk)
 *   impersonation  calibrated probability of the whole line (predict.score)
 *
 * Model files ship inside the app (assets/models/, versions in manifest.json). G.10: newer federated versions
 * are downloaded from the model registry (data/ModelUpdater.kt) and used instead; see get().
 */
class OnDeviceScorer(
    private val urgency: LinearTextModel,
    private val money: LinearTextModel,
    private val impersonation: LinearTextModel,
) {
    private val impersonationText = ImpersonationText(impersonation.orgTypes)
    /** money_en v2c cleans with brand masking; a v1 model file has none (exactly v1's cleaning). */
    val moneyBrands: MoneyBrands? = if (money.maskBrands) MoneyBrands(money.brandTypes) else null

    /** urgency / money / impersonation -> 0-1, for one caller line. */
    fun score(line: String): Map<String, Float> = mapOf(
        "urgency" to urgency(line).toFloat(),
        "money" to money(line).toFloat(),
        "impersonation" to impersonation(line).toFloat(),
    )

    fun urgency(text: String): Double =
        UrgencyText.windows(text).maxOfOrNull { urgency.proba(UrgencyText.normalize(it)) } ?: 0.0

    fun money(text: String): Double =
        MoneyText.splitSentences(text).ifEmpty { listOf(text) }.maxOf { money.proba(MoneyText.clean(it, moneyBrands)) }

    fun impersonation(text: String): Double = impersonation.proba(impersonationText.prep(text))

    /** For the benchmark screen and the version note. */
    val models: List<LinearTextModel> get() = listOf(urgency, money, impersonation)

    /** G (real-phone training): the model behind a signal. */
    fun model(signal: String): LinearTextModel = when (signal) {
        "urgency" -> urgency
        "money" -> money
        "impersonation" -> impersonation
        else -> error("unknown signal $signal")
    }

    /** G (real-phone training): one caller line as that model's training rows, cleaned the way it trained. */
    fun trainingTexts(signal: String, line: String): List<String> = when (signal) {
        "urgency" -> listOf(UrgencyText.normalize(line))
        "money" -> MoneyText.splitSentences(line).ifEmpty { listOf(line) }.map { MoneyText.clean(it, moneyBrands) }
        "impersonation" -> listOf(impersonationText.prep(line))
        else -> error("unknown signal $signal")
    }.filter { it.isNotBlank() }

    /** G (real-phone training): this signal's score for one caller line, as during a call. */
    fun score(signal: String, line: String): Double = when (signal) {
        "urgency" -> urgency(line)
        "money" -> money(line)
        "impersonation" -> impersonation(line)
        else -> error("unknown signal $signal")
    }

    companion object {
        const val DIR = "models"
        val FILES = listOf("urgency_en.json", "money_en.json", "impersonation_en.json")

        /** From any source of the three files (assets on the phone, the source tree in JVM tests). */
        fun load(open: (String) -> Reader): OnDeviceScorer {
            val (u, m, i) = FILES.map { f -> open(f).use { LinearTextModel.load(it) } }
            return OnDeviceScorer(u, m, i)
        }

        private val lock = Mutex()
        @Volatile private var cached: OnDeviceScorer? = null

        /**
         * Loaded once off the main thread, then kept until [reload]. G.10: a model downloaded from the registry
         * (files/models/, ModelUpdater) wins over the one built into the app; if it can't be read, the built-in
         * one is used.
         */
        suspend fun get(context: Context): OnDeviceScorer = cached ?: lock.withLock {
            cached ?: withContext(Dispatchers.Default) {
                val app = context.applicationContext
                val (u, m, i) = FILES.map { f ->
                    val downloaded = java.io.File(app.filesDir, "$DIR/$f")
                    (if (downloaded.isFile) runCatching { downloaded.bufferedReader().use { LinearTextModel.load(it) } }.getOrNull() else null)
                        ?: app.assets.open("$DIR/$f").bufferedReader().use { LinearTextModel.load(it) }
                }
                OnDeviceScorer(u, m, i)
            }.also { cached = it }
        }

        /** G.10: forget the loaded models, so the next call picks up the ones just downloaded. */
        fun reload() { cached = null }
    }
}
