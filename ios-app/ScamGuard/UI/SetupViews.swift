import SwiftUI

// First-launch setup: up to five screens, one decision each. Same screens, wording and look as
// android-app ui/SetupScreens.kt. The wording lives in Logic/Onboarding.swift.

/// The setup. Swipe (left/right, including swipe back) or Back / Next between screens; Skip saves what is picked
/// so far (defaults for the rest).
struct SetupView: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        let choices = vm.setupChoices
        let steps = Onboarding.steps(choices.privacy)
        let step = vm.setupStep
        let index = steps.firstIndex(of: step) ?? 0
        let previous = Onboarding.previous(step, choices.privacy)
        VStack(spacing: 0) {
            // top bar: Back on the left, Skip on the right
            HStack {
                if let previous {
                    Button { go(previous) } label: { Text("‹ Back").font(.body).frame(minWidth: 44, minHeight: 44) }
                        .accessibilityLabel("Back")
                }
                Spacer()
                if step != .done {
                    Button { vm.finishSetup(startTour: false) } label: { Text("Skip").font(.body).frame(minWidth: 44, minHeight: 44) }
                        .accessibilityIdentifier("setupSkip")
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 56)

            TabView(selection: Binding(get: { vm.setupStep }, set: { vm.setSetupStep($0) })) {
                ForEach(steps, id: \.self) { s in
                    SetupPage(step: s) { pageContent(s, choices) }.tag(s)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut, value: step)

            // bottom: progress dots and the main button
            VStack(spacing: 12) {
                Dots(index: index, count: steps.count, spoken: Onboarding.progress(step, choices.privacy))
                switch step {
                case .welcome:
                    BigButton(title: "Get started", id: "setupNext") { go(Onboarding.next(step, choices.privacy)) }
                case .done:
                    BigButton(title: "Show me around", id: "setupTour") { vm.finishSetup(startTour: true) }
                    BigButton(title: "Skip tour", id: "setupNoTour", prominent: false) { vm.finishSetup(startTour: false) }
                default:
                    BigButton(title: "Next", id: "setupNext") { go(Onboarding.next(step, choices.privacy)) }
                    if step == .server {
                        Button {
                            vm.updateSetup { $0.server = .later }
                            go(Onboarding.next(step, choices.privacy))
                        } label: { Text("Set up later").font(.body).frame(minHeight: 44) }
                            .accessibilityIdentifier("setupLater")
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
        }
        .background(Theme.background.ignoresSafeArea())
        .accessibilityIdentifier("setup")
    }

    private func go(_ to: Onboarding.Step?) {
        guard let to else { return }
        withAnimation { vm.setSetupStep(to) }
    }

    @ViewBuilder
    private func pageContent(_ s: Onboarding.Step, _ c: Onboarding.Choices) -> some View {
        switch s {
        case .welcome: EmptyView()
        case .privacy: PrivacyChoices(current: c.privacy) { p in vm.updateSetup { $0.privacy = p } }
        case .server: ServerChoice()
        case .data: DataChoices()
        case .done: SetupSummary(choices: c)
        }
    }
}

private struct BigButton: View {
    let title: String
    let id: String
    var prominent = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(prominent ? Theme.onBrand : Theme.brand)
                .frame(maxWidth: 480, minHeight: 56)
                .frame(maxWidth: .infinity)
                .background(prominent ? Theme.brand : Color.clear, in: Capsule())
                .overlay(Capsule().stroke(Theme.brand, lineWidth: prominent ? 0 : 1.5))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(id)
    }
}

/// Big icon, title, one line, then the screen's own content; scrolls with very large text.
private struct SetupPage<Content: View>: View {
    let step: Onboarding.Step
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Spacer().frame(height: step == .welcome ? 40 : 0)
                SetupIcon(step: step)
                Text(Onboarding.title(step))
                    .font(.title.bold())                    // 28 pt, as Android's headlineMedium
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(Onboarding.line(step))
                    .font(.title3)
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 4)
                content
            }
            .frame(maxWidth: 520)
            .padding(.horizontal, 24)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity)
        }
    }
}

private struct SetupIcon: View {
    let step: Onboarding.Step

    var body: some View {
        if step == .welcome {
            Image("AppLogo")
                .resizable()
                .frame(width: 128, height: 128)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
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
        case .privacy: return "lock.fill"
        case .server: return "server.rack"
        case .data: return "person.fill"
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
                    .fill(i == index ? Theme.brand : Theme.track)
                    .frame(width: i == index ? 24 : 10, height: 10)
            }
        }
        .animation(.easeInOut, value: index)
        .accessibilityElement()
        .accessibilityLabel(spoken)
    }
}

// ---------------------------------------------------------------- 2. privacy

func privacyIcon(_ level: PrivacyLevel) -> String {
    switch level {
    case .cloud: return "cloud.fill"
    case .hybrid: return "arrow.triangle.2.circlepath.icloud.fill"
    case .onDevice: return "iphone"
    }
}

private struct PrivacyChoices: View {
    let current: PrivacyLevel
    let onPick: (PrivacyLevel) -> Void
    @State private var info: PrivacyLevel?

