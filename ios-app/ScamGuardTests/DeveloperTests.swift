import XCTest
@testable import ScamGuard

/// The hidden Developer view (Logic/Developer.swift). Same cases as android-app DeveloperTest.kt, plus whole calls
/// through CallViewModel with a stubbed gateway, the full reset and "nothing written" on the real settings store.
@MainActor
final class DeveloperTests: XCTestCase {
    override func setUp() async throws { StubProtocol.reset() }
    override func tearDown() async throws { StubProtocol.reset() }

    private final class MapStore: DevStore {
        var map: [String: Any] = [:]
        var developerUnlocked: Bool {
            get { map["developer_view_unlocked"] as? Bool ?? false }
            set { map["developer_view_unlocked"] = newValue }
        }
    }

    private func line(_ seq: Int) -> DevLine {
        DevLine(seq: seq, tMs: seq * 1000, urgency: 0.1, money: 0.9, impersonation: 0.2, combined: 0.7, bertScore: 0.95,
                bertStatus: "ok", rule: nil, liveAlert: "SCAM", verdict: "SCAM", place: "cloud", latencyMs: 120)
    }

    // ---- 7 taps unlock, the switch locks

    func testSevenTapsUnlock() {
        let s = DevSession(store: MapStore(), log: DevLog())
        var count = 0
        var outcomes: [DevUnlock.Outcome] = []
        for _ in 1...7 { let t = s.tap(count); count = t.count; outcomes.append(t.outcome) }
        XCTAssertEqual(outcomes, [.none, .none, .none, .stepsLeft, .stepsLeft, .stepsLeft, .unlocked])
        XCTAssertTrue(s.unlocked)
        XCTAssertTrue(s.log.recording)
        XCTAssertEqual(s.tap(0).outcome, .already)
    }

    func testTapsSayHowManyAreLeft() {
        XCTAssertEqual(DevUnlock.tap(3, unlocked: false).left, 3)
        XCTAssertEqual(DevUnlock.tap(4, unlocked: false).left, 2)
        XCTAssertEqual(DevUnlock.tap(5, unlocked: false).left, 1)
        XCTAssertEqual(DevUnlock.tap(6, unlocked: false), DevUnlock.Tap(count: 0, outcome: .unlocked))
    }

    func testSixTapsAreNotEnough() {
        let s = DevSession(store: MapStore(), log: DevLog())
        var count = 0
        for _ in 1...6 { count = s.tap(count).count }
        XCTAssertFalse(s.unlocked)
        XCTAssertFalse(s.log.recording)
    }

