import XCTest
@testable import ScamGuard

/// Port of android-app LocalRulesTest: every cascade-mode case from backend/aggregator/src/policy.rs's own tests,
/// same numbers, same expected rule, so the phone and the cloud agree.
final class LocalRulesTests: XCTestCase {
    private let start: Int64 = 1_700_000_000_000
    private typealias Cfg = LocalRules.Config

    private func replay(_ lines: [(Int64, [Float?])], _ cfg: Cfg = Cfg()) -> (LocalRules, [LocalRules.Decision]) {
        let r = LocalRules(cfg)
        return (r, lines.map { secs, line in r.decide(line, nowMs: start + secs * 1000, callTimeMs: secs * 1000) })
    }

    private func replayBert(_ lines: [(Int64, [Float?], Float?)], _ cfg: Cfg = Cfg()) -> [LocalRules.Decision] {
        let r = LocalRules(cfg)
        return lines.map { secs, line, bert in r.decide(line, nowMs: start + secs * 1000, callTimeMs: secs * 1000, bert: bert) }
    }

    private func atLine(_ n: Int, _ cfg: Cfg = Cfg()) -> LocalRules {
        let r = LocalRules(cfg)
        for _ in 0..<n { r.decide(all(0, 0, 0), nowMs: start, callTimeMs: 30_000) }
        return r
    }

    private func all(_ u: Float, _ m: Float, _ i: Float) -> [Float?] { [u, m, i] }
    private func vr(_ d: LocalRules.Decision) -> String { d.verdict + "/" + d.rule }

    func testMissingWorkerSharesItsWeight() {
        let d = replay([(20, [0.9, nil, 0.6])]).1
        XCTAssertEqual(d[0].combined, 0.75, accuracy: 1e-6)
        XCTAssertTrue(d[0].quorumMet)
    }

    func testFewerThanTwoAnswersIsUnsureEvenInWarmup() {
        let d = replay([(2, [nil, 0.1, nil])]).1
        XCTAssertEqual(vr(d[0]), "UNSURE/quorum")
        XCTAssertTrue(!d[0].quorumMet && d[0].warmingUp)
        XCTAssertTrue(d[0].reasons.contains { $0.signal == "quorum" })
    }

    func testWarmupStaysQuietEvenAgainstTheMoneyRule() {
        let d = replay([(8, all(0.2, 0.8, 0.6)), (17, all(0.1, 0.1, 0.1))]).1
        XCTAssertEqual(vr(d[0]), "SAFE/warmup")
        XCTAssertTrue(d[0].warmingUp && d[0].reasons.isEmpty)
        XCTAssertEqual(vr(d[1]), "UNSURE/unsure")
        XCTAssertFalse(d[1].warmingUp)
    }

    func testAVeryHighScoreBreaksTheWarmup() {
        let d = replay([(5, all(0.2, 0.95, 0.6))]).1
        XCTAssertEqual(vr(d[0]), "UNSURE/unsure")
        XCTAssertTrue(d[0].warmingUp)
    }

    func testMoneyRuleNeedsAnotherSignal() {
        let d = replay([(20, all(0.1, 0.95, 0.1))]).1
        XCTAssertNotEqual(d[0].rule, "money_rule")
        XCTAssertNotEqual(d[0].verdict, "SCAM")
    }

    func testMoneyRuleDefaultSpanIsTheWholeWindow() {
        let d = replay([(20, all(0.4, 1, 0)), (25, all(0.4, 1, 0)), (30, all(0.3, 0.3, 0)), (35, all(0.5, 0.3, 0))]).1
        XCTAssertEqual(vr(d[3]), "UNSURE/unsure")
    }

    func testMoneyRuleSpanTwoIgnoresUnrelatedLines() {
        var cfg = Cfg(); cfg.moneyRuleLines = 2
        let d = replay([(20, all(0.4, 1, 0)), (25, all(0.4, 1, 0)), (30, all(0.3, 0.3, 0)), (35, all(0.5, 0.3, 0))], cfg).1
        XCTAssertTrue(d.allSatisfy { $0.rule != "money_rule" && $0.verdict != "SCAM" })
        XCTAssertEqual(d[3].recent[1]!, 1, accuracy: 1e-6)
    }

