import XCTest
@testable import ScamGuard

final class FakeClock: @unchecked Sendable {
    var now: Int64 = 1_700_000_000_000
}

/// Port of android-app OnDeviceCallTest (G.2 / G.3): whole demo calls scored on the phone, line by line with the
/// script's own timing.
final class OnDeviceCallTests: XCTestCase {
    nonisolated(unsafe) static var scorer: OnDeviceScorer!

    override class func setUp() {
        super.setUp()
        scorer = try! OnDeviceScorer.load { try Fixtures.appResource($0) }
    }

    private var scorer: OnDeviceScorer { Self.scorer }

    /// The verdict at the end of a script, the way the app plays it (red stays red on the bar).
    private func play(_ s: CallScript) -> String? {
        let clock = FakeClock()
        let call = OnDeviceCall(scorer, clock: { clock.now })
        var verdict: String?
        for (i, line) in s.lines.enumerated() {
            clock.now += Int64(line.delayMs ?? 1_500)
            if line.isCaller {
                let a = call.answerNow(callId: "test", seq: i + 1, text: line.text!)
                if verdict != "SCAM" { verdict = a.verdict }
            }
        }
        return verdict
    }

    private func isLearningLoopTest(_ name: String) -> Bool { name.contains("new_scam_test") }

    func testEnglishDemoCallsGetAVerdictOnThePhone() {
        let scripts = ScriptRepository.loadAll().filter { $0.fileName.contains("_en_") }
        XCTAssertGreaterThanOrEqual(scripts.count, 10)
        var results: [(String, String?)] = []
        for s in scripts { results.append((s.fileName, play(s))) }
        for (name, v) in results { print("ON-DEVICE \(v ?? "nil")  \(name)") }
        XCTAssertTrue(results.allSatisfy { ["SAFE", "UNSURE", "SCAM"].contains($0.1 ?? "") })
        for (name, v) in results where name.hasPrefix("scam_") && !isLearningLoopTest(name) {
            XCTAssertTrue(v == "SCAM" || v == "UNSURE", "\(name): \(v ?? "nil")")
        }
    }

    func testAnswerHasTheGatewaysShape() {
        let a = OnDeviceCall(scorer, clock: { 0 }).answerNow(callId: "c1", seq: 3, text: "This is the police, transfer the money to the safe account now.")
        XCTAssertEqual(a.callId, "c1")
        XCTAssertEqual(a.seq, 3)
        XCTAssertEqual(Set(a.scores!.keys), ["urgency", "money", "impersonation"])
        XCTAssertEqual(Set(a.scores!.keys), Set(a.callScores!.keys))
        XCTAssertEqual(a.callerLines, 1)
        XCTAssertTrue(["SAFE", "UNSURE", "SCAM"].contains(a.verdict!))
    }

    func testEnglishOnly() {
        XCTAssertTrue(OnDeviceCall.isEnglish("en", "Read me the OTP"))
        XCTAssertTrue(OnDeviceCall.isEnglish(nil, "Read me the OTP"))
        XCTAssertFalse(OnDeviceCall.isEnglish("zh", "Read me the OTP"))
        XCTAssertFalse(OnDeviceCall.isEnglish(nil, "请把验证码告诉我"))
        XCTAssertTrue(OnDeviceCall.isEnglish(nil, "Your DBS 银行 account is fine, thank you very much"))
    }

    // ------------------------------------------------------------ G.3 Hybrid

    private struct Played {
        let verdict: String?
        let asked: [(Int, OnDeviceCall.SecondOpinion)]
        let callerLines: Int
    }

    private func playHybrid(_ s: CallScript, cloud: @escaping @Sendable ([String]) -> Float?) async -> Played {
        let clock = FakeClock()
        let call = OnDeviceCall(scorer, clock: { clock.now })
        var verdict: String?
        var asked: [(Int, OnDeviceCall.SecondOpinion)] = []
        var n = 0
        for (i, line) in s.lines.enumerated() {
            clock.now += Int64(line.delayMs ?? 1_500)
            guard line.isCaller else { continue }
            n += 1
            let a = await call.answerHybrid(callId: "test", seq: i + 1, text: line.text!) { cloud($0) }
            if let c = a.second { asked.append((n, c)) }
            if verdict != "SCAM" { verdict = a.response.verdict }
        }
        return Played(verdict: verdict, asked: asked, callerLines: n)
    }

