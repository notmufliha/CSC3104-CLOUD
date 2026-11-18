import XCTest
@testable import ScamGuard

/// The same cases as android-app OnboardingTest.kt.
final class OnboardingTests: XCTestCase {
    static let defaultURL = "http://localhost:8080/"

    /// AppPrefs stand-in: a dictionary, so "never written" can be told apart from false.
    final class FakeStore: OnboardingStore {
        var saved: [String: Any]
        init(_ saved: [String: Any] = [:]) { self.saved = saved }

        var privacyLevel: PrivacyLevel {
            get { PrivacyLevel.fromId(saved["privacy_level"] as? String) }
            set { saved["privacy_level"] = newValue.rawValue }
        }
        var gatewayUrl: String {
            get { saved["gateway_url"] as? String ?? OnboardingTests.defaultURL }
            set { saved["gateway_url"] = newValue }
        }
        var saveRiskiestSentence: Bool {
            get { saved["save_riskiest_sentence"] as? Bool ?? false }
            set { saved["save_riskiest_sentence"] = newValue }
        }
        var helpImproveDetection: Bool {
            get { saved["help_improve_detection"] as? Bool ?? false }
            set { saved["help_improve_detection"] = newValue }
        }
        var onboardingCompleted: Bool? {
            get { saved["onboarding_completed"] as? Bool }
            set { saved["onboarding_completed"] = newValue }
        }
        var onboardingVersion: Int {
            get { saved["onboarding_version"] as? Int ?? 0 }
            set { saved["onboarding_version"] = newValue }
        }
        func hasAnySetting() -> Bool { saved.keys.contains { !$0.hasPrefix("onboarding") } }

        func launch(hasHistory: Bool = false) -> Onboarding.Launch {
            Onboarding.atLaunch(completed: onboardingCompleted, hasSettings: hasAnySetting(), hasHistory: hasHistory)
        }
    }

    // ---- when the setup shows

    func testFirstLaunchShowsSetup() {
        XCTAssertEqual(FakeStore().launch(), .showSetup)
    }

    func testCompletedNeverShowsAgain() {
        let s = FakeStore()
        Onboarding.apply(Onboarding.defaults(Self.defaultURL), s)
        XCTAssertEqual(s.onboardingCompleted, true)
        XCTAssertEqual(s.onboardingVersion, Onboarding.version)
        XCTAssertEqual(s.launch(), .done)
        XCTAssertEqual(s.launch(hasHistory: true), .done)
    }

    func testExistingUserUpgradeSkipsSetup() {
        // an install from before the setup: settings saved, no flag
        XCTAssertEqual(FakeStore(["privacy_level": "hybrid"]).launch(), .existingUser)
        XCTAssertEqual(FakeStore(["gateway_url": "http://x/"]).launch(), .existingUser)
        // no settings changed, but calls in the history
        XCTAssertEqual(FakeStore().launch(hasHistory: true), .existingUser)
        // marked done, then it stays done
        let s = FakeStore(["save_riskiest_sentence": true])
        Onboarding.markCompleted(s)
        XCTAssertEqual(s.launch(), .done)
    }

    func testResetShowsSetupEvenWithSettings() {
        let s = FakeStore(["privacy_level": "on_device"])
        Onboarding.markCompleted(s)
        Onboarding.reset(s)
        XCTAssertEqual(s.onboardingCompleted, false)
        XCTAssertEqual(s.launch(hasHistory: true), .showSetup)
    }

    func testReplayStartsFromSavedSettingsAndSkipKeepsThem() {
        let s = FakeStore()
        Onboarding.apply(Onboarding.Choices(privacy: .hybrid, serverUrl: "http://mac:8080/", server: .connected,
                                            saveSentence: true, helpImprove: true), s)
        let replay = Onboarding.fromSaved(s)
        XCTAssertEqual(replay.privacy, .hybrid)
        XCTAssertEqual(replay.serverUrl, "http://mac:8080/")
        XCTAssertTrue(replay.saveSentence && replay.helpImprove)
        Onboarding.apply(replay, s)   // Skip right away
        XCTAssertEqual(s.privacyLevel, .hybrid)
        XCTAssertEqual(s.gatewayUrl, "http://mac:8080/")
        XCTAssertTrue(s.saveRiskiestSentence && s.helpImproveDetection)
        XCTAssertEqual(s.launch(), .done)
    }

    // ---- what it saves

    func testSkipOnFirstLaunchSavesDefaults() {
        let s = FakeStore()
        Onboarding.apply(Onboarding.defaults(s.gatewayUrl), s)
        XCTAssertEqual(s.privacyLevel, .cloud)
        XCTAssertEqual(s.gatewayUrl, Self.defaultURL)
        XCTAssertFalse(s.saveRiskiestSentence)
        XCTAssertFalse(s.helpImproveDetection)
        XCTAssertEqual(s.launch(), .done)
    }

    func testChoicesAreSaved() {
        let s = FakeStore()
        Onboarding.apply(Onboarding.Choices(privacy: .hybrid, serverUrl: "https://192.168.1.23:8443/api/",
                                            server: .connected, saveSentence: true, helpImprove: false), s)
        XCTAssertEqual(s.privacyLevel, .hybrid)
        XCTAssertEqual(s.gatewayUrl, "https://192.168.1.23:8443/api/")
        XCTAssertTrue(s.saveRiskiestSentence)
        XCTAssertFalse(s.helpImproveDetection)
    }

