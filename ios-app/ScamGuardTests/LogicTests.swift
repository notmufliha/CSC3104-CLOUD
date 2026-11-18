import XCTest
@testable import ScamGuard

/// Risk / level logic, the summary, Mask, reason codes, implicit labels and the dashboard numbers: ports of
/// android-app's FinalReviewTest, MaskTest, MaskParityTest, ReasonsTest, ImplicitLabelsTest and DashboardTest.
final class RiskTests: XCTestCase {
    func testLevels() {
        XCTAssertEqual(Risk.level("SCAM", nil), .red)
        XCTAssertEqual(Risk.level("UNSURE", "err"), .yellow)        // a warning always stays
        XCTAssertEqual(Risk.level("SAFE", "err"), .error)           // an error beats SAFE
        XCTAssertEqual(Risk.level("SAFE", nil), .green)
        XCTAssertEqual(Risk.level(nil, nil), .listening)
        XCTAssertEqual(Risk.level(nil, "err"), .error)
    }

    func testChipsLightAtHalf() {
        XCTAssertEqual(Risk.litSignals(["urgency": 0.5, "money": 0.49, "impersonation": 0.9]), ["urgency", "impersonation"])
        XCTAssertEqual(Risk.signalOn, 0.5)
    }

    func testBannerWording() {
        XCTAssertEqual(Risk.banner(.red, ["money"], ["Read me the OTP"]),
                       "Likely scam: asking for your OTP. Hang up and call your bank on the number on your card.")
        XCTAssertEqual(Risk.banner(.yellow, ["impersonation"], []),
                       "Be careful: pretending to be an official (police, bank, government). Hang up and call the agency back on its official number.")
        XCTAssertNil(Risk.banner(.green, [], []))
        XCTAssertTrue(Risk.banner(.error, [], [])!.contains("does NOT mean it is safe"))
    }

    func testSummaryLevelUsesTheFinalVerdictButNeverLooksCalmerThanTheLiveOneOnFallback() {
        let fallbackSafe = FinalReview(finalVerdict: "SAFE", reason: "", reviewed: false, topWindowSeqs: [])
        XCTAssertEqual(Summary.level(fallbackSafe, "UNSURE", nil), .yellow)
        XCTAssertEqual(Summary.level(.unavailable, "SCAM", nil), .red)
        XCTAssertEqual(Summary.level(.unavailable, "SAFE", nil), .green)
        XCTAssertEqual(Summary.level(fallbackSafe, "SAFE", "Could not reach the server"), .error)
        XCTAssertEqual(Summary.level(nil, nil, nil), .listening)
        let reviewedScam = FinalReview(finalVerdict: "SCAM", reason: "", reviewed: true, topWindowSeqs: [3])
        XCTAssertEqual(Summary.level(reviewedScam, "UNSURE", nil), .red)
        // the review is the final word when it answered: UNSURE live -> SAFE final (the normal call case)
        let reviewedSafe = FinalReview(finalVerdict: "SAFE", reason: "", reviewed: true, topWindowSeqs: [])
        XCTAssertEqual(Summary.level(reviewedSafe, "UNSURE", nil), .green)
    }

    func testFinalReviewFromAnswers() {
        let fb = FinalReview.from(EndCallResponse(finalVerdict: "SCAM", callScore: 0.8, review: "fallback", topWindowSeqs: [],
                                                  reason: "Asked for your OTP or money"))
        XCTAssertEqual(fb.finalVerdict, "SCAM")
        XCTAssertFalse(fb.reviewed)
        XCTAssertEqual(fb.reason, "Asked for your OTP or money")
        XCTAssertEqual(FinalReview.from(nil), .unavailable)
        XCTAssertEqual(FinalReview.unavailable.reviewType, "unavailable")
        XCTAssertEqual(FinalReview.onDeviceReview.reviewType, "on_device")
        XCTAssertEqual(FinalReview.from(EndCallResponse(finalVerdict: "SCAM", callScore: 0.93, review: "bert")).callScore!, 0.93, accuracy: 1e-6)
    }

    func testHistoryVerdicts() {
        XCTAssertEqual(Risk.historyVerdict(.red), "SCAM")
        XCTAssertEqual(Risk.historyVerdict(.yellow), "UNSURE")
        XCTAssertEqual(Risk.historyVerdict(.green), "SAFE")
        XCTAssertEqual(Risk.historyVerdict(.error), "NOT CHECKED")
        XCTAssertEqual(Risk.historyVerdict(.listening), "NOT CHECKED")
    }

