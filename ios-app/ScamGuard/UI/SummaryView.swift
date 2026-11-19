import SwiftUI

let scamShieldURL = URL(string: "https://www.scamshield.gov.sg/")!

/// Port of Android's SummaryScreen (4.6 + B.5): leads with the final result (else the live one), in plain
/// words; "Checking the whole call once more…" while the end-of-call answer is on its way (up to 3 s).
struct SummaryView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
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
                // After a Scam or "Be careful" call: what to do now (nothing extra after a safe call)
                let afterCard = AfterCall.shows(level, showCareful: vm.showBeCareful)
                if afterCard { AfterCallCard(state: s) }
                if s.showsTriggers && !hiddenYellow {
                    Text(L.s(.sum_triggered)).font(.title3.weight(.semibold))
                    let risky = s.finalReview?.topWindowSeqs ?? []
                    let shown = s.triggerLines
                    if shown.isEmpty { Text(L.s(.sum_no_lines)).font(.body) }
                    ForEach(shown) { ChatBubble(line: $0, riskyHere: risky.contains($0.seq), showNotes: !vm.simpleMode) }
                }
                BigButton(title: L.s(.btn_done), id: "summaryDone") { vm.reset() }
                // The card above already has "Report to ScamShield"; other calls keep the plain button.
                if !afterCard {
                    BigButton(title: L.s(.btn_report_scamshield), id: "summaryScamShield", prominent: false, danger: true) { openURL(scamShieldURL) }
                }
            }
            .padding(16)
        }
    }
}
