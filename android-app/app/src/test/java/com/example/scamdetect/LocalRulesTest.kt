package com.example.scamdetect

import com.example.scamdetect.ondevice.LocalRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream G.2: the aggregator's decision rules on the phone. Every cascade-mode case from
 * backend/aggregator/src/policy.rs's own tests (Workstream A's decision table), same numbers, same expected rule,
 * so the phone and the cloud agree. (AGG_MODE=bert_only cases are not ported: the phone has no such mode.)
 */
class LocalRulesTest {
    private val start = 1_700_000_000_000L

    /** Lines at (seconds into the call, scores), decided as the aggregator's `replay` does (BERT "off"). */
    private fun replay(lines: List<Pair<Long, List<Float?>>>, cfg: LocalRules.Config = LocalRules.Config()): Pair<LocalRules, List<LocalRules.Decision>> {
        val rules = LocalRules(cfg)
        return rules to lines.map { (secs, line) -> rules.decide(line, start + secs * 1000, secs * 1000) }
    }

    private fun replayBert(lines: List<Triple<Long, List<Float?>, Float?>>, cfg: LocalRules.Config = LocalRules.Config()): List<LocalRules.Decision> {
        val rules = LocalRules(cfg)
        return lines.map { (secs, line, bert) -> rules.decide(line, start + secs * 1000, secs * 1000, bert) }
    }

    /** A call that already has `callerLines` lines (the aggregator's `at_line`): quiet lines, past the warm-up. */
    private fun atLine(callerLines: Int, cfg: LocalRules.Config = LocalRules.Config()): LocalRules {
        val rules = LocalRules(cfg)
        repeat(callerLines) { rules.decide(all(0f, 0f, 0f), start, 30_000) }
        return rules
    }

    private fun all(u: Float, m: Float, i: Float): List<Float?> = listOf(u, m, i)
    private fun LocalRules.Decision.vr() = verdict to rule

    @Test fun missingWorkerSharesItsWeight() {
        val d = replay(listOf(20L to listOf(0.9f, null, 0.6f))).second
        assertEquals(0.75f, d[0].combined, 1e-6f)
        assertTrue(d[0].quorumMet)
    }

    @Test fun fewerThanTwoAnswersIsUnsureEvenInWarmup() {
        val d = replay(listOf(2L to listOf(null, 0.1f, null))).second
        assertEquals("UNSURE" to "quorum", d[0].vr())
        assertTrue(!d[0].quorumMet && d[0].warmingUp)
        assertTrue(d[0].reasons.any { it.signal == "quorum" })
    }

    @Test fun warmupStaysQuietEvenAgainstTheMoneyRule() {
        val d = replay(listOf(8L to all(0.2f, 0.8f, 0.6f), 17L to all(0.1f, 0.1f, 0.1f))).second
        assertEquals("SAFE" to "warmup", d[0].vr())
        assertTrue(d[0].warmingUp && d[0].reasons.isEmpty())
        assertEquals("UNSURE" to "unsure", d[1].vr())
        assertFalse(d[1].warmingUp)
    }

    @Test fun aVeryHighScoreBreaksTheWarmup() {
        val d = replay(listOf(5L to all(0.2f, 0.95f, 0.6f))).second
        assertEquals("UNSURE" to "unsure", d[0].vr())
        assertTrue(d[0].warmingUp)
    }

    @Test fun moneyRuleNeedsAnotherSignal() {
        val d = replay(listOf(20L to all(0.1f, 0.95f, 0.1f))).second
        assertNotEquals("money_rule", d[0].rule)
        assertNotEquals("SCAM", d[0].verdict)
    }

    @Test fun moneyRuleDefaultSpanIsTheWholeWindow() {
        val d = replay(listOf(20L to all(0.4f, 1f, 0f), 25L to all(0.4f, 1f, 0f), 30L to all(0.3f, 0.3f, 0f), 35L to all(0.5f, 0.3f, 0f))).second
        assertEquals("UNSURE" to "unsure", d[3].vr())
    }

    @Test fun moneyRuleSpanTwoIgnoresUnrelatedLines() {
        val d = replay(listOf(20L to all(0.4f, 1f, 0f), 25L to all(0.4f, 1f, 0f), 30L to all(0.3f, 0.3f, 0f), 35L to all(0.5f, 0.3f, 0f)),
            LocalRules.Config(moneyRuleLines = 2)).second
        assertTrue(d.all { it.rule != "money_rule" && it.verdict != "SCAM" })
        assertEquals(1f, d[3].recent[1]!!, 1e-6f)
    }

