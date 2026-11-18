import XCTest
@testable import ScamGuard

/// Phase 3 parity: the Swift cleaning + bundled models against the workers' own Python
/// (android-app/app/src/androidTest/assets/parity/parity_cases.json, written by android-app/tools/parity_cases.py).
/// Every cleaned text must be identical and every score within 0.001 (the file's own tolerance is 0.01).
final class OnDeviceParityTests: XCTestCase {
    static let tolerance = 0.001
    nonisolated(unsafe) static var scorer: OnDeviceScorer!
    nonisolated(unsafe) static var cases: [[String: Any]] = []

    override class func setUp() {
        super.setUp()
        scorer = try! OnDeviceScorer.load { try Fixtures.appResource($0) }
        cases = (try! Fixtures.json("parity_cases.json"))["cases"] as! [[String: Any]]
    }

    private func check(_ signal: String, clean: (String) -> String, score: (String) -> Double) {
        var worst = 0.0
        var worstText = ""
        var failures: [String] = []
        for c in Self.cases {
            let text = c["text"] as! String
            let want = c[signal] as! [String: Any]
            let wantClean = want["clean"] as! String
            let gotClean = clean(text)
            if gotClean != wantClean { failures.append("clean \"\(text)\"\n   python: \(wantClean)\n   swift:  \(gotClean)") }
            let wantScore = (want["score"] as! NSNumber).doubleValue
            let got = score(text)
            let d = abs(got - wantScore)
            if d > worst { worst = d; worstText = text }
            if d > Self.tolerance { failures.append("score \"\(text)\": python \(wantScore), swift \(got)") }
        }
        print("PARITY \(signal): \(Self.cases.count) sentences, largest difference \(String(format: "%.7f", worst)) (\"\(worstText.prefix(60))\")")
        XCTAssertTrue(failures.isEmpty, "\(signal): \(failures.count) mismatches\n" + failures.prefix(10).joined(separator: "\n"))
    }

    func testAtLeast300Sentences() { XCTAssertGreaterThanOrEqual(Self.cases.count, 300) }

    func testUrgencyMatchesPython() {
        check("urgency", clean: UrgencyText.normalize, score: Self.scorer.urgency)
    }

    func testMoneyMatchesPython() {
        let brands = Self.scorer.moneyBrands
        check("money", clean: { MoneyText.clean($0, brands) }, score: Self.scorer.money)
    }

    func testImpersonationMatchesPython() {
        let prep = ImpersonationText(Self.scorer.impersonationModel.orgTypes)
        check("impersonation", clean: prep.prep, score: Self.scorer.impersonation)
    }

    func testBundledModelsMatchTheManifest() throws {
        let manifest = try JSONSerialization.jsonObject(with: Fixtures.appResource("manifest.json")) as! [String: Any]
        let models = manifest["models"] as! [String: [String: Any]]
        for (_, m) in models {
            let file = m["file"] as! String
            XCTAssertEqual(ModelUpdater.sha256Hex(try Fixtures.appResource(file)), m["sha256"] as? String, file)
        }
        XCTAssertEqual(Self.scorer.urgencyModel.version, "v4")
        XCTAssertEqual(Self.scorer.moneyModel.version, "money_en_v2c")
        XCTAssertEqual(Self.scorer.impersonationModel.version, "impersonation_en_v5")
        XCTAssertTrue(Self.scorer.moneyModel.maskBrands)
        XCTAssertFalse(Self.scorer.urgencyModel.isHashing)
    }

