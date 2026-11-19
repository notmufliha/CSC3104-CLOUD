import SwiftUI

// Simple mode (Home, Settings), the settings pages both modes share (text size, family contact, Advanced
// settings, PIN) and the PIN screens. Same layout and wording as Android ui/SimpleScreens.kt and ui/PinScreens.kt.

// ---------------------------------------------------------------- Home (Simple mode)

/// Home's status: protecting (green ✓), can't connect (yellow, "Try again"), or still checking. No address or error code.
enum HomeStatus { case protecting, notConnected, checking }

func homeStatus(_ privacy: PrivacyLevel, _ connection: Bool?) -> HomeStatus {
    if privacy == .onDevice { return .protecting }          // needs no server
    switch connection {
    case true?: return .protecting
    case false?: return .notConnected
    case nil: return .checking
    }
}

struct SimpleHomeView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var fixing = false

    var body: some View {
        let _ = vm.historyVersion
        let status = homeStatus(vm.privacyLevel, vm.connection)
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                statusCard(status)
                VStack(alignment: .leading, spacing: 8) {
                    Button { if let s = vm.randomScript("scam") { vm.ring(s) } } label: {
                        Label(L.s(.btn_practice_call), systemImage: "phone.fill")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(Theme.onBrand)
                            .padding(.horizontal, 16).padding(.vertical, 14)
                            .frame(maxWidth: .infinity, minHeight: 76)
                            .background(Theme.brand, in: RoundedRectangle(cornerRadius: 24))
                            .contentShape(RoundedRectangle(cornerRadius: 24))
                    }
                    .buttonStyle(.plain)
                    .tourTarget(.practiceCall)
                    .accessibilityIdentifier("practiceCall")
                    Text(L.s(.home_practice_line)).font(.body).foregroundStyle(Theme.muted).padding(.horizontal, 8)
                }
                LearnHomeCard()
                recentCalls
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle(L.s(.app_name_text))
        .task { await vm.refreshConnection() }
    }

    /// "Try again": checks the connection again (the card then says the result).
    private func fix() {
        fixing = true
        Task {
            await vm.refreshConnection()
            fixing = false
        }
    }

    @ViewBuilder
    private func statusCard(_ status: HomeStatus) -> some View {
        let (level, title, line): (Risk.Level, K, K?) = {
            switch status {
            case .protecting: return (.green, .home_status_ok, .home_status_ok_line)
            case .notConnected: return (.yellow, .conn_fail, .conn_fail_line)
            case .checking: return (.listening, .home_status_checking, nil)
            }
        }()
        VStack(alignment: .leading, spacing: 12) {
            // Very large text: the icon goes above the words, so they don't break mid-word.
            let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
                                                      : AnyLayout(HStackLayout(spacing: 14))
            layout {
                RiskIcon(level: level, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.s(title) + (status == .protecting ? " ✓" : "")).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    if let line { Text(L.s(line)).font(.body) }
                }
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            if status == .notConnected {
                BigButton(title: L.s(fixing ? .status_checking : .btn_try_again), id: "fixIt") { fix() }.disabled(fixing)
            }
        }
        .padding(20)
        .background(status == .notConnected ? Theme.warnYellow.opacity(0.22) : Theme.card, in: RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Theme.levelColor(level), lineWidth: 2))
        .accessibilityIdentifier("homeStatus")
    }

    /// The last 3 calls: icon + word + when. Information only (details are in "For family").
    private var recentCalls: some View {
        let calls = Array(vm.store.calls().prefix(3))
        return VStack(alignment: .leading, spacing: 12) {
            Text(L.s(.home_recent)).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if calls.isEmpty { EmptyState(text: L.s(.home_no_calls), compact: true) }
            ForEach(Array(calls.enumerated()), id: \.element.id) { i, c in
                if i > 0 { Divider() }
                let level = Dashboard.level(c.finalVerdict)
                HStack(spacing: 12) {
                    RiskIcon(level: level, size: 32)
                    Text(Theme.riskWord(level)).font(.title3.weight(.semibold))
                    Spacer()
                    Text(whenText(c.endedAt)).font(.body).foregroundStyle(Theme.muted)
                }
                .frame(minHeight: 56)
                .accessibilityElement(children: .combine)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 24))
    }
}