    func testMoneyRuleSpanTwoStillCatchesMoneyNextToTheClaim() {
        var cfg = Cfg(); cfg.moneyRuleLines = 2
        XCTAssertEqual(vr(replay([(20, all(0.2, 0, 0)), (25, all(0.54, 1, 0))], cfg).1[1]), "UNSURE/unsure")
        XCTAssertEqual(vr(replay([(20, all(0.1, 0.1, 1)), (25, all(0.1, 0.71, 0))], cfg).1[1]), "UNSURE/unsure")
        let d = replay([(20, all(0.1, 0.1, 1)), (25, all(0.1, 0.1, 0.1)), (30, all(0.1, 0.71, 0))], cfg).1
        XCTAssertNotEqual(d[2].rule, "money_rule")
    }

    func testMoneyRuleSpanOneIsSameLineOnly() {
        var cfg = Cfg(); cfg.moneyRuleLines = 1
        XCTAssertNotEqual(replay([(20, all(0.1, 0.1, 1)), (25, all(0.1, 0.9, 0))], cfg).1[1].rule, "money_rule")
        XCTAssertEqual(vr(replay([(20, all(0.1, 0.9, 0.8))], cfg).1[0]), "SCAM/strong_fallback")
    }

    func testOneHighSentenceIsNotRed() {
        let d = replay([(20, all(0.65, 0.6, 0.65)), (25, all(0.1, 0.1, 0.1))]).1
        XCTAssertEqual(vr(d[0]), "UNSURE/unsure")
        XCTAssertEqual(vr(d[1]), "UNSURE/unsure")
    }

    func testNoContextCannotAuthorizeFallbackRed() {
        XCTAssertEqual(vr(LocalRules().decide(all(0.9, 0.9, 0.1), nowMs: start + 20_000, callTimeMs: 20_000, bert: nil, bertStatus: "no_context")), "UNSURE/unsure")
        XCTAssertEqual(LocalRules().decide(all(0.9, 0.9, 0.1), nowMs: start + 20_000, callTimeMs: 20_000, bert: nil, bertStatus: "skipped").verdict, "UNSURE")
        XCTAssertEqual(LocalRules().decide(all(0.9, 0.9, 0.1), nowMs: start + 20_000, callTimeMs: 20_000, bert: nil, bertStatus: "timeout").verdict, "SCAM")
    }

    func testALowUpdateResetsTheStreak() {
        var cfg = Cfg(); cfg.windowLines = 1
        let d = replay([(20, all(0.65, 0.6, 0.65)), (25, all(0.1, 0.1, 0.1)), (30, all(0.65, 0.6, 0.65))], cfg).1
        XCTAssertEqual(d[0].rule, "unsure")
        XCTAssertEqual(vr(d[1]), "SAFE/safe")
        XCTAssertEqual(d[2].rule, "unsure")
    }

    func testStrongFallbackCanTurnRedWithoutBert() {
        let (rules, d) = replay([(20, all(0.9, 0.9, 0.9)), (25, all(0, 0, 0)), (99, [nil, nil, nil])])
        XCTAssertEqual(d[0].verdict, "SCAM")
        XCTAssertEqual(vr(d[1]), "SCAM/red_bert_unavailable")
        XCTAssertEqual(vr(d[2]), "SCAM/red_bert_unavailable")
        XCTAssertTrue(rules.red)
    }

    func testUnsureBand() { XCTAssertEqual(vr(replay([(20, all(0.5, 0.5, 0.5))]).1[0]), "UNSURE/unsure") }

    func testWindowForgetsOldLines() {
        var cfg = Cfg(); cfg.weights = [0.01, 1.0 / 3, 1.0 / 3]
        let lines: [(Int64, [Float?])] = [(20, all(0.9, 0.2, 0.2))] + (0..<6).map { (25 + Int64($0), all(0.1, 0.1, 0.1)) }
        let (rules, d) = replay(lines, cfg)
        XCTAssertEqual(d[5].recent[0]!, 0.9)
        XCTAssertEqual(d[6].recent[0]!, 0.1)
        XCTAssertEqual(rules.callerLines, 7)
    }

    func testNormalCallStaysGreen() {
        let (rules, d) = replay((0..<10).map { (Int64($0) * 4, all(0.35, 0.3, 0.05)) })
        XCTAssertTrue(d.allSatisfy { $0.verdict == "SAFE" })
        XCTAssertFalse(rules.red)
    }

    func testScriptedScamTurnsRedAtTheRightLine() {
        let d = replay([(0, all(0.1, 0, 0.2)), (6, all(0.2, 0.1, 0.95)), (20, all(0.6, 0.2, 0.3)), (28, all(0.4, 0.95, 0.2))]).1
        XCTAssertEqual(d[0].verdict, "SAFE")
        XCTAssertNotEqual(d[1].verdict, "SCAM")
        XCTAssertNotEqual(d[2].verdict, "SCAM")
        XCTAssertEqual(vr(d[3]), "SCAM/strong_fallback")
        XCTAssertEqual(d[3].reasons.map(\.signal), ["money", "impersonation", "urgency"])
        XCTAssertEqual(d[3].reasons[0].text, "Asked for your OTP or money")
    }

