package com.example.scamdetect

import com.example.scamdetect.ondevice.LocalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream G.2: the aggregator's decision rules on the phone. These are the cases from
 * backend/aggregator/src/policy.rs's own tests, with the same numbers and the same expected rule, so the phone
 * and the cloud agree.
 */
class LocalRulesTest {
    private val start = 1_700_000_000_000L

    private fun replay(lines: List<Pair<Long, List<Float?>>>, cfg: LocalRules.Config = LocalRules.Config()): List<LocalRules.Decision> {
        val rules = LocalRules(cfg)
        return lines.map { (secs, line) -> rules.decide(line, start + secs * 1000, secs * 1000) }
    }

    private fun all(u: Float, m: Float, i: Float): List<Float?> = listOf(u, m, i)
    private fun LocalRules.Decision.vr() = verdict to rule

    @Test fun missingWorkerSharesItsWeight() {
        val d = replay(listOf(20L to listOf(0.9f, null, 0.6f)))
        assertEquals(0.75f, d[0].combined, 1e-6f)
        assertTrue(d[0].quorumMet)
    }

    @Test fun fewerThanTwoAnswersIsUnsureEvenInWarmup() {
        val d = replay(listOf(2L to listOf(null, 0.1f, null)))
        assertEquals("UNSURE" to "quorum", d[0].vr())
        assertTrue(!d[0].quorumMet && d[0].warmingUp)
        assertTrue(d[0].reasons.any { it.signal == "quorum" })
    }

    @Test fun warmupStaysQuietEvenAgainstTheMoneyRule() {
        val d = replay(listOf(8L to all(0.2f, 0.8f, 0.6f), 17L to all(0.1f, 0.1f, 0.1f)))
        assertEquals("SAFE" to "warmup", d[0].vr())
        assertTrue(d[0].reasons.isEmpty())
        assertEquals("SCAM" to "money_rule", d[1].vr())   // the window still remembers 0.8 / 0.6
    }

    @Test fun aVeryHighScoreBreaksTheWarmup() {
        assertEquals("SCAM" to "money_rule", replay(listOf(5L to all(0.2f, 0.95f, 0.6f)))[0].vr())
    }

    @Test fun moneyRuleNeedsAnotherSignal() {
        val d = replay(listOf(20L to all(0.1f, 0.95f, 0.1f)))
        assertNotEquals("money_rule", d[0].rule)
        assertNotEquals("SCAM", d[0].verdict)
    }

    @Test fun moneyRuleDefaultSpanIsTheWholeWindow() {
        val d = replay(listOf(20L to all(0.4f, 1f, 0f), 25L to all(0.4f, 1f, 0f), 30L to all(0.3f, 0.3f, 0f), 35L to all(0.5f, 0.3f, 0f)))
        assertEquals("SCAM" to "money_rule", d[3].vr())
    }

    @Test fun moneyRuleSpanTwoIgnoresUnrelatedLines() {
        val cfg = LocalRules.Config(moneyRuleLines = 2)
        val d = replay(listOf(20L to all(0.4f, 1f, 0f), 25L to all(0.4f, 1f, 0f), 30L to all(0.3f, 0.3f, 0f), 35L to all(0.5f, 0.3f, 0f)), cfg)
        assertTrue(d.all { it.rule != "money_rule" && it.verdict != "SCAM" })
        assertEquals(1f, d[3].recent[1]!!, 1e-6f)
    }

    @Test fun scamNeedsTwoHighUpdatesInARow() {
        val d = replay(listOf(20L to all(0.65f, 0.6f, 0.65f), 25L to all(0.1f, 0.1f, 0.1f)))
        assertEquals("UNSURE" to "high_once", d[0].vr())
        assertEquals("SCAM" to "streak", d[1].vr())
    }

    @Test fun aLowUpdateResetsTheStreak() {
        val d = replay(listOf(20L to all(0.65f, 0.6f, 0.65f), 25L to all(0.1f, 0.1f, 0.1f), 30L to all(0.65f, 0.6f, 0.65f)),
            LocalRules.Config(windowLines = 1))
        assertEquals("high_once", d[0].rule)
        assertEquals("SAFE" to "safe", d[1].vr())
        assertEquals("high_once", d[2].rule)
    }

    @Test fun redStaysRed() {
        val d = replay(listOf(20L to all(0.9f, 0.9f, 0.9f), 25L to all(0f, 0f, 0f), 99L to listOf(null, null, null)))
        assertEquals("SCAM", d[0].verdict)
        assertEquals("SCAM" to "red", d[1].vr())
        assertEquals("SCAM" to "red", d[2].vr())
    }

    @Test fun unsureBand() = assertEquals("UNSURE" to "unsure", replay(listOf(20L to all(0.5f, 0.5f, 0.5f)))[0].vr())

    @Test fun windowForgetsOldLines() {
        val lines = listOf(20L to all(0.9f, 0.2f, 0.2f)) + (0 until 6).map { (25L + it) to all(0.1f, 0.1f, 0.1f) }
        val d = replay(lines, LocalRules.Config(weights = listOf(0.01f, 1f / 3, 1f / 3)))
        assertEquals(0.9f, d[5].recent[0]!!, 0f)
        assertEquals(0.1f, d[6].recent[0]!!, 0f)
        assertEquals(7, d[6].callerLines)
    }

