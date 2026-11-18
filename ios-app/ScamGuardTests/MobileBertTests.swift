import XCTest
@testable import ScamGuard

/// Phase 4 (G.4): ports of android-app WordPieceTokenizerTest and MobileBertDeviceTest, on the fixtures written by
/// android-app/tools/mobilebert_fixtures.py (Hugging Face `tokenizers` ids + Python onnxruntime scores for a DUMMY
/// model with MobileBERT's interface).
final class MobileBertTests: XCTestCase {
    private func fixture() throws -> (tokenizer: WordPieceTokenizer, maxLen: Int, cases: [[String: Any]]) {
        let vocab = String(decoding: try Fixtures.data("dummy_vocab.txt"), as: UTF8.self)
        let doc = try Fixtures.json("dummy_cases.json")
        return (try WordPieceTokenizer.fromVocab(vocab, lowerCase: true), doc["max_len"] as! Int, doc["cases"] as! [[String: Any]])
    }

    func testIdsMatchHuggingFaceTokenizers() throws {
        let f = try fixture()
        XCTAssertGreaterThanOrEqual(f.cases.count, 30)
        var failures: [String] = []
        for c in f.cases {
            let window = c["window"] as! String
            let want = (c["ids"] as! [NSNumber]).map(\.int64Value)
            let got = f.tokenizer.encode(window, maxLen: f.maxLen)
            if got != want { failures.append("\"\(window)\"\n  hf:    \(want)\n  phone: \(got)") }
        }
        XCTAssertTrue(failures.isEmpty, "\(failures.count) of \(f.cases.count) differ\n" + failures.prefix(5).joined(separator: "\n"))
    }

    func testWindowMatchesTheServersMakeWindow() throws {
        for c in try fixture().cases {
            XCTAssertEqual(MobileBertModel.window(c["lines"] as! [String]), c["window"] as! String)
        }
    }

    func testLeftTruncationKeepsTheNewestWords() throws {
        let long = Array(repeating: "please", count: 60).joined(separator: " ") + " transfer money now"
        let t = try WordPieceTokenizer(vocab: ["[CLS]": 0, "[SEP]": 1, "[UNK]": 2, "please": 3, "transfer": 4, "money": 5, "now": 6])
        XCTAssertEqual(t.encode(long, maxLen: 8), [0, 3, 3, 3, 4, 5, 6, 1])
    }

    func testSoftmaxWithTemperature() {
        let p = MobileBertModel.softmax([0, 2], temperature: 2)
        XCTAssertEqual(p[1], 1 / (1 + exp(-1.0)), accuracy: 1e-9)
        XCTAssertEqual(p.reduce(0, +), 1, accuracy: 1e-12)
    }

    func testAVocabWithoutSpecialTokensIsRefused() {
        XCTAssertThrowsError(try WordPieceTokenizer.fromVocab("a\nb\n"))
    }

    func testNoModelFileMeansNotInstalled() {
        guard case .notInstalled(let reason) = MobileBertModel.load({ _ in nil }) else { return XCTFail("should not load") }
        XCTAssertTrue(reason.hasPrefix("MobileBERT not installed"))
    }

    func testAModelWithoutItsVocabIsNotInstalledEither() throws {
        let model = try XCTUnwrap(Fixtures.bundle.url(forResource: "dummy_model", withExtension: "onnx"))
        guard case .notInstalled(let reason) = MobileBertModel.load({ $0 == "model.onnx" ? model : nil }) else { return XCTFail() }
        XCTAssertEqual(reason, "MobileBERT not installed (no vocab.txt)")
    }

    private func loadDummy() throws -> MobileBertModel {
        let status = MobileBertModel.load { name in
            Fixtures.bundle.url(forResource: "dummy_" + (name as NSString).deletingPathExtension, withExtension: (name as NSString).pathExtension)
        }
        guard case .installed(let m) = status else { XCTFail("dummy model should load: \(status)"); throw XCTSkip() }
        return m
    }

    /// The whole pipeline (window -> tokenizer -> ONNX Runtime -> softmax(logits / T)) gives the score Python's
    /// onnxruntime gave, for every window.
    func testDummyModelScoresMatchPythonOnnxRuntime() throws {
        let model = try loadDummy()
        XCTAssertEqual(model.config.version, "DUMMY-not-a-real-model")
        XCTAssertEqual(model.config.maxLen, 32)
        var worst = 0.0
        var failures: [String] = []
        for c in try fixture().cases {
            let got = Double(try model.scoreNow(c["lines"] as! [String]))
            let want = (c["score"] as! NSNumber).doubleValue
            worst = max(worst, abs(got - want))
            if abs(got - want) > 1e-4 { failures.append("\"\((c["window"] as! String).prefix(60))\": python \(want), phone \(got)") }
        }
        print("G4-DUMMY windows, largest difference \(String(format: "%.7f", worst))")
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
    }