    func testPrivacyLevelDefaultsToCloud() {
        XCTAssertEqual(PrivacyLevel.fromId(nil), .cloud)
        XCTAssertEqual(PrivacyLevel.fromId("nonsense"), .cloud)
        XCTAssertEqual(PrivacyLevel.fromId("hybrid"), .hybrid)
        XCTAssertEqual(PrivacyLevel.fromId("on_device"), .onDevice)
        XCTAssertEqual(PrivacyLevel.badgeFor("cloud"), "Cloud")
        XCTAssertEqual(PrivacyLevel.badgeFor("on_device"), "On-device")
        XCTAssertEqual(PrivacyLevel.badgeFor("weird"), "weird")
        XCTAssertTrue(PrivacyLevel.cloud.cloudCall)
        XCTAssertFalse(PrivacyLevel.hybrid.cloudCall)
        XCTAssertTrue(PrivacyLevel.onDevice.scoresOnPhone)
    }

    func testPrefsUseAndroidKeys() {
        let d = freshDefaults()
        let p = AppPrefs(defaults: d)
        XCTAssertEqual(p.gatewayUrl, "http://localhost:8080/")
        p.gatewayUrl = "192.168.1.5:8080"
        XCTAssertEqual(d.string(forKey: "gateway_url"), "http://192.168.1.5:8080/")
        p.privacyLevel = .onDevice
        XCTAssertEqual(d.string(forKey: "privacy_level"), "on_device")
        XCTAssertFalse(p.saveRiskiestSentence)
        XCTAssertFalse(p.helpImproveDetection)
        p.helpImproveDetection = true
        XCTAssertTrue(d.bool(forKey: "help_improve_detection"))
        let id = p.flPhoneId
        XCTAssertEqual(p.flPhoneId, id)
        XCTAssertEqual(d.string(forKey: "fl_phone_id"), id)
    }
}

final class MaskTests: XCTestCase {
    private func check(_ input: String, _ expected: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(Mask.mask(input), expected, file: file, line: line)
    }

    func testNamesAfterTitlesAndCues() {
        check("This is Officer John Tan from the Singapore Police Force.",
              "This is Officer [PERSON_NAME] from the Singapore Police Force.")
        check("My name is Sarah, I am calling from DBS.", "My name is [PERSON_NAME], I am calling from DBS.")
        check("Inspector Wong here. Mrs Tan, please hold.", "Inspector [PERSON_NAME] here. Mrs [PERSON_NAME], please hold.")
    }

    func testOrganisationsStay() {
        check("This is the police. This is DBS bank. I am calling about your account.",
              "This is the police. This is DBS bank. I am calling about your account.")
        check("Hello this is Singapore Customs.", "Hello this is Singapore Customs.")
    }

    func testNumbersMoneyContacts() {
        check("Transfer $5,000 to account 123-456-789 now and read me the OTP 482913.",
              "Transfer [MONEY_AMOUNT] to account [PHONE_NUMBER] now and read me the OTP [NUMBER].")
        check("Call me back at +65 9123 4567 or email scam@fake.com, visit www.dbs-secure.com/login",
              "Call me back at [PHONE_NUMBER] or email [EMAIL_ADDRESS], visit [URL]")
        check("Your NRIC S1234567D has been used. Pay 300 dollars within 24 hours.",
              "Your NRIC [NUMBER] has been used. Pay [MONEY_AMOUNT] within [NUMBER] hours.")
    }

    func testChinese() {
        check("我是王警官，请转账5000元。我叫李明", "我是王警官，请转账[MONEY_AMOUNT]。我叫[PERSON_NAME]")
    }

    /// The same cases backend/review/mask.py, Mask.kt and web/app.js are checked against (mask_cases.json).
    func testMasksLikeTheReviewService() throws {
        let cases = try Fixtures.json("mask_cases.json")["cases"] as! [[String: Any]]
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for c in cases {
            let text = c["text"] as! String
            XCTAssertEqual(Mask.mask(text), c["masked"] as! String, text)
        }
    }
}

final class ReasonsTests: XCTestCase {
    private func call(_ id: String, _ reason: String, _ code: String?, verdict: String = "SCAM") -> CallRow {
        CallRow(id: id, startedAt: 0, endedAt: 1, finalVerdict: verdict, liveVerdict: verdict, topReason: reason,
                callScore: 0.8, signalsFired: [], review: "fallback", riskiestSentence: nil, reasonCode: code)
    }

