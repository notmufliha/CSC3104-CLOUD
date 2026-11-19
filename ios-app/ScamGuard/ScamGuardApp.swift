import SwiftUI

@main
struct ScamGuardApp: App {
    @State private var vm: CallViewModel
    @State private var tourTargets = TourTargets()
    @State private var pinUnlock = PinUnlock()

    init() {
        // Unit tests run inside this app: then it stays quiet (no start-up network traffic) and keeps its data in memory.
        let testing = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        let store: CallStore
        do {
            store = try CallStore(inMemory: testing)
        } catch {
            // A store that can't be opened must not stop the app: keep this session's calls in memory.
            store = try! CallStore(inMemory: true)   // swiftlint:disable:this force_try
        }
        #if DEBUG
        // -demoDev YES|NO: unlock / lock the Developer view before the view model reads the flag (screenshots).
        if !testing, UserDefaults.standard.object(forKey: "demoDev") != nil {
            AppPrefs().developerUnlocked = UserDefaults.standard.bool(forKey: "demoDev")
        }
        #endif
        _vm = State(initialValue: CallViewModel(store: store, startUp: !testing))
    }

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .environment(vm)
                .environment(tourTargets)
                .environment(pinUnlock)
                .tint(Theme.brand)
                .seniorTextSize(vm.textSize)
        }
    }
}

/// First launch: the setup; after it (or for an install that had settings or history), the app.
struct AppRoot: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        Group {
            switch vm.gate {
            case .setup: SetupView().transition(.opacity)
            case .app: RootView().transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: vm.gate)
        #if DEBUG
        .task { demoOnboarding() }
        #endif
    }

    #if DEBUG
    /// Debug builds only, for screenshots and UI tests:
    ///   -demoReset YES (first-launch setup again), -demoSetup who|welcome|privacy|server|data|helper|handover|done
    ///   [-demoPath standard|simple|custom|helper] (none = nothing picked) [-demoPrivacy hybrid]
    ///   -demoTour 0-6 (a tour step over the app; skips a pending setup)
    ///   -demoDev YES|NO (unlock / lock the Developer view), -demoSpeed 1x|2x|4x|instant, -demoOverlay YES,
    ///   -demoLevel cloud|hybrid|on_device
    ///   -demoTip YES (show the one-time App style tip again)
    private func demoOnboarding() {
        let d = UserDefaults.standard
        // -demoPin 2468|off (Keychain)  -demoFamily "Mei Ling|91234567"  -demoConnection ok|fail  -demoWarning red|yellow
        if let pin = d.string(forKey: "demoPin") { if pin == "off" { vm.clearPin() } else { vm.setPin(pin) } }
        if let f = d.string(forKey: "demoFamily") {
            let parts = f.split(separator: "|", maxSplits: 1).map(String.init)
            vm.setFamily(name: parts.first ?? "", phone: parts.count > 1 ? parts[1] : "")
        }
        switch d.string(forKey: "demoConnection") {
        case "ok": vm.demoConnection(true)
        case "fail": vm.demoConnection(false)
        default: break
        }
        switch d.string(forKey: "demoWarning") {
        case "red": vm.demoWarning(red: true)
        case "yellow": vm.demoWarning(red: false)
        default: break
        }
        if d.bool(forKey: "demoReset") { vm.resetOnboarding() }
        if let id = d.string(forKey: "demoLevel") { vm.setPrivacyLevel(PrivacyLevel.fromId(id)) }
        if let id = d.string(forKey: "demoSpeed"), let sp = PlaybackSpeed(rawValue: id) { vm.setPlaybackSpeed(sp) }
        if d.bool(forKey: "demoOverlay") { vm.setDevOverlay(true) }
        // -demoSummary red|yellow: a finished pretend call's summary (screenshots, accessibility audits)
        switch d.string(forKey: "demoSummary") {
        case "red": vm.demoSummary(red: true)
        case "yellow": vm.demoSummary(red: false)
        default: break
        }
        // -demoResetApp YES: "Reset app to first launch" (needs the Developer view unlocked), for screenshots
        if d.bool(forKey: "demoResetApp") { vm.resetApp() }
        if d.bool(forKey: "demoTip") { vm.demoAppStyleTip() }
        if let name = d.string(forKey: "demoSetup"),
           let step = Onboarding.Step.allCases.first(where: { "\($0)" == name }) {
            vm.demoSetup(step, privacy: d.string(forKey: "demoPrivacy").map(PrivacyLevel.fromId), path: d.string(forKey: "demoPath"))
        }
        if d.object(forKey: "demoTour") != nil {
            if vm.gate == .setup { vm.finishSetup(startTour: false) }   // a clean install: setup skipped first
            vm.startTour(at: d.integer(forKey: "demoTour"))
        }
    }
    #endif
}