    func testSetUpLaterAndOnDeviceKeepTheOldAddress() {
        let later = FakeStore(["gateway_url": "http://old/"])
        Onboarding.apply(Onboarding.Choices(privacy: .cloud, serverUrl: "http://typed-but-not-used/", server: .later), later)
        XCTAssertEqual(later.gatewayUrl, "http://old/")
        let onDevice = FakeStore(["gateway_url": "http://old/"])
        Onboarding.apply(Onboarding.Choices(privacy: .onDevice, serverUrl: "http://other/"), onDevice)
        XCTAssertEqual(onDevice.gatewayUrl, "http://old/")
        XCTAssertEqual(onDevice.privacyLevel, .onDevice)
    }

    /// The real settings store: a missing flag reads as nil, old keys count as settings.
    func testAppPrefsFlag() {
        let suite = "onboarding-test-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let p = AppPrefs(defaults: d)
        XCTAssertNil(p.onboardingCompleted)
        XCTAssertFalse(p.hasAnySetting())
        d.set("hybrid", forKey: AppPrefs.keyPrivacyLevel)
        XCTAssertTrue(p.hasAnySetting())
        Onboarding.reset(p)
        XCTAssertEqual(p.onboardingCompleted, false)
        Onboarding.markCompleted(p)
        XCTAssertEqual(p.onboardingCompleted, true)
        XCTAssertEqual(p.onboardingVersion, Onboarding.version)
    }

    // ---- which screens

    func testServerStepSkippedForOnDevice() {
        XCTAssertEqual(Onboarding.steps(.cloud), [.welcome, .privacy, .server, .data, .done])
        XCTAssertEqual(Onboarding.steps(.hybrid), [.welcome, .privacy, .server, .data, .done])
        XCTAssertEqual(Onboarding.steps(.onDevice), [.welcome, .privacy, .data, .done])
        XCTAssertEqual(Onboarding.next(.privacy, .onDevice), .data)
        XCTAssertEqual(Onboarding.next(.privacy, .cloud), .server)
        XCTAssertEqual(Onboarding.previous(.data, .onDevice), .privacy)
        XCTAssertNil(Onboarding.previous(.welcome, .cloud))
        XCTAssertNil(Onboarding.next(.done, .cloud))
        XCTAssertEqual(Onboarding.progress(.data, .onDevice), "Step 3 of 4")
        XCTAssertEqual(Onboarding.progress(.server, .hybrid), "Step 3 of 5")
    }

    // ---- wording (identical on Android)

    func testSummaryWording() {
        var c = Onboarding.Choices(privacy: .cloud, serverUrl: Self.defaultURL, server: .connected, saveSentence: true)
        let rows = Onboarding.summary(c)
        XCTAssertEqual(rows.map(\.0), ["Privacy", "Server", "Save riskiest sentence", "Help improve detection"])
        XCTAssertEqual(rows.map(\.1), ["Cloud", "Connected ✓", "On", "Off"])
        c.server = .cantReach; XCTAssertEqual(Onboarding.serverLabel(c), "Can't reach")
        c.server = .later; XCTAssertEqual(Onboarding.serverLabel(c), "Set up later")
        c.server = .notChecked; XCTAssertEqual(Onboarding.serverLabel(c), "Not checked")
        c.privacy = .onDevice; c.server = .connected; XCTAssertEqual(Onboarding.serverLabel(c), "Not needed")
        XCTAssertEqual(Onboarding.line(.welcome), "ScamGuard warns you during calls if the caller might be a scammer.")
        XCTAssertEqual(Onboarding.privacyTag(.onDevice), "Most private")
    }

    func testTourStepsAreShortAndInOrder() {
        XCTAssertEqual(Tour.steps.count, 7)
        for s in Tour.steps {
            XCTAssertLessThanOrEqual(s.title.split(separator: " ").count, 4, "title too long: \(s.title)")
            XCTAssertFalse(s.line.contains("\n"))
            for w in ["BERT", "verdict", "gateway"] { XCTAssertNil(s.line.range(of: w, options: .caseInsensitive)) }
        }
        XCTAssertEqual(Tour.steps.map(\.place), [.home, .home, .call, .call, .call, .dashboard, .settings])
        XCTAssertEqual(Tour.progress(1), "2 of 7")
        XCTAssertEqual(Tour.nextLabel(5), "Next")
        XCTAssertEqual(Tour.nextLabel(6), "Done")
        XCTAssertEqual(Tour.steps[4].line, "Hang up when you see this.")
    }

    // ---- the view model (setup -> app -> tour)

    @MainActor
    func testViewModelFirstLaunchSkipAndTour() throws {
        let suite = "onboarding-vm-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let vm = CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [],
                               startUp: false)
        XCTAssertEqual(vm.gate, .setup)
        vm.updateSetup { $0.privacy = .onDevice }
        vm.finishSetup(startTour: true)
        XCTAssertEqual(vm.gate, .app)
        XCTAssertEqual(vm.privacyLevel, .onDevice)
        XCTAssertEqual(vm.tourStep, 0)
        for _ in 0..<6 { vm.tourNext() }
        XCTAssertEqual(vm.tourStep, 6)
        vm.tourNext()
        XCTAssertNil(vm.tourStep)
        // next start: straight into the app
        let again = CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [],
                                  startUp: false)
        XCTAssertEqual(again.gate, .app)
        // replay from Help & about starts from what is saved
        again.runSetupAgain()
        XCTAssertEqual(again.gate, .setup)
        XCTAssertEqual(again.setupChoices.privacy, .onDevice)
        again.leaveSetup()
        XCTAssertEqual(again.gate, .app)
    }

    @MainActor
    func testViewModelExistingUserGoesStraightIn() throws {
        let suite = "onboarding-old-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        d.set("http://192.168.1.23:8080/", forKey: AppPrefs.keyURL)
        let prefs = AppPrefs(defaults: d)
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], startUp: false)
        XCTAssertEqual(vm.gate, .app)
        XCTAssertEqual(prefs.onboardingCompleted, true)
    }
}
