import SwiftUI

// Port of android-app ui/IncomingCallScreen.kt and ui/InCallScreen.kt.

struct IncomingCallView: View {
    let callerName: String
    let callerNumber: String
    let onAnswer: () -> Void
    let onDecline: () -> Void

    var body: some View {
        VStack {
            Text(L.s(.call_incoming)).font(.headline).foregroundStyle(Theme.muted)
            Spacer()
            Text(callerName).font(.system(size: 40, weight: .semibold)).multilineTextAlignment(.center)
            Text(callerNumber).font(.title2).foregroundStyle(Theme.muted).padding(.top, 12)
            Spacer()
            HStack {
                Spacer()
                actionButton(L.s(.btn_decline), "xmark", Theme.scamRed, onDecline)
                Spacer()
                actionButton(L.s(.btn_answer), "phone.fill", Theme.safeGreen, onAnswer)
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

    var body: some View {
        InCallContent(state: vm.state, onHangUp: { vm.hangUp() }, onDiscard: { vm.discard() }, showCareful: vm.showBeCareful,
                      simple: vm.simpleMode)
    }
}

/// The in-call screen itself, for a real call or the tour's pretend one (demo: nothing can be tapped). The red
/// warning is drawn over it by CallFlowView. showCareful = false: a "Be careful" moment shows as "Listening…" (it is
/// still saved as "Be careful").
struct InCallContent: View {
    let state: CallUiState
    let onHangUp: () -> Void
    let onDiscard: () -> Void
    var demo = false
    var showCareful = true
    /// Simple mode: no technical words (no Cloud / Hybrid / On-device), no score, no chips, no notes.
    var simple = false

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let s = state
        VStack(spacing: 12) {
            if typeSize.isAccessibilitySize {
                // Very large text: everything scrolls as one page (the status stays at the top).
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        status(s)
                        LazyVStack(spacing: 8) { ForEach(s.lines) { ChatBubble(line: $0, showNotes: !simple) } }
                    }
                }
            } else {
                status(s)
                Transcript(lines: s.lines, showNotes: !simple)
            }
            Button(action: onHangUp) {
                Label(L.s(.btn_hang_up), systemImage: "phone.down.fill")
                    .font(.title3.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.scamRed)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            // iOS has no Back button: Android's Back during a call = discard (not saved, nothing kept).
            Button(action: onDiscard) {
                Text(L.s(.btn_discard_call)).font(.body).frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.bordered)
            .accessibilityHint(L.s(.a11y_discard_hint))
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        }
        .padding(16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color(.systemBackground))
        .allowsHitTesting(!demo)
    }

    @ViewBuilder
    private func status(_ s: CallUiState) -> some View {
        let shown = Risk.shown(s.level, showCareful: showCareful)
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(L.s(.call_on_call)).font(.headline).foregroundStyle(Theme.muted)
                Spacer()
                // Developer view's playback speed, when it isn't 1×
                if let tag = s.speedTag { SpeedTag(text: tag) }
                if !simple { PrivacyBadge(level: s.privacyLevel) }
            }
            // Capped so the yellow or red message below stays on the first screen at the largest text sizes.
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            RiskBar(level: shown, score: s.combinedScore, showScore: !simple).tourTarget(.riskBar)
                .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            if !simple { SignalChips(lit: s.lit).tourTarget(.warningChips) }
            switch shown {
            case .yellow: CarefulBanner(kind: s.warningKind)
            case .red:
                if !s.warningOpen {
                    StateBanner(level: .red, text: L.s(.warn_title) + ". " + L.s(Warnings.message(s.warningKind)))
                }
            case .error: StateBanner(level: .error, text: L.s(.error_banner))
            default: EmptyView()
            }
        }
    }
}

struct RiskBar: View {
    let level: Risk.Level
    let score: Float?
    var showScore = true

