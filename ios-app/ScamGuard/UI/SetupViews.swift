import SwiftUI

// First-launch setup. Simple (the default): Welcome, "Use recommended settings", family contact.
// "Choose myself": the full choices. "Set up for a family member": their settings, the contact, an optional PIN.
// Same screens, wording and look as android-app ui/SetupScreens.kt.

/// The setup. Swipe or Back / the big buttons between screens; Skip saves what is picked so far (recommended
/// settings for the rest).
struct SetupView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var confirmLeave = false

    var body: some View {
        let choices = vm.setupChoices
        let steps = Onboarding.steps(choices)
        let step = vm.setupStep
        let index = steps.firstIndex(of: step) ?? 0
        let previous = Onboarding.previous(step, choices)
        VStack(spacing: 0) {
            // top bar: Back on the left, Skip on the right
            HStack {
                if let previous {
                    Button { go(previous) } label: { Text("‹ " + L.s(.btn_back)).font(.body).frame(minWidth: 56, minHeight: 56) }
                        .accessibilityLabel(L.s(.a11y_back))
                } else if vm.setupIsReplay {
                    Button { confirmLeave = true } label: { Text("‹ " + L.s(.btn_back)).font(.body).frame(minWidth: 56, minHeight: 56) }
                }
                Spacer()
                if step != .done {
                    Button { vm.finishSetup(startTour: false) } label: { Text(L.s(.btn_skip)).font(.body).frame(minWidth: 56, minHeight: 56) }
                        .accessibilityIdentifier("setupSkip")
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 56)

            TabView(selection: Binding(get: { vm.setupStep }, set: { vm.setSetupStep($0) })) {
                ForEach(steps, id: \.self) { s in
                    SetupPage(step: s, choices: choices) { pageContent(s, choices) }.tag(s)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut, value: step)

            // bottom: progress dots and the main button(s)
            VStack(spacing: 12) {
                let (n, of) = Onboarding.progress(step, choices)
                Dots(index: index, count: steps.count, spoken: L.s(.setup_step, "\(n)", "\(of)"))
                switch step {
                case .welcome:
                    BigButton(title: L.s(.btn_get_started), id: "setupNext") {
                        vm.updateSetup { $0.path = .simple }
                        go(.recommended)
                    }
                    BigButton(title: L.s(.btn_family_member), id: "setupHelper", prominent: false) {
                        vm.updateSetup { $0.path = .helper }
                        go(.helper)
                    }
                case .recommended:
                    BigButton(title: L.s(.btn_use_recommended), id: "setupRecommended") {
                        vm.updateSetup { $0 = Onboarding.recommended($0) }
                        go(.family)
                    }
                    BigButton(title: L.s(.btn_choose_myself), id: "setupCustom", prominent: false) {
                        vm.updateSetup { $0.path = .custom }
                        go(.privacy)
                    }
                case .family:
                    let last = Onboarding.next(step, choices) == nil
                    BigButton(title: L.s(last ? .btn_save_finish : .btn_next), id: "setupFamilyNext") {
                        if last { vm.finishSetup(startTour: true) } else { go(Onboarding.next(step, choices)) }
                    }
                    Button {
                        vm.updateSetup { $0.familyName = ""; $0.familyPhone = "" }
                        if last { vm.finishSetup(startTour: true) } else { go(Onboarding.next(step, vm.setupChoices)) }
                    } label: { Text(L.s(.btn_skip_for_now)).font(.body).frame(minHeight: 56) }
                case .done:
                    BigButton(title: L.s(.btn_show_me_around), id: "setupTour") { vm.finishSetup(startTour: true) }
                    BigButton(title: L.s(.btn_skip_tour), id: "setupNoTour", prominent: false) { vm.finishSetup(startTour: false) }
                default:
                    BigButton(title: L.s(.btn_next), id: "setupNext") { go(Onboarding.next(step, choices)) }
                    if step == .server {
                        Button {
                            vm.updateSetup { $0.server = .later }
                            go(Onboarding.next(step, choices))
                        } label: { Text(L.s(.btn_set_up_later)).font(.body).frame(minHeight: 56) }
                            .accessibilityIdentifier("setupLater")
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            // The fixed bottom buttons stop growing at the first accessibility size, so the page above keeps room.
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        }
        .background(Theme.background.ignoresSafeArea())
        .accessibilityIdentifier("setup")
        .sheet(isPresented: $confirmLeave) {
            YesNoSheet(title: L.s(.setup_leave_title), text: L.s(.setup_leave_replay), yes: L.s(.btn_leave), no: L.s(.btn_stay),
                       onYes: { confirmLeave = false; vm.leaveSetup() }, onNo: { confirmLeave = false })
        }
    }

    private func go(_ to: Onboarding.Step?) {
        guard let to else { return }
        withAnimation { vm.setSetupStep(to) }
    }

    @ViewBuilder
    private func pageContent(_ s: Onboarding.Step, _ c: Onboarding.Choices) -> some View {
        switch s {
        case .welcome: EmptyView()
        case .recommended: RecommendedList()
        case .privacy: PrivacyChoices()
        case .server: ServerChoice()
        case .data: DataChoices()
        case .helper: HelperChoices()
        case .family:
            ContactFields(name: Binding(get: { vm.setupChoices.familyName }, set: { v in vm.updateSetup { $0.familyName = v } }),
                          phone: Binding(get: { vm.setupChoices.familyPhone }, set: { v in vm.updateSetup { $0.familyPhone = v } }))
        case .pin: NewPinFields { pin in vm.updateSetup { $0.pin = pin } }
        case .done: SetupSummary(choices: c)
        }
    }
}

struct BigButton: View {
    let title: String
    let id: String
    var prominent = true
    var danger = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(prominent ? Theme.onBrand : (danger ? Theme.warningInkAdaptive : Theme.brand))
                .padding(.horizontal, 16).padding(.vertical, 12)
                .frame(maxWidth: 480, minHeight: 60)
                .frame(maxWidth: .infinity)
                .background(prominent ? Theme.brand : Color.clear, in: Capsule())
                .overlay(Capsule().stroke(danger ? Theme.scamRed : Theme.brand, lineWidth: prominent ? 0 : 2))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

/// Plain yes / no question: big text, nothing pre-selected, both buttons the same size. Never closes by itself.
struct YesNoSheet: View {
    let title: String
    let text: String
    let yes: String
    let no: String
    var yesIsDanger = false
    let onYes: () -> Void
    let onNo: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(title).font(.title.bold()).accessibilityAddTraits(.isHeader)
                Text(text).font(.body).fixedSize(horizontal: false, vertical: true)
                BigButton(title: yes, id: "sheetYes", prominent: false, danger: yesIsDanger, action: onYes)
                BigButton(title: no, id: "sheetNo", prominent: false, action: onNo)
            }
            .padding(24)
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled()
    }
}

/// Big icon, title, one line, then the screen's own content; scrolls with very large text.
private struct SetupPage<Content: View>: View {
    let step: Onboarding.Step
    let choices: Onboarding.Choices
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Spacer().frame(height: step == .welcome ? 16 : 0)
                SetupIcon(step: step)
                Text(L.s(title))
                    .font(.title.bold())
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(L.s(line))
                    .font(.title3)
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if step == .welcome { PrivacyPromise() }
                Spacer().frame(height: 4)
                content
            }
            .frame(maxWidth: 520)
            .padding(.horizontal, 24)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
        }
    }

    private var helper: Bool { choices.path == .helper }

    private var title: K {
        switch step {
        case .welcome: return .setup_welcome_title
        case .recommended: return .setup_rec_title
        case .privacy: return .setup_privacy_title
        case .server: return .setup_server_title
        case .data: return .setup_data_title
        case .helper: return .setup_helper_title
        case .family: return helper ? .setup_helper_family_title : .setup_family_title
        case .pin: return .pin_setup_title
        case .done: return .setup_done_title
        }
    }

    private var line: K {
        switch step {
        case .welcome: return .setup_welcome_line
        case .recommended: return .setup_rec_line
        case .privacy: return .setup_privacy_line
        case .server: return .setup_server_line
        case .data: return .setup_data_line
        case .helper: return .setup_helper_line
        case .family: return helper ? .setup_helper_family_line : .setup_family_line
        case .pin: return .pin_setup_line
        case .done: return .setup_done_line
        }
    }
}

