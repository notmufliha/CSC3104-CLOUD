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
        // Not saved yet: the app style's default, as AppPrefs does.
        var textSize: String {
            get { saved["text_size"] as? String ?? AppStyle.textSize(simple: AppStyle.simpleAtLaunch(simpleMode)) }
            set { saved["text_size"] = newValue }
        }
        var readAloud: Bool {
            get { saved["read_aloud"] as? Bool ?? AppStyle.readAloud(simple: AppStyle.simpleAtLaunch(simpleMode)) }
            set { saved["read_aloud"] = newValue }
        }
        var textSizeChosen: Bool {
            get { saved["text_size_chosen"] as? Bool ?? false }
            set { saved["text_size_chosen"] = newValue }
        }
        var readAloudChosen: Bool {
            get { saved["read_aloud_chosen"] as? Bool ?? false }
            set { saved["read_aloud_chosen"] = newValue }
        }
        var simpleMode: Bool? {
            get { saved["simple_mode"] as? Bool }
            set { saved["simple_mode"] = newValue }
        }
        var appStyleTipShown: Bool {
            get { saved["app_style_tip_shown"] as? Bool ?? false }
            set { saved["app_style_tip_shown"] = newValue }
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

    // ---- "Who will use this phone?" and the three ways through (the same cases as Android)

    private func c(_ path: Onboarding.Path, _ privacy: PrivacyLevel = .cloud) -> Onboarding.Choices {
        var c = Onboarding.defaults(Self.defaultURL)
        c.privacy = privacy
        return Onboarding.choose(c, path)
    }

    func testTheFirstQuestionComesFirstWithNothingPicked() {
        let start = Onboarding.defaults(Self.defaultURL)
        XCTAssertNil(start.path)
        XCTAssertFalse(start.simple)
        XCTAssertEqual(Onboarding.steps(start), [.who])
        for p in Onboarding.Path.allCases { XCTAssertEqual(Onboarding.steps(c(p)).first, .who) }
    }

    func testMeIsStandardWithTheFullSetup() {
        XCTAssertEqual(Onboarding.steps(c(.standard)), [.who, .welcome, .privacy, .server, .data, .done])
        XCTAssertEqual(Onboarding.steps(c(.standard, .onDevice)), [.who, .welcome, .privacy, .data, .done])
        let s = FakeStore()
        Onboarding.apply(c(.standard, .hybrid), s)
        XCTAssertEqual(s.simpleMode, false)
        XCTAssertEqual(s.textSize, "normal")
        XCTAssertFalse(s.readAloud, "read aloud is off in Standard mode")
        XCTAssertEqual(s.privacyLevel, .hybrid)
        XCTAssertEqual(Tour.steps(simple: AppStyle.simpleAtLaunch(s.simpleMode)).count, 7)
    }

    func testMeButSimplerIsSimpleModeWithThreeScreensAndNoFamilySetup() {
        XCTAssertEqual(Onboarding.steps(c(.simple)), [.who, .welcome, .recommended])
        XCTAssertTrue(Onboarding.progress(.recommended, c(.simple)) == (3, 3))
        XCTAssertFalse(Onboarding.steps(c(.simple)).contains(.family) || Onboarding.steps(c(.simple)).contains(.pin))
        let s = FakeStore()
        Onboarding.apply(Onboarding.recommended(c(.simple, .onDevice)), s)
        XCTAssertEqual(s.simpleMode, true)
        XCTAssertEqual(s.textSize, "large")
        XCTAssertTrue(s.readAloud)
        XCTAssertEqual(s.privacyLevel, .cloud)
        XCTAssertEqual(s.familyPhone, "")
        // "Choose myself": the detailed screens, still no family setup
        XCTAssertEqual(Onboarding.steps(c(.custom, .onDevice)), [.who, .welcome, .recommended, .privacy, .data, .done])
        XCTAssertTrue(c(.custom).simple)
    }

    func testFamilyPathGoesThroughContactAndPinAndEndsSimple() {
        XCTAssertEqual(Onboarding.steps(c(.helper)), [.who, .helper, .family, .pin, .handover])
        XCTAssertEqual(Onboarding.next(.helper, c(.helper)), .family)
        XCTAssertEqual(Onboarding.next(.pin, c(.helper)), .handover)
        XCTAssertNil(Onboarding.next(.handover, c(.helper)))
        let s = FakeStore()
        var h = c(.helper)
        h.textSize = "xlarge"; h.textSizeChosen = true; h.readAloud = false; h.readAloudChosen = true
        h.familyName = " Mei Ling "; h.familyPhone = " 9123 4567 "; h.pin = "2468"
        Onboarding.apply(h, s)
        XCTAssertEqual(s.simpleMode, true)
        XCTAssertEqual(s.textSize, "xlarge")
        XCTAssertFalse(s.readAloud)
        XCTAssertEqual(s.familyName, "Mei Ling")
        XCTAssertEqual(s.familyPhone, "9123 4567")
        XCTAssertFalse(s.saved.values.contains { ($0 as? String) == "2468" }, "the PIN is never kept with the settings")
        XCTAssertEqual(Tour.steps(simple: AppStyle.simpleAtLaunch(s.simpleMode)).count, 3)
    }

    func testSkipIsStandardWithRecommendedSettings() {
        let s = FakeStore()
        Onboarding.apply(Onboarding.defaults(Self.defaultURL), s)       // Skip on "Who will use this phone?"
        XCTAssertEqual(s.simpleMode, false)
        XCTAssertEqual(s.textSize, "normal")
        XCTAssertFalse(s.readAloud)
        XCTAssertEqual(s.privacyLevel, .cloud)
        XCTAssertFalse(s.saveRiskiestSentence || s.helpImproveDetection)
        XCTAssertFalse(AppStyle.showTip(simple: false, tipShown: s.appStyleTipShown), "no tip about the choice just made")
    }

    func testChangingTheAnswerResetsOnlyTheDefaults() {
        let simpler = c(.simple)
        XCTAssertEqual(simpler.textSize, "large")
        XCTAssertTrue(simpler.readAloud)
        let back = Onboarding.choose(simpler, .standard)
        XCTAssertEqual(back.textSize, "normal")
        XCTAssertFalse(back.readAloud)
        var picked = simpler
        picked.textSize = "xlarge"; picked.textSizeChosen = true
        XCTAssertEqual(Onboarding.choose(picked, .standard).textSize, "xlarge", "a size the user picked stays")
    }

    func testRunSetupAgainStartsAtTheQuestionAndKeepsTheRest() {
        let s = FakeStore()
        var h = c(.helper, .hybrid)
        h.familyName = "Mei Ling"; h.familyPhone = "91234567"
        Onboarding.apply(h, s)
        let replay = Onboarding.fromSaved(s)
        XCTAssertNil(replay.path, "nothing picked")
        XCTAssertEqual(Onboarding.steps(replay), [.who])
        XCTAssertEqual(replay.familyPhone, "91234567")
        XCTAssertEqual(replay.privacy, .hybrid)
    }

    // ---- switching later (Settings → App style)

    func testSwitchingKeepsDataAndOnlyChangesDefaultsTheUserLeftAlone() {
        let s = FakeStore()
        var h = c(.helper, .hybrid)
        h.familyName = "Mei Ling"; h.familyPhone = "91234567"; h.saveSentence = true
        Onboarding.apply(h, s)
        s.textSize = "xlarge"; s.textSizeChosen = true             // the user picked Extra large in Settings
        AppStyle.switchTo(simple: false, s)
        XCTAssertEqual(s.simpleMode, false)
        XCTAssertEqual(s.textSize, "xlarge")
        XCTAssertFalse(s.readAloud, "read aloud follows Standard (not set by the user)")
        XCTAssertEqual(s.familyPhone, "91234567")
        XCTAssertEqual(s.privacyLevel, .hybrid)
        XCTAssertTrue(s.saveRiskiestSentence)
        XCTAssertEqual(s.launch(), .done)
        AppStyle.switchTo(simple: true, s)
        XCTAssertTrue(s.readAloud)
        XCTAssertEqual(s.textSize, "xlarge")
    }

    func testLeavingSimpleModeAsksForThePinWhenSet() {
        XCTAssertTrue(AppStyle.needsPin(fromSimple: true, toSimple: false, pinSet: true))
        XCTAssertFalse(AppStyle.needsPin(fromSimple: true, toSimple: false, pinSet: false))
        XCTAssertFalse(AppStyle.needsPin(fromSimple: false, toSimple: true, pinSet: true), "turning Simple on never asks")
        XCTAssertFalse(AppStyle.needsPin(fromSimple: true, toSimple: true, pinSet: true))
    }

    // ---- existing installs

    func testUpgradeKeepsTheModeAndShowsTheTipOnce() {
        // set up before Simple mode existed: Standard, phone-size text, tip once
        let old = FakeStore(["privacy_level": "hybrid"])
        XCTAssertEqual(old.launch(), .existingUser)
        Onboarding.markCompleted(old)
        XCTAssertFalse(AppStyle.simpleAtLaunch(old.simpleMode))
        XCTAssertEqual(old.textSize, "normal")
        XCTAssertTrue(AppStyle.showTip(simple: AppStyle.simpleAtLaunch(old.simpleMode), tipShown: old.appStyleTipShown))
        old.appStyleTipShown = true                                         // "Got it"
        XCTAssertFalse(AppStyle.showTip(simple: false, tipShown: old.appStyleTipShown))
        // already in Simple mode: stays Simple, no tip, no new question
        let simple = FakeStore(["simple_mode": true, "onboarding_completed": true, "onboarding_version": 1])
        XCTAssertEqual(simple.launch(), .done)
        XCTAssertTrue(AppStyle.simpleAtLaunch(simple.simpleMode))
        XCTAssertEqual(simple.textSize, "large")
        XCTAssertFalse(AppStyle.showTip(simple: true, tipShown: simple.appStyleTipShown))
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
        XCTAssertEqual(vm.setupStep, .who)
        XCTAssertNil(vm.setupChoices.path, "nothing pre-selected")
        vm.updateSetup { $0 = Onboarding.choose($0, .simple); $0.privacy = .onDevice }
        vm.finishSetup(startTour: true)
        XCTAssertEqual(vm.gate, .app)
        XCTAssertEqual(vm.privacyLevel, .onDevice)
        XCTAssertTrue(vm.simpleMode, "Me, but simpler = Simple mode")
        XCTAssertEqual(vm.textSize, "large")
        XCTAssertTrue(vm.readAloud)
        XCTAssertFalse(vm.appStyleTip)
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
        XCTAssertEqual(again.setupStep, .who, "Run setup again starts at the question")
        XCTAssertNil(again.setupChoices.path)
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
        // upgrade: Standard stays (phone-size text, not read aloud), the App style tip shows once
        XCTAssertFalse(vm.simpleMode)
        XCTAssertEqual(vm.textSize, "normal")
        XCTAssertFalse(vm.readAloud)
        XCTAssertTrue(vm.appStyleTip)
        vm.dismissAppStyleTip()
        XCTAssertFalse(vm.appStyleTip)
        let again = CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [],
                                  pins: PinStore(service: "test.\(suite)"), startUp: false)
        XCTAssertFalse(again.simpleMode)
        XCTAssertFalse(again.appStyleTip, "shown only once")
        again.setAppStyle(simple: true)
        XCTAssertEqual(prefs.simpleMode, true)
    }

    @MainActor
    func testViewModelSkipIsStandard() throws {
        let suite = "onboarding-skip-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        let vm = CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [],
                               pins: PinStore(service: "test.\(suite)"), startUp: false)
        vm.finishSetup(startTour: true)                     // Skip on the first screen, then the tour
        XCTAssertFalse(vm.simpleMode)
        XCTAssertEqual(vm.textSize, "normal")
        XCTAssertFalse(vm.readAloud)
        XCTAssertFalse(vm.tourSimple, "the full 7-step tour")
        XCTAssertFalse(vm.appStyleTip)
    }

    @MainActor
    func testViewModelSwitchingKeepsDataAndAsksForThePin() throws {
        let suite = "onboarding-switch-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        let pins = PinStore(service: "test.\(suite)")
        defer { d.removePersistentDomain(forName: suite); pins.clear() }
        let store = try CallStore(inMemory: true)
        let vm = CallViewModel(prefs: AppPrefs(defaults: d), store: store, scripts: [], pins: pins, startUp: false)
        vm.updateSetup { $0 = Onboarding.choose($0, .helper); $0.familyName = "Mei Ling"; $0.familyPhone = "91234567"; $0.pin = "2468" }
        vm.finishSetup(startTour: false)
        vm.loadSampleHistory()
        let calls = store.calls().count
        XCTAssertTrue(vm.simpleMode)
        XCTAssertTrue(vm.pinSet)
        XCTAssertTrue(vm.appStyleNeedsPin(toSimple: false), "leaving Simple mode needs the PIN")
        XCTAssertFalse(vm.appStyleNeedsPin(toSimple: true))
        XCTAssertTrue(vm.verifyPin("2468"))
        vm.setAppStyle(simple: false)
        XCTAssertFalse(vm.simpleMode)
        XCTAssertEqual(vm.textSize, "normal")
        XCTAssertFalse(vm.readAloud)
        XCTAssertEqual(store.calls().count, calls, "history kept")
        XCTAssertGreaterThan(calls, 0)
        XCTAssertEqual(vm.family?.phone, "91234567", "contact kept")
        XCTAssertTrue(vm.pinSet, "PIN kept")
        XCTAssertFalse(vm.appStyleNeedsPin(toSimple: false), "already Standard")
        vm.setAppStyle(simple: true)
        XCTAssertTrue(vm.simpleMode)
        XCTAssertTrue(vm.readAloud)
    }
}
