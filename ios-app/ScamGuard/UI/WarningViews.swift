import SwiftUI

// The red warning (full screen) and the yellow "Be careful" banner. Same layout, wording and order as Android
// ui/WarningScreen.kt.

/// What the warning's buttons do. Calls and messages open the phone's own app: the user always taps Call / Send.
struct WarningActions {
    var onHangUp: () -> Void = {}
    var onCallFamily: () -> Void = {}
    var onCall1799: () -> Void = {}
    var onTellFamily: () -> Void = {}
    var onClose: () -> Void = {}
}

/// The red warning: the whole screen, a strong red, one sentence for what happened and one for what to do, then big
/// buttons in this order: Hang up, Call my family (if a contact is set), Call 1799, Tell [name] (if set), Close.
/// It never closes by itself. demo = the tour's pretend call (no flash, nothing announced).
struct WarningScreen: View {
    let kind: Warnings.Kind
    let callEnded: Bool
    let familyName: String?
    let actions: WarningActions
    var demo = false
    @State private var flash = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let title = L.s(.warn_title)
        let message = L.s(Warnings.message(kind))
        let doLine = L.s(Warnings.doLine(kind))
        ZStack {
            Theme.scamRed.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 12) {
                        Image(systemName: "xmark")
                            .font(.system(size: 44, weight: .black))
                            .foregroundStyle(Theme.warningInk)
                            .frame(width: 84, height: 84)
                            .background(Color.white, in: Circle())
                            .accessibilityHidden(true)
                        Text(title).font(.largeTitle.bold())
                            .accessibilityAddTraits(.isHeader)
                        Text(message).font(.title2)
                        Text(doLine).font(.title2.bold())
                        if callEnded { Text(L.s(.warn_call_ended)).font(.title3) }
                    }
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                    .tourTarget(.redWarning)
                    VStack(spacing: 16) {
                        WarningButton(label: L.s(.btn_hang_up), icon: "phone.down.fill", action: actions.onHangUp)
                            .accessibilityIdentifier("warnHangUp")
                        if let familyName {
                            WarningButton(label: L.s(.btn_call_family), sub: familyName, icon: "phone.fill",
                                          hint: L.s(.a11y_ends_call_first), action: actions.onCallFamily)
                        }
                        WarningButton(label: L.s(.btn_call_1799), icon: "phone.fill", hint: L.s(.a11y_ends_call_first),
                                      action: actions.onCall1799)
                        if let familyName {
                            WarningButton(label: L.s(.btn_tell_family, familyName), icon: "paperplane.fill",
                                          hint: L.s(.a11y_opens_messages), action: actions.onTellFamily)
                        }
                        Button(action: actions.onClose) {
                            Text(L.s(.btn_close_warning))
                                .font(.title3.weight(.semibold))
                                .foregroundStyle(.white)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 16).padding(.vertical, 14)
                                .frame(maxWidth: .infinity, minHeight: 64)
                                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.white, lineWidth: 2))
                                .contentShape(RoundedRectangle(cornerRadius: 20))
                        }
                        .buttonStyle(.plain)
                    }
                    .tourTarget(.warningButtons)
                }
                .frame(maxWidth: 560)
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity)
            }
            // the flash sits on top but takes no touches
            Color.white.opacity(flash).ignoresSafeArea().allowsHitTesting(false).accessibilityHidden(true)
        }
        .accessibilityAddTraits(.isModal)
        .task {
            guard !demo else { return }
            UIAccessibility.post(notification: .screenChanged, argument: "\(title). \(message) \(doLine)")
            // A gentle flash: three soft pulses, one a second (well under the 3-a-second seizure limit). Off with
            // Reduce Motion.
            guard !reduceMotion else { return }
            for _ in 0..<3 {
                withAnimation(.easeInOut(duration: 0.3)) { flash = 0.28 }
                try? await Task.sleep(for: .milliseconds(300))
                withAnimation(.easeInOut(duration: 0.45)) { flash = 0 }
                try? await Task.sleep(for: .milliseconds(700))
            }
        }
    }
}

private struct WarningButton: View {
    let label: String
    var sub: String?
    let icon: String
    var hint: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon).font(.system(size: 30, weight: .semibold)).frame(width: 36)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.title2.bold())
                    if let sub { Text(sub).font(.title3) }
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .foregroundStyle(Theme.warningInk)
            .padding(.horizontal, 20).padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 20))
            .contentShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        .accessibilityHint(hint ?? "")
    }
}

// ---------------------------------------------------------------- risk badge: colour + icon + word, everywhere

/// A filled circle in the risk colour with ✓ / ! / ✕ / i, so the meaning never depends on colour alone.
struct RiskIcon: View {
    let level: Risk.Level
    var size: CGFloat = 28

    var body: some View {
        let (fill, glyph, symbol): (Color, Color, String) = {
            switch level {
            case .green: return (Theme.safeGreen, .white, "checkmark")
            case .yellow: return (Theme.warnYellow, Theme.onYellow, "exclamationmark")
            case .red: return (Theme.scamRed, .white, "xmark")
            case .listening: return (Theme.badgeGrey, .white, "phone.fill")
            case .error: return (Theme.badgeGrey, .white, "info")
            }
        }()
        Image(systemName: symbol)
            .font(.system(size: size * 0.5, weight: .black))
            .foregroundStyle(glyph)
            .frame(width: size, height: size)
            .background(fill, in: Circle())
            .accessibilityHidden(true)
    }
}

/// Icon + word, read as one item by VoiceOver.
struct RiskBadge: View {
    let level: Risk.Level
    var word: String?
    var size: CGFloat = 28
    var font: Font = .title2.bold()

    var body: some View {
        HStack(spacing: 10) {
            RiskIcon(level: level, size: size)
            Text(word ?? Theme.riskWord(level)).font(font).foregroundStyle(Color.primary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The yellow "Be careful" banner: small, in the call screen's layout, no sound, no pop-up.
struct CarefulBanner: View {
    let kind: Warnings.Kind

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark")
                .font(.system(size: 17, weight: .black))
                .foregroundStyle(Theme.warnYellow)
                .frame(width: 32, height: 32)
                .background(Theme.onYellow, in: Circle())
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(L.s(.risk_careful)).font(.title3.bold())
                Text(L.s(Warnings.careful(kind))).font(.body)
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Theme.onYellow)
        .padding(14)
        .background(Theme.warnYellow, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }
}

/// Banner for the other states (could not check, or a red warning the user closed).
struct StateBanner: View {
    let level: Risk.Level
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            RiskIcon(level: level, size: 32)
            Text(text).font(.body).foregroundStyle(Color.primary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(14)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.levelColor(level), lineWidth: 2))
        .accessibilityElement(children: .combine)
    }
}

/// Opens the phone app / Messages. Never calls or sends by itself: the user taps Call / Send.
enum PhoneLinks {
    static let helpline = "1799"

    static func telURL(_ number: String) -> URL? {
        URL(string: "tel:" + number.filter { $0.isNumber || $0 == "+" })
    }

    static func smsURL(_ phone: String, body: String) -> URL? {
        var c = URLComponents()
        c.scheme = "sms"
        c.path = phone.filter { $0.isNumber || $0 == "+" }
        c.queryItems = [URLQueryItem(name: "body", value: body)]
        return c.url
    }
}