    var body: some View {
        VStack(spacing: 10) {
            ForEach(PrivacyLevel.allCases) { level in
                let selected = level == current
                HStack(spacing: 14) {
                    Button { onPick(level) } label: {
                        HStack(spacing: 14) {
                            Image(systemName: privacyIcon(level))
                                .font(.system(size: 22, weight: .semibold))
                                .foregroundStyle(selected ? Theme.onBrand : Theme.brand)
                                .frame(width: 52, height: 52)
                                .background(selected ? Theme.brand : Theme.brandContainer, in: Circle())
                            VStack(alignment: .leading, spacing: 2) {
                                Text(Onboarding.privacyTitle(level)).font(.title2.bold()).foregroundStyle(Color.primary)
                                Text(Onboarding.privacyTag(level)).font(.body.weight(.semibold)).foregroundStyle(Theme.brand)
                                Text(Onboarding.privacyLine(level)).font(.body).foregroundStyle(Theme.muted)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 0)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(selected ? [.isSelected] : [])
                    .accessibilityIdentifier("privacy_\(level.rawValue)")
                    Button { info = level } label: {
                        Image(systemName: "info.circle.fill").font(.title2).foregroundStyle(Theme.brand)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("About \(Onboarding.privacyTitle(level))")
                }
                .padding(.leading, 14).padding(.vertical, 12).padding(.trailing, 4)
                .background(selected ? Theme.brandContainer : Theme.card, in: RoundedRectangle(cornerRadius: 24))
                .overlay(RoundedRectangle(cornerRadius: 24).stroke(selected ? Theme.brand : .clear, lineWidth: 2))
            }
        }
        .sheet(item: $info) { l in InfoSheet(title: Onboarding.privacyTitle(l), text: l.explanation) }
    }
}

// ---------------------------------------------------------------- 3. server

private struct ServerChoice: View {
    @Environment(CallViewModel.self) private var vm
    @State private var testing = false

    var body: some View {
        let c = vm.setupChoices
        VStack(alignment: .leading, spacing: 14) {
            TextField("Server address", text: Binding(
                get: { vm.setupChoices.serverUrl },
                set: { v in vm.updateSetup { $0.serverUrl = v; $0.server = .notChecked } }))
                .font(.body)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(14)
                .frame(minHeight: 52)
                .background(Theme.card, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.muted.opacity(0.5), lineWidth: 1))
                .accessibilityLabel("Server address")
                .accessibilityIdentifier("setupUrl")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ServerPage.presets, id: \.0) { p in
                        let on = c.serverUrl == p.1
                        Button { vm.updateSetup { $0.serverUrl = p.1; $0.server = .notChecked } } label: {
                            Text(p.0).font(.body)
                                .foregroundStyle(on ? Theme.onBrand : Theme.brand)
                                .padding(.horizontal, 16)
                                .frame(minHeight: 44)
                                .background(on ? Theme.brand : Theme.brandContainer, in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                }
            }
            Button {
                testing = true
                let url = c.serverUrl
                Task {
                    // iOS asks for Local Network here, the first time the app reaches the Mac.
                    let ok = await vm.testSetupServer(url)
                    vm.updateSetup { $0.server = ok ? .connected : .cantReach }
                    testing = false
                }
            } label: {
                Text("Test connection").font(.body.weight(.semibold)).foregroundStyle(Theme.brand)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .overlay(Capsule().stroke(Theme.brand, lineWidth: 1.5))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(testing)
            .accessibilityIdentifier("setupTest")
            if let st = status(c) {
                StatusChip(text: st.0, color: st.1)
                    .frame(maxWidth: .infinity)
                    .accessibilityAddTraits(.updatesFrequently)
            }
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "info.circle.fill").foregroundStyle(Theme.brand).accessibilityHidden(true)
                Text(Onboarding.networkHeadsUp).font(.body).foregroundStyle(Theme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func status(_ c: Onboarding.Choices) -> (String, Color)? {
        if testing { return ("Checking…", Theme.muted) }
        switch c.server {
        case .connected: return ("Connected ✓", Theme.safeGreen)
        case .cantReach: return ("Can't reach", Theme.scamRed)
        default: return nil
        }
    }
}

// ---------------------------------------------------------------- 4. data

private struct DataChoices: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        VStack(spacing: 0) {
            toggle("Save the riskiest sentence", "Keep one line from warned calls",
                   Binding(get: { vm.setupChoices.saveSentence }, set: { v in vm.updateSetup { $0.saveSentence = v } }))
            Divider().padding(.leading, 16)
            toggle("Help improve detection", "Keep labels on this phone",
                   Binding(get: { vm.setupChoices.helpImprove }, set: { v in vm.updateSetup { $0.helpImprove = v } }))
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }

    private func toggle(_ title: String, _ line: String, _ on: Binding<Bool>) -> some View {
        Toggle(isOn: on) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                Text(line).font(.body).foregroundStyle(Theme.muted)
            }
        }
        .tint(Theme.brand)
        .padding(16)
    }
}

// ---------------------------------------------------------------- 5. done

private struct SetupSummary: View {
    let choices: Onboarding.Choices

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(Onboarding.summary(choices).enumerated()), id: \.offset) { i, row in
                if i > 0 { Divider().padding(.leading, 16) }
                KeyValueRow(key: row.0, value: row.1).padding(16)
            }
        }
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 20))
    }
}
