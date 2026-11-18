import SwiftUI

// Settings as a grouped menu; each row opens its own page. Same rows, wording and order as
// android-app ui/SettingsScreens.kt.

extension PrivacyLevel {
    var oneLine: String {
        switch self {
        case .cloud: return "Checked by the ScamGuard server"
        case .hybrid: return "Checked on this phone, server helps when unsure"
        case .onDevice: return "Everything stays on this phone"
        }
    }
}

func connectionLabel(_ c: Bool?) -> String { c == true ? "Connected" : c == false ? "Can't reach" : "Not checked" }
func connectionColor(_ c: Bool?) -> Color { c == true ? Theme.safeGreen : c == false ? Theme.scamRed : Theme.muted }

private var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0" }

/// Grouped list styling shared by every settings page (Android's grouped cards).
private extension View {
    func settingsList() -> some View {
        self.listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
    }
}

private struct MenuRow: View {
    let icon: String
    let title: String
    let value: String
    var valueColor: Color = Theme.muted

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Theme.onBrand)
                .frame(width: 36, height: 36)
                .background(Theme.brand, in: RoundedRectangle(cornerRadius: 10))
            Text(title).font(.headline)
            Spacer()
            Text(value).font(.body).foregroundStyle(valueColor)
        }
        .padding(.vertical, 4)
    }
}

enum SettingsRoute: String, Hashable { case privacy, server, models, data, help }

struct SettingsView: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        let _ = vm.historyVersion
        let calls = vm.store.calls().count
        List {
            Section {
                NavigationLink(value: SettingsRoute.privacy)  { MenuRow(icon: "lock.fill", title: "Privacy level", value: vm.privacyLevel.badge) }
                    .tourTarget(.privacyRow)
                NavigationLink(value: SettingsRoute.server) {
                    MenuRow(icon: "server.rack", title: "Server", value: connectionLabel(vm.connection), valueColor: connectionColor(vm.connection))
                }
                NavigationLink(value: SettingsRoute.models) {
                    MenuRow(icon: "cpu", title: "On-device models", value: vm.modelStatus.contains("federated") ? "Updated" : "Built-in")
                }
            }
            .listRowBackground(Theme.card)
            Section {
                NavigationLink(value: SettingsRoute.data)  { MenuRow(icon: "person.fill", title: "My data & history", value: "\(calls) call\(calls == 1 ? "" : "s")") }
                NavigationLink(value: SettingsRoute.help)  { MenuRow(icon: "info", title: "Help & about", value: "v\(appVersion)") }
            }
            .listRowBackground(Theme.card)
        }
        .settingsList()
        .navigationTitle("Settings")
        .navigationDestination(for: SettingsRoute.self) { route in
            switch route {
            case .privacy: PrivacyPage()
            case .server: ServerPage()
            case .models: ModelsPage()
            case .data: DataPage()
            case .help: HelpPage()
            }
        }
        .task { await vm.refreshConnection() }
    }
}

/// "What does this mean?" + ⓘ at the top of a page, opening a sheet with the long explanation.
private struct InfoRow: View {
    let title: String
    let text: String
    @State private var show = false

    var body: some View {
        Button { show = true } label: {
            HStack {
                Text("What does this mean?").font(.body).foregroundStyle(Theme.brand)
                Spacer()
                Image(systemName: "info.circle.fill").font(.title3).foregroundStyle(Theme.brand)
            }
        }
        .accessibilityLabel("About \(title)")
        .sheet(isPresented: $show) { InfoSheet(title: title, text: text) }
    }
}

// ---------------------------------------------------------------- Privacy level

