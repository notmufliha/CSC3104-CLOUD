import XCTest
@testable import ScamGuard

/// Phase 3: levels 2 and 3 through the whole app flow (CallViewModel), watching every request the app makes.
@MainActor
final class PrivacyLevelTests: XCTestCase {
    override func setUp() async throws { StubProtocol.reset() }
    override func tearDown() async throws { StubProtocol.reset() }

    private func makeVM(_ level: PrivacyLevel, startUp: Bool = false, updater: ModelUpdater? = nil) throws -> CallViewModel {
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.gatewayUrl = "http://stub.test:8080/"
        prefs.privacyLevel = level
        let services = OnDeviceServices(updater: updater ?? ModelUpdater(dir: FileManager.default.temporaryDirectory
            .appendingPathComponent("models-\(UUID().uuidString)")))
        return CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                             delayScale: 0, haptic: {}, onDevice: services, startUp: startUp)
    }

    private func waitForSummary(_ vm: CallViewModel) async throws {
        let end = Date().addingTimeInterval(20)
        while !(vm.state.phase == .ended && !vm.state.reviewing) {
            if Date() > end { XCTFail("call did not finish"); return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    /// Level 3 makes no network request at all: not at start-up, not during the call, not at the end, not for the
    /// report buttons. Every request the app could make goes through this session, which records it (and the
    /// handler fails the test if anything gets that far).
    func testOnDeviceMakesNoNetworkTrafficAtAll() async throws {
        StubProtocol.handler = { req, _ in
            XCTFail("level 3 made a request: \(req.url!.absoluteString)")
            return (500, Data(), 0)
        }
        let vm = try makeVM(.onDevice, startUp: true)       // start-up too: no model-update check at level 3
        vm.ring(try Fixtures.script("scam_en_11_bank_otp.json"))
        vm.answer()
        try await waitForSummary(vm)
        let s = vm.state
        XCTAssertEqual(s.privacyLevel, .onDevice)
        XCTAssertEqual(s.verdict, "SCAM")
        XCTAssertTrue(s.lines.filter(\.isCaller).allSatisfy { !$0.pending && !$0.failed })
        XCTAssertEqual(s.finalReview, .onDeviceReview)
        XCTAssertTrue(s.duringCallLine.hasSuffix("Checked on this phone"))
        XCTAssertTrue(s.summaryReason!.hasPrefix(Reasons.text(s.reasonCode)! + ". "))
        let msg = await vm.sendReport("scam")
        XCTAssertEqual(msg, CallViewModel.nothingShared)
        vm.thisWasFine()
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(StubProtocol.seen.count, 0, "requests: \(StubProtocol.seen.map(\.url))")
        let saved = vm.store.calls()
        XCTAssertEqual(saved.first?.privacyLevel, "on_device")
        XCTAssertEqual(saved.first?.review, "on_device")
        XCTAssertEqual(saved.first?.finalVerdict, "SCAM")
        // the summary's reason is the dashboard's
        XCTAssertTrue(s.summaryReason!.hasPrefix(saved.first!.displayReason + ". "))
    }

    func testChineseLinesCannotBeCheckedOnThePhone() async throws {
        StubProtocol.handler = { req, _ in XCTFail("request \(req.url!)"); return (500, Data(), 0) }
        let vm = try makeVM(.onDevice)
        vm.ring(try Fixtures.script("scam_zh_20_ica_to_china_police.json"))
        vm.answer()
        try await waitForSummary(vm)
        XCTAssertTrue(vm.state.lines.filter(\.isCaller).allSatisfy(\.failed))
        XCTAssertEqual(vm.state.gatewayError, "On-device checks are English only")
        XCTAssertEqual(StubProtocol.seen.count, 0)
    }

    /// Level 2: only masked 5-line windows to POST /v1/verify, from the 3rd caller line on; never /v1/utterance,
    /// /v1/call/end or /v1/report; nothing once the call is red.
    func testHybridSendsOnlyMaskedVerifyWindows() async throws {
        StubProtocol.handler = { req, _ in
            req.url!.path.hasSuffix("/v1/verify") ? (200, Data(#"{"score":0.95,"verdict":"SCAM","model_version":"bert-v2","latency_ms":50}"#.utf8), 0)
                                                    : (500, Data(), 0)
        }
        let vm = try makeVM(.hybrid)
        vm.ring(try Fixtures.script("scam_en_23_bank_card_handover.json"))
        vm.answer()
        try await waitForSummary(vm)
        let reqs = StubProtocol.seen
        XCTAssertFalse(reqs.isEmpty)
        XCTAssertTrue(reqs.allSatisfy { $0.path == "/v1/verify" }, "\(reqs.map(\.path))")
        for r in reqs {
            let lines = try XCTUnwrap(r.json?["lines"] as? [String])
            XCTAssertTrue((3...5).contains(lines.count), "from the 3rd caller line, at most 5: \(lines.count)")
            XCTAssertEqual(r.json?["lang"] as? String, "en")
            XCTAssertTrue(lines.allSatisfy { $0 == Mask.mask($0) }, "already masked")
            XCTAssertFalse(lines.joined().contains(where: \.isNumber))
        }
        XCTAssertEqual(vm.state.verdict, "SCAM")
        XCTAssertTrue(vm.state.lines.contains { $0.cloudNote == CallViewModel.cloudChecked })
        // red: nothing more was sent after the line that turned it red
        let calls = vm.state.lines.filter(\.isCaller)
        let notes = calls.compactMap(\.cloudNote).count
        XCTAssertEqual(notes, reqs.count)
        let msg = await vm.sendReport("scam")
        XCTAssertEqual(msg, CallViewModel.nothingShared)
        XCTAssertEqual(StubProtocol.seen.count, reqs.count, "no report traffic")
    }

    /// A slow cloud (> 1.5 s): the phone keeps its own answer and says so under the line.
    func testHybridGivesUpOnTheCloudAfterOneAndAHalfSeconds() async throws {
        StubProtocol.handler = { _, _ in (200, Data(#"{"score":0.99}"#.utf8), 3.0) }
        let vm = try makeVM(.hybrid)
        let script = try Fixtures.script("scam_en_11_bank_otp.json")
        let started = Date()
        vm.ring(CallScript(fileName: script.fileName, kind: script.kind, lang: "en", title: "t",
                           lines: Array(script.lines.filter(\.isCaller).prefix(3))))
        vm.answer()
        try await waitForSummary(vm)
        let line3 = try XCTUnwrap(vm.state.lines.last)
        if StubProtocol.seen.isEmpty {
            XCTAssertNil(line3.cloudNote)                  // not unsure on line 3: nothing was asked
        } else {
            XCTAssertEqual(line3.cloudNote, CallViewModel.cloudUnavailable)
            XCTAssertLessThan(Date().timeIntervalSince(started), 6)
        }
    }

    func testHybridWithNoServerWarnsLikeOnDevice() async throws {
        let vm = try makeVM(.hybrid)                        // no handler: verify fails
        vm.ring(try Fixtures.script("scam_en_11_bank_otp.json"))
        vm.answer()
        try await waitForSummary(vm)
        XCTAssertTrue(["SCAM", "UNSURE"].contains(vm.state.verdict ?? ""))
        XCTAssertTrue(vm.state.lines.contains { $0.cloudNote == CallViewModel.cloudUnavailable })
        XCTAssertTrue(StubProtocol.seen.allSatisfy { $0.path == "/v1/verify" })
    }
}

/// G.10: model updates from the registry, next to /api/ on the edge.
final class ModelUpdaterTests: XCTestCase {
    override func setUp() { StubProtocol.reset() }
    override func tearDown() { StubProtocol.reset() }

    func testModelsLiveNextToApiOnTheEdge() {
        XCTAssertEqual(ModelUpdater.modelsBase("https://1.2.3.4:8443/api/"), "https://1.2.3.4:8443/models/")
        XCTAssertEqual(ModelUpdater.modelsBase("http://localhost:8088/api/"), "http://localhost:8088/models/")
        XCTAssertEqual(ModelUpdater.modelsBase("http://localhost:8080/"), "http://localhost:8088/models/")
        XCTAssertEqual(ModelUpdater.modelsBase("192.168.1.23:8080"), "http://192.168.1.23:8088/models/")
        XCTAssertEqual(ModelUpdater.modelsBase("http://example.org/"), "http://example.org/models/")
        // the gateway port with a path: the edge serves /models at its root (same cases as Android ModelUpdaterTest)
        XCTAssertEqual(ModelUpdater.modelsBase("http://10.0.2.2:8080/gw/"), "http://10.0.2.2:8088/models/")
        XCTAssertEqual(ModelUpdater.modelsBase("http://10.0.2.2:8080/"), "http://10.0.2.2:8088/models/")
    }

    private func tempDir() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("models-\(UUID().uuidString)") }

    func testInstallsAVerifiedModelAndTheScorerUsesIt() async throws {
        let model = try Fixtures.appResource("urgency_en.json")
        // a "federated" version: same weights, new version name, so it still loads and is recognisable
        var doc = try JSONSerialization.jsonObject(with: model) as! [String: Any]
        doc["version"] = "v9-federated"
        let bytes = try JSONSerialization.data(withJSONObject: doc)
        let sha = ModelUpdater.sha256Hex(bytes)
        let manifest = #"{"models":{"urgency_en_phone":{"version":"v9","sha256":"\#(sha)","path":"/models/urgency_en_phone/v9"},"money_en_phone":{"version":"v2","sha256":"deadbeef"}}}"#
        StubProtocol.handler = { req, _ in
            switch req.url!.path {
            case "/models/manifest": return (200, Data(manifest.utf8), 0)
            case "/models/urgency_en_phone/v9": return (200, bytes, 0)
            case "/models/money_en_phone/v2": return (200, Data("{}".utf8), 0)
            default: return (404, Data(), 0)
            }
        }
        let dir = tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let updater = ModelUpdater(dir: dir)
        let msg = await updater.update(gatewayUrl: "http://localhost:8080/", session: StubProtocol.session())
        XCTAssertTrue(msg.hasPrefix("Updated: urgency v9"), msg)
        XCTAssertTrue(msg.contains("money: checksum mismatch"), msg)
        XCTAssertEqual(StubProtocol.seen.first?.url.absoluteString, "http://localhost:8088/models/manifest")
        XCTAssertEqual(updater.installed()["urgency_en_phone"]?.version, "v9")
        XCTAssertNil(updater.installed()["money_en_phone"])
        XCTAssertTrue(updater.describe().contains("urgency: federated v9"))
        XCTAssertTrue(updater.describe().contains("money: built-in model"))
        let services = OnDeviceServices(updater: updater)
        let scorer = await services.scorer()
        XCTAssertEqual(scorer?.urgencyModel.version, "v9-federated")
        XCTAssertEqual(scorer?.moneyModel.version, "money_en_v2c")    // bundled = fallback
        // the same sha again: nothing to do
        let again = await updater.update(gatewayUrl: "http://localhost:8080/", session: StubProtocol.session())
        XCTAssertTrue(again.contains("Kept the current models: money"), again)
    }

    func testAnUnreadableDownloadIsRefusedAndAnEmptyRegistryIsReported() async throws {
        let junk = Data("not a model".utf8)
        let sha = ModelUpdater.sha256Hex(junk)
        StubProtocol.handler = { req, _ in
            req.url!.path == "/models/manifest"
                ? (200, Data(#"{"models":{"urgency_en_phone":{"version":"v3","sha256":"\#(sha)"}}}"#.utf8), 0)
                : (200, junk, 0)
        }
        let dir = tempDir()
        let updater = ModelUpdater(dir: dir)
        let msg = await updater.update(gatewayUrl: "https://10.0.0.5:8443/api/", session: StubProtocol.session())
        XCTAssertTrue(msg.hasPrefix("Kept the current models: urgency"), msg)
        XCTAssertNil(updater.downloadedFile("urgency_en.json"))
        StubProtocol.handler = { _, _ in (200, Data(#"{"models":{}}"#.utf8), 0) }
        let empty = await updater.update(gatewayUrl: "http://localhost:8080/", session: StubProtocol.session())
        XCTAssertEqual(empty, "The registry has no phone models yet (run a federated round with --publish)")
        StubProtocol.handler = nil
        let down = await updater.update(gatewayUrl: "http://localhost:8080/", session: StubProtocol.session())
        XCTAssertTrue(down.hasPrefix("No model service at http://localhost:8088/models/manifest"), down)
    }
}