    func testPhoneTimeWinsOverTheClock() {
        let r = LocalRules()
        XCTAssertTrue(r.decide(all(0.1, 0.1, 0.1), nowMs: start, callTimeMs: 3_000).warmingUp)
        XCTAssertFalse(r.decide(all(0.1, 0.1, 0.1), nowMs: start + 1_000, callTimeMs: 40_000).warmingUp)
        let r2 = LocalRules()
        XCTAssertTrue(r2.decide(all(0.1, 0.1, 0.1), nowMs: start).warmingUp)
        XCTAssertFalse(r2.decide(all(0.1, 0.1, 0.1), nowMs: start + 16_000).warmingUp)
    }

    // ------------------------------------------------------------ BERT second opinion (cascade)

    func testBertIsOnlyWantedWhenTheFastVoteIsUnsureOrAlarmed() {
        let t = start + 20_000
        XCTAssertFalse(LocalRules().wantsSecondOpinion(all(0.1, 0.1, 0.1), nowMs: t, callTimeMs: 20_000))
        XCTAssertFalse(LocalRules().wantsSecondOpinion(all(0.5, 0.5, 0.5), nowMs: start, callTimeMs: 3_000))
        XCTAssertTrue(LocalRules().wantsSecondOpinion(all(0.5, 0.5, 0.5), nowMs: t, callTimeMs: 20_000))
        XCTAssertTrue(LocalRules().wantsSecondOpinion(all(0.65, 0.6, 0.65), nowMs: t, callTimeMs: 20_000))
        XCTAssertTrue(LocalRules().wantsSecondOpinion(all(0.2, 0.95, 0.6), nowMs: t, callTimeMs: 20_000))
        XCTAssertTrue(LocalRules().wantsSecondOpinion([nil, 0.1, nil], nowMs: t, callTimeMs: 20_000))
        let red = LocalRules()
        red.decide(all(0.9, 0.9, 0.9), nowMs: t, callTimeMs: 20_000)
        XCTAssertTrue(red.red)
        XCTAssertTrue(red.wantsSecondOpinion(all(0.9, 0.9, 0.9), nowMs: t, callTimeMs: 20_000))
    }

    func testAskingChangesNothingWhenBertDoesNotAnswer() {
        let lines: [(Int64, [Float?])] = [(20, all(0.65, 0.6, 0.65)), (25, all(0.1, 0.1, 0.1))]
        let without = replay(lines).1
        XCTAssertEqual(without, replayBert(lines.map { ($0.0, $0.1, nil) }))
        XCTAssertEqual(without[0].fast, without[0].combined)
    }

    func testFinalScoreIsTheAverageOfFastVoteAndBert() {
        let d = replayBert([(20, all(0.5, 0.5, 0.5), 0.9)])
        XCTAssertEqual(d[0].fast, 0.5, accuracy: 1e-6)
        XCTAssertEqual(d[0].combined, 0.7, accuracy: 1e-6)
        XCTAssertEqual(vr(d[0]), "SCAM/bert_confirmed")
        XCTAssertEqual(d[0].bert, 0.9)
        XCTAssertEqual(d[0].reasons[0].signal, "bert")
    }

    func testACalmSecondOpinionTurnsUnsureIntoSafe() {
        let d = replayBert([(20, all(0.5, 0.5, 0.5), 0.1)])
        XCTAssertEqual(vr(d[0]), "SAFE/safe")
        XCTAssertTrue(d[0].reasons.isEmpty)
    }

    func testBertScamRequiresAnAlarmedWorkerVote() {
        let d = replayBert([(20, all(0.5, 0.5, 0.5), 0.9), (25, all(0.5, 0.5, 0.5), 0.9)])
        XCTAssertEqual(d[0].rule, "bert_confirmed")
        XCTAssertEqual(vr(d[1]), "SCAM/red_bert_confirmed")
    }

    func testBertSafeKeepsAnAlarmedWorkerVoteYellow() {
        XCTAssertEqual(vr(replayBert([(20, all(0.2, 0.95, 0.6), 0)])[0]), "UNSURE/unsure")
        XCTAssertEqual(vr(replayBert([(20, all(0.9, 0.9, 0.9), 0.8)])[0]), "SCAM/bert_confirmed")
    }