struct PrivacyPage: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                InfoRow(title: "Privacy level", text: PrivacyLevel.allCases.map { "\($0.badge): \($0.explanation)" }.joined(separator: "\n\n"))
                ForEach(PrivacyLevel.allCases) { level in
                    let selected = level == vm.privacyLevel
                    Button { vm.setPrivacyLevel(level) } label: {
                        HStack(spacing: 14) {
                            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                                .font(.title2).foregroundStyle(selected ? Theme.brand : Theme.muted)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(level.badge + (level == .defaultLevel ? " (default)" : "")).font(.title3.weight(.semibold))
                                Text(level.oneLine).font(.body).foregroundStyle(Theme.muted)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(selected ? Theme.brandContainer : Theme.card, in: RoundedRectangle(cornerRadius: 20))
                        .overlay(RoundedRectangle(cornerRadius: 20).stroke(selected ? Theme.brand : .clear, lineWidth: 2))
                    }
                    .buttonStyle(.plain)
                    .disabled(!level.available)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                }
                Text("Applies from the next call.").font(.body).foregroundStyle(Theme.muted)
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle("Privacy level")
    }
}

// ---------------------------------------------------------------- Server

struct ServerPage: View {
    @Environment(CallViewModel.self) private var vm
    @State private var url = ""
    @State private var result: String?
    @State private var testing = false
    @State private var loaded = false

    static let presets = [
        ("Simulator", "http://localhost:8080/"),
        ("Simulator edge", "http://localhost:8088/api/"),
        ("Mac on Wi-Fi", "http://192.168.1.23:8080/"),
        ("HTTPS", "https://192.168.1.23:8443/api/"),
    ]

    var body: some View {
        List {
            Section {
                InfoRow(title: "Server", text: "The address of the ScamGuard server. Simulator: localhost is the Mac running docker compose (:8080 = gateway, :8088/api/ = edge). iPhone: use the Mac's Wi-Fi IP instead of 192.168.1.23 (on the Mac: ipconfig getifaddr en0). HTTPS (Kubernetes, demo/expose): install demo/tls/ca.crt on the iPhone as a profile (Settings → General → VPN & Device Management), then trust it in General → About → Certificate Trust Settings. Without HTTPS, what callers say is sent as plain text.")
            }
            Section {
                StatusChip(text: testing ? "Checking…" : connectionLabel(vm.connection), color: testing ? Theme.muted : connectionColor(vm.connection))
                TextField("Server address", text: $url)
                    .font(.body)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                let encrypted = url.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("https://")
                Label(encrypted ? "Encrypted" : "Not encrypted", systemImage: encrypted ? "lock.fill" : "exclamationmark.triangle.fill")
                    .font(.body).foregroundStyle(encrypted ? Theme.safeGreen : Theme.scamRed)
            }
            .listRowBackground(Theme.card)
            Section("Presets") {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Self.presets, id: \.0) { p in Button(p.0) { url = p.1 }.buttonStyle(.bordered).font(.body) }
                    }
                }
            }
            .listRowBackground(Theme.card)
            Section {
                HStack(spacing: 12) {
                    Button { vm.saveGatewayUrl(url); url = vm.gatewayUrl; result = "Saved" } label: {
                        Text("Save").frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Theme.onBrand)
                    Button {
                        testing = true
                        let u = url
                        Task { result = await vm.testConnection(u); testing = false }
                    } label: { Text("Test").frame(maxWidth: .infinity, minHeight: 36) }
                    .buttonStyle(.bordered)
                }
                .font(.body)
                if let result { Text(result).font(.body) }
            }
            .listRowBackground(Color.clear)
        }
        .settingsList()
        .navigationTitle("Server")
        .onAppear { if !loaded { url = vm.gatewayUrl; loaded = true } }
    }
}

struct StatusChip: View {
    let text: String
    let color: Color

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(color).frame(width: 10, height: 10)
            Text(text).font(.headline)
        }
        .padding(.horizontal, 14).padding(.vertical, 8)
        .background(color.opacity(0.15), in: Capsule())
    }
}

// ---------------------------------------------------------------- On-device models