    @Test fun withoutACallTimeTheWarmupRunsFromTheFirstCallerLine() {
        val rules = LocalRules()
        assertEquals("warmup", rules.decide(all(0.2f, 0.8f, 0.6f), start).rule)
        assertEquals("warmup", rules.decide(all(0.2f, 0.8f, 0.6f), start + 14_999).rule)
        assertEquals("money_rule", rules.decide(all(0.2f, 0.8f, 0.6f), start + 15_000).rule)
    }

    @Test fun reasonsStrongestFirstAndNoneWhenSafe() {
        val d = replay(listOf(20L to all(0.55f, 0.2f, 0.8f)))
        assertEquals(listOf("impersonation", "urgency"), d[0].reasons.map { it.signal })
        assertTrue(replay(listOf(20L to all(0.1f, 0.1f, 0.1f)))[0].reasons.isEmpty())
    }

    // ------------------------------------------------------------ G.3: BERT second opinion (policy.rs bert tests)

    private fun replayBert(lines: List<Triple<Long, List<Float?>, Float?>>, cfg: LocalRules.Config = LocalRules.Config()): List<LocalRules.Decision> {
        val rules = LocalRules(cfg)
        return lines.map { (secs, line, bert) -> rules.decide(line, start + secs * 1000, secs * 1000, bert) }
    }

    @Test fun askingChangesNothingWhenBertDoesNotAnswer() {
        val lines = listOf(20L to all(0.65f, 0.6f, 0.65f), 25L to all(0.1f, 0.1f, 0.1f))
        val without = replay(lines)
        val withNone = replayBert(lines.map { Triple(it.first, it.second, null) })
        assertEquals(without, withNone)
        assertEquals(without[0].fast, without[0].combined, 0f)
    }

    @Test fun finalScoreIsTheAverageOfFastVoteAndBert() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.9f)))
        assertEquals(0.5f, d[0].fast, 1e-6f)
        assertEquals(0.7f, d[0].combined, 1e-6f)
        assertEquals("UNSURE" to "high_once", d[0].vr())
        assertEquals("bert", d[0].reasons[0].signal)
    }

    @Test fun aCalmSecondOpinionTurnsUnsureIntoSafe() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.1f)))
        assertEquals("SAFE" to "safe", d[0].vr())
        assertTrue(d[0].reasons.isEmpty())
    }

    @Test fun twoHighBlendedUpdatesInARowAreScam() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.9f), Triple(25L, all(0.5f, 0.5f, 0.5f), 0.9f)))
        assertEquals("high_once", d[0].rule)
        assertEquals("SCAM" to "streak", d[1].vr())
    }

    @Test fun bertCannotCancelTheMoneyRuleUnlessTheVetoIsSwitchedOn() {
        val line = all(0.2f, 0.95f, 0.6f)
        assertEquals("SCAM" to "money_rule", replayBert(listOf(Triple(20L, line, 0f)))[0].vr())
        val on = LocalRules.Config(bertVeto = 0.2f)
        val vetoed = replayBert(listOf(Triple(20L, line, 0.05f)), on)[0]
        assertNotEquals("money_rule", vetoed.rule)
        assertNotEquals("SCAM", vetoed.verdict)
        assertEquals("SCAM" to "money_rule", replayBert(listOf(Triple(20L, line, 0.3f)), on)[0].vr())
    }

    @Test fun bertStandsInWhenTooFewChecksAnswer() {
        val d = replayBert(listOf(Triple(20L, listOf(null, 0.1f, null), 0.8f), Triple(25L, listOf(null, null, null), 0.8f)))
        assertTrue(!d[0].quorumMet)
        assertEquals(0.8f, d[0].combined, 1e-6f)
        assertEquals("UNSURE" to "high_once", d[0].vr())
        assertEquals("SCAM" to "streak", d[1].vr())
        assertTrue(d[0].reasons.none { it.signal == "quorum" })
    }

    @Test fun warmupStillWinsUnlessBertIsVerySure() {
        assertEquals("SAFE" to "warmup", replayBert(listOf(Triple(3L, listOf(null, 0.1f, null), 0.5f)))[0].vr())
        assertNotEquals("warmup", replayBert(listOf(Triple(3L, listOf(null, 0.1f, null), 0.95f)))[0].rule)
    }

    @Test fun bertWeightIsConfigurable() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.9f)), LocalRules.Config(bertWeight = 0.25f))
        assertEquals(0.6f, d[0].combined, 1e-6f)
    }

    @Test fun peekChangesNothing() {
        val rules = LocalRules()
        val line = all(0.65f, 0.6f, 0.65f)
        repeat(3) { assertEquals("high_once", rules.peek(line, start + 20_000, 20_000).rule) }   // no streak builds up
        assertEquals("high_once", rules.decide(line, start + 20_000, 20_000).rule)
        assertEquals("streak", rules.peek(line, start + 25_000, 25_000).rule)
    }
}