    @Test fun moneyRuleSpanTwoStillCatchesMoneyNextToTheClaim() {
        val cfg = LocalRules.Config(moneyRuleLines = 2)
        assertEquals("UNSURE" to "unsure", replay(listOf(20L to all(0.2f, 0f, 0f), 25L to all(0.54f, 1f, 0f)), cfg).second[1].vr())
        assertEquals("UNSURE" to "unsure", replay(listOf(20L to all(0.1f, 0.1f, 1f), 25L to all(0.1f, 0.71f, 0f)), cfg).second[1].vr())
        val d = replay(listOf(20L to all(0.1f, 0.1f, 1f), 25L to all(0.1f, 0.1f, 0.1f), 30L to all(0.1f, 0.71f, 0f)), cfg).second
        assertNotEquals("money_rule", d[2].rule)
    }

    @Test fun moneyRuleSpanOneIsSameLineOnly() {
        val cfg = LocalRules.Config(moneyRuleLines = 1)
        assertNotEquals("money_rule", replay(listOf(20L to all(0.1f, 0.1f, 1f), 25L to all(0.1f, 0.9f, 0f)), cfg).second[1].rule)
        assertEquals("SCAM" to "strong_fallback", replay(listOf(20L to all(0.1f, 0.9f, 0.8f)), cfg).second[0].vr())
    }

    @Test fun oneHighSentenceIsNotRed() {
        val d = replay(listOf(20L to all(0.65f, 0.6f, 0.65f), 25L to all(0.1f, 0.1f, 0.1f))).second
        assertEquals("UNSURE" to "unsure", d[0].vr())
        assertEquals("UNSURE" to "unsure", d[1].vr())
    }

    @Test fun noContextCannotAuthorizeFallbackRed() {
        val d = LocalRules().decide(all(0.9f, 0.9f, 0.1f), start + 20_000, 20_000, bert = null, bertStatus = "no_context")
        assertEquals("UNSURE" to "unsure", d.vr())
        // ...and neither can "skipped" (BERT not needed) - only "off" / "timeout" / "error"
        assertEquals("UNSURE", LocalRules().decide(all(0.9f, 0.9f, 0.1f), start + 20_000, 20_000, null, "skipped").verdict)
        assertEquals("SCAM", LocalRules().decide(all(0.9f, 0.9f, 0.1f), start + 20_000, 20_000, null, "timeout").verdict)
    }

    @Test fun aLowUpdateResetsTheStreak() {
        val d = replay(listOf(20L to all(0.65f, 0.6f, 0.65f), 25L to all(0.1f, 0.1f, 0.1f), 30L to all(0.65f, 0.6f, 0.65f)),
            LocalRules.Config(windowLines = 1)).second
        assertEquals("unsure", d[0].rule)
        assertEquals("SAFE" to "safe", d[1].vr())
        assertEquals("unsure", d[2].rule)
    }

    @Test fun strongFallbackCanTurnRedWithoutBert() {
        val (rules, d) = replay(listOf(20L to all(0.9f, 0.9f, 0.9f), 25L to all(0f, 0f, 0f), 99L to listOf(null, null, null)))
        assertEquals("SCAM", d[0].verdict)
        assertEquals("SCAM" to "red_bert_unavailable", d[1].vr())
        assertEquals("SCAM" to "red_bert_unavailable", d[2].vr())
        assertTrue(rules.red)
    }

    @Test fun unsureBand() = assertEquals("UNSURE" to "unsure", replay(listOf(20L to all(0.5f, 0.5f, 0.5f))).second[0].vr())

    @Test fun windowForgetsOldLines() {
        val lines = listOf(20L to all(0.9f, 0.2f, 0.2f)) + (0 until 6).map { (25L + it) to all(0.1f, 0.1f, 0.1f) }
        val (rules, d) = replay(lines, LocalRules.Config(weights = listOf(0.01f, 1f / 3, 1f / 3)))
        assertEquals(0.9f, d[5].recent[0]!!, 0f)
        assertEquals(0.1f, d[6].recent[0]!!, 0f)
        assertEquals(7, rules.callerLines)
    }

