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
 * Model files ship inside the app (assets/models/, versions in manifest.json). There is no model download
 * service (G.10) in this repo yet, so the bundled versions are the ones used; tools/export_models.py refreshes them.
 */
class OnDeviceScorer(
    private val urgency: LinearTextModel,
    private val money: LinearTextModel,
    private val impersonation: LinearTextModel,
) {
    private val impersonationText = ImpersonationText(impersonation.orgTypes)

    /** urgency / money / impersonation -> 0-1, for one caller line. */
    fun score(line: String): Map<String, Float> = mapOf(
        "urgency" to urgency(line).toFloat(),
        "money" to money(line).toFloat(),
        "impersonation" to impersonation(line).toFloat(),
    )

    fun urgency(text: String): Double =
        UrgencyText.windows(text).maxOfOrNull { urgency.proba(UrgencyText.normalize(it)) } ?: 0.0

    fun money(text: String): Double =
        MoneyText.splitSentences(text).ifEmpty { listOf(text) }.maxOf { money.proba(MoneyText.clean(it)) }

    fun impersonation(text: String): Double = impersonation.proba(impersonationText.prep(text))

    /** For the benchmark screen and the version note. */
    val models: List<LinearTextModel> get() = listOf(urgency, money, impersonation)

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

        /** Loaded once (about 3 MB of JSON) off the main thread, then kept for the app's lifetime. */
        suspend fun get(context: Context): OnDeviceScorer = cached ?: lock.withLock {
            cached ?: withContext(Dispatchers.Default) {
                load { f -> context.applicationContext.assets.open("$DIR/$f").bufferedReader() }
            }.also { cached = it }
        }
    }
}
