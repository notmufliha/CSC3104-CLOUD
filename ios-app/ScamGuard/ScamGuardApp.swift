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
    ///   -demoReset YES (first-launch setup again), -demoSetup welcome|privacy|server|data|done [-demoPrivacy hybrid]
    ///   [-demoServer connected|cantReach], -demoTour 0-6 (a tour step over the app; skips a pending setup)
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
        if let name = d.string(forKey: "demoSetup"),
           let step = Onboarding.Step.allCases.first(where: { "\($0)" == name }) {
            vm.demoSetup(step, privacy: d.string(forKey: "demoPrivacy").map(PrivacyLevel.fromId),
                         server: d.string(forKey: "demoServer"), path: d.string(forKey: "demoPath"))
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
                        case .paste: PasteTextView()
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
        .task { demo() }
        #endif
    }

    #if DEBUG
    /// Debug builds only, for screenshots from the command line:
    ///   xcrun simctl launch booted com.rhaylene.ScamGuard -demoTab dashboard
    ///   xcrun simctl launch booted com.rhaylene.ScamGuard -demoScript scam_en_11_bank_otp.json [-demoRingOnly YES]
    ///   -demoDeleteHistory YES / -demoLoadSample YES, -demoSettings privacy|server|models|data|help,
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
        switch d.string(forKey: "demoDashboard") {   // -demoDashboard insights|history
        case "insights": tab = .dashboard; dashboardPath = [.insights]
        case "history": tab = .dashboard; dashboardPath = [.history]
        default: break
        }
        switch d.string(forKey: "demoTab") {
        case "dashboard": tab = .dashboard
        case "settings": tab = .settings
        default: break
        }
        if let name = d.string(forKey: "demoScript"), let s = vm.scripts.first(where: { $0.fileName == name }) {
            vm.ring(s)
            if !d.bool(forKey: "demoRingOnly") { vm.answer() }
        }
    }
    #endif
}

enum HomeRoute: Hashable { case pick, paste }

/// The full-screen call: ringing -> in call -> summary, with the red warning over it until the user acts.
struct CallFlowView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
    @State private var composing: MessageDraft?
    @State private var noPhoneApp = false

    var body: some View {
        ZStack {
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
    /// Big type for older users, the same sizes as Android (ui/theme/Type.kt): never smaller than Dynamic Type's
    /// xxLarge (body 21 pt, title 31 pt), or xxxLarge (body 23 pt) for "Text size: Extra large". The phone's larger
    /// accessibility sizes still apply on top; screens scroll instead of shrinking.
    func seniorTextSize(_ size: String) -> some View {
        dynamicTypeSize(size == AppPrefs.textXLarge ? DynamicTypeSize.xxxLarge ... .accessibility5
                                                    : DynamicTypeSize.xxLarge ... .accessibility5)
    }
}
