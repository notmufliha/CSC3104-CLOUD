import SwiftUI

// Port of android-app ui/IncomingCallScreen.kt and ui/InCallScreen.kt.

struct IncomingCallView: View {
    let callerName: String
    let callerNumber: String
    let onAnswer: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack {
            Text("Incoming call").font(.headline).foregroundStyle(.secondary)
            Spacer()
            Text(callerName).font(.system(size: 40, weight: .semibold)).multilineTextAlignment(.center)
            Text(callerNumber).font(.title2).foregroundStyle(.secondary).padding(.top, 12)
            Spacer()
            HStack {
                Spacer()
                actionButton("Decline", "xmark", Theme.scamRed, onDecline)
                Spacer()
                actionButton("Answer", "phone.fill", Theme.safeGreen, onAnswer)
                Spacer()
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }

    private func actionButton(_ label: String, _ icon: String, _ color: Color, _ action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(color, in: Circle())
            }
            .accessibilityLabel(label)
            Text(label).font(.subheadline)
        }
    }
}

/// 4.4 In-call screen: risk bar, chips, banner, live transcript. 4.5 red alert pops over the top.
struct InCallView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var alertDismissed = false

    var body: some View {
        let s = vm.state
        ZStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("On call · Unknown caller").font(.headline).foregroundStyle(.secondary)
                    Spacer()
                    PrivacyBadge(level: s.privacyLevel)
                }
                RiskBar(level: s.level, score: s.combinedScore)
                SignalChips(lit: s.lit)
                // The yellow / grey banner lives in the layout; the red one is the pop-up until it is dismissed.
                if s.level != .red || alertDismissed || !s.scamAlert, let b = s.banner {
                    InfoBanner(text: b, color: Theme.levelColor(s.level))
                }
                Transcript(lines: s.lines)
                Button { vm.hangUp() } label: {
                    Label("Hang up", systemImage: "phone.down.fill")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.scamRed)
                // iOS has no Back button: Android's Back during a call = discard (not saved, nothing kept).
                Button { vm.discard() } label: {
                    Text("Discard call").frame(maxWidth: .infinity, minHeight: 30)
                }
                .buttonStyle(.bordered)
                .accessibilityHint("Ends the call without a summary; nothing is saved.")
            }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(.systemBackground))

            if s.scamAlert && !alertDismissed {
                ScamAlert(text: s.banner ?? "Likely scam.", onHangUp: { vm.hangUp() }, onDismiss: {
                    withAnimation { alertDismissed = true }
                })
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.3), value: s.scamAlert)
    }
}

struct RiskBar: View {
    let level: Risk.Level
    let score: Float?

    var body: some View {
        let color = Theme.levelColor(level)
        let hasVerdict = level != .listening && level != .error
        // always a sliver once there is a verdict
        let target = hasVerdict ? CGFloat(min(max(score ?? 0, 0.08), 1)) : 0
        VStack(spacing: 6) {
            HStack {
                Text(Theme.levelLabel(level)).fontWeight(.semibold).foregroundStyle(color)
                Spacer()
                // An old score next to "Could not check" would read like a current result, so hide it.
                if hasVerdict, let score { Text("risk " + String(format: "%.2f", score)).font(.caption) }
            }
            ZStack(alignment: .leading) {
                Rectangle().fill(Color(.systemGray5))
                Rectangle().fill(color).scaleEffect(x: target, y: 1, anchor: .leading)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 14)
            .clipShape(Capsule())
            .animation(.easeInOut(duration: 0.3), value: target)
            .animation(.easeInOut(duration: 0.3), value: level)
        }
        .accessibilityElement(children: .combine)
    }
}

struct SignalChips: View {
    let lit: [String]

    var body: some View {
        HStack(spacing: 8) {
            ForEach(Risk.signals, id: \.self) { signal in
                let on = lit.contains(signal)
                Text(Risk.chipLabel(signal))
                    .font(.subheadline.weight(on ? .bold : .regular))
                    .foregroundStyle(on ? Theme.scamRed : Color.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(on ? Theme.scamRed.opacity(0.15) : Color.clear, in: Capsule())
                    .overlay(Capsule().stroke(on ? Theme.scamRed : Theme.neutralGrey, lineWidth: 1))
                    .accessibilityLabel(Risk.chipLabel(signal) + (on ? ", flagged" : ""))
            }
        }
    }
}

/// The privacy level this call runs at, as a small pill.
struct PrivacyBadge: View {
    let level: PrivacyLevel

    var body: some View {
        Text(level.badge)
            .font(.caption)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .overlay(Capsule().stroke(Color.secondary, lineWidth: 1))
            .accessibilityLabel("Privacy level: \(level.badge)")
    }
}

struct InfoBanner: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.subheadline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(color, lineWidth: 1))
    }
}

struct ScamAlert: View {
    let text: String
    let onHangUp: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 28))
                Text("WARNING").font(.title2.bold())
            }
            Text(text).font(.headline)
            HStack(spacing: 8) {
                Button(action: onHangUp) {
                    Text("Hang up now").fontWeight(.semibold).foregroundStyle(Theme.scamRed)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(Color.white, in: Capsule())
                }
                Button(action: onDismiss) {
                    Text("Dismiss").foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 10)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.scamRed.shadow(.drop(radius: 8)))
        .accessibilityElement(children: .contain)
    }
}

/// Chat view: Speaker 1 (caller) on the left, Speaker 2 (you) on the right.
struct Transcript: View {
    let lines: [ChatLine]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(lines) { ChatBubble(line: $0).id($0.seq) }
                }
                .padding(.vertical, 4)
            }
            .onChange(of: lines.count) { _, _ in
                if let last = lines.last { withAnimation { proxy.scrollTo(last.seq, anchor: .bottom) } }
            }
        }
    }
}

/// riskyHere = B.5: one of the lines where the end-of-call review says "the conversation turned risky here".
struct ChatBubble: View {
    let line: ChatLine
    var riskyHere = false

    var body: some View {
        let flagged = !line.flags.isEmpty
        VStack(alignment: line.isCaller ? .leading : .trailing, spacing: 3) {
            Text(line.isCaller ? "Speaker 1 · Caller" : "Speaker 2 · You")
                .font(.caption2).foregroundStyle(.secondary)
            Text(line.text)
                .padding(12)
                .background(line.isCaller ? Color(.secondarySystemBackground) : Theme.brand.opacity(0.18),
                            in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16)
                    .stroke(Theme.scamRed, lineWidth: flagged || riskyHere ? 2 : 0))
                .frame(maxWidth: 300, alignment: line.isCaller ? .leading : .trailing)
            if riskyHere {
                Text("the conversation turned risky here").font(.caption2.weight(.semibold)).foregroundStyle(Theme.scamRed)
            }
            if let note = line.cloudNote {
                Text(note).font(.caption2).foregroundStyle(Theme.brand)
            }
            if line.pending {
                Text("checking…").font(.caption2).foregroundStyle(Theme.neutralGrey)
            } else if line.failed {
                Text("could not check").font(.caption2).foregroundStyle(Theme.neutralGrey)
            } else if flagged {
                Text("flagged: " + line.flags.map(Risk.chipLabel).joined(separator: ", "))
                    .font(.caption2).foregroundStyle(Theme.scamRed)
            }
        }
        .frame(maxWidth: .infinity, alignment: line.isCaller ? .leading : .trailing)
    }
}