/// Users set up before the "Who will use this phone?" question: a one-time tip about Settings → App style.
struct AppStyleTipCard: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.s(.tip_app_style)).font(.body).fixedSize(horizontal: false, vertical: true)
            BigButton(title: L.s(.btn_got_it), id: "styleTipOk", prominent: false) { vm.dismissAppStyleTip() }
        }
        .foregroundStyle(Theme.onBrandContainer)
        .padding(20)
        .background(Theme.brandContainer, in: RoundedRectangle(cornerRadius: 24))
        .accessibilityIdentifier("styleTip")
    }
}

// ---------------------------------------------------------------- settings

/// One settings row: icon, title, current value (chevron from the NavigationLink). At least 64 pt tall.
struct SettingsRowLabel: View {
    let icon: String
    let title: String
    var value: String?
    var valueColor: Color = Theme.muted
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        HStack(spacing: 14) {
            // Very large text: no icon, so words keep the whole width and don't break mid-word.
            if !typeSize.isAccessibilitySize { icon_ }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                if let value { Text(value).font(.body).foregroundStyle(valueColor) }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 56)
        .accessibilityElement(children: .combine)
    }

    private var icon_: some View {
        Image(systemName: icon)
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(Theme.onBrand)
            .frame(width: 40, height: 40)
            .background(Theme.brand, in: RoundedRectangle(cornerRadius: 12))
            .accessibilityHidden(true)
    }
}

/// What both modes put first: app style, text size, read aloud, family contact.
struct EverydaySettings: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        Section {
            NavigationLink(value: SettingsRoute.appStyle) {
                SettingsRowLabel(icon: "face.smiling", title: L.s(.set_app_style),
                                 value: L.s(vm.simpleMode ? .style_simple : .style_standard))
            }
            .accessibilityIdentifier("rowAppStyle")
            NavigationLink(value: SettingsRoute.textSize) {
                SettingsRowLabel(icon: "textformat.size", title: L.s(.set_text_size), value: L.s(textSizeKey(vm.textSize)))
            }
            .accessibilityIdentifier("rowTextSize")
            Toggle(isOn: Binding(get: { vm.readAloud }, set: { vm.setReadAloud($0) })) {
                ToggleLabel(title: L.s(.set_read_aloud), line: L.s(.set_read_aloud_line))
            }
            .tint(Theme.brand)
            .frame(minHeight: 64)
            NavigationLink(value: SettingsRoute.family) {
                SettingsRowLabel(icon: "phone.fill", title: L.s(.set_family), value: vm.family?.name ?? L.s(.not_set))
            }
            .accessibilityIdentifier("rowFamily")
        }
        .listRowBackground(Theme.card)
    }
}

/// What Advanced settings holds (and the full app's Settings shows directly).
struct AdvancedSections: View {
    @Environment(CallViewModel.self) private var vm
    let simple: Bool

