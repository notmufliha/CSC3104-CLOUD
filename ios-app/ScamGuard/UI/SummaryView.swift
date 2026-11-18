import SwiftUI

let scamShieldURL = URL(string: "https://www.scamshield.gov.sg/")!

/// Port of Android's SummaryScreen (4.6 + B.5 + H.3): leads with the final verdict (else the live one), the live
/// verdict small underneath; "Final review…" while the end-of-call answer is on its way (up to 3 s).
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
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Final review…").font(.headline)
                        }
                        Text("Checking the whole call once more.").font(.footnote)
                        Spacer()
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    summary(s)
                }
            }
            .navigationTitle("Call summary")
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
                reportResult = "Nothing was shared."
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
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(Theme.levelLabel(level).uppercased())
                    .font(.title.bold())
                    .foregroundStyle(Theme.levelColor(level))
                Text(s.duringCallLine).font(.footnote).foregroundStyle(.secondary)
                if let reason = s.summaryReason {
                    InfoBanner(text: reason, color: Theme.levelColor(level))
                } else if let b = Risk.banner(level, s.lit, s.flaggedTexts) {
                    InfoBanner(text: b, color: Theme.levelColor(level))
                }
                if s.showsTriggers {
                    Text("What triggered it").fontWeight(.semibold)
                    let risky = s.finalReview?.topWindowSeqs ?? []
                    let shown = s.triggerLines
                    if shown.isEmpty { Text("No caller lines were flagged.") }
                    ForEach(shown) { ChatBubble(line: $0, riskyHere: risky.contains($0.seq)) }
                }
                Button { openURL(scamShieldURL) } label: {
                    Text("Report to ScamShield").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.scamRed)
                reportButtons(s)
                if sending { Text("Sending…").font(.footnote) }
                if let r = reportResult { Text(r).font(.footnote) }
                Button { vm.reset() } label: {
                    Text("Done").frame(maxWidth: .infinity, minHeight: 36)
                }
                .buttonStyle(.bordered)
            }
            .padding(16)
        }
    }

    /// H.3: help ScamGuard learn. Disabled once sent, so one call is shared at most once. At levels 2 and 3 calls
    /// aren't shared: no consent screen, nothing is sent, the answer is kept as a local label.
    @ViewBuilder
    private func reportButtons(_ s: CallUiState) -> some View {
        let sent = reportResult?.hasPrefix("Thank you") == true
        HStack(spacing: 8) {
            Button { share("scam", s) } label: {
                Text("This was a scam").frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.bordered)
            Button { vm.thisWasFine(); share("fine", s) } label: {
                Text("This was fine").frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.bordered)
        }
        .disabled(sent || sending)
    }

    private func share(_ kind: String, _ s: CallUiState) {
        if s.privacyLevel.cloudCall { asking = kind } else { reportResult = CallViewModel.nothingShared }
    }
}

/// Shows exactly what would leave the phone before anything is sent. Nothing is sent on "Don't share".
struct ConsentSheet: View {
    let kind: String
    let lines: [ReportLine]
    let onShare: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Helps ScamGuard learn new scams. A team member reads it, labels it, and it may be used to " +
                         "retrain the detectors.")
                    Text("Sent: the caller's \(lines.count) lines below. We try to remove numbers, amounts, links and " +
                         "names, but some can slip through, such as a name said in passing or digits read out as " +
                         "words. Check the text; if you see anything private, tap Don't share.").font(.footnote)
                    Text("Not sent: your phone number, the caller's number, anything you said.").font(.footnote)
                    Text("Kept: deleted after 30 days, except lines a reviewer approves as training examples. Those " +
                         "stay encrypted with no link to you, so they can't be withdrawn later.").font(.footnote)
                    Divider()
                    ForEach(lines, id: \.seq) { Text("“\($0.text)”").font(.footnote) }
                }
                .padding(16)
            }
            .navigationTitle(kind == "scam" ? "Share this scam call?" : "Share this call as a false alarm?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Don't share", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) { Button("Share", action: onShare).disabled(lines.isEmpty) }
            }
        }
        .interactiveDismissDisabled()
        .presentationDetents([.large])
    }
}
