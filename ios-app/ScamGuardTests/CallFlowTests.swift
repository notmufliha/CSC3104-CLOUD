import XCTest
@testable import ScamGuard

/// Whole calls through CallViewModel with a stubbed gateway (StubProtocol): how the app handles every kind of
/// server answer, as Android does (CallViewModel.kt).
@MainActor
final class CallFlowTests: XCTestCase {
    private var hapticCount = 0

    override func setUp() async throws {
        StubProtocol.reset()
        hapticCount = 0
    }

    override func tearDown() async throws { StubProtocol.reset() }

    private func makeVM(level: PrivacyLevel = .cloud, url: String = "http://stub.test:8080/") throws -> CallViewModel {
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.gatewayUrl = url
        prefs.privacyLevel = level
        return CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                             delayScale: 0, haptic: { [weak self] in self?.hapticCount += 1 }, startUp: false)
    }

    private func script(_ lines: [(String, String)]) -> CallScript {
        CallScript(fileName: "scam_en_99_test.json", kind: "scam", lang: "en", title: "Test",
                   lines: lines.map { ScriptLine(speaker: $0.0, text: $0.1, delayMs: 0) })
    }

    private let threeLines = [("caller", "This is Officer Tan from the police."), ("receiver", "Oh no, what happened?"),
                              ("caller", "Read me the OTP 123456 now or you will be arrested.")]

    private func waitUntil(_ what: String, timeout: Double = 10, _ cond: () -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !cond() {
            if Date() > end { XCTFail("timed out waiting for \(what)"); return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    private func finished(_ vm: CallViewModel) async throws {
        try await waitUntil("the summary") { vm.state.phase == .ended && !vm.state.reviewing }
    }

    nonisolated private static func json(_ s: String) -> Data { Data(s.utf8) }

    /// Answers /v1/utterance by seq and /v1/call/end with `end`.
    private func serve(utterance: @escaping @Sendable (Int) -> (Int, String), end: (Int, String)? = nil, endDelay: Double = 0) {
        StubProtocol.handler = { req, body in
            let path = req.url!.path
            if path.hasSuffix("/v1/utterance") {
                let seq = ((try? JSONSerialization.jsonObject(with: body ?? Data())) as? [String: Any])?["seq"] as? Int ?? 0
                let (code, s) = utterance(seq)
                return (code, Self.json(s), 0)
            }
            if path.hasSuffix("/v1/call/end"), let end { return (end.0, Self.json(end.1), endDelay) }
            if path.hasSuffix("/v1/report") { return (200, Self.json(#"{"report_id":12,"lines_stored":2,"expires_at":1795000000}"#), 0) }
            return (404, Self.json("{}"), 0)
        }
    }

    nonisolated private static func answer(_ seq: Int, _ verdict: String, money: Double, imp: Double, combined: Double, duplicate: Bool = false) -> String {
        """
        {"call_id":"x","seq":\(seq),"scored":true,"duplicate":\(duplicate),"verdict":"\(verdict)","combined_score":\(combined),
         "scores":{"urgency":0.3,"money":\(money),"impersonation":\(imp)},
         "recent_scores":{"urgency":0.3,"money":\(money),"impersonation":\(imp)},
         "action":"Hang up now. Call the organisation back on its official number, or ScamShield on 1799.",
         "ignored":false}
        """
    }

    func testAScamCallEndsReviewedAndIsSaved() async throws {
        serve(utterance: { seq in seq == 1 ? (200, Self.answer(1, "UNSURE", money: 0.1, imp: 0.95, combined: 0.5))
                                           : (200, Self.answer(3, "SCAM", money: 0.99, imp: 0.95, combined: 0.8)) },
              end: (200, #"{"call_id":"x","final_verdict":"SCAM","call_score":0.97,"live_verdict":"SCAM","review":"bert","top_window_seqs":[1,3],"reason":"Asked for your OTP or money","ended_by":"script_finished","already_ended":false}"#))
        let vm = try makeVM()
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        let s = vm.state
        XCTAssertEqual(s.verdict, "SCAM")
        XCTAssertTrue(s.scamAlert)
        XCTAssertEqual(hapticCount, 1, "one haptic on the first SCAM only")
        XCTAssertEqual(s.lines.first { $0.seq == 3 }?.flags, ["money", "impersonation"])
        XCTAssertEqual(s.summaryLevel, .red)
        XCTAssertTrue(s.finalReview!.reviewed)
        XCTAssertEqual(s.summaryReason, "Asked for your OTP or money. " + LocalRules.actionScam)
        XCTAssertEqual(s.triggerLines.map(\.seq), [1, 3])

        // what the server got: caller lines only (receivers aren't sent), seqs shared with the receiver lines
        let utterances = StubProtocol.seen.filter { $0.path.hasSuffix("/v1/utterance") }
        XCTAssertEqual(Set(utterances.compactMap { $0.json?["seq"] as? Int }), [1, 3])
        XCTAssertEqual(utterances.first?.json?["lang"] as? String, "en")
        let end = try XCTUnwrap(StubProtocol.seen.first { $0.path.hasSuffix("/v1/call/end") }?.json)
        XCTAssertEqual(end["ended_by"] as? String, "script_finished")
        XCTAssertEqual(end["last_seq"] as? Int, 3)
        XCTAssertEqual((end["caller_lines"] as? [[String: Any]])?.count, 2)

        let saved = vm.store.calls()
        XCTAssertEqual(saved.count, 1)
        XCTAssertEqual(saved[0].finalVerdict, "SCAM")
        XCTAssertEqual(saved[0].liveVerdict, "SCAM")
        XCTAssertEqual(saved[0].review, "bert")
        XCTAssertEqual(saved[0].reasonCode, "money")
        XCTAssertEqual(saved[0].privacyLevel, "cloud")
        XCTAssertNil(saved[0].riskiestSentence, "off by default")
        XCTAssertEqual(vm.store.points(saved[0].id).count, 2)
        // G.12: the review was very sure (0.97 > 0.9) -> a scam label
        XCTAssertEqual(vm.store.labels().map(\.source), ["final_review"])
        XCTAssertEqual(vm.store.sentenceCount(), 0, "no words kept while Help improve detection is off")
    }

    func testServerErrorsShowCouldNotCheckAndNeverSafe() async throws {
        serve(utterance: { _ in (500, #"{"error":"aggregator failed"}"#) }, end: nil)
        let vm = try makeVM()
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        let s = vm.state
        XCTAssertTrue(s.lines.filter(\.isCaller).allSatisfy(\.failed))
        XCTAssertEqual(s.gatewayError, "Server error 500")
        XCTAssertEqual(s.level, .error)
        XCTAssertEqual(s.finalReview, .unavailable)
        XCTAssertEqual(s.summaryLevel, .error)
        XCTAssertEqual(vm.store.calls().first?.finalVerdict, "NOT CHECKED")
        XCTAssertEqual(vm.store.calls().first?.reasonCode, "server_not_reached")
        XCTAssertEqual(vm.store.calls().first?.review, "unavailable")
    }

    func testNoServerAtAll() async throws {
        let vm = try makeVM()     // no handler: every request fails to connect
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        XCTAssertEqual(vm.state.gatewayError, "Could not reach the server")
        XCTAssertTrue(vm.state.duringCallLine.hasSuffix("Final check not available"))
    }

    func testIgnoredAndDuplicateAnswersChangeNothing() async throws {
        serve(utterance: { seq in
            seq == 1 ? (200, Self.answer(1, "UNSURE", money: 0.2, imp: 0.7, combined: 0.5))
                     : (200, #"{"call_id":"x","seq":3,"verdict":"SCAM","scored":false,"ignored":true,"reason":"call_ended"}"#)
        }, end: (200, #"{"final_verdict":"UNSURE","review":"fallback","already_ended":true}"#))
        let vm = try makeVM()
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        let s = vm.state
        XCTAssertEqual(s.verdict, "UNSURE", "an ignored answer is no change, even if it says SCAM")
        XCTAssertFalse(s.scamAlert)
        let line3 = try XCTUnwrap(s.lines.first { $0.seq == 3 })
        XCTAssertFalse(line3.pending)
        XCTAssertFalse(line3.failed)
        XCTAssertFalse(s.finalReview!.reviewed)             // already_ended: the live-verdict fallback
        XCTAssertEqual(s.summaryLevel, .yellow)

        // duplicate: true -> the answer is applied, but no second chart point
        StubProtocol.reset()
        serve(utterance: { seq in (200, Self.answer(seq, "UNSURE", money: 0.2, imp: 0.7, combined: 0.5, duplicate: true)) },
              end: (200, #"{"final_verdict":"UNSURE","review":"fallback"}"#))
        let vm2 = try makeVM()
        vm2.ring(script(threeLines))
        vm2.answer()
        try await finished(vm2)
        XCTAssertEqual(vm2.state.verdict, "UNSURE")
        XCTAssertEqual(vm2.store.points(vm2.store.calls()[0].id).count, 0)
    }

    func testUnknownCallAtTheEnd() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "SAFE", money: 0.1, imp: 0.1, combined: 0.1)) },
              end: (200, #"{"call_id":"x","final_verdict":"UNKNOWN","review":"fallback"}"#))
        let vm = try makeVM()
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        XCTAssertNil(vm.state.finalReview?.finalVerdict)
        XCTAssertEqual(vm.state.summaryLevel, .green)       // the live verdict stands
        XCTAssertFalse(vm.state.showsTriggers)              // SAFE: "What triggered it" is hidden
        XCTAssertEqual(vm.store.calls().first?.review, "fallback")
    }

    func testAnUnknownVerdictIsCouldNotCheck() async throws {
        serve(utterance: { seq in (200, #"{"seq":\#(seq),"verdict":"VERDICT_UNSPECIFIED"}"#) },
              end: (200, #"{"final_verdict":"UNKNOWN","review":"fallback"}"#))
        let vm = try makeVM()
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        XCTAssertEqual(vm.state.gatewayError, "Server gave no verdict")
        XCTAssertEqual(vm.state.summaryLevel, .error)
    }

    func testTheSummaryWaitsAtMost3sForTheFinalReview() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "UNSURE", money: 0.6, imp: 0.2, combined: 0.5)) },
              end: (200, #"{"final_verdict":"SAFE","review":"bert"}"#), endDelay: 3.6)
        let vm = try makeVM()
        vm.ring(script([("caller", "Please pay now")]))
        vm.answer()
        try await waitUntil("reviewing") { vm.state.reviewing }
        let started = Date()
        try await finished(vm)
        let waited = Date().timeIntervalSince(started)
        XCTAssertGreaterThan(waited, 2.5)
        XCTAssertLessThan(waited, 3.5)
        XCTAssertEqual(vm.state.finalReview, .unavailable)
        XCTAssertEqual(vm.state.summaryLevel, .yellow)
    }

    func testDiscardTellsTheServerAndSavesNothing() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "UNSURE", money: 0.2, imp: 0.7, combined: 0.5)) },
              end: (200, #"{"final_verdict":"UNSURE","review":"fallback"}"#))
        let vm = try makeVM()
        let long = CallScript(fileName: "scam_en_98_long.json", kind: "scam", lang: "en", title: "Long",
                              lines: [ScriptLine(speaker: "caller", text: "Hello this is the bank", delayMs: 0),
                                      ScriptLine(speaker: "caller", text: "Later line", delayMs: 60_000)])
        let slow = try CallViewModel(prefs: vm.prefs, store: CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                                     delayScale: 1, haptic: {}, startUp: false)
        slow.ring(long)
        slow.answer()
        try await waitUntil("the first answer") { slow.state.lines.first?.pending == false }
        slow.discard()
        XCTAssertEqual(slow.state.phase, .idle)
        try await waitUntil("the end message") { StubProtocol.seen.contains { $0.path.hasSuffix("/v1/call/end") } }
        let end = StubProtocol.seen.first { $0.path.hasSuffix("/v1/call/end") }?.json
        XCTAssertEqual(end?["ended_by"] as? String, "user_discarded")
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(slow.store.calls().isEmpty, "a discarded call isn't saved")
        XCTAssertTrue(slow.store.labels().isEmpty)
    }

    func testHangingUpRightAfterTheRedAlertIsLabelledScam() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "SCAM", money: 0.99, imp: 0.95, combined: 0.9)) },
              end: (200, #"{"final_verdict":"SCAM","review":"bert","call_score":0.5}"#))
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.gatewayUrl = "http://stub.test:8080/"
        prefs.helpImproveDetection = true
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                               delayScale: 1, haptic: {}, startUp: false)
        vm.ring(CallScript(fileName: "scam_en_97.json", kind: "scam", lang: "en", title: "T",
                           lines: [ScriptLine(speaker: "caller", text: "Read me the OTP 4829 from DBS, I'm Officer Tan", delayMs: 0),
                                   ScriptLine(speaker: "caller", text: "never reached", delayMs: 60_000)]))
        vm.answer()
        try await waitUntil("the red alert") { vm.state.scamAlert }
        vm.hangUp()
        try await finished(vm)
        let end = StubProtocol.seen.first { $0.path.hasSuffix("/v1/call/end") }?.json
        XCTAssertEqual(end?["ended_by"] as? String, "user_hung_up")
        XCTAssertEqual(vm.store.labels().map(\.source), ["hung_up_after_alert"])
        // "Help improve detection" on: the caller's line is kept, masked
        let sentences = vm.store.sentences(vm.store.calls()[0].id)
        XCTAssertEqual(sentences.map(\.text), ["Read me the OTP [NUMBER] from DBS, I'm Officer [PERSON_NAME]"])
        // "This was fine" after a warning adds a false-alarm label once
        vm.thisWasFine(); vm.thisWasFine()
        XCTAssertEqual(vm.store.labels().map(\.source), ["hung_up_after_alert", "this_was_fine"])
        // turning the opt-in off deletes the sentences, keeps the labels
        vm.setHelpImprove(false)
        XCTAssertEqual(vm.store.sentenceCount(), 0)
        XCTAssertEqual(vm.store.labelCount(), 2)
    }

    func testReportSendsOnlyMaskedCallerLinesAfterConsent() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "SCAM", money: 0.99, imp: 0.95, combined: 0.9)) },
              end: (200, #"{"final_verdict":"SCAM","review":"bert"}"#))
        let vm = try makeVM()
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        XCTAssertEqual(vm.reportPreview().map(\.text), ["This is Officer [PERSON_NAME] from the police.",
                                                        "Read me the OTP [NUMBER] now or you will be arrested."])
        XCTAssertFalse(StubProtocol.seen.contains { $0.path.hasSuffix("/v1/report") }, "nothing before consent")
        let msg = await vm.sendReport("scam")
        XCTAssertTrue(msg.hasPrefix("Thank you. Shared 2 lines"))
        let body = try XCTUnwrap(StubProtocol.seen.first { $0.path.hasSuffix("/v1/report") }?.json)
        XCTAssertEqual(body["kind"] as? String, "scam")
        XCTAssertEqual(body["app_verdict"] as? String, "SCAM")
        XCTAssertEqual(body["language"] as? String, "en")
        XCTAssertEqual(body["consent"] as? Bool, true)
        XCTAssertEqual(body["consent_version"] as? Int, 2)
        XCTAssertEqual((body["lines"] as? [[String: Any]])?.map { $0["text"] as? String },
                       ["This is Officer [PERSON_NAME] from the police.", "Read me the OTP [NUMBER] now or you will be arrested."])
    }

    func testDeleteMyHistoryClearsAllFourTables() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "SCAM", money: 0.99, imp: 0.95, combined: 0.9)) },
              end: (200, #"{"final_verdict":"SCAM","review":"bert","call_score":0.99}"#))
        let vm = try makeVM()
        vm.setHelpImprove(true)
        vm.setSaveSentence(true)
        vm.ring(script(threeLines))
        vm.answer()
        try await finished(vm)
        let id = vm.store.calls()[0].id
        XCTAssertEqual(vm.store.calls()[0].riskiestSentence, "Read me the OTP 123456 now or you will be arrested.")
        XCTAssertGreaterThan(vm.store.pointCount(), 0)
        XCTAssertGreaterThan(vm.store.labelCount(), 0)
        XCTAssertGreaterThan(vm.store.sentences(id).count, 0)
        vm.setSaveSentence(false)
        XCTAssertNil(vm.store.calls()[0].riskiestSentence, "turning it off forgets saved sentences")
        vm.deleteHistory()
        XCTAssertEqual(vm.store.calls().count, 0)
        XCTAssertEqual(vm.store.pointCount(), 0)
        XCTAssertEqual(vm.store.labelCount(), 0)
        XCTAssertEqual(vm.store.sentenceCount(), 0)
    }

    func testTestConnection() async throws {
        StubProtocol.handler = { req, _ in
            req.url!.path.hasSuffix("/healthz") ? (200, Self.json(#"{"status":"ok","calls_in_memory":0}"#), 0) : (404, Data(), 0)
        }
        let vm = try makeVM()
        let ok = await vm.testConnection("http://stub.test:8080/")
        XCTAssertEqual(ok, "Connected: ok")
        StubProtocol.handler = { _, _ in (404, Data(), 0) }
        let notFound = await vm.testConnection("http://stub.test:8443/")
        XCTAssertTrue(notFound.hasPrefix("Server answered 404"))
        StubProtocol.handler = { _, _ in (200, Data(), 0) }
        let proxy = await vm.testConnection("http://stub.test/")
        XCTAssertEqual(proxy, "Something answered, but not the ScamGuard gateway: check the address")
        let bad = await vm.testConnection("http://")
        XCTAssertEqual(bad, "Not a valid address")
    }

    func testPastedTextIsNeverSaved() async throws {
        serve(utterance: { seq in (200, Self.answer(seq, "SCAM", money: 0.99, imp: 0.95, combined: 0.9)) },
              end: (200, #"{"final_verdict":"SCAM","review":"bert","call_score":0.99}"#))
        let vm = try makeVM()
        vm.startPasted("Read me your OTP now")
        try await finished(vm)
        XCTAssertEqual(vm.state.verdict, "SCAM")
        XCTAssertTrue(vm.store.calls().isEmpty)
        XCTAssertTrue(vm.store.labels().isEmpty)
    }
}