    var body: some View {
        let _ = vm.historyVersion
        let calls = vm.store.calls().count
        Section {
            Toggle(isOn: Binding(get: { vm.showBeCareful }, set: { vm.setShowBeCareful($0) })) {
                ToggleLabel(title: L.s(.set_show_careful), line: L.s(.set_show_careful_line))
            }
            .frame(minHeight: 64)
            NavigationLink(value: SettingsRoute.pin) {
                SettingsRowLabel(icon: "lock.fill", title: L.s(.set_pin), value: L.s(vm.pinSet ? .set_pin_line_on : .set_pin_line_off))
            }
            .accessibilityIdentifier("rowPin")
        }
        .tint(Theme.brand)
        .listRowBackground(Theme.card)
        Section {
            NavigationLink(value: SettingsRoute.privacy) {
                SettingsRowLabel(icon: "lock.shield.fill", title: L.s(.set_how_checked), value: vm.privacyLevel.choiceTitle(simple: simple))
            }
            .tourTarget(.privacyRow)
            ConnectionStatusRow()
        } header: { SettingsHeader(text: L.s(.set_group_checking)) }
        .listRowBackground(Theme.card)
        Section {
            NavigationLink(value: SettingsRoute.data) {
                SettingsRowLabel(icon: "person.fill", title: L.s(.set_my_data), value: callsCount(calls))
            }
        }
        .listRowBackground(Theme.card)
    }
}

/// The plain connection status: "Connected ✓" or "Can't connect to ScamGuard" with one line of help and "Try again"
/// (it checks again). No address, IP or error code: those are in the hidden Developer view.
struct ConnectionStatusRow: View {
    @Environment(CallViewModel.self) private var vm
    @State private var checking = false

    var body: some View {
        let state = ConnectionStatus.of(vm.privacyLevel, vm.connection)
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 20, weight: .semibold)).foregroundStyle(Theme.onBrand)
                    .frame(width: 40, height: 40)
                    .background(Theme.brand, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.s(.set_connection)).font(.headline)
                    Text(L.s(checking ? ConnectionStatus.checking.key : state.key)).font(.body)
                        .foregroundStyle(state == .connected ? Theme.safeGreen : state == .cantConnect ? Theme.scamRed : Theme.muted)
                    if state == .cantConnect {
                        Text(L.s(.conn_fail_line)).font(.body).fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            if state == .cantConnect {
                BigButton(title: L.s(checking ? .status_checking : .btn_try_again), id: "connectionRetry", prominent: false) {
                    checking = true
                    Task { await vm.refreshConnection(); checking = false }
                }
                .disabled(checking)
            }
        }
        .padding(.vertical, 4)
        .accessibilityIdentifier("connectionStatus")
    }
}

/// The hidden Developer view's row: only once unlocked (Help & about → tap the version 7 times).
struct DeveloperRow: View {
    var body: some View {
        Section {
            NavigationLink(value: SettingsRoute.developer) {
                SettingsRowLabel(icon: "hammer.fill", title: L.s(.set_developer))
            }
            .accessibilityIdentifier("rowDeveloper")
        }
        .listRowBackground(Theme.card)
    }
}

func callsCount(_ n: Int) -> String { n == 1 ? L.s(.calls_one) : L.s(.calls_many, "\(n)") }

struct SettingsHeader: View {
    let text: String
    var body: some View { Text(text).font(.title3.weight(.semibold)).foregroundStyle(Color.primary).textCase(nil) }
}

/// Advanced settings (Simple mode), behind the PIN if one is set.
struct AdvancedPage: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(PinUnlock.self) private var unlock

    var body: some View {
        if vm.pinSet && !unlock.open {
            PinGateView()
        } else {
            List {
                AdvancedSections(simple: true)
                // Simple mode: the Developer view sits here, behind the PIN when one is set.
                if vm.devUnlocked { DeveloperRow() }
            }
            .task { await vm.refreshConnection() }
                .settingsListStyle()
                .navigationTitle(L.s(.set_advanced))
        }
    }
}

