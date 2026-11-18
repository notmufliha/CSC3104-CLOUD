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
        var textSize: String {
            get { saved["text_size"] as? String ?? "large" }
            set { saved["text_size"] = newValue }
        }
        var readAloud: Bool {
            get { saved["read_aloud"] as? Bool ?? true }
            set { saved["read_aloud"] = newValue }
        }
        var simpleMode: Bool? {
            get { saved["simple_mode"] as? Bool }
            set { saved["simple_mode"] = newValue }
        }
        var simpleModeOffered: Bool {
            get { saved["simple_mode_offered"] as? Bool ?? false }
            set { saved["simple_mode_offered"] = newValue }
        }
        var familyName: String {
            get { saved["family_name"] as? String ?? "" }
            set { saved["family_name"] = newValue }
        }
        var familyPhone: String {
            get { saved["family_phone"] as? String ?? "" }
            set { saved["family_phone"] = newValue }
        }
        /// Only keys an earlier version could have saved (as AppPrefs.existingKeys).
        func hasAnySetting() -> Bool {
            saved.keys.contains { ["gateway_url", "save_riskiest_sentence", "privacy_level", "help_improve_detection"].contains($0) }
        }

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

    private func c(_ path: Onboarding.Path, _ privacy: PrivacyLevel = .cloud) -> Onboarding.Choices {
        var c = Onboarding.defaults(Self.defaultURL)
        c.path = path
        c.privacy = privacy
        return c
    }

    func testSimpleSetupIsThreeScreens() {
        XCTAssertEqual(Onboarding.steps(c(.simple)), [.welcome, .recommended, .family])
        XCTAssertNil(Onboarding.next(.family, c(.simple)))
        XCTAssertTrue(Onboarding.progress(.recommended, c(.simple)) == (2, 3))
    }

    func testChooseMyselfKeepsTheFullChoicesAndSkipsServerForOnDevice() {
        XCTAssertEqual(Onboarding.steps(c(.custom)), [.welcome, .recommended, .privacy, .server, .data, .family, .done])
        XCTAssertEqual(Onboarding.steps(c(.custom, .onDevice)), [.welcome, .recommended, .privacy, .data, .family, .done])
        XCTAssertEqual(Onboarding.next(.privacy, c(.custom, .onDevice)), .data)
        XCTAssertEqual(Onboarding.next(.privacy, c(.custom)), .server)
        XCTAssertNil(Onboarding.previous(.welcome, c(.custom)))
        XCTAssertTrue(Onboarding.progress(.data, c(.custom, .onDevice)) == (4, 6))
    }

    func testFamilyHelperSetup() {
        XCTAssertEqual(Onboarding.steps(c(.helper)), [.welcome, .helper, .family, .pin, .done])
        let s = FakeStore()
        var h = c(.helper)
        h.textSize = "xlarge"; h.simple = true; h.readAloud = false
        h.familyName = " Mei Ling "; h.familyPhone = " 9123 4567 "; h.pin = "2468"
        Onboarding.apply(h, s)
        XCTAssertEqual(s.textSize, "xlarge")
        XCTAssertEqual(s.simpleMode, true)
        XCTAssertFalse(s.readAloud)
        XCTAssertEqual(s.familyName, "Mei Ling")
        XCTAssertEqual(s.familyPhone, "9123 4567")
        XCTAssertFalse(s.saved.values.contains { ($0 as? String) == "2468" }, "the PIN is never kept with the settings")
    }

    func testRecommendedSettings() {
        var x = c(.custom, .onDevice)
        x.saveSentence = true; x.helpImprove = true; x.readAloud = false
        let r = Onboarding.recommended(x)
        XCTAssertEqual(r.privacy, .cloud)
        XCTAssertFalse(r.saveSentence || r.helpImprove)
        XCTAssertTrue(r.readAloud)
        XCTAssertEqual(r.path, .simple)
    }

    // ---- Simple mode: on for new installs, never forced on existing ones

    func testSimpleModeDefaults() {
        let s = FakeStore()
        Onboarding.apply(Onboarding.defaults(Self.defaultURL), s)       // a new install (finished or skipped)
        XCTAssertEqual(s.simpleMode, true)
        XCTAssertTrue(Onboarding.simpleModeAtLaunch(s.simpleMode))
        XCTAssertFalse(Onboarding.offerSimpleMode(s.simpleMode, offered: s.simpleModeOffered))
        XCTAssertTrue(Onboarding.defaults(Self.defaultURL).simple)
        XCTAssertTrue(Onboarding.defaults(Self.defaultURL).readAloud)
        XCTAssertEqual(Onboarding.defaults(Self.defaultURL).textSize, "large")
    }

    func testExistingUsersKeepTheirModeAndAreOfferedSimpleModeOnce() {
        let s = FakeStore(["privacy_level": "hybrid"])                  // installed before Simple mode
        XCTAssertEqual(s.launch(), .existingUser)
        Onboarding.markCompleted(s)
        XCTAssertNil(s.simpleMode)
        XCTAssertFalse(Onboarding.simpleModeAtLaunch(s.simpleMode), "keeps the full app")
        XCTAssertTrue(Onboarding.offerSimpleMode(s.simpleMode, offered: s.simpleModeOffered), "offered once")
        s.simpleModeOffered = true                                        // "No, thanks"
        XCTAssertFalse(Onboarding.offerSimpleMode(s.simpleMode, offered: s.simpleModeOffered))
        XCTAssertFalse(Onboarding.simpleModeAtLaunch(s.simpleMode))
        XCTAssertEqual(s.privacyLevel, .hybrid)
        XCTAssertFalse(Onboarding.offerSimpleMode(false, offered: false))
        XCTAssertFalse(Onboarding.simpleModeAtLaunch(false))
    }

    // ---- wording (identical on Android)

    func testSetupWording() {
        XCTAssertEqual(L.s(.setup_welcome_line), "ScamGuard warns you during calls if the caller might be a scammer.")
        XCTAssertEqual(L.s(.privacy_promise), "Your calls stay private. We never record your voice.")
        XCTAssertEqual(L.s(.privacy_tag_on_device), "Most private")
        XCTAssertEqual(L.s(.setup_step, "2", "3"), "Step 2 of 3")
    }

    func testToursAreShortAndInOrder() {
        XCTAssertEqual(Tour.simpleSteps.map(\.target), [.practiceCall, .redWarning, .warningButtons])
        XCTAssertEqual(Tour.simpleSteps.map(\.place), [.home, .call, .call])
        XCTAssertEqual(Tour.fullSteps.map(\.place), [.home, .home, .call, .call, .call, .dashboard, .settings])
        for s in Tour.simpleSteps + Tour.fullSteps {
            let title = L.s(s.title), line = L.s(s.line)
            XCTAssertLessThanOrEqual(title.split(separator: " ").count, 4, "title too long: \(title)")
            XCTAssertFalse(line.contains("\n"))
            for w in ["BERT", "verdict", "gateway", "server"] { XCTAssertNil(line.range(of: w, options: .caseInsensitive), line) }
        }
        XCTAssertTrue(Tour.isLast(2, simple: true))
        XCTAssertFalse(Tour.isLast(2, simple: false))
        XCTAssertEqual(L.s(.tour_progress, "2", "3"), "2 of 3")
        XCTAssertEqual(L.s(Tour.fullSteps[4].line), "Hang up when you see this.")
    }

    // ---- the view model (setup -> app -> tour)

    @MainActor
    func testViewModelFirstLaunchSkipAndTour() throws {
        let suite = "onboarding-vm-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let vm = CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [],
                               pins: PinStore(service: "test.\(suite)"), startUp: false)
        XCTAssertEqual(vm.gate, .setup)
        vm.updateSetup { $0.privacy = .onDevice }
        vm.finishSetup(startTour: true)
        XCTAssertEqual(vm.gate, .app)
        XCTAssertEqual(vm.privacyLevel, .onDevice)
        XCTAssertTrue(vm.simpleMode, "a new install is in Simple mode")
        XCTAssertFalse(vm.simpleOffer)
        XCTAssertEqual(vm.tourStep, 0)
        XCTAssertTrue(vm.tourSimple)
        for _ in 0..<2 { vm.tourNext() }
        XCTAssertEqual(vm.tourStep, 2)
        vm.tourNext()
        XCTAssertNil(vm.tourStep, "Simple mode's tour has 3 steps")
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
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [],
                               pins: PinStore(service: "test.\(suite)"), startUp: false)
        XCTAssertEqual(vm.gate, .app)
        XCTAssertEqual(prefs.onboardingCompleted, true)
        // upgrade: the full app stays, Simple mode is offered once
        XCTAssertFalse(vm.simpleMode)
        XCTAssertTrue(vm.simpleOffer)
        vm.declineSimpleOffer()
        XCTAssertFalse(vm.simpleOffer)
        let again = CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [],
                                  pins: PinStore(service: "test.\(suite)"), startUp: false)
        XCTAssertFalse(again.simpleMode)
        XCTAssertFalse(again.simpleOffer, "asked only once")
        again.setSimpleMode(true)
        XCTAssertEqual(prefs.simpleMode, true)
    }
}