/// "Your calls stay private. We never record your voice." (Welcome and Help.)
struct PrivacyPromise: View {
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "lock.fill").font(.system(size: 26)).accessibilityHidden(true)
            Text(L.s(.privacy_promise)).font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Theme.onBrandContainer)
        .padding(16)
        .background(Theme.brandContainer, in: RoundedRectangle(cornerRadius: 20))
        .accessibilityElement(children: .combine)
    }
}

private struct SetupIcon: View {
    let step: Onboarding.Step

    var body: some View {
        if step == .welcome {
            Image("AppLogo")
                .resizable()
                .frame(width: 104, height: 104)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .accessibilityHidden(true)
        } else {
            Image(systemName: icon)
                .font(.system(size: 42, weight: .semibold))
                .foregroundStyle(Theme.brand)
                .frame(width: 96, height: 96)
                .background(Theme.brandContainer, in: Circle())
                .accessibilityHidden(true)
        }
    }

    private var icon: String {
        switch step {
        case .recommended: return "star.fill"
        case .privacy, .pin: return "lock.fill"
        case .server: return "server.rack"
        case .data: return "person.fill"
        case .helper: return "gearshape.fill"
        case .family: return "phone.fill"
        default: return "checkmark.circle.fill"
        }
    }
}

private struct Dots: View {
    let index: Int
    let count: Int
    let spoken: String