/// Settings → App style: Standard or Simple, with a one-line preview of each. Leaving Simple mode asks for the PIN
/// first when one is set, so a senior can't switch by accident.
struct AppStylePage: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(PinUnlock.self) private var unlock
    @State private var askPin = false

    var body: some View {
        Group {
            if askPin {
                PinGateView()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L.s(.style_page_line)).font(.body).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
                        ChoiceCard(selected: !vm.simpleMode, title: L.s(.style_standard), line: L.s(.style_standard_line),
                                   id: "style_standard") { pick(simple: false) }
                        ChoiceCard(selected: vm.simpleMode, title: L.s(.style_simple), line: L.s(.style_simple_line),
                                   id: "style_simple") { pick(simple: true) }
                    }
                    .padding(16)
                }
            }
        }
        .background(Theme.background)
        .navigationTitle(L.s(.set_app_style))
        // the right PIN, or "Forgot PIN" (which removes it): then switch
        .onChange(of: unlock.open) { _, open in if open && askPin { askPin = false; vm.setAppStyle(simple: false) } }
        .onChange(of: vm.pinSet) { _, set in if !set && askPin { askPin = false; vm.setAppStyle(simple: false) } }
    }

    private func pick(simple: Bool) {
        guard simple != vm.simpleMode else { return }
        if vm.appStyleNeedsPin(toSimple: simple) {
            unlock.open = false
            askPin = true
        } else {
            vm.setAppStyle(simple: simple)
        }
    }
}

struct TextSizePage: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        ScrollView {
            TextSizeChoices(current: vm.textSize) { vm.setTextSize($0) }.padding(16)
        }
        .background(Theme.background)
        .navigationTitle(L.s(.set_text_size))
    }
}

struct FamilyPage: View {
    @Environment(CallViewModel.self) private var vm
    @State private var name = ""
    @State private var phone = ""
    @State private var note: String?
    @State private var loaded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L.s(.family_page_line)).font(.body).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
                ContactFields(name: $name, phone: $phone)
                BigButton(title: L.s(.btn_save), id: "familySave") {
                    if phone.trimmingCharacters(in: .whitespaces).isEmpty { note = L.s(.family_need_phone) }
                    else { vm.setFamily(name: name, phone: phone); note = L.s(.saved) + " ✓" }
                }
                if let note { Text(note).font(.body.weight(.semibold)) }
                if vm.family != nil {
                    BigButton(title: L.s(.btn_remove_contact), id: "familyRemove", prominent: false, danger: true) {
                        name = ""; phone = ""; vm.setFamily(name: "", phone: ""); note = nil
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle(L.s(.set_family))
        .onAppear {
            guard !loaded else { return }
            loaded = true
            if let f = vm.family { phone = f.phone; name = f.name == f.phone ? "" : f.name }
        }
    }
}

struct PinSettingsPage: View {
    @Environment(CallViewModel.self) private var vm
    @State private var newPin = ""
    @State private var changing = false
    @State private var done = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(L.s(.pin_setup_line)).font(.body).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
                Text(L.s(vm.pinSet ? .set_pin_line_on : .set_pin_line_off)).font(.title3.weight(.semibold))
                    .fixedSize(horizontal: false, vertical: true)
                if changing || !vm.pinSet {
                    NewPinFields { newPin = $0 }
                    BigButton(title: L.s(.btn_save), id: "pinSave") { vm.setPin(newPin); changing = false; done = true }
                        .disabled(newPin.isEmpty)
                        .opacity(newPin.isEmpty ? 0.5 : 1)
                } else {
                    if done { Text(L.s(.saved) + " ✓").font(.body.weight(.semibold)) }
                    BigButton(title: L.s(.btn_change_pin), id: "pinChange", prominent: false) { changing = true; done = false }
                }
                if vm.pinSet {
                    BigButton(title: L.s(.btn_turn_off_pin), id: "pinOff", prominent: false, danger: true) {
                        vm.clearPin(); changing = true; done = false
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.background)
        .navigationTitle(L.s(.set_pin))
    }
}

// ---------------------------------------------------------------- PIN

/// Simple mode with a PIN: "For family" and Advanced settings stay open until the user goes back Home.
@Observable
final class PinUnlock {
    var open = false
}

