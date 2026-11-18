import SwiftUI

// Settings as a grouped menu; each row opens its own page. Same rows, wording and order as
// android-app ui/SettingsScreens.kt.

extension PrivacyLevel {
    var oneLine: String {
        switch self {
        case .cloud: return L.s(.privacy_plain_cloud)
        case .hybrid: return L.s(.privacy_plain_hybrid)
        case .onDevice: return L.s(.privacy_plain_on_device)
        }
    }
}

func connectionLabel(_ c: Bool?) -> String { L.s(c == true ? .status_connected : c == false ? .status_cant_reach : .status_not_checked) }
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

enum SettingsRoute: String, Hashable { case privacy, server, models, data, help, textSize, family, advanced, pin }

/// Settings. Simple mode: the everyday rows, Help, and "Advanced settings" (PIN-protected if set). The full app:
/// the everyday rows, everything Advanced settings holds, and Help.
struct SettingsView: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        List {
            EverydaySettings()
            if vm.simpleMode {
                Section {
                    NavigationLink(value: SettingsRoute.help) { SettingsRowLabel(icon: "questionmark.circle.fill", title: L.s(.set_help)) }
                        .accessibilityIdentifier("rowHelp")
                    NavigationLink(value: SettingsRoute.advanced) {
                        SettingsRowLabel(icon: vm.pinSet ? "lock.fill" : "gearshape.fill", title: L.s(.set_advanced), value: L.s(.set_advanced_line))
                    }
                    .accessibilityIdentifier("rowAdvanced")
                }
                .listRowBackground(Theme.card)
            } else {
                AdvancedSections(simple: false)
                Section {
                    NavigationLink(value: SettingsRoute.help) { SettingsRowLabel(icon: "questionmark.circle.fill", title: L.s(.set_help)) }
                        .accessibilityIdentifier("rowHelp")
                }
                .listRowBackground(Theme.card)
            }
        }
        .settingsList()
        .navigationTitle(L.s(.tab_settings))
        .navigationDestination(for: SettingsRoute.self) { route in
            switch route {
            case .privacy: PrivacyPage()
            case .server: ServerPage()
            case .models: ModelsPage()
            case .data: DataPage()
            case .help: HelpPage()
            case .textSize: TextSizePage()
            case .family: FamilyPage()
            case .advanced: AdvancedPage()
            case .pin: PinSettingsPage()
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
                Text(L.s(.what_does_this_mean)).font(.body).foregroundStyle(Theme.brand)
                Spacer()
                Image(systemName: "info.circle.fill").font(.title3).foregroundStyle(Theme.brand)
            }
        }
        .accessibilityLabel(L.s(.a11y_about, title))
        .sheet(isPresented: $show) { InfoSheet(title: title, text: text) }
    }
}

// ---------------------------------------------------------------- Privacy level