    var body: some View {
        HStack(spacing: 8) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(i == index ? Theme.brand : Theme.outline)
                    .frame(width: i == index ? 24 : 10, height: 10)
            }
        }
        .animation(.easeInOut, value: index)
        .accessibilityElement()
        .accessibilityLabel(spoken)
    }
}

/// A big radio card: one choice in a list (text size, privacy).
struct ChoiceCard: View {
    let selected: Bool
    let title: String
    var line: String?
    var id = ""
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.title2).foregroundStyle(selected ? Theme.brand : Theme.muted)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.title3.bold()).foregroundStyle(Color.primary)
                    if let line { Text(line).font(.body).foregroundStyle(Theme.muted) }
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .background(selected ? Theme.brandContainer : Theme.card, in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(selected ? Theme.brand : Theme.outlineVariant, lineWidth: 2))
            .contentShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : [.isButton])
        .accessibilityIdentifier(id)
    }
}

// ---------------------------------------------------------------- 2. recommended

private struct RecommendedList: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach([K.setup_rec_b1, .setup_rec_b2, .setup_rec_b3], id: \.self) { k in
                HStack(spacing: 14) {
                    RiskIcon(level: .green, size: 30)
                    Text(L.s(k)).font(.body).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
        }
        .padding(.vertical, 8)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }
}

// ---------------------------------------------------------------- privacy ("Choose myself")

extension PrivacyLevel {
    /// Plain name ("Most accurate"…), no technical words.
    var tagKey: K {
        switch self {
        case .cloud: return .privacy_tag_cloud
        case .hybrid: return .privacy_tag_hybrid
        case .onDevice: return .privacy_tag_on_device
        }
    }

    var plainKey: K {
        switch self {
        case .cloud: return .privacy_plain_cloud
        case .hybrid: return .privacy_plain_hybrid
        case .onDevice: return .privacy_plain_on_device
        }
    }

    /// "Most accurate" in Simple mode; "Cloud · Most accurate" in the full app.
    func choiceTitle(simple: Bool) -> String { simple ? L.s(tagKey) : "\(badge) · \(L.s(tagKey))" }
}

private struct PrivacyChoices: View {
    @Environment(CallViewModel.self) private var vm
    @State private var info = false