    func testLockingHidesItAgainAndForgetsEverything() {
        let store = MapStore()
        let s = DevSession(store: store, log: DevLog())
        s.setUnlocked(true)
        s.setOverlay(true)
        s.setSpeed(.x4)
        s.log.add(timeMs: 1, method: "GET", path: "/healthz", status: 200, ms: 3, request: "", response: #"{"status":"ok"}"#)
        s.setUnlocked(false)
        XCTAssertFalse(store.developerUnlocked)
        XCTAssertFalse(s.overlay)
        XCTAssertEqual(s.speed, .x1)
        XCTAssertTrue(s.log.all().isEmpty)
        XCTAssertFalse(s.log.recording)
        s.setOverlay(true); s.setSpeed(.instant)
        s.log.add(timeMs: 2, method: "GET", path: "/healthz", status: 200, ms: 3, request: "", response: "")
        XCTAssertFalse(s.overlay)
        XCTAssertEqual(s.speed, .x1)
        XCTAssertTrue(s.log.all().isEmpty)
    }

    /// The real settings store (UserDefaults): every Developer view action writes only its flag.
    func testTheUnlockedFlagIsTheOnlySettingWritten() throws {
        let name = UUID().uuidString
        let d = freshDefaults(name)
        let prefs = AppPrefs(defaults: d)
        prefs.privacyLevel = .cloud
        prefs.textSize = AppPrefs.textLarge
        let store = try CallStore(inMemory: true)
        let vm = CallViewModel(prefs: prefs, store: store, scripts: [], session: StubProtocol.session(), delayScale: 0,
                               pins: PinStore(service: "test.\(name)"), startUp: false, devLog: DevLog())
        let before = d.persistentDomain(forName: name) ?? [:]
        let callsBefore = store.calls().count
        var taps = 0
        for _ in 1...7 { taps = vm.developerTap(taps).count }
        vm.setDevOverlay(true)
        vm.setPlaybackSpeed(.instant)
        vm.devLog.add(timeMs: 1, method: "POST", path: "/v1/utterance", status: 200, ms: 4, request: #"{"text":"OTP 123456"}"#, response: "{}")
        _ = vm.devDiagnostics()
        _ = vm.devModelRows()
        vm.setDevOverlay(false)
        var after = d.persistentDomain(forName: name) ?? [:]
        XCTAssertEqual(after.removeValue(forKey: AppPrefs.keyDevUnlocked) as? Bool, true)
        XCTAssertEqual(NSDictionary(dictionary: after), NSDictionary(dictionary: before))
        XCTAssertEqual(store.calls().count, callsBefore)
    }

    // ---- the in-memory log

    func testTheLogKeepsTheNewest20() {
        let log = DevLog()
        log.recording = true
        for i in 0..<25 { log.add(timeMs: Int64(i), method: "POST", path: "/v1/utterance", status: 200, ms: i, request: #"{"seq":\#(i)}"#, response: "{}") }
        let e = log.all()
        XCTAssertEqual(e.count, 20)
        XCTAssertEqual(e.count, DevLog.capacity)
        XCTAssertEqual(e.first?.timeMs, 24)
        XCTAssertEqual(e.last?.timeMs, 5)
    }

    func testNothingIsKeptWhileLocked() {
        let log = DevLog()
        log.add(timeMs: 1, method: "GET", path: "/healthz", status: 200, ms: 3, request: "", response: "")
        log.startCall("c1", privacy: "cloud")
        log.addLine("c1", line(1))
        log.setModelVersions(["urgency": "v5"])
        XCTAssertTrue(log.all().isEmpty)
        XCTAssertNil(log.lastCall())
        XCTAssertTrue(log.modelVersions().isEmpty)
    }

    func testLastCallKeepsLinesInOrderAndTheEnd() {
        let log = DevLog()
        log.recording = true
        log.startCall("c1", privacy: "cloud")
        log.addLine("c1", line(3)); log.addLine("c1", line(1)); log.addLine("c1", line(3))
        log.addLine("other", line(9))
        log.endCall("c1", endedBy: "user_hung_up", end: DevEnd(finalVerdict: "SCAM", callScore: 0.91, review: "bert", topWindow: [1, 3]))
        let c = log.lastCall()!
        XCTAssertEqual(c.lines.map(\.seq), [1, 3])
        XCTAssertEqual(c.endedBy, "user_hung_up")
        XCTAssertEqual(c.end?.finalVerdict, "SCAM")
        XCTAssertEqual(log.latestLine("c1")?.seq, 3)
    }

    // ---- masking

    private let callerText = "This is Officer Tan from the police. Your NRIC S1234567D, call 9123 4567, OTP 482913, pay $5,000 to bob@mail.com"
    private let secrets = ["S1234567D", "9123 4567", "482913", "5,000", "bob@mail.com", "Tan", "111222"]

    func testTheLogMasksCallerText() {
        let log = DevLog()
        log.recording = true
        log.add(timeMs: 1, method: "POST", path: "/v1/utterance", status: 200, ms: 10,
                request: #"{"call_id":"c-42","seq":3,"speaker":"caller","text":"\#(callerText)","lang":"en"}"#,
                response: #"{"verdict":"SCAM","reasons":[{"signal":"money","text":"\#(callerText)"}]}"#)
        log.add(timeMs: 2, method: "POST", path: "/v1/call/end", status: 200, ms: 10,
                request: #"{"call_id":"c-42","caller_lines":[{"seq":3,"text":"\#(callerText)"}]}"#, response: "{}")
        log.add(timeMs: 3, method: "POST", path: "/v1/verify", status: 200, ms: 10,
                request: #"{"lines":["\#(callerText)","OTP 111222"],"lang":"en"}"#, response: #"{"score":0.9}"#)
        log.add(timeMs: 4, method: "POST", path: "/v1/utterance", status: 0, ms: 10, request: "not json: \(callerText)", response: "URLError: \(callerText)")
        let all = log.all().map { $0.request + "\n" + $0.response }.joined(separator: "\n")
        for s in secrets { XCTAssertFalse(all.contains(s), "\(s) leaked") }
        XCTAssertTrue(all.contains("[NUMBER]"))
        XCTAssertTrue(all.contains("[EMAIL_ADDRESS]"))
        XCTAssertTrue(all.contains("[PERSON_NAME]"))
        XCTAssertTrue(all.contains("\"c-42\"") && all.contains("\"seq\" : 3"), all)
    }

    func testMaskedBodiesArePrettyPrinted() {
        let out = DevMask.body(#"{"a":1,"text":"call 91234567"}"#)
        XCTAssertTrue(out.contains("\n  \"a\" : 1"), out)
        XCTAssertTrue(out.contains("[PHONE_NUMBER]"))
        XCTAssertEqual(DevMask.body(""), "")
    }

    // ---- whole calls with a stubbed gateway

    private func makeVM(_ level: PrivacyLevel = .cloud, unlocked: Bool = true) throws -> CallViewModel {
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.gatewayUrl = "http://stub.test:8080/"
        prefs.privacyLevel = level
        prefs.developerUnlocked = unlocked
        return CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                             delayScale: 0, pins: PinStore(service: "test.\(UUID().uuidString)"), startUp: false, devLog: DevLog())
    }

    private func script() -> CallScript {
        CallScript(fileName: "scam_en_99_test.json", kind: "scam", lang: "en", title: "Test", lines: [
            ScriptLine(speaker: "caller", text: "This is Officer Tan from the police.", delayMs: 3000),
            ScriptLine(speaker: "receiver", text: "Oh no?", delayMs: 1000),
            ScriptLine(speaker: "caller", text: callerText, delayMs: 2000),
        ])
    }

    private func serveScam() {
        StubProtocol.handler = { req, body in
            let path = req.url!.path
            if path.hasSuffix("/v1/utterance") {
                let seq = ((try? JSONSerialization.jsonObject(with: body ?? Data())) as? [String: Any])?["seq"] as? Int ?? 0
                let verdict = seq >= 3 ? "SCAM" : "UNSURE"
                return (200, Data(#"{"call_id":"x","seq":\#(seq),"scored":true,"verdict":"\#(verdict)","live_alert":"\#(verdict)","combined_score":0.8,"fast_score":0.8,"bert_score":0.93,"bert_status":"ok","scores":{"urgency":0.3,"money":0.99,"impersonation":0.95},"recent_scores":{"money":0.99},"model_versions":{"urgency":"v5","bert":"bert_v2"},"ignored":false}"#.utf8), 0)
            }
            if path.hasSuffix("/v1/call/end") {
                return (200, Data(#"{"call_id":"x","final_verdict":"SCAM","call_score":0.97,"review":"bert","top_window_seqs":[1,3]}"#.utf8), 0)
            }
            return (404, Data("{}".utf8), 0)
        }
    }

    private func waitUntil(_ what: String, timeout: Double = 10, _ cond: () -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !cond() {
            if Date() > end { XCTFail("timed out waiting for \(what)"); return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    /// Every speed plays every line in order and still sends the end message (so the server's memory is cleared).
    func testTheEndMessageIsSentAtEverySpeed() async throws {
        for speed in PlaybackSpeed.allCases {
            StubProtocol.reset()
            serveScam()
            let vm = try makeVM()
            vm.setPlaybackSpeed(speed)
            vm.ring(script())
            vm.answer()
            XCTAssertEqual(vm.state.speedTag, speed.tag)
            try await waitUntil("the summary at \(speed)") { vm.state.phase == .ended && !vm.state.reviewing }
            let paths = StubProtocol.seen.map(\.path)
            XCTAssertEqual(paths.filter { $0.hasSuffix("/v1/call/end") }.count, 1, "\(speed): \(paths)")
            XCTAssertEqual(vm.state.lines.map(\.seq), [1, 2, 3], "\(speed)")
            let seqs = StubProtocol.seen.filter { $0.path.hasSuffix("/v1/utterance") }.compactMap { $0.json?["seq"] as? Int }
            XCTAssertEqual(seqs.sorted(), [1, 3])
            XCTAssertEqual(StubProtocol.seen.last?.path.hasSuffix("/v1/call/end"), true, "the end comes last")
        }
    }

    func testEachSpeedScalesTheDelays() {
        XCTAssertEqual(Playback.gap(1_500, .x1), 1_500)
        XCTAssertEqual(Playback.gap(1_500, .x2), 750)
        XCTAssertEqual(Playback.gap(1_500, .x4), 375)
        XCTAssertEqual(Playback.gap(1_500, .instant), Playback.instantGapMs)
        XCTAssertEqual(Playback.gap(9_000, .instant), 150)
        XCTAssertNil(PlaybackSpeed.x1.tag)
        XCTAssertEqual(PlaybackSpeed.x2.tag, "2× speed")
        XCTAssertEqual(PlaybackSpeed.x4.tag, "4× speed")
        XCTAssertEqual(PlaybackSpeed.instant.tag, "Instant")
    }

    func testEverySpeedKeepsTheOrderAndEnds() async {
        let lines = [ScriptLine(speaker: "receiver", text: "Hello?", delayMs: 0), ScriptLine(speaker: "caller", text: "a", delayMs: 2000),
                     ScriptLine(speaker: "caller", text: "b", delayMs: 1000), ScriptLine(speaker: "receiver", text: "ok", delayMs: nil),
                     ScriptLine(speaker: "caller", text: "c", delayMs: 4000)]
        for speed in PlaybackSpeed.allCases {
            var events: [String] = []
            await Playback.play(lines, speed: speed, defaultDelayMs: 1500, sleep: { events.append("sleep \($0)"); return true },
                                onLine: { i, _ in events.append("line \(i)") }) { events.append("end") }
            let gaps = [0, 2000, 1000, 1500, 4000].map { Playback.gap($0, speed) }
            XCTAssertEqual(events, gaps.indices.flatMap { ["sleep \(gaps[$0])", "line \($0)"] } + ["end"], "\(speed)")
        }
    }

    /// The Developer view records the call (numbers only), the masked requests and the server's model versions;
    /// "Copy diagnostics" then has sections 1-3 and no caller text.
    func testACallIsRecordedAndDiagnosticsHaveNoCallerText() async throws {
        serveScam()
        let vm = try makeVM()
        vm.ring(script())
        vm.answer()
        try await waitUntil("the summary") { vm.state.phase == .ended && !vm.state.reviewing }
        let call = try XCTUnwrap(vm.devLog.lastCall())
        XCTAssertEqual(call.lines.map(\.seq), [1, 3])
        XCTAssertEqual(call.lines.last?.place, "cloud")
        XCTAssertEqual(call.lines.last?.bertStatus, "ok")
        XCTAssertEqual(call.lines.last?.liveAlert, "SCAM")
        XCTAssertEqual(call.endedBy, "script_finished")
        XCTAssertEqual(call.end?.finalVerdict, "SCAM")
        XCTAssertEqual(call.end?.topWindow, [1, 3])
        XCTAssertEqual(vm.devLog.modelVersions()["bert"], "bert_v2")
        XCTAssertEqual(vm.devLatest?.seq, 3)
        let raw = vm.devLog.all()
        XCTAssertEqual(raw.map(\.path).filter { $0.hasSuffix("/v1/utterance") }.count, 2)
        let rawText = raw.map { $0.request + $0.response }.joined()
        for s in secrets where s != "111222" { XCTAssertFalse(rawText.contains(s), "\(s) in the raw log") }
        let diag = vm.devDiagnostics()
        for s in secrets + ["Officer", "police"] { XCTAssertFalse(diag.contains(s), "\(s) in diagnostics") }
        XCTAssertTrue(diag.contains("1. Connection") && diag.contains("2. Models") && diag.contains("3. Last call"))
        XCTAssertTrue(diag.contains("final SCAM"))
        XCTAssertFalse(diag.contains("utterance"), "raw replies are not in the copy")
        // the chat bubbles still show the real text, as before
        XCTAssertTrue(vm.state.lines.contains { $0.text.contains("S1234567D") })
    }

    func testNothingIsRecordedWhileLocked() async throws {
        serveScam()
        let vm = try makeVM(unlocked: false)
        vm.ring(script())
        vm.answer()
        try await waitUntil("the summary") { vm.state.phase == .ended && !vm.state.reviewing }
        XCTAssertNil(vm.devLog.lastCall())
        XCTAssertTrue(vm.devLog.all().isEmpty)
        XCTAssertNil(vm.state.speedTag)
    }

    func testDeleteMyHistoryClearsTheLog() async throws {
        serveScam()
        let vm = try makeVM()
        vm.ring(script())
        vm.answer()
        try await waitUntil("the summary") { vm.state.phase == .ended && !vm.state.reviewing }
        XCTAssertFalse(vm.devLog.all().isEmpty)
        vm.deleteHistory()
        XCTAssertTrue(vm.devLog.all().isEmpty)
        XCTAssertNil(vm.devLog.lastCall())
        XCTAssertNil(vm.devLatest)
        XCTAssertTrue(vm.devUnlocked)
    }

    // ---- overlay

    func testOverlayHidesWhileTheRedWarningShows() {
        XCTAssertTrue(DevOverlay.visible(enabled: true, unlocked: true, inCall: true, warningOpen: false))
        XCTAssertFalse(DevOverlay.visible(enabled: true, unlocked: true, inCall: true, warningOpen: true))
        XCTAssertFalse(DevOverlay.visible(enabled: false, unlocked: true, inCall: true, warningOpen: false))
        XCTAssertFalse(DevOverlay.visible(enabled: true, unlocked: false, inCall: true, warningOpen: false))
        XCTAssertFalse(DevOverlay.visible(enabled: true, unlocked: true, inCall: false, warningOpen: false))
    }

    func testTheOverlayIsHiddenWhenTheRedWarningOpensDuringACall() async throws {
        serveScam()
        let vm = try makeVM()
        vm.setDevOverlay(true)
        vm.ring(script())
        vm.answer()
        try await waitUntil("the red warning") { vm.state.warningOpen }
        XCTAssertFalse(DevOverlay.visible(enabled: vm.devOverlay, unlocked: vm.devUnlocked, inCall: vm.state.phase == .inCall,
                                          warningOpen: vm.state.warningOpen))
        vm.closeWarning()
        if vm.state.phase == .inCall {
            XCTAssertTrue(DevOverlay.visible(enabled: vm.devOverlay, unlocked: vm.devUnlocked, inCall: true, warningOpen: vm.state.warningOpen))
        }
    }

    // ---- what normal users see

    func testTheUserFacingStatusShowsNoAddress() {
        XCTAssertEqual(ConnectionStatus.of(.cloud, true), .connected)
        XCTAssertEqual(ConnectionStatus.of(.hybrid, false), .cantConnect)
        XCTAssertEqual(ConnectionStatus.of(.cloud, nil), .checking)
        XCTAssertEqual(ConnectionStatus.of(.onDevice, false), .notNeeded)
        XCTAssertEqual(L.s(ConnectionStatus.connected.key), "Connected ✓")
        XCTAssertEqual(L.s(ConnectionStatus.cantConnect.key), "Can't connect to ScamGuard")
        XCTAssertEqual(L.s(.conn_fail_line), "Ask the person who set up this phone for help")
        let shown = [ConnectionStatus.connected, .cantConnect, .checking, .notNeeded].map { L.s($0.key) } + [L.s(.conn_fail_line)]
        for t in shown {
            XCTAssertNil(t.range(of: #"https?://|\d|/api|localhost|error"#, options: [.regularExpression, .caseInsensitive]), t)
        }
    }

    func testServerSettingsAreOnlyInTheDeveloperView() {
        let routes = SettingsRoute.allCases.map(\.rawValue)
        XCTAssertFalse(routes.contains { $0.contains("server") || $0.contains("models") })
        XCTAssertTrue(routes.contains("developer"))
        XCTAssertFalse(Onboarding.Step.allCases.contains { "\($0)".contains("server") })
    }

    func testLearningLoopCallsAreHiddenUntilUnlocked() throws {
        XCTAssertTrue(DevScripts.isTestCall("scam_en_90_new_scam_test_A_report_this.json"))
        XCTAssertTrue(DevScripts.isTestCall("scam_en_91_new_scam_test_B_same_scam_new_words.json"))
        XCTAssertFalse(DevScripts.isTestCall("scam_en_11_bank_otp.json"))
        let scripts = [CallScript(fileName: "scam_en_90_test.json", kind: "scam", lang: "en", title: "T", lines: [])]
        let prefs = AppPrefs(defaults: freshDefaults())
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: scripts, session: StubProtocol.session(),
                               delayScale: 0, pins: PinStore(service: "test.\(UUID().uuidString)"), startUp: false, devLog: DevLog())
        XCTAssertNil(vm.randomScript("scam"), "locked: never a learning-loop test call")
        vm.setDeveloperUnlocked(true)
        XCTAssertEqual(vm.randomScript("scam")?.fileName, "scam_en_90_test.json")
    }

    // ---- resets

    func testResetAppToFirstLaunchEmptiesEverything() throws {
        let name = UUID().uuidString
        let d = freshDefaults(name)
        let prefs = AppPrefs(defaults: d)
        let store = try CallStore(inMemory: true)
        let pins = PinStore(service: "test.\(name)")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("models-\(name)")
        let vm = CallViewModel(prefs: prefs, store: store, scripts: [], session: StubProtocol.session(), delayScale: 0,
                               onDevice: OnDeviceServices(updater: ModelUpdater(dir: dir)), pins: pins, startUp: false, devLog: DevLog())
        // a used phone: settings, history (all 4 tables), a PIN, a downloaded model, logs in memory
        prefs.privacyLevel = .hybrid
        prefs.gatewayUrl = "http://192.168.1.23:8080/"
        prefs.familyPhone = "91234567"
        prefs.simpleMode = true
        Onboarding.markCompleted(prefs)
        vm.setDeveloperUnlocked(true)
        vm.setPin("2468")
        store.insertSample(SampleHistory.generate(nowMs: 1_800_000_000_000, todayStartMs: 1_799_990_000_000))
        store.recordLabels(callId: store.calls()[0].id, labels: [ImplicitLabels.Label(label: 1, source: "hung_up")],
                           callerLines: [(1, "masked")], helpImprove: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("{}".utf8).write(to: dir.appendingPathComponent("urgency_en.json"))
        vm.devLog.add(timeMs: 1, method: "GET", path: "/healthz", status: 200, ms: 3, request: "", response: "{}")
        XCTAssertGreaterThan(store.calls().count, 0)
        XCTAssertGreaterThan(store.labelCount(), 0)
        XCTAssertGreaterThan(store.pointCount(), 0)

        vm.resetApp()

        XCTAssertEqual(store.calls().count, 0)
        XCTAssertEqual(store.pointCount(), 0)
        XCTAssertEqual(store.labelCount(), 0)
        XCTAssertEqual(store.sentenceCount(), 0)
        let left = (d.persistentDomain(forName: name) ?? [:]).keys.filter { AppPrefs.allKeys.contains($0) }
        XCTAssertEqual(left, [], "settings left: \(left)")
        XCTAssertFalse(pins.isSet)
        XCTAssertFalse(vm.pinSet)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.path))
        XCTAssertTrue(vm.devLog.all().isEmpty)
        XCTAssertFalse(vm.devUnlocked)
        XCTAssertFalse(prefs.developerUnlocked)
        // lands on the first setup screen, as a new install
        XCTAssertEqual(vm.gate, .setup)
        XCTAssertEqual(vm.setupStep, .who)
        XCTAssertNil(vm.setupChoices.path)
        XCTAssertEqual(Onboarding.atLaunch(completed: prefs.onboardingCompleted, hasSettings: prefs.hasAnySetting(), hasHistory: false), .showSetup)
        // bundled models and scripts are untouched
        XCTAssertNotNil(Bundle.main.url(forResource: "urgency_en", withExtension: "json"))
        XCTAssertFalse(ScriptRepository.loadAll().isEmpty)
    }

    func testResetSetupOnlyKeepsHistory() throws {
        let prefs = AppPrefs(defaults: freshDefaults())
        let store = try CallStore(inMemory: true)
        prefs.privacyLevel = .hybrid
        Onboarding.markCompleted(prefs)
        let vm = CallViewModel(prefs: prefs, store: store, scripts: [], session: StubProtocol.session(), delayScale: 0,
                               pins: PinStore(service: "test.\(UUID().uuidString)"), startUp: false, devLog: DevLog())
        vm.setDeveloperUnlocked(true)
        vm.loadSampleHistory()
        let calls = store.calls().count
        XCTAssertGreaterThan(calls, 0)
        vm.resetOnboarding()
        XCTAssertEqual(vm.gate, .setup)
        XCTAssertEqual(vm.setupStep, .who)
        XCTAssertEqual(store.calls().count, calls)
        XCTAssertEqual(prefs.privacyLevel, .hybrid)
        XCTAssertTrue(prefs.developerUnlocked)
        vm.deleteSampleHistory()
        XCTAssertEqual(store.calls().count, 0)
    }

    // ---- formats

    func testTableLinesUpAndModelsAreRead() {
        let t = DevFormat.table([line(1), line(12)]).components(separatedBy: "\n")
        XCTAssertEqual(t.count, 3)
        XCTAssertTrue(t[0].hasPrefix("seq"))
        XCTAssertEqual(t[1].range(of: "cloud")?.lowerBound.utf16Offset(in: t[1]), t[2].range(of: "cloud")?.lowerBound.utf16Offset(in: t[2]))
        let bundled = DevModels.bundled(Data(#"{"models":{"urgency_en":{"version":"urgency_en_v5","sha256":"abc"}}}"#.utf8))
        XCTAssertEqual(bundled.map { "\($0.0)=\($0.1)" }, ["bundled urgency_en=urgency_en_v5  sha256 abc"])
        XCTAssertTrue(DevModels.downloaded(nil).isEmpty)
        XCTAssertEqual(DevModels.downloaded(Data(#"{"money_en_phone":{"version":"v3","sha256":"def","installed_at":1}}"#.utf8)).first?.1, "v3  sha256 def")
    }
}