    @Test fun normalCallStaysGreen() {
        val (rules, d) = replay((0 until 10).map { (it * 4L) to all(0.35f, 0.3f, 0.05f) })
        assertTrue(d.all { it.verdict == "SAFE" })
        assertFalse(rules.red)
    }

    @Test fun scriptedScamTurnsRedAtTheRightLine() {
        val d = replay(listOf(0L to all(0.1f, 0f, 0.2f), 6L to all(0.2f, 0.1f, 0.95f), 20L to all(0.6f, 0.2f, 0.3f), 28L to all(0.4f, 0.95f, 0.2f))).second
        assertEquals("SAFE", d[0].verdict)
        assertNotEquals("SCAM", d[1].verdict)
        assertNotEquals("SCAM", d[2].verdict)
        assertEquals("SCAM" to "strong_fallback", d[3].vr())
        assertEquals(listOf("money", "impersonation", "urgency"), d[3].reasons.map { it.signal })
        assertEquals("Asked for your OTP or money", d[3].reasons[0].text)
    }

    @Test fun phoneTimeWinsOverTheClock() {
        val rules = LocalRules()
        assertTrue(rules.decide(all(0.1f, 0.1f, 0.1f), start, 3_000).warmingUp)
        assertFalse(rules.decide(all(0.1f, 0.1f, 0.1f), start + 1_000, 40_000).warmingUp)
        // without a call time: the clock since the first caller line
        val r2 = LocalRules()
        assertTrue(r2.decide(all(0.1f, 0.1f, 0.1f), start).warmingUp)
        assertFalse(r2.decide(all(0.1f, 0.1f, 0.1f), start + 16_000).warmingUp)
    }

    // ------------------------------------------------------------ BERT second opinion (cascade)

    @Test fun bertIsOnlyWantedWhenTheFastVoteIsUnsureOrAlarmed() {
        val t = start + 20_000
        assertFalse(LocalRules().wantsSecondOpinion(all(0.1f, 0.1f, 0.1f), t, 20_000))      // clearly safe
        assertFalse(LocalRules().wantsSecondOpinion(all(0.5f, 0.5f, 0.5f), start, 3_000))   // warm-up
        assertTrue(LocalRules().wantsSecondOpinion(all(0.5f, 0.5f, 0.5f), t, 20_000))       // unsure band
        assertTrue(LocalRules().wantsSecondOpinion(all(0.65f, 0.6f, 0.65f), t, 20_000))     // high
        assertTrue(LocalRules().wantsSecondOpinion(all(0.2f, 0.95f, 0.6f), t, 20_000))      // money rule
        assertTrue(LocalRules().wantsSecondOpinion(listOf(null, 0.1f, null), t, 20_000))    // not enough data
        val red = LocalRules().also { it.decide(all(0.9f, 0.9f, 0.9f), t, 20_000) }
        assertTrue(red.red)
        assertTrue(red.wantsSecondOpinion(all(0.9f, 0.9f, 0.9f), t, 20_000))                // red: confirm or clear
    }

    @Test fun askingChangesNothingWhenBertDoesNotAnswer() {
        val lines = listOf(20L to all(0.65f, 0.6f, 0.65f), 25L to all(0.1f, 0.1f, 0.1f))
        val without = replay(lines).second
        assertEquals(without, replayBert(lines.map { Triple(it.first, it.second, null) }))
        assertEquals(without[0].fast, without[0].combined, 0f)
    }

