import XCTest
@testable import ScamGuard

/// Phase 1: the red warning's and yellow banner's wording, the spoken warning, the alert tone, and how a call
/// drives them. The same cases as Android WarningsTest.
@MainActor
final class WarningsTests: XCTestCase {
    typealias Kind = Warnings.Kind

    func testStrongestReasonFirst() {
        XCTAssertEqual(Warnings.kind(["urgency", "money"], ["Read me the OTP you just received"]), .otp)
        XCTAssertEqual(Warnings.kind(["money", "impersonation"], ["Transfer the money now"]), .money)
        XCTAssertEqual(Warnings.kind(["impersonation", "urgency"], []), .official)
        XCTAssertEqual(Warnings.kind(["urgency"], []), .rush)
        XCTAssertEqual(Warnings.kind(["money"], ["请告诉我验证码"]), .otp)
    }

    func testReasonCodeDecidesWhenNoSignalIsLit() {
        XCTAssertEqual(Warnings.kind([], [], reasonCode: Reasons.money), .money)
        XCTAssertEqual(Warnings.kind([], [], reasonCode: Reasons.impersonation), .official)
        XCTAssertEqual(Warnings.kind([], [], reasonCode: Reasons.urgency), .rush)
        XCTAssertEqual(Warnings.kind([], [], reasonCode: Reasons.several), .several)
        XCTAssertEqual(Warnings.kind([], [], reasonCode: Reasons.earlier), .earlier)
        XCTAssertEqual(Warnings.kind([], [], reasonCode: Reasons.bert), .general)
        XCTAssertEqual(Warnings.kind([], [], reasonCode: nil), .general)
    }

    func testEnglishWording() {
        XCTAssertEqual(Warnings.spoken(.otp), "This may be a scam. Do not give your OTP. Hang up now.")
        XCTAssertEqual(Warnings.spoken(.money), "This may be a scam. Do not send any money. Hang up now.")
        XCTAssertEqual(Warnings.spoken(.official), "This may be a scam. Do not give any personal details. Hang up now.")
        XCTAssertEqual(L.s(Warnings.message(.otp)), "They asked for your OTP. Banks and police never do this.")
        XCTAssertEqual(L.s(Warnings.careful(.money)), "They talked about money or codes. Don't share them.")
        XCTAssertEqual(Warnings.familyMessage(), "ScamGuard: I may be on a scam call. Please call me.")
    }

    /// The spoken warning for every reason code: filled in, with its "do" line.
    func testSpokenTextPerReasonCode() {
        for code in Warnings.reasonCodes {
            let kind = Warnings.kind([], [], reasonCode: code)
            let text = Warnings.spoken(kind)
            XCTAssertFalse(text.isEmpty, code)
            XCTAssertFalse(text.contains("%") || text.contains("{"), "\(code): \(text)")
            XCTAssertTrue(text.contains(L.s(Warnings.doLine(kind))), code)
        }
    }

    /// reason code -> the app's wording, for every code; each one different.
    func testReasonLabelForEveryCode() {
        var seen = Set<String>()
        for code in Warnings.reasonCodes {
            guard let label = Reasons.label(code) else { XCTFail(code); continue }
            XCTAssertFalse(label.isEmpty)
            XCTAssertTrue(seen.insert(label).inserted, "\(code) not unique")
        }
    }

    /// The server's wording maps back to the code, so a stored reason is shown in the app's wording.
    func testStoredServerWordingIsShownInTheAppsWording() {
        XCTAssertEqual(Reasons.localized("Asked for your OTP or money"), L.s(.reason_money))
        XCTAssertEqual(Reasons.localized("asking for your OTP"), L.s(.reason_money))
        // a wording the app doesn't know: shown as the server sent it
        XCTAssertEqual(Reasons.localized("Asked to install a remote-access app"), "Asked to install a remote-access app")
    }