    func testAllNineCodes() {
        XCTAssertEqual(Set(Reasons.allCodes),
                       ["money", "impersonation", "urgency", "bert", "several", "earlier", "none", "server_not_reached", "no_lines"])
        for code in Reasons.allCodes { XCTAssertNotNil(Reasons.text(code), code) }
    }

    func testAWarningIsCodedByItsStrongestSignalLikeTheAggregator() {
        XCTAssertEqual(Reasons.codeFor(.red, ["urgency": 0.55, "money": 0.92, "impersonation": 0.7], nil), Reasons.money)
        XCTAssertEqual(Reasons.codeFor(.yellow, ["impersonation": 0.6, "urgency": 0.5], nil), Reasons.impersonation)
        XCTAssertEqual(Reasons.codeFor(.yellow, ["urgency": 0.4], nil), Reasons.several)
    }

    func testTheServersEndOfCallReasonWins() {
        let scores: [String: Float] = ["urgency": 0.9]
        XCTAssertEqual(Reasons.codeFor(.red, scores, "Asked for your OTP or money"), Reasons.money)
        XCTAssertEqual(Reasons.codeFor(.red, scores, "Warning signs earlier in the call"), Reasons.earlier)
        XCTAssertEqual(Reasons.codeFor(.red, scores, "a reason the app doesn't know"), Reasons.urgency)
    }

    func testCalmAndErrorLevels() {
        XCTAssertEqual(Reasons.codeFor(.green, [:], nil), Reasons.none)
        XCTAssertEqual(Reasons.codeFor(.error, [:], nil), Reasons.serverNotReached)
        XCTAssertEqual(Reasons.codeFor(.listening, [:], nil), Reasons.noLines)
    }

    func testShownInTheServersWording() {
        for (code, text) in LocalRules.reasonText { XCTAssertEqual(Reasons.text(code), text) }
        XCTAssertEqual(call("a", "asking for your OTP", Reasons.money).displayReason, "Asked for your OTP or money")
        XCTAssertEqual(call("b", "whatever was saved", nil).displayReason, "whatever was saved")
        XCTAssertNil(Reasons.text("not-a-code"))
    }

    func testOldWordingsMapToTheSameCode() {
        for t in ["asking for your OTP", "asking you to transfer money", "Asked for your OTP or money", " ASKED FOR YOUR OTP OR MONEY "] {
            XCTAssertEqual(Reasons.codeForText(t), Reasons.money, t)
        }
        XCTAssertEqual(Reasons.codeForText("pretending to be an official (police, bank, government)"), Reasons.impersonation)
        XCTAssertNil(Reasons.codeForText("no idea"))
    }

    func testTopReasonsCountOneWordingTogether() {
        let calls = [
            call("1", "asking for your OTP", Reasons.money),
            call("2", "Asked for your OTP or money", Reasons.money),
            call("3", "pretending to be an official (police, bank, government)", Reasons.impersonation),
            call("4", "no warning signs", Reasons.none, verdict: "SAFE"),
        ]
        let top = Dashboard.topReasons(calls)
        XCTAssertEqual(top.map(\.0), ["Asked for your OTP or money", "Claims to be police, a bank or the government"])
        XCTAssertEqual(top.map(\.1), [2, 1])
    }

    // ------------------------------------------------------------ the two Android bugs, not copied

    private func ended(_ level: PrivacyLevel, verdict: String, scores: [String: Float], review: FinalReview?,
                       action: String? = nil) -> CallUiState {
        var s = CallUiState()
        s.phase = .ended
        s.privacyLevel = level
        s.verdict = verdict
        s.callScores = scores
        s.finalReview = review
        s.serverAction = action
        s.lines = [ChatLine(seq: 1, isCaller: true, text: "Read me the OTP now", flags: ["money"])]
        return s
    }

    /// Bug 2: at levels 2-3 the summary used the app's own wording ("asking for your OTP") while the dashboard used
    /// the server's. Now both use the reason code's wording.
    func testOnPhoneSummaryReasonUsesTheDashboardsWording() {
        for level in [PrivacyLevel.hybrid, .onDevice] {
            let s = ended(level, verdict: "SCAM", scores: ["money": 0.97, "urgency": 0.6], review: .onDeviceReview)
            XCTAssertEqual(s.reasonCode, Reasons.money)
            let row = CallRow(id: "x", startedAt: 0, endedAt: 0, finalVerdict: "SCAM", liveVerdict: "SCAM",
                              topReason: "", callScore: nil, signalsFired: [], review: "on_device",
                              riskiestSentence: nil, reasonCode: s.reasonCode)
            XCTAssertEqual(s.summaryReason, row.displayReason + ". " + LocalRules.actionScam)
            XCTAssertTrue(s.summaryReason!.hasPrefix("Asked for your OTP or money. "))
            XCTAssertFalse(s.summaryReason!.contains("asking for your OTP"))
        }
        let unsure = ended(.onDevice, verdict: "UNSURE", scores: ["impersonation": 0.7], review: .onDeviceReview)
        XCTAssertEqual(unsure.summaryReason, "Claims to be police, a bank or the government. " + LocalRules.actionUnsure)
    }

