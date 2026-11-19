import Foundation

/// First-launch setup: when it shows, which screens it has, and what it saves. Pure (no UI), kept in step with
/// android-app logic/Onboarding.kt; both apps test the same rules and wording. The wording itself is in
/// Localizable.xcstrings (generated from tools/i18n).
nonisolated enum Onboarding {
    /// Bump when the setup gains a screen that existing users should see. Saved with the "completed" flag.
    static let version = 2

    enum Step: Int, CaseIterable, Hashable, Sendable {
        case who, welcome, recommended, privacy, data, helper, family, pin, handover, done
    }

    /// Which way through the setup, from the first screen ("Who will use this phone?"); nil = not chosen yet.
    /// standard ("Me"): the full app and its 5-screen setup. simple ("Me, but simpler"): Simple mode, Welcome and
    /// "Use recommended settings"; custom is its "Choose myself". helper ("An older family member"): Simple mode set
    /// up by a helper: text size and read-aloud, the family contact, an optional PIN, then "Hand the phone to ...".
    enum Path: CaseIterable, Sendable { case standard, simple, custom, helper }

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

    /// The screens, in order, for the way chosen. There is no server screen: the setup uses the saved (or default)
    /// address without asking; the server address is set in the hidden Developer view.
    static func steps(_ c: Choices) -> [Step] {
        switch c.path {
        case nil: return [.who]
        case .standard?: return [.who, .welcome, .privacy, .data, .done]
        case .simple?: return [.who, .welcome, .recommended]
        case .custom?: return [.who, .welcome, .recommended, .privacy, .data, .done]
        case .helper?: return [.who, .helper, .family, .pin, .handover]
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


    struct Choices: Equatable, Sendable {
        var privacy: PrivacyLevel = .defaultLevel
        var saveSentence = false
        var helpImprove = false
        /// nil until "Who will use this phone?" is answered (nothing is pre-selected).
        var path: Path?
        var textSize = AppStyle.textSize(simple: false)
        var readAloud = AppStyle.readAloud(simple: false)
        /// The user picked the text size / read-aloud themselves: switching modes then keeps it.
        var textSizeChosen = false
        var readAloudChosen = false
        var familyName = ""
        var familyPhone = ""
        /// A 4-digit PIN for Advanced settings and "For family" (helper setup only); "" = none.
        var pin = ""

        /// Simple mode: every way except "Me" (and Skip on the first screen).
        var simple: Bool { path != nil && path != .standard }
    }

    /// What Skip saves on a first launch: Standard mode, recommended settings (Cloud, both data switches off).
    static func defaults() -> Choices { Choices() }

    /// An answer to "Who will use this phone?": the way through, and that mode's text size and read-aloud.
    static func choose(_ c: Choices, _ path: Path) -> Choices {
        let simple = path != .standard
        var r = c
        r.path = path
        if !c.textSizeChosen { r.textSize = AppStyle.textSize(simple: simple) }
        if !c.readAloudChosen { r.readAloud = AppStyle.readAloud(simple: simple) }
        return r
    }

    /// Skip before answering the first question = Standard mode.
    static func answered(_ c: Choices) -> Choices { c.path == nil ? choose(c, .standard) : c }

    /// "Run setup again" starts from what is saved now (Skip keeps it), at the first question, nothing picked.
    static func fromSaved(_ store: OnboardingStore) -> Choices {
        var c = Choices(privacy: store.privacyLevel,
                        saveSentence: store.saveRiskiestSentence, helpImprove: store.helpImproveDetection)
        c.textSize = store.textSize
        c.readAloud = store.readAloud
        c.textSizeChosen = store.textSizeChosen
        c.readAloudChosen = store.readAloudChosen
        c.familyName = store.familyName
        c.familyPhone = store.familyPhone
        return c
    }

    /// "Use recommended settings" (Simple mode): the most accurate checking, nothing kept, warnings read aloud.
    static func recommended(_ c: Choices) -> Choices {
        var r = c
        r.privacy = .cloud
        r.saveSentence = false
        r.helpImprove = false
        if !c.readAloudChosen { r.readAloud = true }
        r.path = .simple
        return r
    }

    /// Saves the choices and marks the setup done. The server address is never changed here (the saved or default one
    /// is used). The PIN is saved separately (Keychain), by the caller.
    static func apply(_ choices: Choices, _ store: OnboardingStore) {
        let c = answered(choices)
        store.privacyLevel = c.privacy
        store.saveRiskiestSentence = c.saveSentence
        store.helpImproveDetection = c.helpImprove
        store.simpleMode = c.simple
        store.textSize = c.textSize
        store.readAloud = c.readAloud
        store.textSizeChosen = c.textSizeChosen
        store.readAloudChosen = c.readAloudChosen
        store.appStyleTipShown = true            // they just chose: no tip about it
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

    /// (step number, number of steps), for "Step 2 of 3".
    static func progress(_ s: Step, _ c: Choices) -> (Int, Int) {
        let all = steps(c)
        return ((all.firstIndex(of: s) ?? 0) + 1, all.count)
    }
}

/// The two app styles. Standard: the full app (dashboard, insights, history, all settings), the phone's own text
/// size, warnings not read aloud. Simple: Simple Home, "For family", short settings + Advanced, large text, read
/// aloud. Both get the same red warning, "Be careful" banner and plain wording. Same rules on Android (logic/Onboarding.kt).
nonisolated enum AppStyle {
    static func textSize(simple: Bool) -> String { simple ? AppPrefs.textLarge : AppPrefs.textNormal }

    static func readAloud(simple: Bool) -> Bool { simple }

    /// Saved mode; nothing saved = an install from before Simple mode, which keeps the full app.
    static func simpleAtLaunch(_ saved: Bool?) -> Bool { saved ?? false }

    /// Settings → App style. Keeps the history, settings, contact and PIN; only the layout changes, and the text size
    /// and read-aloud follow the new style unless the user set them.
    static func switchTo(simple: Bool, _ store: OnboardingStore) {
        store.simpleMode = simple
        if !store.textSizeChosen { store.textSize = textSize(simple: simple) }
        if !store.readAloudChosen { store.readAloud = readAloud(simple: simple) }
        store.appStyleTipShown = true
    }

    /// Leaving Simple mode needs the PIN when one is set, so a senior can't switch by accident.
    static func needsPin(fromSimple: Bool, toSimple: Bool, pinSet: Bool) -> Bool { fromSimple && !toSimple && pinSet }

    /// The one-time Home tip for users who were set up before the "Who will use this phone?" question.
    static func showTip(simple: Bool, tipShown: Bool) -> Bool { !simple && !tipShown }
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
    var textSizeChosen: Bool { get set }
    var readAloudChosen: Bool { get set }
    /// nil = never written (an install from before Simple mode: Standard).
    var simpleMode: Bool? { get set }
    /// The one-time "Try Simple mode in Settings → App style" tip on Home was shown and closed.
    var appStyleTipShown: Bool { get set }
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