    var body: some View {
        let c = vm.setupChoices
        VStack(spacing: 12) {
            ForEach(PrivacyLevel.allCases) { level in
                ChoiceCard(selected: level == c.privacy, title: level.choiceTitle(simple: c.simple), line: L.s(level.plainKey),
                           id: "privacy_\(level.rawValue)") {
                    vm.updateSetup { $0.privacy = level }
                }
            }
            BigButton(title: L.s(.setup_more_about), id: "privacyInfo", prominent: false) { info = true }
        }
        .sheet(isPresented: $info) {
            InfoSheet(title: L.s(.set_how_checked),
                      text: PrivacyLevel.allCases.map { "\(L.s($0.tagKey)): \(L.s($0.plainKey))" }.joined(separator: "\n\n")
                        + "\n\n" + L.s(.privacy_english_only))
        }
    }
}

func privacyIcon(_ level: PrivacyLevel) -> String {
    switch level {
    case .cloud: return "cloud.fill"
    case .hybrid: return "arrow.triangle.2.circlepath.icloud.fill"
    case .onDevice: return "iphone"
    }
}

// ---------------------------------------------------------------- server

private struct ServerChoice: View {
    @Environment(CallViewModel.self) private var vm
    @State private var testing = false

    var body: some View {
        let c = vm.setupChoices
        VStack(alignment: .leading, spacing: 14) {
            TextField(L.s(.field_server), text: Binding(
                get: { vm.setupChoices.serverUrl },
                set: { v in vm.updateSetup { $0.serverUrl = v; $0.server = .notChecked } }))
                .font(.body)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .frame(minHeight: 56)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.muted.opacity(0.5), lineWidth: 1))
                .accessibilityLabel(L.s(.field_server))
                .accessibilityIdentifier("setupUrl")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(ServerPage.presets, id: \.0) { p in
                        let on = c.serverUrl == p.1
                        Button { vm.updateSetup { $0.serverUrl = p.1; $0.server = .notChecked } } label: {
                            Text(p.0).font(.body)
                                .foregroundStyle(on ? Theme.onBrand : Theme.brand)
                                .padding(.horizontal, 16)
                                .frame(minHeight: 56)
                                .background(on ? Theme.brand : Theme.brandContainer, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                }
            }
            BigButton(title: L.s(.btn_test_connection), id: "setupTest", prominent: false) {
                testing = true
                let url = c.serverUrl
                Task {
                    // iOS asks for Local Network here, the first time the app reaches the Mac.
                    let ok = await vm.testSetupServer(url)
                    vm.updateSetup { $0.server = ok ? .connected : .cantReach }
                    testing = false
                }
            }
            .disabled(testing)
            if let st = status(c) {
                StatusChip(text: st.0, color: st.1)
                    .frame(maxWidth: .infinity)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "info.circle.fill").foregroundStyle(Theme.brand).accessibilityHidden(true)
                Text(L.s(.net_heads_up)).font(.body).foregroundStyle(Theme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func status(_ c: Onboarding.Choices) -> (String, Color)? {
        if testing { return (L.s(.status_checking), Theme.muted) }
        switch c.server {
        case .connected: return (L.s(.status_connected) + " ✓", Theme.safeGreen)
        case .cantReach: return (L.s(.status_cant_reach), Theme.scamRed)
        default: return nil
        }
    }
}

// ---------------------------------------------------------------- data

private struct DataChoices: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        VStack(spacing: 0) {
            toggle(L.s(.data_save_sentence), L.s(.data_save_sentence_line),
                   Binding(get: { vm.setupChoices.saveSentence }, set: { v in vm.updateSetup { $0.saveSentence = v } }))
            Divider().padding(.leading, 16)
            toggle(L.s(.data_help_improve), L.s(.data_help_improve_line),
                   Binding(get: { vm.setupChoices.helpImprove }, set: { v in vm.updateSetup { $0.helpImprove = v } }))
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }

    private func toggle(_ title: String, _ line: String, _ on: Binding<Bool>) -> some View {
        Toggle(isOn: on) { ToggleLabel(title: title, line: line) }
            .tint(Theme.brand)
            .padding(16)
            .frame(minHeight: 64)
    }
}

// ---------------------------------------------------------------- helper: their settings

private struct HelperChoices: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        VStack(spacing: 16) {
            TextSizeChoices(current: vm.setupChoices.textSize) { size in
                vm.updateSetup { $0.textSize = size }
                vm.setTextSize(size)
            }
            VStack(spacing: 0) {
                Toggle(isOn: Binding(get: { vm.setupChoices.simple }, set: { v in vm.updateSetup { $0.simple = v } })) {
                    ToggleLabel(title: L.s(.set_simple_mode), line: L.s(.set_simple_mode_line))
                }
                .padding(16).frame(minHeight: 64)
                Divider().padding(.leading, 16)
                Toggle(isOn: Binding(get: { vm.setupChoices.readAloud }, set: { v in vm.updateSetup { $0.readAloud = v } })) {
                    ToggleLabel(title: L.s(.set_read_aloud), line: L.s(.set_read_aloud_line))
                }
                .padding(16).frame(minHeight: 64)
            }
            .tint(Theme.brand)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
        }
    }
}