    func testCloudSummaryReasonAndActionComeFromTheServer() {
        let review = FinalReview(finalVerdict: "SCAM", reason: "Warning signs earlier in the call", reviewed: true, topWindowSeqs: [1])
        let s = ended(.cloud, verdict: "SCAM", scores: ["money": 0.9], review: review, action: "Server action.")
        XCTAssertEqual(s.summaryReason, "Warning signs earlier in the call. Server action.")
        XCTAssertEqual(s.reasonCode, Reasons.earlier)
        // final verdict differs from the live one: the action follows the final verdict (policy.rs action_for)
        let calmer = ended(.cloud, verdict: "SCAM",
                           scores: ["money": 0.9],
                           review: FinalReview(finalVerdict: "UNSURE", reason: "Asked for your OTP or money", reviewed: true, topWindowSeqs: []),
                           action: LocalRules.actionScam)
        XCTAssertEqual(calmer.summaryReason, "Asked for your OTP or money. " + LocalRules.actionUnsure)
        // review unavailable: the code's wording
        let offline = ended(.cloud, verdict: "SCAM", scores: ["money": 0.9], review: .unavailable)
        XCTAssertEqual(offline.summaryReason, "Asked for your OTP or money. " + LocalRules.actionScam)
    }

    /// Bug 1: "What triggered it" is hidden when the final verdict is SAFE.
    func testWhatTriggeredItIsHiddenWhenTheFinalVerdictIsSafe() {
        let safe = ended(.cloud, verdict: "UNSURE", scores: ["money": 0.6],
                         review: FinalReview(finalVerdict: "SAFE", reason: "", reviewed: true, topWindowSeqs: [1]))
        XCTAssertEqual(safe.summaryLevel, .green)
        XCTAssertFalse(safe.showsTriggers)
        XCTAssertNil(safe.summaryReason)
        let scam = ended(.cloud, verdict: "SCAM", scores: ["money": 0.9], review: .unavailable)
        XCTAssertTrue(scam.showsTriggers)
        XCTAssertEqual(scam.triggerLines.map(\.seq), [1])
        XCTAssertTrue(scam.duringCallLine.hasSuffix("Final review unavailable"))
    }
}

final class LabelsAndDashboardTests: XCTestCase {
    private let alert: Int64 = 100_000
    private func reviewed(_ score: Float) -> FinalReview {
        FinalReview(finalVerdict: "SCAM", reason: "", reviewed: true, topWindowSeqs: [], callScore: score)
    }
    private typealias L = ImplicitLabels.Label

    func testHangingUpWithin10sOfTheRedAlertIsLikelyScam() {
        XCTAssertEqual(ImplicitLabels.atEnd(hungUp: true, alertAt: alert, endedAt: alert + 4_000, review: nil), [L(label: 1, source: "hung_up_after_alert")])
        XCTAssertEqual(ImplicitLabels.atEnd(hungUp: true, alertAt: alert, endedAt: alert + 10_000, review: nil).count, 1)
        XCTAssertTrue(ImplicitLabels.atEnd(hungUp: true, alertAt: alert, endedAt: alert + 10_001, review: nil).isEmpty)
        XCTAssertTrue(ImplicitLabels.atEnd(hungUp: true, alertAt: nil, endedAt: alert, review: nil).isEmpty)
        XCTAssertTrue(ImplicitLabels.atEnd(hungUp: false, alertAt: alert, endedAt: alert + 2_000, review: nil).isEmpty)
    }