struct PrivacyPage: View {
    @Environment(CallViewModel.self) private var vm
    @State private var info = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(PrivacyLevel.allCases) { level in
                    ChoiceCard(selected: level == vm.privacyLevel, title: level.choiceTitle(simple: vm.simpleMode),
                               line: L.s(level.plainKey), id: "privacy_\(level.rawValue)") {
                        if level.available { vm.setPrivacyLevel(level) }
                    }
                }
                Text(L.s(.privacy_applies_next)).font(.body).foregroundStyle(Theme.muted)
                Text(L.s(.privacy_english_only)).font(.body).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
                if !vm.simpleMode {
                    BigButton(title: L.s(.setup_more_about), id: "privacyInfo", prominent: false) { info = true }
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle(L.s(.set_how_checked))
        .sheet(isPresented: $info) {
            InfoSheet(title: L.s(.set_how_checked), text: PrivacyLevel.allCases.map { "\($0.badge): \($0.explanation)" }.joined(separator: "\n\n"))
        }
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
                InfoRow(title: L.s(.srv_title), text: L.s(.srv_info_ios))
            }
            Section {
                StatusChip(text: testing ? L.s(.status_checking) : connectionLabel(vm.connection), color: testing ? Theme.muted : connectionColor(vm.connection))
                TextField(L.s(.field_server), text: $url)
                    .font(.body)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                let encrypted = url.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("https://")
                Label(L.s(encrypted ? .srv_encrypted : .srv_not_encrypted), systemImage: encrypted ? "lock.fill" : "exclamationmark.triangle.fill")
                    .font(.body).foregroundStyle(encrypted ? Theme.safeGreen : Theme.scamRed)
            }
            .listRowBackground(Theme.card)
            Section(L.s(.srv_presets)) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Self.presets, id: \.0) { p in Button(p.0) { url = p.1 }.buttonStyle(.bordered).font(.body) }
                    }
                }
            }
            .listRowBackground(Theme.card)
            Section {
                HStack(spacing: 12) {
                    Button { vm.saveGatewayUrl(url); url = vm.gatewayUrl; result = L.s(.saved) } label: {
                        Text(L.s(.btn_save)).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56)
                    }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Theme.onBrand)
                    Button {
                        testing = true
                        let u = url
                        Task { result = await vm.testConnection(u); testing = false }
                    } label: { Text(L.s(.btn_test)).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56) }
                    .buttonStyle(.bordered)
                }
                .font(.body)
                if let result { Text(result).font(.body) }
            }
            .listRowBackground(Color.clear)
        }
        .settingsList()
        .navigationTitle(L.s(.srv_title))
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
                InfoRow(title: L.s(.mdl_title), text: L.s(.mdl_info))
            }
            Section {
                ForEach(vm.modelStatus.split(separator: "\n").map(String.init), id: \.self) { line in
                    let name = line.split(separator: ":").first.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? line
                    KeyValueRow(key: name == "Money" ? "Money / OTP" : name,
                                value: line.contains("federated") ? L.s(.mdl_updated_v, line.components(separatedBy: "federated ").last?.components(separatedBy: " (").first ?? "") : L.s(.models_built_in))
                }
                KeyValueRow(key: "MobileBERT", value: L.s(vm.mobileBertStatus.hasPrefix("MobileBERT installed") ? .mdl_installed : .mdl_not_installed))
            }
            .listRowBackground(Theme.card)
            Section {
                Button { Task { _ = await vm.checkModelUpdates() } } label: { Text(L.s(.mdl_check)).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56) }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Theme.onBrand)
                if !vm.modelCheck.isEmpty {
                    Text(friendlyModelCheck(vm.modelCheck)).font(.body).foregroundStyle(Theme.brand)
                        .accessibilityHint(vm.modelCheck)
                }
                Button {} label: { Text(L.s(.mdl_train)).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56) }
                    .buttonStyle(.bordered).disabled(true)
                Text(L.s(.mdl_soon)).font(.body).foregroundStyle(Theme.muted)
            }
            .font(.body)
            .listRowBackground(Color.clear)
        }
        .settingsList()
        .navigationTitle(L.s(.mdl_title))
    }
}

/// The update check's result in plain words (same wording on Android).
func friendlyModelCheck(_ msg: String) -> String {
    if msg.hasPrefix("Checking") { return L.s(.mdl_res_checking) }
    if msg.hasPrefix("No model service") || msg.hasPrefix("Bad server address") { return L.s(.mdl_res_unreachable) }
    if msg.hasPrefix("The registry has no phone models") { return L.s(.mdl_res_none) }
    if msg.hasPrefix("Kept the current models") { return L.s(.mdl_res_failed) }
    if msg.hasPrefix("Updated") { return L.s(.mdl_res_updated) }
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
                InfoRow(title: L.s(.set_my_data), text: L.s(.data_info))
            }
            Section {
                Toggle(isOn: Binding(get: { vm.saveSentence }, set: { vm.setSaveSentence($0) })) {
                    ToggleLabel(title: L.s(.data_save_sentence), line: L.s(.data_save_sentence_line))
                }
                Toggle(isOn: Binding(get: { vm.helpImprove }, set: { vm.setHelpImprove($0) })) {
                    ToggleLabel(title: L.s(.data_help_improve), line: L.s(.data_help_improve_line))
                }
            }
            .listRowBackground(Theme.card)
            Section {
                KeyValueRow(key: L.s(.data_calls_saved), value: "\(calls)")
                KeyValueRow(key: L.s(.data_labels_kept), value: "\(labels)")
            } footer: {
                Text(L.s(.data_nothing_uploaded)).font(.body).foregroundStyle(Theme.muted)
            }
            .listRowBackground(Theme.card)
            Section {
                Button(role: .destructive) { confirm = true } label: {
                    Text(L.s(.btn_delete_history)).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.scamRed)
                .font(.body)
                .disabled(calls == 0 && labels == 0)
            }
            .listRowBackground(Color.clear)
        }
        .settingsList()
        .navigationTitle(L.s(.set_my_data))
        .sheet(isPresented: $confirm) {
            YesNoSheet(title: L.s(.delete_title), text: L.s(.delete_text), yes: L.s(.btn_yes_delete), no: L.s(.btn_no_keep),
                       yesIsDanger: true, onYes: { confirm = false; vm.deleteHistory() }, onNo: { confirm = false })
        }
    }
}