    func testTwoBertDisagreementsClearALiveRedAlert() {
        let d = replayBert([(20, all(0.9, 0.9, 0.9), 0.8), (25, all(0.1, 0.1, 0.1), 0.1), (30, all(0.1, 0.1, 0.1), 0.1)])
        XCTAssertEqual(d[0].verdict, "SCAM")
        XCTAssertEqual(d[1].rule, "red_bert_disagrees")
        XCTAssertEqual(vr(d[2]), "UNSURE/red_cleared")
    }

    func testBertStandsInWhenTooFewChecksAnswer() {
        let d = replayBert([(20, [nil, 0.1, nil], 0.8), (25, [nil, nil, nil], 0.8)])
        XCTAssertFalse(d[0].quorumMet)
        XCTAssertEqual(d[0].combined, 0.8, accuracy: 1e-6)
        XCTAssertEqual(vr(d[0]), "SCAM/bert_confirmed")
        XCTAssertEqual(vr(d[1]), "SCAM/red_bert_confirmed")
        XCTAssertFalse(d[0].reasons.contains { $0.signal == "quorum" })
        XCTAssertEqual(vr(replayBert([(20, [nil, 0.1, nil], 0.1)])[0]), "SAFE/safe")
    }

    func testWarmupStillWinsUnlessBertIsVerySure() {
        XCTAssertEqual(vr(replayBert([(3, [nil, 0.1, nil], 0.5)])[0]), "SAFE/warmup")
        XCTAssertNotEqual(replayBert([(3, [nil, 0.1, nil], 0.95)])[0].rule, "warmup")
    }

    func testBertWeightIsConfigurable() {
        var cfg = Cfg(); cfg.bertWeight = 0.25
        XCTAssertEqual(replayBert([(20, all(0.5, 0.5, 0.5), 0.9)], cfg)[0].combined, 0.6, accuracy: 1e-6)
    }

    // ------------------------------------------------------------ spot check

    func testSpotCheckAsksOnEvery5thLineOfAQuietCall() {
        let asked = (0..<12).filter { n in atLine(n).wantsSecondOpinion(all(0, 0, 0), nowMs: start + 30_000, callTimeMs: 30_000) }.map { $0 + 1 }
        XCTAssertEqual(asked, [5, 10])
    }

    func testSpotCheckRunsDuringTheWarmUpAndCanBreakIt() {
        let r = LocalRules()
        for _ in 0..<4 { r.decide(all(0, 0, 0), nowMs: start, callTimeMs: 1_000) }
        XCTAssertTrue(r.wantsSecondOpinion(all(0, 0, 0), nowMs: start + 5_000, callTimeMs: 5_000))
        let d = r.decide(all(0, 0, 0), nowMs: start + 5_000, callTimeMs: 5_000, bert: 0.95)
        XCTAssertTrue(d.warmingUp)
        XCTAssertNotEqual(d.rule, "warmup")
    }

    func testSpotCheckCanBeSwitchedOff() {
        var cfg = Cfg(); cfg.bertSpotEvery = 0
        XCTAssertFalse(atLine(4, cfg).wantsSecondOpinion(all(0, 0, 0), nowMs: start + 30_000, callTimeMs: 30_000))
    }

    func testLowSpotCheckOnASafeLineStaysSafe() {
        XCTAssertEqual(vr(atLine(4).decide(all(0.1, 0.1, 0.1), nowMs: start + 30_000, callTimeMs: 30_000, bert: 0.02)), "SAFE/safe")
    }

    func testPeekChangesNothing() {
        let r = LocalRules()
        for _ in 0..<3 { XCTAssertEqual(r.peek(all(0.9, 0.9, 0.9), nowMs: start + 20_000, callTimeMs: 20_000).rule, "strong_fallback") }
        XCTAssertFalse(r.red)
        XCTAssertEqual(r.callerLines, 0)
    }

    func testConstants() {
        let c = Cfg()
        XCTAssertEqual(c.warmupMs, 15_000)
        XCTAssertEqual(c.warmupOverride, 0.9)
        XCTAssertEqual(c.moneyRule, 0.7)
        XCTAssertEqual(c.moneyRuleOther, 0.5)
        XCTAssertEqual(c.minQuorum, 2)
        XCTAssertEqual(c.bertScamThreshold, 0.6)
        XCTAssertEqual(c.fallbackStrongSignal, 0.8)
        XCTAssertEqual(c.redDisagreementsToClear, 2)
    }
}