/// A 4-number field: digits only, hidden, big.
struct PinField: View {
    let label: String
    @Binding var value: String
    let id: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.headline)
            SecureField("", text: Binding(get: { value }, set: { v in value = String(v.filter(\.isNumber).prefix(Pin.length)) }))
                .keyboardType(.numberPad)
                .font(.title.bold())
                .multilineTextAlignment(.center)
                .padding(12)
                .frame(minHeight: 64)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.muted.opacity(0.6), lineWidth: 1.5))
                .accessibilityLabel(label)
                .accessibilityValue(L.s(.a11y_pin_digits, "\(value.count)"))
                .accessibilityIdentifier(id)
        }
    }
}

/// Choose a PIN twice; onPin gets it when both match (or "" while they don't).
struct NewPinFields: View {
    let onPin: (String) -> Void
    @State private var first = ""
    @State private var second = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PinField(label: L.s(.pin_new), value: $first, id: "pinNew")
            PinField(label: L.s(.pin_again), value: $second, id: "pinAgain")
            if let note { Text(note).font(.body.weight(.semibold)).accessibilityAddTraits(.updatesFrequently) }
        }
        .onChange(of: first) { _, _ in report() }
        .onChange(of: second) { _, _ in report() }
    }

    private func report() { onPin(Pin.isValid(first) && first == second ? first : "") }

    private var note: String? {
        if first.isEmpty && second.isEmpty { return nil }
        if first.count < Pin.length { return L.s(.pin_need_4) }
        if second.count == Pin.length && first != second { return L.s(.pin_mismatch) }
        if Pin.isValid(first) && first == second { return L.s(.pin_ok) + " ✓" }
        return nil
    }
}

/// Asks for the PIN. Nothing times out. "Forgot PIN" removes only the PIN, after a clear warning (the call history
/// is not touched).
struct PinGateView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(PinUnlock.self) private var unlock
    @State private var pin = ""
    @State private var wrong = false
    @State private var short = false
    @State private var forgot = false

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 42, weight: .semibold)).foregroundStyle(Theme.brand)
                    .frame(width: 96, height: 96).background(Theme.brandContainer, in: Circle())
                    .accessibilityHidden(true)
                Text(L.s(.pin_enter_title)).font(.title.bold()).multilineTextAlignment(.center).accessibilityAddTraits(.isHeader)
                Text(L.s(.pin_enter_line)).font(.body).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
                PinField(label: L.s(.field_pin), value: $pin, id: "pinEnter").frame(maxWidth: 360)
                    .onChange(of: pin) { _, _ in wrong = false }
                if wrong {
                    Text(L.s(.pin_wrong)).font(.body.weight(.semibold)).foregroundStyle(Theme.warningInkAdaptive)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                if short && !Pin.isValid(pin) {
                    Text(L.s(.pin_need_4)).font(.body.weight(.semibold)).accessibilityAddTraits(.updatesFrequently)
                }
                // Always full contrast: tapping too early says "Use 4 numbers" instead of a faded button.
                BigButton(title: L.s(.btn_unlock), id: "pinUnlock") {
                    if !Pin.isValid(pin) { short = true }
                    else if vm.verifyPin(pin) { unlock.open = true } else { wrong = true; pin = "" }
                }
                Button { forgot = true } label: { Text(L.s(.btn_forgot_pin)).font(.body).frame(minHeight: 56) }
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background)
        .accessibilityIdentifier("pinGate")
        .sheet(isPresented: $forgot) {
            YesNoSheet(title: L.s(.pin_forgot_title), text: L.s(.pin_forgot_text), yes: L.s(.btn_remove_pin), no: L.s(.btn_keep_pin),
                       yesIsDanger: true,
                       onYes: { forgot = false; vm.clearPin(); unlock.open = true }, onNo: { forgot = false })
        }
    }
}

extension View {
    /// Grouped list styling shared by every settings page (Android's grouped cards).
    func settingsListStyle() -> some View {
        self.listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Theme.background)
    }
}