    var body: some View {
        let color = Theme.levelColor(level)
        let hasVerdict = level != .listening && level != .error
        // always a sliver once there is a verdict
        let target = hasVerdict ? CGFloat(min(max(score ?? 0, 0.08), 1)) : 0
        VStack(spacing: 6) {
            RiskBadge(level: level, word: Theme.levelLabel(level))
                .frame(maxWidth: .infinity, alignment: .leading)
            ZStack(alignment: .leading) {
                Rectangle().fill(Color(.systemGray5))
                Rectangle().fill(color).scaleEffect(x: target, y: 1, anchor: .leading)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 14)
            .clipShape(Capsule())
            .animation(.easeInOut(duration: 0.3), value: target)
            .animation(.easeInOut(duration: 0.3), value: level)
            // Under the bar, so the word above never has to squeeze. An old score next to "Could not check" would
            // read like a current result, so it is hidden then.
            if hasVerdict && showScore, let score {
                Text(L.s(.call_risk_score, String(format: "%.2f", score))).font(.caption).foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct SignalChips: View {
    let lit: [String]

    var body: some View {
        // Wraps onto more lines with big text instead of shrinking it.
        FlowLayout(spacing: 8, lineSpacing: 8) {
            ForEach(Risk.signals, id: \.self) { signal in
                let on = lit.contains(signal)
                Text(Risk.chipLabel(signal))
                    .font(.subheadline.weight(on ? .bold : .regular))
                    .foregroundStyle(on ? Color.primary : Theme.muted)
                    .padding(.horizontal, 12).padding(.vertical, 6)
                    .background(on ? Theme.scamRed.opacity(0.15) : Color.clear, in: Capsule())
                    .overlay(Capsule().stroke(on ? Theme.scamRed : Theme.neutralGrey, lineWidth: on ? 2 : 1))
                    .accessibilityLabel(on ? L.s(.a11y_flagged_suffix, Risk.chipLabel(signal)) : Risk.chipLabel(signal))
            }
        }
    }
}

/// The privacy level this call runs at, as a small pill.
/// Developer view: "2× speed" / "4× speed" / "Instant" on the call screen.
struct SpeedTag: View {
    let text: String
    var body: some View {
        Text(text).font(.caption.weight(.semibold))
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Theme.brandContainer, in: Capsule())
            .accessibilityLabel("Playback \(text)")
            .accessibilityIdentifier("speedTag")
    }
}

struct PrivacyBadge: View {
    let level: PrivacyLevel

    var body: some View {
        Text(level.badge)
            .font(.caption)
            .foregroundStyle(Theme.muted)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .overlay(Capsule().stroke(Color.secondary, lineWidth: 1))
            .accessibilityLabel(L.s(.a11y_privacy_badge, level.badge))
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

/// Chat view: Speaker 1 (caller) on the left, Speaker 2 (you) on the right.
struct Transcript: View {
    let lines: [ChatLine]
    var showNotes = true

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(lines) { ChatBubble(line: $0, showNotes: showNotes).id($0.seq) }
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
    var showNotes = true

    var body: some View {
        let flagged = !line.flags.isEmpty
        VStack(alignment: line.isCaller ? .leading : .trailing, spacing: 3) {
            Text(L.s(line.isCaller ? .call_caller : .call_you))
                .font(.caption).foregroundStyle(Theme.muted)
            Text(line.text)
                .padding(12)
                .background(line.isCaller ? Color(.secondarySystemBackground) : Theme.brand.opacity(0.18),
                            in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16)
                    .stroke(Theme.scamRed, lineWidth: flagged || riskyHere ? 2 : 0))
                .frame(maxWidth: 300, alignment: line.isCaller ? .leading : .trailing)
            if riskyHere {
                Text(L.s(.call_risky_here)).font(.caption.weight(.semibold)).foregroundStyle(Color.primary)
            }
            if showNotes, let note = line.cloudNote {
                Text(noteText(note)).font(.caption).foregroundStyle(Theme.brand)
            }
            if line.pending {
                Text(L.s(.call_checking)).font(.caption).foregroundStyle(Theme.muted)
            } else if line.failed {
                Text(L.s(.risk_could_not_check)).font(.caption).foregroundStyle(Theme.muted)
            } else if flagged {
                Text(L.s(.call_flagged, line.flags.map(Risk.chipLabel).joined(separator: ", ")))
                    .font(.caption.weight(.semibold)).foregroundStyle(Color.primary)
            }
        }
        .frame(maxWidth: .infinity, alignment: line.isCaller ? .leading : .trailing)
    }
}


/// A bubble's note ("checked with cloud"…) in the app's wording; the view model keeps the English constants.
func noteText(_ note: String) -> String {
    switch note {
    case CallViewModel.cloudChecked: return L.s(.note_cloud_checked)
    case CallViewModel.cloudUnavailable: return L.s(.note_cloud_unavailable)
    case CallViewModel.mobileBertChecked: return L.s(.note_mb_checked)
    case CallViewModel.mobileBertFailed: return L.s(.note_mb_failed)
    default: return note
    }
}