/// The three tabs (Home, Dashboard, Settings); calls take the whole screen, over the tabs, as on Android.
struct RootView: View {
    enum Tab: Hashable { case home, dashboard, settings }

    @Environment(CallViewModel.self) private var vm
    @Environment(PinUnlock.self) private var unlock
    @State private var tab: Tab = .home
    @State private var homePath: [HomeRoute] = []
    @State private var dashboardPath: [DashboardRoute] = []
    @State private var settingsPath: [SettingsRoute] = []
    @State private var keyboard = KeyboardState()

    var body: some View {
        let tourStep = vm.tourStep
        let tourSteps = Tour.steps(simple: vm.tourSimple)
        let place = tourStep.flatMap { $0 < tourSteps.count ? tourSteps[$0].place : nil }
        ZStack {
            tabs
                // While the tour runs, VoiceOver reads only the coach mark.
                .accessibilityHidden(tourStep != nil)
            if place == .call, let i = tourStep {
                // The tour's pretend call: sample data, nothing sent, nothing to tap.
                let target = tourSteps[i].target
                let demoCall = CallViewModel.tourDemoCall(redAlert: target == .redWarning || target == .warningButtons)
                ZStack {
                    InCallContent(state: demoCall, onHangUp: {}, onDiscard: {}, demo: true, simple: vm.tourSimple)
                        .background(Color(.systemBackground).ignoresSafeArea())
                    if demoCall.warningOpen {
                        WarningScreen(kind: demoCall.warningKind, callEnded: false, familyName: vm.family?.name,
                                      actions: WarningActions(), demo: true)
                    }
                }
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .transition(.opacity)
            }
            if let i = tourStep {
                TourOverlay(steps: tourSteps, index: i, onNext: { vm.tourNext() }, onSkip: { vm.endTour() })
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: tourStep)
        .onAppear { if let p = place { show(p) } }
        .onChange(of: place) { _, p in show(p ?? .home) }
    }

    /// The tour moves between tabs itself; each tab opens at its first screen.
    private func show(_ place: Tour.Place) {
        switch place {
        case .home, .call: homePath = []; tab = .home
        case .dashboard: dashboardPath = []; tab = .dashboard
        case .settings: settingsPath = []; tab = .settings
        }
    }

    private var tabs: some View {
        VStack(spacing: 0) {
            tabPages
            // The app's own tab bar (UI/TabBar.swift): readable at large text sizes. Hidden while typing.
            if !keyboard.visible {
                SeniorTabBar(items: [
                    .init(tab: Tab.home, title: L.s(.tab_home), systemImage: "house", id: "tab.home"),
                    .init(tab: Tab.dashboard, title: L.s(vm.simpleMode ? .tab_family : .tab_dashboard),
                          systemImage: "list.bullet.rectangle", id: "tab.dashboard"),
                    .init(tab: Tab.settings, title: L.s(.tab_settings), systemImage: "gearshape", id: "tab.settings"),
                ], selection: $tab)
            }
        }
    }

    private var tabPages: some View {
        TabView(selection: $tab) {
            NavigationStack(path: $homePath) {
                Group { if vm.simpleMode { SimpleHomeView() } else { HomeView(path: $homePath) } }
                    .navigationDestination(for: HomeRoute.self) { route in
                        switch route {
                        case .pick: ScriptPickerView()
                        case .learn: LearnView()
                        case .learnType(let id): ScamTypeView(id: id)
                        case .quiz: QuizView()
                        }
                    }
            }
            .toolbar(.hidden, for: .tabBar)
            .tag(Tab.home)

            NavigationStack(path: $dashboardPath) {
                // Simple mode with a PIN: "For family" asks for it first.
                if vm.simpleMode && vm.pinSet && !unlock.open { PinGateView() }
                else { DashboardView(goHome: { dashboardPath = []; tab = .home }) }
            }
                .toolbar(.hidden, for: .tabBar)
                .tag(Tab.dashboard)

            NavigationStack(path: $settingsPath) { SettingsView() }
                .toolbar(.hidden, for: .tabBar)
                .tag(Tab.settings)
        }
        .fullScreenCover(isPresented: Binding(
            get: { vm.state.phase != .idle },
            set: { shown in if !shown && vm.state.phase != .idle { vm.reset() } }
        )) {
            CallFlowView()
                .environment(vm)
                .tint(Theme.brand)
                .seniorTextSize(vm.textSize)
        }
        .onChange(of: tab) { _, t in if t == .home { unlock.open = false } }
        .onChange(of: vm.state.phase) { _, phase in
            // Done / Decline / Discard: back to Home, as Android does.
            if phase == .idle { homePath = []; tab = .home }
        }
        #if DEBUG
        // -demoAfterCall developer: once a demo call's summary is ready, close it and open the Developer view in the
        // same run (its log lives in memory only), for screenshots.
        .onChange(of: vm.state.phase == .ended && !vm.state.reviewing) { _, done in
            guard done, UserDefaults.standard.string(forKey: "demoAfterCall") == "developer" else { return }
            Task {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                vm.reset()
                try? await Task.sleep(nanoseconds: 600_000_000)
                tab = .settings
                settingsPath = [.developer]
            }
        }
        #endif
        #if DEBUG
        .task { demo() }
        #endif
    }

    #if DEBUG
    /// Debug builds only, for screenshots from the command line:
    ///   xcrun simctl launch booted com.rhaylene.ScamGuard -demoTab dashboard
    ///   xcrun simctl launch booted com.rhaylene.ScamGuard -demoScript scam_en_11_bank_otp.json [-demoRingOnly YES]
    ///   -demoDeleteHistory YES / -demoLoadSample YES, -demoSettings privacy|data|help|advanced|developer,
    ///   -demoDetail YES (opens the newest call), -demoDashboard insights|history, -dashboardPeriod week|month|all
    private func demo() {
        let d = UserDefaults.standard
        if d.bool(forKey: "demoDeleteHistory") { vm.deleteHistory() }
        if d.bool(forKey: "demoLoadSample") { vm.loadSampleHistory() }
        if let page = d.string(forKey: "demoSettings").flatMap(SettingsRoute.init(rawValue:)) {
            tab = .settings
            settingsPath = [page]
        }
        if d.bool(forKey: "demoDetail"), let first = vm.store.calls().first {
            tab = .dashboard
            dashboardPath = [.call(first.id)]
        }
        switch d.string(forKey: "demoDashboard") {   // -demoDashboard insights|history|weekly
        case "insights": tab = .dashboard; dashboardPath = [.insights]
        case "history": tab = .dashboard; dashboardPath = [.history]
        case "weekly": tab = .dashboard; dashboardPath = [.weekly]
        default: break
        }
        switch d.string(forKey: "demoTab") {
        case "dashboard": tab = .dashboard
        case "settings": tab = .settings
        default: break
        }
        // -demoLearn list|quiz|<scam type id> (with -demoQuiz answered|result for the quiz)
        switch d.string(forKey: "demoLearn") {
        case nil: break
        case "list": tab = .home; homePath = [.learn]
        case "quiz": tab = .home; homePath = [.learn, .quiz]
        case let id?: tab = .home; homePath = [.learn, .learnType(id)]
        }
        if let name = d.string(forKey: "demoScript"), let s = vm.scripts.first(where: { $0.fileName == name }) {
            vm.ring(s)
            if !d.bool(forKey: "demoRingOnly") { vm.answer() }
        }
    }
    #endif
}

enum HomeRoute: Hashable { case pick, learn, learnType(String), quiz }

/// The full-screen call: ringing -> in call -> summary, with the red warning over it until the user acts.
struct CallFlowView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
    @State private var composing: MessageDraft?
    @State private var noPhoneApp = false

