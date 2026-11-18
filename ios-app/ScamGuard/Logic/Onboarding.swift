import Foundation

/// First-launch setup: when it shows, which screens it has, and what it saves. Pure (no UI), kept in step with
/// android-app logic/Onboarding.kt; both apps test the same rules and wording.
nonisolated enum Onboarding {
    /// Bump when the setup gains a screen that existing users should see. Saved with the "completed" flag.
    static let version = 1

    enum Step: Int, CaseIterable, Hashable, Sendable { case welcome, privacy, server, data, done }

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

    /// The screens, in order. On-device needs no server, so that screen is left out.
    static func steps(_ privacy: PrivacyLevel) -> [Step] {
        Step.allCases.filter { $0 != .server || privacy != .onDevice }
    }

    static func next(_ step: Step, _ privacy: PrivacyLevel) -> Step? {
        let s = steps(privacy)
        guard let i = s.firstIndex(of: step), i + 1 < s.count else { return nil }
        return s[i + 1]
    }

    static func previous(_ step: Step, _ privacy: PrivacyLevel) -> Step? {
        let s = steps(privacy)
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
    }

    /// What Skip saves on a first launch: Cloud, the current server address, both data switches off.
    static func defaults(_ currentUrl: String) -> Choices { Choices(serverUrl: currentUrl) }

    /// "Run setup again" starts from what is saved now, so Skip keeps it.
    static func fromSaved(_ store: OnboardingStore) -> Choices {
        Choices(privacy: store.privacyLevel, serverUrl: store.gatewayUrl,
                saveSentence: store.saveRiskiestSentence, helpImprove: store.helpImproveDetection)
    }

    /// Saves the choices and marks the setup done. The server address is kept as it was for On-device (no server
    /// screen) and for "Set up later".
    static func apply(_ c: Choices, _ store: OnboardingStore) {
        store.privacyLevel = c.privacy
        if c.privacy != .onDevice && c.server != .later { store.gatewayUrl = c.serverUrl }
        store.saveRiskiestSentence = c.saveSentence
        store.helpImproveDetection = c.helpImprove
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

    // ---- wording (the same on Android)

    static func privacyTitle(_ p: PrivacyLevel) -> String {
        switch p {
        case .cloud: return "Cloud"
        case .hybrid: return "Hybrid"
        case .onDevice: return "On-device"
        }
    }

    static func privacyTag(_ p: PrivacyLevel) -> String {
        switch p {
        case .cloud: return "Most accurate"
        case .hybrid: return "Balanced"
        case .onDevice: return "Most private"
        }
    }

    /// What leaves the phone, in one line.
    static func privacyLine(_ p: PrivacyLevel) -> String {
        switch p {
        case .cloud: return "What the caller says goes to the server."
        case .hybrid: return "Only unsure moments go, names removed."
        case .onDevice: return "Nothing leaves this phone."
        }
    }

    static func serverLabel(_ c: Choices) -> String {
        if c.privacy == .onDevice { return "Not needed" }
        switch c.server {
        case .connected: return "Connected ✓"
        case .cantReach: return "Can't reach"
        case .later: return "Set up later"
        case .notChecked: return "Not checked"
        }
    }

    private static func onOff(_ b: Bool) -> String { b ? "On" : "Off" }

    /// The "You're all set" summary, label to value.
    static func summary(_ c: Choices) -> [(String, String)] {
        [("Privacy", privacyTitle(c.privacy)),
         ("Server", serverLabel(c)),
         ("Save riskiest sentence", onOff(c.saveSentence)),
         ("Help improve detection", onOff(c.helpImprove))]
    }

    /// Screen titles and their one line, by step.
    static func title(_ s: Step) -> String {
        switch s {
        case .welcome: return "Welcome to ScamGuard"
        case .privacy: return "Choose your privacy"
        case .server: return "Connect to the server"
        case .data: return "Your data"
        case .done: return "You're all set"
        }
    }

    static func line(_ s: Step) -> String {
        switch s {
        case .welcome: return "ScamGuard warns you during calls if the caller might be a scammer."
        case .privacy: return "Pick where calls are checked."
        case .server: return "ScamGuard needs its server to check calls."
        case .data: return "Your call history stays on this phone."
        case .done: return "Change these later in Settings."
        }
    }

    /// One line before the local-network question (iOS asks on Test connection; Android 17+ asks there too).
    static let networkHeadsUp = "Your phone may ask to find devices on your network. Tap Allow."

    /// "Step 2 of 5", for screen readers on the progress dots.
    static func progress(_ s: Step, _ privacy: PrivacyLevel) -> String {
        let all = steps(privacy)
        return "Step \((all.firstIndex(of: s) ?? 0) + 1) of \(all.count)"
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
    /// Any setting saved by an earlier version of the app.
    func hasAnySetting() -> Bool
}

/// The guided tour: seven coach marks over real screens (the call steps use a pretend call, nothing is sent).
nonisolated enum Tour {
    enum Place: Sendable { case home, call, dashboard, settings }

    enum Target: String, CaseIterable, Sendable {
        case scamCall = "tour.scamCall"
        case pickScript = "tour.pickScript"
        case riskBar = "tour.riskBar"
        case warningChips = "tour.warningChips"
        case redWarning = "tour.redWarning"
        case dashboardHero = "tour.dashboardHero"
        case privacyRow = "tour.privacyRow"

        var spoken: String {
            switch self {
            case .scamCall: return "Simulate scam call button"
            case .pickScript: return "Choose a call script button"
            case .riskBar: return "Risk bar"
            case .warningChips: return "Warning signs"
            case .redWarning: return "Red warning"
            case .dashboardHero: return "Dashboard summary"
            case .privacyRow: return "Privacy level"
            }
        }
    }

    struct Step: Sendable {
        let target: Target
        let place: Place
        let title: String
        let line: String
    }

    static let steps: [Step] = [
        Step(target: .scamCall, place: .home, title: "Try a demo call", line: "Plays a pretend scam call."),
        Step(target: .pickScript, place: .home, title: "Pick a call", line: "Choose which pretend call to play."),
        Step(target: .riskBar, place: .call, title: "The risk bar", line: "Shows how risky the call is."),
        Step(target: .warningChips, place: .call, title: "Warning signs", line: "Warning signs it heard light up."),
        Step(target: .redWarning, place: .call, title: "Red warning", line: "Hang up when you see this."),
        Step(target: .dashboardHero, place: .dashboard, title: "Your dashboard", line: "Your safety at a glance."),
        Step(target: .privacyRow, place: .settings, title: "Privacy level", line: "Change privacy anytime."),
    ]

    static func progress(_ index: Int) -> String { "\(index + 1) of \(steps.count)" }

    static func isLast(_ index: Int) -> Bool { index == steps.count - 1 }

    /// The bubble's main button.
    static func nextLabel(_ index: Int) -> String { isLast(index) ? "Done" : "Next" }
}