    /// Message, action and yellow line exist for every kind, with no scores or technical words.
    func testWarningWordingIsPlain() {
        for k in Warnings.Kind.allCases {
            for key in [Warnings.message(k), Warnings.doLine(k), Warnings.careful(k)] {
                let t = L.s(key)
                XCTAssertFalse(t.isEmpty, "\(k)")
                XCTAssertFalse(t.contains("%") || t.contains("{"), "\(k): \(t)")
            }
            let text = L.s(Warnings.message(k)) + " " + L.s(Warnings.doLine(k))
            for w in ["BERT", "verdict", "gateway", "model", "server", "score"] {
                XCTAssertNil(text.range(of: "\\b\(w)\\b", options: [.regularExpression, .caseInsensitive]), "\(k): \(w)")
            }
        }
    }

    func testFamilyMessage() {
        XCTAssertTrue(Warnings.familyMessage().hasPrefix("ScamGuard"))
    }

    func testBeCarefulOffShowsYellowAsListening() {
        XCTAssertEqual(Risk.shown(.yellow, showCareful: false), .listening)
        XCTAssertEqual(Risk.shown(.yellow, showCareful: true), .yellow)
        for l in Risk.Level.allCases where l != .yellow { XCTAssertEqual(Risk.shown(l, showCareful: false), l) }
    }

    func testAlertToneIsThreeSoftEdgedBeeps() {
        let pcm = AlertTone.pcm()
        let beep = AlertTone.sampleRate * AlertTone.beepMs / 1000
        let gap = AlertTone.sampleRate * AlertTone.gapMs / 1000
        XCTAssertEqual(pcm.count, 3 * beep + 2 * gap)
        XCTAssertLessThan(abs(Int(pcm.first!)), 50)
        XCTAssertLessThan(abs(Int(pcm.last!)), 50)
        XCTAssertLessThanOrEqual(pcm.map { abs(Int($0)) }.max()!, Int(Double(Int16.max) * AlertTone.amplitude) + 1)
        XCTAssertTrue((beep..<(beep + gap)).allSatisfy { pcm[$0] == 0 })
        XCTAssertEqual(AlertTone.durationMs, 1060)
        XCTAssertEqual(AlertTone.wav().count, 44 + pcm.count * 2)
    }

    /// Every string key the app uses has text in the String Catalog.
    func testEveryKeyHasText() {
        for key in K.allCases { XCTAssertNotNil(L.raw(key), "\(key) has no text") }
    }
}

/// Records what the warnings would play (tests make no sound).
@MainActor
final class RecordingAlerts: WarningAlerts {
    var reds: [WarningSpeech?] = []
    var yellows = 0
    var stops = 0
    func red(_ speech: WarningSpeech?) { reds.append(speech) }
    func yellow() { yellows += 1 }
    func stop() { stops += 1 }
}

/// A call through CallViewModel (stubbed gateway) and what the warnings do.
@MainActor
final class WarningCallTests: XCTestCase {
    override func setUp() async throws { StubProtocol.reset() }
    override func tearDown() async throws { StubProtocol.reset() }

