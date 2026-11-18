import Foundation

/// First-launch setup: when it shows, which screens it has, and what it saves. Pure (no UI), kept in step with
/// android-app logic/Onboarding.kt; both apps test the same rules and wording. The wording itself is in
/// Localizable.xcstrings (generated from tools/i18n).
nonisolated enum Onboarding {
    /// Bump when the setup gains a screen that existing users should see. Saved with the "completed" flag.
    static let version = 1

    enum Step: Int, CaseIterable, Hashable, Sendable { case welcome, recommended, privacy, server, data, helper, family, pin, done }

    /// Which way through the setup. simple (the default): Welcome, "Use recommended settings", family
    /// contact (3 screens). custom ("Choose myself"): the full choices. helper ("Set up for a family member"): text
    /// size, Simple mode and read-aloud for them, the contact, an optional PIN.
    enum Path: Sendable { case simple, custom, helper }

    /// What the app does when it starts.
    enum Launch: Equatable, Sendable {
        /// First launch, or the setup was reset / never finished: show it.
        case showSetup
        /// An install from before the setup existed (it already has settings or history): never show it.
        case existingUser
        /// Setup was finished (or skipped) before.
        case done
    }

    /// completed: the saved flag, nil when it was never written. A reset writes false, so a reset install with old
    /// settings still shows the setup; only a missing flag counts settings or history as "already set up".
    static func atLaunch(completed: Bool?, hasSettings: Bool, hasHistory: Bool) -> Launch {
        switch completed {
        case true?: return .done
        case false?: return .showSetup
        case nil: return hasSettings || hasHistory ? .existingUser : .showSetup
        }
    }

    /// The screens, in order, for the way chosen. On-device needs no server, so that screen is left out.
    static func steps(_ c: Choices) -> [Step] {
        switch c.path {
        case .simple: return [.welcome, .recommended, .family]
        case .custom: return [.welcome, .recommended, .privacy] + (c.privacy == .onDevice ? [] : [.server]) + [.data, .family, .done]
        case .helper: return [.welcome, .helper, .family, .pin, .done]
        }
    }

    static func next(_ step: Step, _ c: Choices) -> Step? {
        let s = steps(c)
        guard let i = s.firstIndex(of: step), i + 1 < s.count else { return nil }
        return s[i + 1]
    }

    static func previous(_ step: Step, _ c: Choices) -> Step? {
        let s = steps(c)
        guard let i = s.firstIndex(of: step), i > 0 else { return nil }
        return s[i - 1]
    }

    /// Where the server screen left things.
    enum Server: Sendable { case notChecked, connected, cantReach, later }

    struct Choices: Equatable, Sendable {
        var privacy: PrivacyLevel = .defaultLevel
        var serverUrl: String
        var server: Server = .notChecked
        var saveSentence = false
        var helpImprove = false
        var path: Path = .simple
        var textSize = AppPrefs.textLarge
        /// Simple mode: on for every new install (an install from before it keeps the full app).
        var simple = true
        var readAloud = true
        var familyName = ""
        var familyPhone = ""
        /// A 4-digit PIN for Advanced settings and "For family" (helper setup only); "" = none.
        var pin = ""
    }

    /// What Skip saves on a first launch: recommended settings (Cloud, both data switches off, Simple mode on).
    static func defaults(_ currentUrl: String) -> Choices { Choices(serverUrl: currentUrl) }

    /// "Run setup again" starts from what is saved now, so Skip keeps it.
    static func fromSaved(_ store: OnboardingStore) -> Choices {
        var c = Choices(privacy: store.privacyLevel, serverUrl: store.gatewayUrl,
                        saveSentence: store.saveRiskiestSentence, helpImprove: store.helpImproveDetection)
        c.textSize = store.textSize
        c.simple = simpleModeAtLaunch(store.simpleMode)
        c.readAloud = store.readAloud
        c.familyName = store.familyName
        c.familyPhone = store.familyPhone
        return c
    }

    /// "Use recommended settings": the most accurate checking, nothing kept, warnings read aloud.
    static func recommended(_ c: Choices) -> Choices {
        var r = c
        r.privacy = .cloud
        r.saveSentence = false
        r.helpImprove = false
        r.readAloud = true
        r.path = .simple
        return r
    }

    /// Saves the choices and marks the setup done. The server address is kept as it was for On-device (no server
    /// screen) and for "Set up later". The PIN is saved separately (Keychain), by the caller.
    static func apply(_ c: Choices, _ store: OnboardingStore) {
        store.privacyLevel = c.privacy
        if c.privacy != .onDevice && c.server != .later { store.gatewayUrl = c.serverUrl }
        store.saveRiskiestSentence = c.saveSentence
        store.helpImproveDetection = c.helpImprove
        store.textSize = c.textSize
        store.readAloud = c.readAloud
        store.simpleMode = c.simple
        store.simpleModeOffered = true
        store.familyName = c.familyName.trimmingCharacters(in: .whitespacesAndNewlines)
        store.familyPhone = c.familyPhone.trimmingCharacters(in: .whitespacesAndNewlines)
        markCompleted(store)
    }

    static func markCompleted(_ store: OnboardingStore) {
        store.onboardingCompleted = true
        store.onboardingVersion = version
    }

    /// Debug "Reset setup": the next start shows the setup again, even with settings saved.
    static func reset(_ store: OnboardingStore) {
        store.onboardingCompleted = false
    }

    // ---- Simple mode for installs from before it

    /// New installs save Simple mode in the setup; an install from before it (nothing saved) keeps the full app.
    static func simpleModeAtLaunch(_ saved: Bool?) -> Bool { saved ?? false }

    /// An existing user is offered Simple mode once (a card on Home), never switched over by itself.
    static func offerSimpleMode(_ saved: Bool?, offered: Bool) -> Bool { saved == nil && !offered }

    /// (step number, number of steps), for "Step 2 of 3".
    static func progress(_ s: Step, _ c: Choices) -> (Int, Int) {
        let all = steps(c)
        return ((all.firstIndex(of: s) ?? 0) + 1, all.count)
    }
}

