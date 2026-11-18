package com.example.scamdetect.logic

import com.example.scamdetect.data.CallScript
import kotlin.random.Random

/**
 * How the call scripts are named and grouped in the app (the files themselves are never renamed: the tools match
 * them by name). Pure; the same rules as ios-app/ScamGuard/Logic/ScriptTitles.swift.
 *
 * - Workstream H's learning-loop test calls ("new_scam_test" in the file name, see demo/LEARNING_LOOP.md) get clear
 *   titles, sit in their own picker section and are never chosen by "Simulate scam call".
 * - Acronyms and names in file names are written properly ("Bank OTP", "ICA to China police").
 * - A Chinese script with the same title as an English one gets " (Chinese)".
 */
object ScriptTitles {
    /** File-name marker of H's learning-loop tests (BenchmarkTest, OnDeviceCallTest and endcall_check.py use it too). */
    const val LEARNING_TEST_MARKER = "new_scam_test"

    const val TEST_SECTION = "Test calls (learning loop)"
    const val TEST_SECTION_NOTE = "Meant to be missed until the models learn this scam."

    /** Titles given by hand, by file name without ".json". */
    private val OVERRIDES = mapOf(
        "scam_en_90_new_scam_test_A_report_this" to "Learning test A (new scam type)",
        "scam_en_91_new_scam_test_B_same_scam_new_words" to "Learning test B (same scam, new words)",
    )

    /** Words written in capitals, or with a capital, whatever the file name says. */
    private val WORDS = mapOf(
        "otp" to "OTP", "ica" to "ICA", "mas" to "MAS", "spf" to "SPF", "dbs" to "DBS", "ocbc" to "OCBC",
        "uob" to "UOB", "cpf" to "CPF", "iras" to "IRAS", "mom" to "MOM", "moh" to "MOH", "usdt" to "USDT",
        "atm" to "ATM", "pin" to "PIN", "ic" to "IC", "nric" to "NRIC", "sms" to "SMS",
        "china" to "China", "apple" to "Apple", "singtel" to "Singtel", "singapore" to "Singapore",
    )

    fun isLearningTest(fileName: String) = LEARNING_TEST_MARKER in fileName

    /** One file's title, before the "(Chinese)" rule. */
    fun baseTitle(fileName: String): String {
        val name = fileName.removeSuffix(".json")
        OVERRIDES[name]?.let { return it }
        val words = name.split("_").drop(3).filter { it.isNotEmpty() }.map { WORDS[it.lowercase()] ?: it }
        if (words.isEmpty()) return fileName
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    /** Every script's title: when an English and a Chinese script share one, the Chinese one says "(Chinese)". */
    fun withTitles(scripts: List<CallScript>): List<CallScript> {
        val base = scripts.map { it to baseTitle(it.fileName) }
        val english = base.filter { it.first.lang == "en" }.map { it.second }.toSet()
        return base.map { (s, t) -> s.copy(title = if (s.lang == "zh" && t in english) "$t (Chinese)" else t) }
    }

    /** The picker's two groups: the normal demo calls, then the learning-loop tests (shown last, apart). */
    fun sections(scripts: List<CallScript>): Pair<List<CallScript>, List<CallScript>> =
        scripts.partition { !isLearningTest(it.fileName) }

    /**
     * "Simulate scam call" / "Simulate normal call": a random script of this kind, never a learning-loop test, and not
     * the one picked last time when there is another to choose.
     */
    fun pickRandom(scripts: List<CallScript>, kind: String, last: String?, random: Random = Random.Default): CallScript? {
        val pool = scripts.filter { it.kind == kind && !isLearningTest(it.fileName) }
        val others = pool.filter { it.fileName != last }
        return (others.ifEmpty { pool }).randomOrNull(random)
    }
}