/// "Text size": Large / Extra large.
struct TextSizeChoices: View {
    let current: String
    let onPick: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.s(.set_text_size)).font(.headline).accessibilityAddTraits(.isHeader)
            ChoiceCard(selected: current == AppPrefs.textLarge, title: L.s(.text_large), line: L.s(.text_size_sample), id: "text_large") {
                onPick(AppPrefs.textLarge)
            }
            ChoiceCard(selected: current == AppPrefs.textXLarge, title: L.s(.text_xlarge), line: L.s(.text_size_sample), id: "text_xlarge") {
                onPick(AppPrefs.textXLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- family contact

/// Name + phone number fields (setup and Settings → My family contact).
struct ContactFields: View {
    @Binding var name: String
    @Binding var phone: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            field(L.s(.field_name), $name, id: "familyName", keyboard: .default)
            field(L.s(.field_phone), $phone, id: "familyPhone", keyboard: .phonePad, prompt: L.s(.field_phone_hint))
        }
    }

    private func field(_ label: String, _ text: Binding<String>, id: String, keyboard: UIKeyboardType, prompt: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.headline)
            TextField(prompt ?? "", text: text)
                .font(.title3)
                .keyboardType(keyboard)
                .padding(14)
                .frame(minHeight: 60)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.muted.opacity(0.6), lineWidth: 1.5))
                .accessibilityLabel(label)
                .accessibilityIdentifier(id)
        }
    }
}

// ---------------------------------------------------------------- done

private struct SetupSummary: View {
    let choices: Onboarding.Choices

    var body: some View {
        let c = choices
        VStack(spacing: 0) {
            ForEach(Array(rows(c).enumerated()), id: \.offset) { i, row in
                if i > 0 { Divider().padding(.leading, 16) }
                KeyValueRow(key: row.0, value: row.1).padding(16)
            }
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }

    private func onOff(_ b: Bool) -> String { L.s(b ? .on : .off) }

    private func rows(_ c: Onboarding.Choices) -> [(String, String)] {
        var r: [(String, String)] = [
            (L.s(.set_text_size), L.s(c.textSize == AppPrefs.textXLarge ? .text_xlarge : .text_large)),
            (L.s(.set_simple_mode), onOff(c.simple)),
            (L.s(.sum_privacy), L.s(c.privacy.tagKey)),
        ]
        if c.path == .custom {
            r.append((L.s(.sum_server), serverLabel(c)))
            r.append((L.s(.data_save_sentence), onOff(c.saveSentence)))
            r.append((L.s(.data_help_improve), onOff(c.helpImprove)))
        }
        let fam = c.familyName.isEmpty ? c.familyPhone : c.familyName
        r.append((L.s(.sum_family), fam.isEmpty ? L.s(.not_set) : fam))
        if c.path == .helper { r.append((L.s(.sum_pin), onOff(c.pin.count == 4))) }
        return r
    }

    private func serverLabel(_ c: Onboarding.Choices) -> String {
        if c.privacy == .onDevice { return L.s(.status_not_needed) }
        switch c.server {
        case .connected: return L.s(.status_connected)
        case .cantReach: return L.s(.status_cant_reach)
        case .later: return L.s(.status_set_up_later)
        case .notChecked: return L.s(.status_not_checked)
        }
    }
}
