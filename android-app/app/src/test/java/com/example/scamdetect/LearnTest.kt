package com.example.scamdetect

import com.example.scamdetect.logic.DevScripts
import com.example.scamdetect.logic.Learn
import com.example.scamdetect.logic.Quiz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** "Learn to spot scams" from the bundled, shared learn.json. Same cases as ios-app LearnTests.swift. */
class LearnTest {
    @Before fun strings() = TestStrings.install()

    private val json = File("src/main/assets/learn/learn.json").readText()
    private val content = Learn.parse(json)
    private val scripts = File("src/main/assets/scripts").list()!!.toSet()

    @Test fun scamCardsComeFromTheSharedJson() {
        // byte-for-byte the same file as iOS's
        assertEquals(json, File("../../ios-app/ScamGuard/Resources/Learn/learn.json").readText())
        assertEquals(10, content.types.size)
        assertEquals(content.types.size, content.types.map { it.id }.toSet().size)
        for (t in content.types) {
            assertEquals(t.id, 3, t.signs.size)
            assertTrue(t.id, t.title.isNotBlank() && t.todo.isNotBlank())
            assertTrue("${t.id}: ${t.script} is a bundled script", t.script in scripts)
            assertFalse(t.id, DevScripts.isTestCall(t.script))
            assertTrue(t.id, t.sourceUrl.startsWith("https://www.scamshield.gov.sg/") || t.sourceUrl.startsWith("https://www.police.gov.sg/"))
        }
        val bank = content.types.first { it.id == "bank_code" }
        assertEquals("A 'bank' asks for your code", bank.title)
        assertEquals("ScamShield", bank.sourceName)
        // the nine SPF-advisory scripts are covered (the scam ones as cards, the genuine look-alikes in the quiz)
        for (s in listOf("scam_en_20_ica_to_china_police.json", "scam_en_21_telco_to_mas_officer.json", "scam_en_22_apple_support_crypto.json",
            "scam_en_23_bank_card_handover.json", "scam_en_24_job_task_deposit.json")) assertTrue(s, content.types.any { it.script == s })
        for (s in listOf("normal_en_20_ica_passport_collection.json", "normal_en_21_bank_fraud_check.json", "normal_en_22_telco_contract_renewal.json"))
            assertTrue(s, content.quiz.any { it.script == s && !it.scam })
    }

    @Test fun quizLinesAreRealScriptLines() {
        val gson = com.google.gson.Gson()
        for (q in content.quiz) {
            assertFalse(q.script, DevScripts.isTestCall(q.script))
            val lines = gson.fromJson(File("src/main/assets/scripts/${q.script}").readText(), Array<com.example.scamdetect.data.ScriptLine>::class.java)
            assertTrue("\"${q.text}\" is a caller line of ${q.script}", lines.any { it.isCaller && it.text?.trim() == q.text })
            assertEquals(q.script, q.script.startsWith("scam_"), q.scam)
            assertTrue(q.why.isNotBlank())
        }
    }

    @Test fun aRoundHasFiveQuestionsOfBothKindsAndNeverTestScripts() {
        repeat(200) { seed ->
            val r = Quiz.round(content, Random(seed))
            assertEquals(Quiz.SIZE, r.size)
            assertEquals(5, r.map { it.text }.toSet().size)
            assertTrue(r.count { it.scam } >= 2 && r.count { !it.scam } >= 2)
            assertTrue(r.none { DevScripts.isTestCall(it.script) })
        }
    }

    @Test fun testScriptsAreNeverUsedEvenIfTheFileHadThem() {
        val sneaky = content.copy(quiz = content.quiz + com.example.scamdetect.logic.QuizItem("Report this", true, "x", "scam_en_90_new_scam_test_A_report_this.json"))
        assertTrue(Quiz.pool(sneaky).none { it.script.contains("_90_") })
    }

    @Test fun tryAgainPicksNewLines() {
        val first = Quiz.round(content, Random(1))
        val again = Quiz.round(content, Random(2), avoid = first.map { it.text }.toSet())
        assertTrue(again.none { it.text in first.map { f -> f.text } })
    }

    @Test fun answersAreMarkedRight() {
        val scam = content.quiz.first { it.scam }
        val safe = content.quiz.first { !it.scam }
        assertTrue(Quiz.isRight(scam, saidScam = true))
        assertFalse(Quiz.isRight(scam, saidScam = false))
        assertTrue(Quiz.isRight(safe, saidScam = false))
        assertFalse(Quiz.isRight(safe, saidScam = true))
    }

    @Test fun resultAndBestScore() {
        assertEquals("Excellent! You spotted them all.", TestStrings.get(Quiz.resultLine(5)))
        assertEquals("Well done!", TestStrings.get(Quiz.resultLine(4)))
        assertEquals("Good try!", TestStrings.get(Quiz.resultLine(3)))
        assertEquals("Keep practising. You will get better each time.", TestStrings.get(Quiz.resultLine(0)))
        assertEquals("4 of 5", TestStrings.get(R.string.quiz_score, "4", "5"))
        assertEquals(3, Quiz.best(null, 3))
        assertEquals(4, Quiz.best(4, 2))
        assertEquals(5, Quiz.best(4, 5))
    }
}