/// The settings the setup reads and writes: AppPrefs on the phone, a dictionary in tests.
nonisolated protocol OnboardingStore: AnyObject {
    var privacyLevel: PrivacyLevel { get set }
    var gatewayUrl: String { get set }
    var saveRiskiestSentence: Bool { get set }
    var helpImproveDetection: Bool { get set }
    /// nil = never written.
    var onboardingCompleted: Bool? { get set }
    var onboardingVersion: Int { get set }
    var textSize: String { get set }
    var readAloud: Bool { get set }
    /// nil = never written (an install from before Simple mode).
    var simpleMode: Bool? { get set }
    var simpleModeOffered: Bool { get set }
    var familyName: String { get set }
    var familyPhone: String { get set }
    /// Any setting saved by an earlier version of the app.
    func hasAnySetting() -> Bool
}

/// The guided tour. Simple mode: 3 coach marks (the practice call button, the red warning, its buttons). The full
/// app: 7 coach marks over real screens. The call steps use a pretend call (nothing starts, nothing is sent).
nonisolated enum Tour {
    enum Place: Sendable { case home, call, dashboard, settings }

    enum Target: String, CaseIterable, Sendable {
        case practiceCall = "tour.practiceCall"
        case scamCall = "tour.scamCall"
        case pickScript = "tour.pickScript"
        case riskBar = "tour.riskBar"
        case warningChips = "tour.warningChips"
        case redWarning = "tour.redWarning"
        case warningButtons = "tour.warningButtons"
        case dashboardHero = "tour.dashboardHero"
        case privacyRow = "tour.privacyRow"
    }

    struct Step: Sendable {
        let target: Target
        let place: Place
        let title: K
        let line: K
    }

    static let simpleSteps: [Step] = [
        Step(target: .practiceCall, place: .home, title: .tour_practice_title, line: .tour_practice_line),
        Step(target: .redWarning, place: .call, title: .tour_red_title, line: .tour_red_line),
        Step(target: .warningButtons, place: .call, title: .tour_buttons_title, line: .tour_buttons_line),
    ]

    static let fullSteps: [Step] = [
        Step(target: .scamCall, place: .home, title: .tour_scam_title, line: .tour_scam_line),
        Step(target: .pickScript, place: .home, title: .tour_pick_title, line: .tour_pick_line),
        Step(target: .riskBar, place: .call, title: .tour_bar_title, line: .tour_bar_line),
        Step(target: .warningChips, place: .call, title: .tour_chips_title, line: .tour_chips_line),
        Step(target: .redWarning, place: .call, title: .tour_red_title, line: .tour_red_line_full),
        Step(target: .dashboardHero, place: .dashboard, title: .tour_dash_title, line: .tour_dash_line),
        Step(target: .privacyRow, place: .settings, title: .tour_privacy_title, line: .tour_privacy_line),
    ]

    static func steps(simple: Bool) -> [Step] { simple ? simpleSteps : fullSteps }

    static func isLast(_ index: Int, simple: Bool) -> Bool { index == steps(simple: simple).count - 1 }
}