    @Test fun finalScoreIsTheAverageOfFastVoteAndBert() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.9f)))
        assertEquals(0.5f, d[0].fast, 1e-6f)
        assertEquals(0.7f, d[0].combined, 1e-6f)
        assertEquals("SCAM" to "bert_confirmed", d[0].vr())
        assertEquals(0.9f, d[0].bert!!, 0f)
        assertEquals("bert", d[0].reasons[0].signal)
    }

    @Test fun aCalmSecondOpinionTurnsUnsureIntoSafe() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.1f)))
        assertEquals("SAFE" to "safe", d[0].vr())
        assertTrue(d[0].reasons.isEmpty())
    }

    @Test fun bertScamRequiresAnAlarmedWorkerVote() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.9f), Triple(25L, all(0.5f, 0.5f, 0.5f), 0.9f)))
        assertEquals("bert_confirmed", d[0].rule)
        assertEquals("SCAM" to "red_bert_confirmed", d[1].vr())
    }

    @Test fun bertSafeKeepsAnAlarmedWorkerVoteYellow() {
        assertEquals("UNSURE" to "unsure", replayBert(listOf(Triple(20L, all(0.2f, 0.95f, 0.6f), 0f)))[0].vr())
        assertEquals("SCAM" to "bert_confirmed", replayBert(listOf(Triple(20L, all(0.9f, 0.9f, 0.9f), 0.8f)))[0].vr())
    }

    @Test fun twoBertDisagreementsClearALiveRedAlert() {
        val d = replayBert(listOf(Triple(20L, all(0.9f, 0.9f, 0.9f), 0.8f), Triple(25L, all(0.1f, 0.1f, 0.1f), 0.1f), Triple(30L, all(0.1f, 0.1f, 0.1f), 0.1f)))
        assertEquals("SCAM", d[0].verdict)
        assertEquals("red_bert_disagrees", d[1].rule)
        assertEquals("UNSURE" to "red_cleared", d[2].vr())
    }

    @Test fun bertStandsInWhenTooFewChecksAnswer() {
        val d = replayBert(listOf(Triple(20L, listOf(null, 0.1f, null), 0.8f), Triple(25L, listOf(null, null, null), 0.8f)))
        assertFalse(d[0].quorumMet)
        assertEquals(0.8f, d[0].combined, 1e-6f)
        assertEquals("SCAM" to "bert_confirmed", d[0].vr())
        assertEquals("SCAM" to "red_bert_confirmed", d[1].vr())
        assertTrue(d[0].reasons.none { it.signal == "quorum" })
        assertEquals("SAFE" to "safe", replayBert(listOf(Triple(20L, listOf(null, 0.1f, null), 0.1f)))[0].vr())
    }

    @Test fun warmupStillWinsUnlessBertIsVerySure() {
        assertEquals("SAFE" to "warmup", replayBert(listOf(Triple(3L, listOf(null, 0.1f, null), 0.5f)))[0].vr())
        assertNotEquals("warmup", replayBert(listOf(Triple(3L, listOf(null, 0.1f, null), 0.95f)))[0].rule)
    }

    @Test fun bertWeightIsConfigurable() {
        val d = replayBert(listOf(Triple(20L, all(0.5f, 0.5f, 0.5f), 0.9f)), LocalRules.Config(bertWeight = 0.25f))
        assertEquals(0.6f, d[0].combined, 1e-6f)
    }

    // ------------------------------------------------------------ spot check

    @Test fun spotCheckAsksOnEvery5thLineOfAQuietCall() {
        val asked = (0 until 12).filter { n -> atLine(n).wantsSecondOpinion(all(0f, 0f, 0f), start + 30_000, 30_000) }.map { it + 1 }
        assertEquals(listOf(5, 10), asked)
    }

    @Test fun spotCheckRunsDuringTheWarmUpAndCanBreakIt() {
        val rules = LocalRules()
        repeat(4) { rules.decide(all(0f, 0f, 0f), start, 1_000) }
        assertTrue(rules.wantsSecondOpinion(all(0f, 0f, 0f), start + 5_000, 5_000))
        val d = rules.decide(all(0f, 0f, 0f), start + 5_000, 5_000, bert = 0.95f)
        assertTrue(d.warmingUp)
        assertNotEquals("warmup", d.rule)
    }

    @Test fun spotCheckCanBeSwitchedOff() {
        assertFalse(atLine(4, LocalRules.Config(bertSpotEvery = 0)).wantsSecondOpinion(all(0f, 0f, 0f), start + 30_000, 30_000))
    }

    @Test fun lowSpotCheckOnASafeLineStaysSafe() {
        assertEquals("SAFE" to "safe", atLine(4).decide(all(0.1f, 0.1f, 0.1f), start + 30_000, 30_000, bert = 0.02f).vr())
    }

    @Test fun peekChangesNothing() {
        val rules = LocalRules()
        val line = all(0.9f, 0.9f, 0.9f)
        repeat(3) { assertEquals("strong_fallback", rules.peek(line, start + 20_000, 20_000).rule) }
        assertFalse(rules.red)
        assertEquals(0, rules.callerLines)
    }
}
