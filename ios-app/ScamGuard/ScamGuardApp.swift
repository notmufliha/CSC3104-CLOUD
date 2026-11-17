import SwiftUI

@main
struct ScamGuardApp: App {
    @State private var vm: CallViewModel

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
            RootView()
                .environment(vm)
                .tint(Theme.brand)
        }
    }
}

/// The three tabs (Home, Dashboard, Settings); calls take the whole screen, over the tabs, as on Android.
struct RootView: View {
    enum Tab: Hashable { case home, dashboard, settings }

    @Environment(CallViewModel.self) private var vm
    @State private var tab: Tab = .home
    @State private var homePath: [HomeRoute] = []
    @State private var dashboardPath: [DashboardRoute] = []
    @State private var settingsPath: [SettingsRoute] = []

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack(path: $homePath) {
                HomeView(path: $homePath)
                    .navigationDestination(for: HomeRoute.self) { route in
                        switch route {
                        case .pick: ScriptPickerView()
                        case .paste: PasteTextView()
                        }
                    }
            }
            .tabItem { Label("Home", systemImage: "house") }
            .tag(Tab.home)

            NavigationStack(path: $dashboardPath) { DashboardView(goHome: { dashboardPath = []; tab = .home }) }
                .tabItem { Label("Dashboard", systemImage: "list.bullet") }
                .tag(Tab.dashboard)

            NavigationStack(path: $settingsPath) { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .fullScreenCover(isPresented: Binding(
            get: { vm.state.phase != .idle },
            set: { shown in if !shown && vm.state.phase != .idle { vm.reset() } }
        )) {
            CallFlowView()
                .environment(vm)
                .tint(Theme.brand)
        }
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

/// The full-screen call: ringing -> in call -> summary.
struct CallFlowView: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        switch vm.state.phase {
        case .ringing:
            IncomingCallView(callerName: "Unknown caller", callerNumber: "Private number",
                             onAnswer: { vm.answer() }, onDecline: { vm.decline() })
        case .inCall:
            InCallView()
        case .ended:
            SummaryView()
        case .idle:
            Color.clear
        }
    }
}
