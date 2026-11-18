import SwiftUI

let scamShieldURL = URL(string: "https://www.scamshield.gov.sg/")!

/// Port of Android's SummaryScreen (4.6 + B.5 + H.3): leads with the final result (else the live one), in plain
/// words; "Checking the whole call once more…" while the end-of-call answer is on its way (up to 3 s).
struct SummaryView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
    /// Which button opened the consent screen ("scam" / "fine").
    @State private var asking: String?
    @State private var reportResult: String?
    @State private var sending = false

    var body: some View {
        let s = vm.state
        NavigationStack {
            Group {
                if s.reviewing {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 12) {
                            ProgressView().controlSize(.large)
                            Text(L.s(.sum_checking)).font(.title3.weight(.semibold))
                        }
                        Text(L.s(.sum_checking_line)).font(.body)
                        Spacer()
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    summary(s)
                }
            }
            .navigationTitle(L.s(.summary_title))
            .background(Theme.background)
        }
        .sheet(item: Binding(get: { asking.map(Asking.init) }, set: { asking = $0?.kind })) { a in
            ConsentSheet(kind: a.kind, lines: vm.reportPreview(), onShare: {
                asking = nil
                sending = true
                Task {
                    reportResult = await vm.sendReport(a.kind)
                    sending = false
                }
            }, onCancel: {
                asking = nil
                reportResult = L.s(.sum_not_shared)
            })
        }
    }

    private struct Asking: Identifiable {
        let kind: String
        var id: String { kind }
    }

    @ViewBuilder
    private func summary(_ s: CallUiState) -> some View {
        let level = s.summaryLevel
        let hiddenYellow = level == .yellow && !vm.showBeCareful
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if hiddenYellow {
                    RiskBadge(level: .listening, word: L.s(.risk_call_ended), size: 40, font: .title.bold())
                } else {
                    RiskBadge(level: level, size: 40, font: .title.bold())
                }
                if !vm.simpleMode { Text(s.duringCallLine).font(.body).foregroundStyle(Theme.muted) }
                // The reason in plain words (the server's English text is only a fallback on the
                // dashboard).
                if !hiddenYellow {
                    switch level {
                    case .red: StateBanner(level: .red, text: L.s(Warnings.message(s.warningKind)) + " " + L.s(Warnings.doLine(s.warningKind)))
                    case .yellow: CarefulBanner(kind: s.warningKind)
                    case .error: StateBanner(level: .error, text: L.s(.error_banner))
                    default: EmptyView()
                    }
                }
                if s.showsTriggers && !hiddenYellow {
                    Text(L.s(.sum_triggered)).font(.title3.weight(.semibold))
                    let risky = s.finalReview?.topWindowSeqs ?? []
                    let shown = s.triggerLines
                    if shown.isEmpty { Text(L.s(.sum_no_lines)).font(.body) }
                    ForEach(shown) { ChatBubble(line: $0, riskyHere: risky.contains($0.seq), showNotes: !vm.simpleMode) }
                }
                BigButton(title: L.s(.btn_done), id: "summaryDone") { vm.reset() }
                BigButton(title: L.s(.btn_report_scamshield), id: "summaryScamShield", prominent: false, danger: true) { openURL(scamShieldURL) }
                reportButtons(s)
                if sending { Text(L.s(.sum_sending)).font(.body) }
                if let r = reportResult { Text(r).font(.body).fixedSize(horizontal: false, vertical: true) }
            }
            .padding(16)
        }
    }

    /// H.3: help ScamGuard learn. Disabled once sent, so one call is shared at most once. At levels 2 and 3 calls
    /// aren't shared: no consent screen, nothing is sent, the answer is kept as a local label.
    @ViewBuilder
    private func reportButtons(_ s: CallUiState) -> some View {
        let off = vm.reportSent || sending
        VStack(spacing: 12) {
            BigButton(title: L.s(.btn_was_scam), id: "summaryScam", prominent: false) { share("scam", s) }
            BigButton(title: L.s(.btn_was_fine), id: "summaryFine", prominent: false) { vm.thisWasFine(); share("fine", s) }
        }
        .disabled(off)
        .opacity(off ? 0.5 : 1)
    }

    private func share(_ kind: String, _ s: CallUiState) {
        if s.privacyLevel.cloudCall { asking = kind } else { reportResult = CallViewModel.nothingShared }
    }
}

/// Shows exactly what would leave the phone before anything is sent: big text, a plain yes / no, nothing
/// pre-selected, both buttons the same size. Nothing is sent on "No".
struct ConsentSheet: View {
    let kind: String
    let lines: [ReportLine]
    let onShare: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(L.s(kind == "scam" ? .consent_title_scam : .consent_title_fine)).font(.title.bold())
                    .accessibilityAddTraits(.isHeader)
                Text(L.s(.consent_why)).font(.body)
                Text(L.s(.consent_sent, "\(lines.count)")).font(.body)
                Text(L.s(.consent_not_sent)).font(.body)
                Text(L.s(.consent_kept)).font(.body)
                Divider()
                ForEach(lines, id: \.seq) { Text("“\($0.text)”").font(.body) }
                BigButton(title: L.s(.btn_yes_share), id: "consentYes", prominent: false, action: onShare)
                    .disabled(lines.isEmpty)
                BigButton(title: L.s(.btn_no_dont_share), id: "consentNo", prominent: false, action: onCancel)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(24)
        }
        .interactiveDismissDisabled()
        .presentationDetents([.large])
    }
}