    func testAVerySureReviewBecomesTheLabel() {
        XCTAssertEqual(ImplicitLabels.atEnd(hungUp: false, alertAt: nil, endedAt: 0, review: reviewed(0.95)), [L(label: 1, source: "final_review")])
        XCTAssertEqual(ImplicitLabels.atEnd(hungUp: false, alertAt: nil, endedAt: 0, review: reviewed(0.05)), [L(label: 0, source: "final_review")])
        XCTAssertTrue(ImplicitLabels.atEnd(hungUp: false, alertAt: nil, endedAt: 0, review: reviewed(0.9)).isEmpty)
        XCTAssertTrue(ImplicitLabels.atEnd(hungUp: false, alertAt: nil, endedAt: 0, review: reviewed(0.5)).isEmpty)
        let fallback = FinalReview(finalVerdict: "SCAM", reason: "", reviewed: false, topWindowSeqs: [], callScore: 0.99)
        XCTAssertTrue(ImplicitLabels.atEnd(hungUp: false, alertAt: nil, endedAt: 0, review: fallback).isEmpty)
        XCTAssertEqual(ImplicitLabels.atEnd(hungUp: true, alertAt: alert, endedAt: alert + 3_000, review: reviewed(0.97)),
                       [L(label: 1, source: "hung_up_after_alert"), L(label: 1, source: "final_review")])
    }

    func testThisWasFineCountsOnlyAfterAWarning() {
        XCTAssertEqual(ImplicitLabels.thisWasFine(.red), L(label: 0, source: "this_was_fine"))
        XCTAssertEqual(ImplicitLabels.thisWasFine(.yellow), L(label: 0, source: "this_was_fine"))
        XCTAssertNil(ImplicitLabels.thisWasFine(.green))
        XCTAssertNil(ImplicitLabels.thisWasFine(.error))
    }

    private func point(_ seq: Int, _ u: Float?, _ m: Float?, _ i: Float?, combined: Float? = 0.5, t: Float? = nil) -> PointRow {
        PointRow(callId: "c", seq: seq, secondsIntoCall: t ?? Float(seq), combined: combined, urgency: u, money: m, impersonation: i)
    }

    func testRiskiestLineIsTheHighestSingleSignal() {
        XCTAssertEqual(Dashboard.riskiestSeq([point(1, 0.2, 0.1, 0.3), point(3, 0.4, 0.95, 0.1), point(5, 0.9, nil, 0.2)]), 3)
        XCTAssertEqual(Dashboard.riskiestSeq([point(1, 0.7, nil, nil), point(5, 0.7, nil, nil)]), 5)
        XCTAssertNil(Dashboard.riskiestSeq([point(1, 0.3, 0.2, 0.1)]))
        XCTAssertNil(Dashboard.riskiestSeq([point(1, nil, nil, nil)]))
    }

    func testChartSkipsLinesWithoutAScoreAndKeepsTimeOrder() {
        let pts = [point(3, 0.1, 0.1, 0.1, combined: 0.7, t: 9), point(1, 0.1, 0.1, 0.1, combined: 0.2, t: 2),
                   point(5, nil, nil, nil, combined: nil, t: 12), point(7, 0.1, 0.1, 0.1, combined: 1.4, t: 15)]
        let c = Dashboard.chart(pts)
        XCTAssertEqual(c.map(\.0), [2, 9, 15])
        XCTAssertEqual(c.map(\.1), [0.2, 0.7, 1])
    }

    func testTopReasonsCountOnlyWarnings() {
        func call(_ id: String, _ v: String, _ r: String) -> CallRow {
            CallRow(id: id, startedAt: 0, endedAt: 0, finalVerdict: v, liveVerdict: v, topReason: r, callScore: nil,
                    signalsFired: [], review: "fallback", riskiestSentence: nil)
        }
        let calls = [call("1", "SCAM", "Asked for your OTP or money"), call("2", "SCAM", "Asked for your OTP or money"),
                     call("3", "UNSURE", "Pressuring you to act now"), call("4", "SAFE", "no warning signs"),
                     call("6", "NOT CHECKED", "server not reached"),
                     call("7", "UNSURE", "Claims to be police, a bank or the government"),
                     call("8", "SCAM", "Claims to be police, a bank or the government"),
                     call("9", "UNSURE", "Asked for your OTP or money")]
        let top = Dashboard.topReasons(calls)
        XCTAssertEqual(top.map(\.0), ["Asked for your OTP or money", "Claims to be police, a bank or the government", "Pressuring you to act now"])
        XCTAssertEqual(top.map(\.1), [3, 2, 1])
        XCTAssertTrue(Dashboard.topReasons([call("1", "SAFE", "no warning signs")]).isEmpty)
    }

    func testSavedVerdictColours() {
        XCTAssertEqual(Dashboard.level("SCAM"), .red)
        XCTAssertEqual(Dashboard.level("UNSURE"), .yellow)
        XCTAssertEqual(Dashboard.level("SAFE"), .green)
        XCTAssertEqual(Dashboard.level("NOT CHECKED"), .error)
    }
}
