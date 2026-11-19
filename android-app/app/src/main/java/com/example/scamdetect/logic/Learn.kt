package com.example.scamdetect.logic

import com.example.scamdetect.R
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlin.random.Random

/**
 * "Learn to spot scams": the scam-type cards and the practice quiz. The content is one bundled file shared by both
 * apps (assets/learn/learn.json, built by tools/learn/build_learn.py from tools/learn/scam_types.json, the call
 * scripts and tools/testing/expected.json). Pure, kept in step with ios-app Logic/Learn.swift.
 */
data class ScamType(
    val id: String,
    val title: String,
    val signs: List<String>,
    val todo: String,
    /** The script "Hear an example" plays as a practice call. */
    val script: String,
    @SerializedName("source_name") val sourceName: String,
    @SerializedName("source_url") val sourceUrl: String,
)

/** One quiz question: a real caller line from our scripts. */
data class QuizItem(val text: String, val scam: Boolean, val why: String, val script: String, val type: String? = null)

data class LearnContent(val version: Int = 1, val types: List<ScamType> = emptyList(), val quiz: List<QuizItem> = emptyList())

object Learn {
    fun parse(json: String): LearnContent = Gson().fromJson(json, LearnContent::class.java)
}

object Quiz {
    const val SIZE = 5
    /** At least this many of each kind in a round, so a round is never all "Scam" or all "Safe". */
    private const val MIN_EACH = 2

    /** The lines a round may use: never a learning-loop test script (90+). */
    fun pool(content: LearnContent): List<QuizItem> = content.quiz.filterNot { DevScripts.isTestCall(it.script) }

    /**
     * A round of [SIZE] questions in random order: at least 2 scam and 2 safe lines, lines in [avoid] (the last round's)
     * only when nothing else is left. "Try again" passes the previous round, so it picks new lines.
     */
    fun round(content: LearnContent, random: Random = Random.Default, avoid: Set<String> = emptySet()): List<QuizItem> {
        val pool = pool(content)
        fun pick(items: List<QuizItem>, n: Int, taken: Set<String>): List<QuizItem> {
            val fresh = items.filter { it.text !in avoid && it.text !in taken }.shuffled(random)
            val old = items.filter { it.text in avoid && it.text !in taken }.shuffled(random)
            return (fresh + old).take(n)
        }
        val scam = pick(pool.filter { it.scam }, MIN_EACH, emptySet())
        val safe = pick(pool.filter { !it.scam }, MIN_EACH, emptySet())
        val taken = (scam + safe).map { it.text }.toSet()
        val rest = pick(pool, SIZE - scam.size - safe.size, taken)
        return (scam + safe + rest).shuffled(random)
    }

    fun isRight(item: QuizItem, saidScam: Boolean): Boolean = item.scam == saidScam

    /** The friendly line under the result (all that Simple mode shows). */
    fun resultLine(score: Int, of: Int = SIZE): Int = when {
        score >= of -> R.string.quiz_r5
        score == of - 1 -> R.string.quiz_r4
        score == of - 2 -> R.string.quiz_r3
        else -> R.string.quiz_r_low
    }

    /** The best score kept on the phone: the higher one. */
    fun best(saved: Int?, score: Int): Int = maxOf(saved ?: 0, score)
}