struct ToggleLabel: View {
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
            Section { PrivacyPromise() }
                .listRowBackground(Color.clear)
            Section {
                ForEach([K.help_b1, .help_b2, .help_b3], id: \.self) { Bullet(text: L.s($0)) }
            } header: { SectionTitle(text: L.s(.help_how)) }
            .listRowBackground(Theme.card)
            Section {
                ColourRow(level: .green, name: L.s(.risk_safe), meaning: L.s(.help_safe_line))
                ColourRow(level: .yellow, name: L.s(.risk_careful), meaning: L.s(.help_careful_line))
                ColourRow(level: .red, name: L.s(.risk_scam), meaning: L.s(.help_scam_line))
                ColourRow(level: .error, name: L.s(.risk_not_checked), meaning: L.s(.help_notchecked_line))
            } header: { SectionTitle(text: L.s(.help_colours)) }
            .listRowBackground(Theme.card)
            Section {
                // The tour moves to Home itself.
                BigButton(title: L.s(.btn_replay_tour), id: "replayTour") { vm.startTour() }
                BigButton(title: L.s(.btn_run_setup), id: "runSetup", prominent: false) { vm.runSetupAgain() }
            } header: { SectionTitle(text: L.s(.help_again)) }
            .listRowBackground(Color.clear)
            AboutSection()
            if !vm.simpleMode {
                Section {
                    ForEach(PrivacyLevel.allCases) { l in Bullet(text: "\(l.badge) (\(L.s(l.tagKey))): \(L.s(l.plainKey))") }
                } header: { SectionTitle(text: L.s(.help_privacy_levels)) }
                .listRowBackground(Theme.card)
                Section {
                    KeyValueRow(key: L.s(.about_version), value: appVersion)
                    ForEach(vm.modelStatus.split(separator: "\n").map(String.init), id: \.self) { line in
                        KeyValueRow(key: (line.split(separator: ":").first.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "") + " model",
                                    value: line.contains("federated") ? (line.components(separatedBy: "federated ").last?.components(separatedBy: " (").first ?? "") : "built-in")
                    }
                    KeyValueRow(key: "MobileBERT", value: vm.mobileBertStatus.firstMatch(of: /\((mobilebert[^)]*)\)/).map { String($0.1) } ?? "not installed")
                } header: { SectionTitle(text: L.s(.about_versions)) }
                .listRowBackground(Theme.card)
            }
            #if DEBUG
            Section {
                Button { vm.loadSampleHistory(); loaded = true } label: { Text("Load sample history").font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56) }
                    .buttonStyle(.bordered).font(.body)
                if loaded { Text("Added about 60 sample calls.").font(.body) }
                Button { vm.resetOnboarding() } label: { Text("Reset first-launch setup").font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56) }
                    .buttonStyle(.bordered).font(.body)
            } header: { SectionTitle(text: "Debug build") }
            .listRowBackground(Color.clear)
            #endif
        }
        .settingsList()
        .navigationTitle(L.s(.set_help))
    }
}

/// Phase 6: who made the app, that it isn't a government app, and how to check a scam (1799, ScamShield).
struct AboutSection: View {
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            Text(L.s(.about_who)).font(.body).fixedSize(horizontal: false, vertical: true)
            Text(L.s(.about_not_gov)).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            Text(L.s(.about_verify)).font(.body).fixedSize(horizontal: false, vertical: true)
            BigButton(title: L.s(.btn_call_1799), id: "about1799") { if let u = PhoneLinks.telURL(PhoneLinks.helpline) { openURL(u) } }
            BigButton(title: L.s(.btn_open_scamshield), id: "aboutScamShield", prominent: false) { openURL(scamShieldURL) }
            Text(L.s(.about_version) + " " + appVersion).font(.body).foregroundStyle(Theme.muted)
        } header: { SectionTitle(text: L.s(.about_title)) }
        .listRowBackground(Theme.card)
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
    let level: Risk.Level
    let name: String
    let meaning: String

    var body: some View {
        HStack(spacing: 12) {
            RiskIcon(level: level, size: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline)
                Text(meaning).font(.body).foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}