struct ModelsPage: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        List {
            Section {
                InfoRow(title: "On-device models", text: "Used at privacy levels 2 and 3. Newer versions come from federated learning: phones train on their own calls and share only model changes, never the calls. The app checks for new versions when it starts (not at level 3, which makes no network traffic).")
            }
            Section {
                ForEach(vm.modelStatus.split(separator: "\n").map(String.init), id: \.self) { line in
                    let name = line.split(separator: ":").first.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? line
                    KeyValueRow(key: name == "Money" ? "Money / OTP" : name,
                                value: line.contains("federated") ? "Updated " + (line.components(separatedBy: "federated ").last?.components(separatedBy: " (").first ?? "") : "Built-in")
                }
                KeyValueRow(key: "MobileBERT", value: vm.mobileBertStatus.hasPrefix("MobileBERT installed") ? "Installed" : "Not installed")
            }
            .listRowBackground(Theme.card)
            Section {
                Button { Task { _ = await vm.checkModelUpdates() } } label: { Text("Check for updates").frame(maxWidth: .infinity, minHeight: 36) }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Theme.onBrand)
                if !vm.modelCheck.isEmpty {
                    Text(friendlyModelCheck(vm.modelCheck)).font(.body).foregroundStyle(Theme.brand)
                        .accessibilityHint(vm.modelCheck)
                }
                Button {} label: { Text("Train on my calls").frame(maxWidth: .infinity, minHeight: 36) }
                    .buttonStyle(.bordered).disabled(true)
                Text("Coming soon").font(.body).foregroundStyle(Theme.muted)
            }
            .font(.body)
            .listRowBackground(Color.clear)
        }
        .settingsList()
        .navigationTitle("On-device models")
    }
}

/// The update check's result in plain words (same wording on Android).
func friendlyModelCheck(_ msg: String) -> String {
    if msg.hasPrefix("Checking") { return "Checking…" }
    if msg.hasPrefix("No model service") || msg.hasPrefix("Bad server address") { return "Can't reach the update service" }
    if msg.hasPrefix("The registry has no phone models") { return "No updates yet" }
    if msg.hasPrefix("Kept the current models") { return "Update failed, kept the current models" }
    if msg.hasPrefix("Updated") { return "Models updated" }
    return msg
}

struct KeyValueRow: View {
    let key: String
    let value: String

    var body: some View {
        HStack {
            Text(key)
            Spacer()
            Text(value).foregroundStyle(Theme.muted)
        }
        .font(.body)
        .accessibilityElement(children: .combine)
    }
}

// ---------------------------------------------------------------- My data & history

struct DataPage: View {
    @Environment(CallViewModel.self) private var vm
    @State private var confirm = false

    var body: some View {
        let _ = vm.historyVersion
        let calls = vm.store.calls().count
        let labels = vm.store.labelCount()
        List {
            Section {
                InfoRow(title: "My data & history", text: "Save the riskiest sentence: keeps the one caller line that scored highest in calls that ended with a warning. Off: no words from your calls are kept, only verdicts and scores.\n\nHelp improve detection: the app keeps simple labels on this phone (for example, you hung up right after a warning, or tapped \"This was fine\"). On: the caller's lines of those calls are kept too, with numbers and names removed. Nothing is uploaded.\n\nYour history stays on this phone.")
            }
            Section {
                Toggle(isOn: Binding(get: { vm.saveSentence }, set: { vm.setSaveSentence($0) })) {
                    ToggleLabel(title: "Save the riskiest sentence", line: "Keep one line from warned calls")
                }
                Toggle(isOn: Binding(get: { vm.helpImprove }, set: { vm.setHelpImprove($0) })) {
                    ToggleLabel(title: "Help improve detection", line: "Keep labels on this phone")
                }
            }
            .listRowBackground(Theme.card)
            Section {
                KeyValueRow(key: "Calls saved", value: "\(calls)")
                KeyValueRow(key: "Labels kept", value: "\(labels)")
            } footer: {
                Text("Nothing here is uploaded.").font(.body).foregroundStyle(Theme.muted)
            }
            .listRowBackground(Theme.card)
            Section {
                Button(role: .destructive) { confirm = true } label: {
                    Text("Delete my history").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.scamRed)
                .font(.body)
                .disabled(calls == 0 && labels == 0)
            }
            .listRowBackground(Color.clear)
        }
        .settingsList()
        .navigationTitle("My data & history")
        .alert("Delete my history?", isPresented: $confirm) {
            Button("Delete", role: .destructive) { vm.deleteHistory() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Deletes every saved call, chart, saved sentence and label on this phone. This can't be undone.")
        }
    }
}

private struct ToggleLabel: View {
    let title: String
    let line: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.headline)
            Text(line).font(.body).foregroundStyle(Theme.muted)
        }
    }
}