    var body: some View {
        ZStack {
            Group {
                switch vm.state.phase {
                case .ringing:
                    IncomingCallView(callerName: L.s(.call_unknown_caller), callerNumber: L.s(.call_private_number),
                                     onAnswer: { vm.answer() }, onDecline: { vm.decline() })
                case .inCall:
                    InCallView()
                case .ended:
                    SummaryView()
                case .idle:
                    Color.clear
                }
            }
            // Covered by the red warning: hidden from VoiceOver and the accessibility audit (only the warning is reachable).
            .accessibilityHidden(vm.state.warningOpen)
            // Developer view: live scores at the top; never over the red warning (it hides while that shows).
            if DevOverlay.visible(enabled: vm.devOverlay, unlocked: vm.devUnlocked, inCall: vm.state.phase == .inCall,
                                  warningOpen: vm.state.warningOpen) {
                DevScoreOverlay(line: vm.devLatest)
                    .padding(.top, 4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
            // The red warning covers the call (and its summary, if the call ended first) until the user acts.
            if vm.state.warningOpen && (vm.state.phase == .inCall || vm.state.phase == .ended) {
                WarningScreen(kind: vm.state.warningKind, callEnded: vm.state.phase == .ended,
                              familyName: vm.family?.name, actions: actions)
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.25), value: vm.state.warningOpen)
        .sheet(item: $composing) { d in MessageComposer(draft: d) { composing = nil }.ignoresSafeArea() }
        .alert(L.s(.no_phone_app), isPresented: $noPhoneApp) { Button(L.s(.btn_ok)) {} }
    }

    private var actions: WarningActions {
        WarningActions(
            onHangUp: { vm.hangUp() },
            onCallFamily: {
                vm.leaveCallForHelp()
                if let f = vm.family { open(PhoneLinks.telURL(f.phone)) }
            },
            onCall1799: {
                vm.leaveCallForHelp()
                open(PhoneLinks.telURL(PhoneLinks.helpline))
            },
            onTellFamily: {
                guard let f = vm.family else { return }
                let draft = MessageDraft(phone: f.phone, body: vm.familyMessage())
                if MessageComposer.canSend { composing = draft } else { open(PhoneLinks.smsURL(f.phone, body: draft.body)) }
            },
            onClose: { vm.closeWarning() })
    }

    private func open(_ url: URL?) {
        guard let url else { noPhoneApp = true; return }
        openURL(url) { accepted in if !accepted { noPhoneApp = true } }
    }
}

extension View {
    /// "Text size", the same sizes as Android (ui/theme/Type.kt). Normal (Standard mode's default): the phone's own
    /// Dynamic Type size. Large: never smaller than xxLarge (body 21 pt, title 31 pt); Extra large: xxxLarge (body
    /// 23 pt). The phone's larger accessibility sizes still apply on top; screens scroll instead of shrinking.
    func seniorTextSize(_ size: String) -> some View {
        switch size {
        case AppPrefs.textXLarge: return dynamicTypeSize(DynamicTypeSize.xxxLarge ... .accessibility5)
        case AppPrefs.textLarge: return dynamicTypeSize(DynamicTypeSize.xxLarge ... .accessibility5)
        default: return dynamicTypeSize(DynamicTypeSize.xSmall ... .accessibility5)
        }
    }
}
