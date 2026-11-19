import XCTest
@testable import ScamGuard

/// The after-call card (Logic/AfterCall.swift). Same cases as android-app AfterCallTest.kt.
final class AfterCallTests: XCTestCase {
    private let sg = TimeZone(identifier: "Asia/Singapore")!
    private let at: Int64 = 1_795_070_700_000   // 19 Nov 2026, 14:45 in Singapore

    func testShownOnlyAfterScamOrBeCareful() {
        XCTAssertTrue(AfterCall.shows(.red, showCareful: true))
        XCTAssertTrue(AfterCall.shows(.red, showCareful: false))
        XCTAssertTrue(AfterCall.shows(.yellow, showCareful: true))
        XCTAssertFalse(AfterCall.shows(.yellow, showCareful: false), "Be careful warnings turned off")
        for l in [Risk.Level.green, .error, .listening] { XCTAssertFalse(AfterCall.shows(l, showCareful: true), "\(l)") }
    }

    private let lines = [
        "This is Officer Tan from the police, call me back at 9123 4567.",
        "Pay $5,000 to account 123-456-789 or visit www.safe-account.com now. My email is tan@mail.com",
        "Read me the OTP 482913.",
    ]

    func testSummaryIsMaskedWithNoNumberOrUrl() {
        let text = AfterCall.reportSummary(timeMs: at, result: "Scam", reason: "They asked for your one-time code.", lines: lines,
                                           includeLines: true, zone: sg)
        XCTAssertTrue(text.hasPrefix("ScamGuard call summary\nWhen: 19 Nov 2026, 2:45 PM\nResult: Scam\nWhy: They asked for your one-time code."), text)
        XCTAssertTrue(text.contains("What the caller said (numbers and names removed):"))
        for secret in ["9123", "4567", "5,000", "123-456", "482913", "www.", "safe-account", "@", "Tan "] {
            XCTAssertFalse(text.contains(secret), secret)
        }
        XCTAssertNil(text.range(of: #"https?://|www\.|\.com"#, options: .regularExpression), "no URL")
        let afterDate = text.components(separatedBy: "\n").dropFirst(2).joined(separator: "\n")
        XCTAssertNil(afterDate.range(of: #"\d"#, options: .regularExpression), "apart from the date, no digits")
        XCTAssertTrue(text.contains("[NUMBER]") && text.contains("[PERSON_NAME]"))
        XCTAssertTrue(text.hasSuffix("From the ScamGuard app."))
    }

    func testCallerLinesOnlyWhenTheUserKeepsThem() {
        let text = AfterCall.reportSummary(timeMs: at, result: "Be careful", reason: "They rushed you.", lines: lines,
                                           includeLines: false, zone: sg)
        XCTAssertFalse(text.contains("What the caller said"))
        XCTAssertFalse(text.contains("OTP"))
        XCTAssertEqual(text.components(separatedBy: "\n").count, 5)
    }

    func testBlockAndSharedSteps() {
        XCTAssertEqual(AfterCall.blockSteps.count, 4)
        XCTAssertEqual(L.s(AfterCall.blockSteps[2]), "Tap ⓘ next to the number.")
        XCTAssertEqual(AfterCall.sharedSteps.map { L.s($0) }, [
            "Call your bank on the number on your card. Ask them to block your card and stop payments.",
            "Change your passwords, starting with your bank and email.", "Call 1799 ScamShield Helpline for help."])
    }

    func testReduceMotionIsRespected() {
        XCTAssertEqual(Motion.pressScale(reduce: true), 1)
        XCTAssertLessThan(Motion.pressScale(reduce: false), 1)
        XCTAssertEqual(Motion.warningStartScale(reduce: true), 1)
        XCTAssertEqual(Motion.warningEnterMs(reduce: true), 0)
        XCTAssertFalse(Motion.warningFlash(reduce: true))
        XCTAssertTrue(Motion.warningFlash(reduce: false))
        XCTAssertEqual(Motion.riskBarMs(reduce: true), 0)
        XCTAssertEqual(Motion.hapticFor(.red), .error)
        XCTAssertEqual(Motion.hapticFor(.yellow), .warning)
        XCTAssertNil(Motion.hapticFor(.green))
    }
}