    func testHybridSendsOnlyMaskedWindowsAtUnsureMomentsOrTheSpotCheck() async throws {
        let sent = Box<[[String]]>([])
        let p = await playHybrid(try Fixtures.script("scam_en_11_bank_otp.json")) { sent.value.append($0); return 0.9 }
        XCTAssertFalse(p.asked.isEmpty)
        for (n, c) in p.asked {
            XCTAssertTrue(c.why == "unsure" || (c.why == "spot_check" && n % 5 == 0))
            XCTAssertTrue((1...5).contains(c.lines.count))
            XCTAssertTrue(c.lines.allSatisfy { !$0.contains(where: \.isNumber) }, "no digits leave the phone: \(c.lines)")
            XCTAssertGreaterThanOrEqual(n, 3, "only from the 3rd caller line")
        }
        XCTAssertEqual(p.asked.map(\.1.lines), sent.value)
        XCTAssertEqual(p.verdict, "SCAM")
    }

    func testWhatIsSentIsMaskedWithTheMaskFromH() async {
        let clock = FakeClock()
        clock.now = 0
        let call = OnDeviceCall(scorer, clock: { clock.now })
        let raw = [
            "Good afternoon, this is Officer Tan from the Singapore Police Force.",
            "Your NRIC S1234567D is linked to a money laundering case.",
            "Call me back on 9123 4567 or email tan@spf-help.com right away.",
            "You must transfer $5,000 to the safe account within 30 minutes.",
            "Read me the 6 digit OTP now or you will be arrested today.",
            "Don't tell your family, this is confidential.",
        ]
        let sent = Box<[(Int, [String])]>([])
        for (i, text) in raw.enumerated() {
            clock.now += 20_000
            _ = await call.answerHybrid(callId: "c", seq: i, text: text) { s in sent.value.append((i, s)); return nil }
        }
        XCTAssertFalse(sent.value.isEmpty)
        for (i, lines) in sent.value {
            XCTAssertEqual(lines, Array(raw[0...i].suffix(5)).map(Mask.mask))
        }
        let all = sent.value.flatMap(\.1).joined(separator: " ")
        for secret in ["Tan", "S1234567D", "9123", "tan@spf-help.com", "5,000", "6 digit"] {
            XCTAssertFalse(all.contains(secret), "'\(secret)' left the phone")
        }
    }

    func testWithTheServerOffHybridStillWarnsFromThePhone() async {
        for s in ScriptRepository.loadAll() where s.fileName.hasPrefix("scam_en_") && !isLearningLoopTest(s.fileName) {
            let offline = await playHybrid(s) { _ in nil }
            XCTAssertEqual(offline.verdict, play(s), "\(s.fileName): same as on-device")
            XCTAssertTrue(offline.verdict == "SCAM" || offline.verdict == "UNSURE", "\(s.fileName): \(offline.verdict ?? "nil")")
        }
    }

    func testACalmCallOnlyAsksOnTheSpotCheck() async throws {
        let p = await playHybrid(try Fixtures.script("normal_en_14_family_call.json")) { _ in 0.05 }
        XCTAssertTrue(p.asked.allSatisfy { $0.1.why == "spot_check" || $0.1.why == "unsure" })
        XCTAssertEqual(p.asked.filter { $0.1.why == "spot_check" }.count, p.callerLines / 5)
    }

    func testACalmSecondOpinionCanCalmANormalCall() async {
        let rank = ["SAFE": 0, "UNSURE": 1, "SCAM": 2]
        for s in ScriptRepository.loadAll() where s.fileName.hasPrefix("normal_en_") {
            let local = play(s)!
            let hybrid = await playHybrid(s) { _ in 0.05 }.verdict!
            print("NORMAL on-device \(local) hybrid(BERT calm) \(hybrid)  \(s.fileName)")
            XCTAssertLessThanOrEqual(rank[hybrid]!, rank[local]!)
        }
    }

    func testNothingIsSentOnceTheCallIsRed() async throws {
        let s = try Fixtures.script("scam_en_11_bank_otp.json")
        let clock = FakeClock()
        let call = OnDeviceCall(scorer, clock: { clock.now })
        var red = false
        var n = 0
        for line in s.lines {
            clock.now += Int64(line.delayMs ?? 1_500)
            guard line.isCaller else { continue }
            n += 1
            let a = await call.answerHybrid(callId: "t", seq: n, text: line.text!) { _ in 0.99 }
            if red { XCTAssertNil(a.second, "line \(n) sent after red") }
            if a.response.verdict == "SCAM" { red = true }
        }
        XCTAssertTrue(red)
    }
}

/// A mutable box for collecting values from @Sendable closures in tests.
final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ v: T) { value = v }
}