    private func makeVM(_ alerts: RecordingAlerts, configure: (AppPrefs) -> Void = { _ in }) throws -> CallViewModel {
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.gatewayUrl = "http://stub.test:8080/"
        configure(prefs)
        return CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                             delayScale: 0, haptic: {}, alerts: alerts, startUp: false)
    }

    private func script(_ texts: [String] = ["This is the bank.", "Read me the OTP 123456 now."]) -> CallScript {
        CallScript(fileName: "scam_en_99_test.json", kind: "scam", lang: "en", title: "Test",
                   lines: texts.map { ScriptLine(speaker: "caller", text: $0, delayMs: 0) })
    }

    nonisolated private static func answer(_ seq: Int, _ verdict: String, money: Double) -> Data {
        Data("""
        {"call_id":"x","seq":\(seq),"scored":true,"duplicate":false,"verdict":"\(verdict)","combined_score":0.6,
         "scores":{"urgency":0.1,"money":\(money),"impersonation":0.2},
         "recent_scores":{"urgency":0.1,"money":\(money),"impersonation":0.2},"ignored":false}
        """.utf8)
    }

    /// verdicts by seq; the call ends with the last one (no final review).
    private func serve(_ verdicts: [String]) {
        StubProtocol.handler = { req, body in
            let path = req.url!.path
            if path.hasSuffix("/v1/utterance") {
                let seq = ((try? JSONSerialization.jsonObject(with: body ?? Data())) as? [String: Any])?["seq"] as? Int ?? 1
                let v = verdicts[min(seq, verdicts.count) - 1]
                return (200, Self.answer(seq, v, money: v == "SCAM" ? 0.99 : 0.55), 0)
            }
            return (503, Data("{}".utf8), 0)
        }
    }

    private func waitUntil(_ what: String, _ cond: () -> Bool) async throws {
        let end = Date().addingTimeInterval(10)
        while !cond() {
            if Date() > end { XCTFail("timed out waiting for \(what)"); return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func testRedWarningOpensSpeaksAndStaysUntilTheUserActs() async throws {
        serve(["UNSURE", "SCAM"])
        let alerts = RecordingAlerts()
        let vm = try makeVM(alerts)
        vm.ring(script())
        vm.answer()
        try await waitUntil("the summary") { vm.state.phase == .ended && !vm.state.reviewing }
        XCTAssertTrue(vm.state.warningOpen, "the call ended but the warning stays until the user closes it")
        XCTAssertEqual(alerts.reds.count, 1)
        XCTAssertTrue(alerts.reds.first??.text.hasPrefix("This may be a scam.") == true)
        XCTAssertLessThanOrEqual(alerts.yellows, 1, "at most one tap (answers can arrive out of order)")
        vm.closeWarning()
        XCTAssertFalse(vm.state.warningOpen)
    }

    func testSpokenWarningSaysWhatToDo() async throws {
        serve(["SCAM"])
        let alerts = RecordingAlerts()
        let vm = try makeVM(alerts)
        vm.ring(script(["Read me the OTP 123456 now."]))
        vm.answer()
        try await waitUntil("the warning") { vm.state.warningOpen }
        let speech = try XCTUnwrap(alerts.reds.first ?? nil)
        XCTAssertEqual(speech.text, "This may be a scam. Do not give your OTP. Hang up now.")
        vm.hangUp()
        XCTAssertFalse(vm.state.warningOpen, "Hang up closes the warning")
    }

    func testReadAloudOffPlaysTheSoundButSaysNothing() async throws {
        serve(["SCAM"])
        let alerts = RecordingAlerts()
        let vm = try makeVM(alerts) { $0.readAloud = false }
        vm.ring(script())
        vm.answer()
        try await waitUntil("the warning") { vm.state.warningOpen }
        XCTAssertEqual(alerts.reds.count, 1)
        XCTAssertNil(alerts.reds.first ?? nil)
    }

    /// "Show 'Be careful' warnings" off: no tap, the screen shows "Listening…", but the call is saved as UNSURE.
    func testBeCarefulOffHidesYellowButStillSavesIt() async throws {
        serve(["UNSURE", "UNSURE"])
        let alerts = RecordingAlerts()
        let vm = try makeVM(alerts) { $0.showBeCareful = false }
        vm.ring(script())
        vm.answer()
        try await waitUntil("the summary") { vm.state.phase == .ended && !vm.state.reviewing }
        XCTAssertEqual(alerts.yellows, 0)
        XCTAssertEqual(Risk.shown(vm.state.level, showCareful: vm.showBeCareful), .listening)
        try await waitUntil("the saved call") { !vm.store.calls().isEmpty }
        XCTAssertEqual(vm.store.calls().first?.finalVerdict, "UNSURE")
    }
}