    /// The real model bundled in the app (notebooks/mobilebert_g4.ipynb export) loads and gives a probability.
    func testTheAppItselfLoadsTheBundledModel() async throws {
        let status = await OnDeviceServices().mobileBert()
        guard case .installed(let model) = status else { return XCTFail("the bundled MobileBERT should load: \(status)") }
        XCTAssertTrue(model.config.version.hasPrefix("mobilebert-"), model.config.version)
        XCTAssertEqual(model.config.maxLen, 128)
        XCTAssertEqual(model.config.temperature, 1.2908, accuracy: 1e-9)
        XCTAssertGreaterThan(model.modelBytes, 1_000_000)
        let scam = try model.scoreNow([
            "This is the police. Your bank account is linked to money laundering.",
            "Transfer all your savings to the safe account now and read me the OTP.",
        ])
        let calm = try model.scoreNow([
            "Hi, this is the clinic calling to confirm your appointment on Tuesday.",
            "Please bring your IC and arrive ten minutes early. Thank you!",
        ])
        let t0 = Date()
        _ = try model.scoreNow(Array(repeating: "Please read me the six digit OTP that was just sent to your phone.", count: 5))
        let ms = Date().timeIntervalSince(t0) * 1000
        print("G4-REAL \(model.config.version): police/OTP \(scam), clinic \(calm), 5-line window \(String(format: "%.1f", ms)) ms")
        XCTAssertTrue((0...1).contains(scam))
        XCTAssertGreaterThan(scam, 0.6)
        XCTAssertLessThan(calm, 0.45)
        let tok = try WordPieceTokenizer.fromVocab(String(decoding: try Fixtures.appResource("vocab.txt"), as: UTF8.self))
        let ids = tok.encode("Please read me the OTP now.", maxLen: 128)
        XCTAssertTrue(ids.count > 3 && !ids.contains(Int64(tok.unkId)))
    }

    /// Extra check beyond Android's: the REAL bundled model against Python onnxruntime + Hugging Face tokenizers on
    /// the same windows (Fixtures/mobilebert_real_cases.json, scored in the backend's bert container). Same token
    /// ids; scores within 0.01 and on the same side of the 0.45 / 0.6 thresholds. (iOS runs ONNX Runtime 1.24.2, the
    /// reference 1.18.1; on this int8 model their kernels differ by up to ~0.005 on one saturated window, both arm64.)
    func testRealModelMatchesPythonOnnxRuntime() async throws {
        guard case .installed(let model) = await OnDeviceServices().mobileBert() else { return XCTFail("not installed") }
        let doc = try Fixtures.json("mobilebert_real_cases.json")
        let cases = doc["cases"] as! [[String: Any]]
        XCTAssertGreaterThanOrEqual(cases.count, 30)
        let tok = try WordPieceTokenizer.fromVocab(String(decoding: try Fixtures.appResource("vocab.txt"), as: UTF8.self))
        var worst = 0.0
        var failures: [String] = []
        for c in cases {
            let lines = c["lines"] as! [String]
            let window = c["window"] as! String
            XCTAssertEqual(MobileBertModel.window(lines), window)
            let wantIds = (c["ids"] as! [NSNumber]).map(\.int64Value)
            if tok.encode(window, maxLen: 128) != wantIds { failures.append("ids differ: \"\(window.prefix(60))\"") }
            let want = (c["score"] as! NSNumber).doubleValue
            let got = Double(try model.scoreNow(lines))
            worst = max(worst, abs(got - want))
            if abs(got - want) > 1e-2 { failures.append("\"\(window.prefix(60))\": python \(want), phone \(got)") }
            for t in [0.45, 0.6] where (got >= t) != (want >= t) {
                failures.append("\"\(window.prefix(60))\": other side of \(t): python \(want), phone \(got)")
            }
        }
        print("G4-REAL-PARITY \(cases.count) windows, largest difference \(String(format: "%.7f", worst))")
        XCTAssertTrue(failures.isEmpty, failures.prefix(10).joined(separator: "\n"))
    }

    /// Level 3 with a second opinion: asked only at unsure moments / the spot check, its score blended in.
    func testLevelThreeAsksMobileBertLikeHybridAsksTheCloud() async throws {
        let model = try loadDummy()
        let scorer = try OnDeviceScorer.load { try Fixtures.appResource($0) }
        let clock = FakeClock()
        clock.now = 0
        let call = OnDeviceCall(scorer, clock: { clock.now })
        let lines = ["Good afternoon, this is Officer Tan from the Singapore Police Force.",
                     "Your bank account has been linked to a money laundering investigation.",
                     "Please read me the six digit OTP that was just sent to your phone."]
        var asked: [OnDeviceCall.SecondOpinion] = []
        for (i, text) in lines.enumerated() {
            clock.now += 20_000
            let a = await call.answerWithSecondOpinion(callId: "t", seq: i, text: text, prepare: { $0 }) { await model.score($0) }
            if let s = a.second { asked.append(s) }
        }
        XCTAssertFalse(asked.isEmpty)
        for s in asked { XCTAssertTrue(s.bertScore != nil && s.lines.count <= 5) }
        // MobileBERT reads the raw lines (they never leave the phone)
        XCTAssertEqual(asked.last?.lines, lines)
    }
}

/// Level 3 with the real MobileBERT, through the whole app flow: still no network traffic.
@MainActor
final class MobileBertCallTests: XCTestCase {
    func testOnDeviceCallWithMobileBertMakesNoRequests() async throws {
        StubProtocol.reset()
        defer { StubProtocol.reset() }
        StubProtocol.handler = { req, _ in XCTFail("request \(req.url!)"); return (500, Data(), 0) }
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.privacyLevel = .onDevice
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                               delayScale: 0, haptic: {}, startUp: true)
        vm.ring(try Fixtures.script("scam_en_11_bank_otp.json"))
        vm.answer()
        let end = Date().addingTimeInterval(30)
        while !(vm.state.phase == .ended && !vm.state.reviewing) {
            if Date() > end { return XCTFail("call did not finish") }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(vm.state.verdict, "SCAM")
        XCTAssertTrue(vm.state.lines.contains { $0.cloudNote == CallViewModel.mobileBertChecked }, "\(vm.state.lines.map(\.cloudNote))")
        XCTAssertTrue(vm.mobileBertStatus.hasPrefix("MobileBERT installed (mobilebert-"), vm.mobileBertStatus)
        XCTAssertEqual(StubProtocol.seen.count, 0)
    }
}
