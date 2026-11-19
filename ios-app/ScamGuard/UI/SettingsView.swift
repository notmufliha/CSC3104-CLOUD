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

enum SettingsRoute: String, Hashable, CaseIterable { case privacy, data, help, appStyle, textSize, family, advanced, pin, developer }

/// Settings. Simple mode: the everyday rows, Help, and "Advanced settings" (PIN-protected if set). The full app:
/// the everyday rows, everything Advanced settings holds, and Help.
struct SettingsView: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        ScrollViewReader { proxy in
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
                if vm.devUnlocked { DeveloperRow() }
            }
            Color.clear.frame(height: 1).listRowBackground(Color.clear).id("settingsEnd")
        }
        #if DEBUG
        // -demoSettingsBottom YES: scrolled to the end (screenshots)
        .task {
            if UserDefaults.standard.bool(forKey: "demoSettingsBottom") {
                try? await Task.sleep(nanoseconds: 500_000_000)
                proxy.scrollTo("settingsEnd", anchor: .bottom)
            }
        }
        #endif
        }
        .settingsList()
        .navigationTitle(L.s(.tab_settings))
        .navigationDestination(for: SettingsRoute.self) { route in
            switch route {
            case .privacy: PrivacyPage()
            case .data: DataPage()
            case .help: HelpPage()
            case .appStyle: AppStylePage()
            case .textSize: TextSizePage()
            case .family: FamilyPage()
            case .advanced: AdvancedPage()
            case .pin: PinSettingsPage()
            case .developer: if vm.devUnlocked { DeveloperView() }
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
            }
        }
        .settingsList()
        .navigationTitle(L.s(.set_help))
    }
}

/// Phase 6: who made the app, that it isn't a government app, and how to check a scam (1799, ScamShield).
struct AboutSection: View {
    @Environment(\.openURL) private var openURL
    @Environment(CallViewModel.self) private var vm
    @State private var taps = 0
    @State private var message: String?

    var body: some View {
        Section {
            Text(L.s(.about_who)).font(.body).fixedSize(horizontal: false, vertical: true)
            Text(L.s(.about_not_gov)).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            Text(L.s(.about_verify)).font(.body).fixedSize(horizontal: false, vertical: true)
            BigButton(title: L.s(.btn_call_1799), id: "about1799") { if let u = PhoneLinks.telURL(PhoneLinks.helpline) { openURL(u) } }
            BigButton(title: L.s(.btn_open_scamshield), id: "aboutScamShield", prominent: false) { openURL(scamShieldURL) }
            // Tapping the version 7 times unlocks the hidden Developer view (like Android's developer options).
            Text(L.s(.about_version) + " " + appVersion).font(.body).foregroundStyle(Theme.muted)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { tap() }
                .accessibilityIdentifier("aboutVersion")
                .accessibilityAddTraits(.isButton)
            if let message {
                // Android shows a toast; here a short line under the version, gone after 2 s.
                Text(message).font(.body.weight(.semibold)).foregroundStyle(Theme.brand)
                    .accessibilityIdentifier("devToast")
                    .transition(.opacity)
            }
        } header: { SectionTitle(text: L.s(.about_title)) }
        .listRowBackground(Theme.card)
    }

    private func tap() {
        let t = vm.developerTap(taps)
        taps = t.count
        let text: String?
        switch t.outcome {
        case .none: text = nil
        case .stepsLeft: text = L.s(.dev_taps_left, "\(t.left)")
        case .unlocked: text = L.s(.dev_unlocked)
        case .already: text = L.s(.dev_already)
        }
        guard let text else { return }
        withAnimation { message = text }
        UIAccessibility.post(notification: .announcement, argument: text)
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if message == text { withAnimation { message = nil } }
        }
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