// ---------------------------------------------------------------- Help & about

struct HelpPage: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
    @State private var loaded = false

    var body: some View {
        List {
            Section {
                // The tour moves to Home itself.
                Button { vm.startTour() } label: { Text("Replay tour").frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.borderedProminent).foregroundStyle(Theme.onBrand).font(.body)
                Button { vm.runSetupAgain() } label: { Text("Run setup again").frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.bordered).font(.body)
            } header: { SectionTitle(text: "Get started again") }
            .listRowBackground(Color.clear)
            Section {
                ForEach(["It listens to what the caller says.", "Each sentence is checked for scam signs.",
                         "The bar turns yellow or red when it's risky.", "A red alert tells you to hang up."], id: \.self) { Bullet(text: $0) }
            } header: { SectionTitle(text: "How ScamGuard works") }
            .listRowBackground(Theme.card)
            Section {
                ColourRow(color: Theme.safeGreen, name: "Safe", meaning: "No warning signs")
                ColourRow(color: Theme.warnYellow, name: "Warning", meaning: "Be careful")
                ColourRow(color: Theme.scamRed, name: "Scam", meaning: "Hang up now")
                ColourRow(color: Theme.neutralGrey, name: "Grey", meaning: "Couldn't check")
            } header: { SectionTitle(text: "What the colours mean") }
            .listRowBackground(Theme.card)
            Section {
                ForEach(PrivacyLevel.allCases) { l in
                    Bullet(text: "\(l.badge): \(l.oneLine.prefix(1).lowercased() + l.oneLine.dropFirst())")
                }
            } header: { SectionTitle(text: "Privacy levels") }
            .listRowBackground(Theme.card)
            Section {
                Button { openURL(scamShieldURL) } label: { Text("Open ScamShield").frame(maxWidth: .infinity, minHeight: 36) }
                    .buttonStyle(.borderedProminent).tint(Theme.scamRed).font(.body)
            } header: { SectionTitle(text: "Report a scam") }
            .listRowBackground(Color.clear)
            Section {
                KeyValueRow(key: "Version", value: appVersion)
                ForEach(vm.modelStatus.split(separator: "\n").map(String.init), id: \.self) { line in
                    KeyValueRow(key: (line.split(separator: ":").first.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "") + " model",
                                value: line.contains("federated") ? (line.components(separatedBy: "federated ").last?.components(separatedBy: " (").first ?? "") : "built-in")
                }
                KeyValueRow(key: "MobileBERT", value: vm.mobileBertStatus.firstMatch(of: /\((mobilebert[^)]*)\)/).map { String($0.1) } ?? "not installed")
            } header: { SectionTitle(text: "About") }
            .listRowBackground(Theme.card)
            #if DEBUG
            Section {
                Button { vm.loadSampleHistory(); loaded = true } label: { Text("Load sample history").frame(maxWidth: .infinity, minHeight: 36) }
                    .buttonStyle(.bordered).font(.body)
                if loaded { Text("Added about 60 sample calls.").font(.body) }
                Button { vm.resetOnboarding() } label: { Text("Reset first-launch setup").frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.bordered).font(.body)
            } header: { SectionTitle(text: "Debug build") }
            .listRowBackground(Color.clear)
            #endif
        }
        .settingsList()
        .navigationTitle("Help & about")
    }
}

private struct SectionTitle: View {
    let text: String
    var body: some View { Text(text).font(.title3.weight(.semibold)).foregroundStyle(Color.primary).textCase(nil) }
}

private struct Bullet: View {
    let text: String
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("•").foregroundStyle(Theme.brand)
            Text(text)
        }
        .font(.body)
    }
}

private struct ColourRow: View {
    let color: Color
    let name: String
    let meaning: String

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(color).frame(width: 18, height: 18)
            Text(name).font(.headline)
            Spacer()
            Text(meaning).font(.body).foregroundStyle(Theme.muted)
        }
        .accessibilityElement(children: .combine)
    }
}