    func testPythonStringHelpers() {
        XCTAssertEqual(Py.split("  a\tb\u{0B}c\u{1C}d  "), ["a", "b", "c", "d"])            // str.split()
        XCTAssertEqual(Py.splitLines("a\r\nb\u{2028}c\n"), ["a", "b", "c"])                  // str.splitlines()
        XCTAssertEqual(Py.strip("\u{1F} x \u{85}"), "x")
        XCTAssertEqual(Py.findAll(Py.re(#"\b\w\w+\b"#), "café 东京 x ab"), ["café", "东京", "ab"])   // Unicode \w
        XCTAssertEqual(Py.sub(Py.re(#"\s+"#), "a\u{0B}\u{0B}b", " "), "a b")                   // Python's \s
        XCTAssertEqual(Py.split(Py.re(#"(?<=[.!?])\s+"#), "Hi. You! Ok"), ["Hi.", "You!", "Ok"])
    }
}

/// G.10: the hashing (format 2) models' hash must equal scikit-learn's (murmurhash3_32(token.encode(), 0)).
final class Murmur3Tests: XCTestCase {
    // Values printed by scikit-learn 1.4.2 (android-app Murmur3Test): hash and abs(h) % 2**18.
    private let cases: [(String, Int32, Int)] = [
        ("now", 1153501715, 68115),
        ("act now", 576793883, 77083),
        ("arrested", 1873934942, 129630),
        ("été", 865297935, 222735),
        (" ab", -760043134, 87678),
        (" pol", 122369946, 210842),
        ("police officer", -1029493358, 53870),
    ]

    func testHashMatchesScikitLearn() {
        for (s, h, _) in cases { XCTAssertEqual(Murmur3.hash32(Array(s.utf8)), h, s) }
    }

    func testSlotMatchesScikitLearn() {
        for (s, _, slot) in cases { XCTAssertEqual(Murmur3.slot(s, 1 << 18), slot, s) }
    }

    /// A tiny format-2 model written by hand: the hashing path (slots, coef_b64 float32 LE, idf_index / idf_default,
    /// Platt calibration) gives exactly the number computed here step by step.
    func testHashingModelScoresLikeTheFormula() throws {
        let n = 1 << 18
        func b64(_ v: [Float]) -> String {
            var d = Data()
            for x in v { var le = x.bitPattern.littleEndian; d.append(Data(bytes: &le, count: 4)) }
            return d.base64EncodedString()
        }
        var coef = [Float](repeating: 0, count: n)
        let sNow = Murmur3.slot("now", n), sAct = Murmur3.slot("act", n), sActNow = Murmur3.slot("act now", n)
        coef[sNow] = 1.5; coef[sAct] = 0.5; coef[sActNow] = 2.0
        let json: [String: Any] = [
            "format": 2, "name": "urgency_en", "version": "v3", "threshold": 0.5, "intercept": -1.0,
            "calibration": ["a": 2.0, "b": 0.1],
            "vectorizers": [[
                "analyzer": "word", "ngram_range": [1, 2], "lowercase": true, "token_pattern": #"(?u)\b\w\w+\b"#,
                "stop_words": [], "sublinear_tf": true, "n_features": n, "idf_default": 1.0,
                "idf_index": [sNow], "idf_value": [3.0], "coef_b64": b64(coef),
            ]],
        ]
        let m = try LinearTextModel.load(JSONSerialization.data(withJSONObject: json))
        XCTAssertTrue(m.isHashing)
        // "Act NOW now": act(1) now(2) "act now"(1) "now now"(1, weight 0)
        let wNow = (log(2.0) + 1) * 3.0, wAct = 1.0, wActNow = 1.0, wNowNow = 1.0
        let norm = (wNow * wNow + wAct * wAct + wActNow * wActNow + wNowNow * wNowNow).squareRoot()
        let z = -1.0 + (wNow * 1.5 + wAct * 0.5 + wActNow * 2.0) / norm
        XCTAssertEqual(m.decision("Act NOW now"), z, accuracy: 1e-6)
        XCTAssertEqual(m.proba("Act NOW now"), 1 / (1 + exp(-(2.0 * z + 0.1))), accuracy: 1e-9)
        XCTAssertEqual(m.proba("nothing here"), 1 / (1 + exp(-(2.0 * -1.0 + 0.1))), accuracy: 1e-9)
    }

    func testABrokenModelFileIsRefused() {
        XCTAssertThrowsError(try LinearTextModel.load(Data(#"{"format":3,"name":"x","version":"v","threshold":0.5,"vectorizers":[],"intercept":0}"#.utf8)))
        XCTAssertThrowsError(try LinearTextModel.load(Data("not json".utf8)))
    }
}
