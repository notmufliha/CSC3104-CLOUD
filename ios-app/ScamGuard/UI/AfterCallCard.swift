import SwiftUI
import UIKit

// After a Scam or "Be careful" call: what to do now, in big buttons. Same order, wording and look as
// android-app ui/AfterCallCard.kt. Nothing here is uploaded: the summary only goes to the clipboard.

struct AfterCallCard: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
    let state: CallUiState
    @State private var copied = false
    @State private var sheet: Sheet?
    @State private var noApp = false

    enum Sheet: String, Identifiable { case block, shared; var id: String { rawValue } }

    var body: some View {
        let level = state.summaryLevel
        VStack(alignment: .leading, spacing: 12) {
            Text(L.s(.after_title)).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            // 1. Report: copy a masked summary first, then open ScamShield's site
            Button {
                let reason = L.s(level == .red ? Warnings.message(state.warningKind) : Warnings.careful(state.warningKind))
                let result = L.s(level == .red ? .risk_scam : .risk_careful)
                let text = AfterCall.reportSummary(timeMs: Int64(Date().timeIntervalSince1970 * 1000), result: result, reason: reason,
                                                   lines: state.triggerLines.map(\.text), includeLines: vm.saveSentence)
                UIPasteboard.general.setItems([[UIPasteboard.typeAutomatic: text]], options: [.localOnly: true])
                copied = true
                UIAccessibility.post(notification: .announcement, argument: L.s(.after_copied))
                openURL(scamShieldURL)
            } label: {
                Text(L.s(.after_report)).font(.title3.weight(.semibold)).foregroundStyle(.white)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .background(Theme.scamRed, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(PressFeedbackStyle())
            .accessibilityIdentifier("afterReport")
            Text(L.s(copied ? .after_copied : .after_report_line))
                .font(copied ? .body.weight(.semibold) : .body)
                .foregroundStyle(copied ? Theme.brand : Theme.muted)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier(copied ? "afterCopied" : "afterReportLine")
            // 2. Call 1799 (the phone app asks before calling)
            BigButton(title: L.s(.btn_call_1799), id: "after1799", prominent: false) { call1799() }
            // 3. Tell my family (only with a trusted contact)
            if let f = vm.family {
                BigButton(title: L.s(.after_tell_family), id: "afterFamily", prominent: false) {
                    if let u = PhoneLinks.smsURL(f.phone, body: vm.familyMessage()) { openURL(u) { ok in if !ok { noApp = true } } }
                    else { noApp = true }
                }
            }
            // 4. How to block, 5. If I already shared details
            BigButton(title: L.s(.after_block), id: "afterBlock", prominent: false) { sheet = .block }
            BigButton(title: L.s(.after_shared), id: "afterShared", prominent: false) { sheet = .shared }
            if noApp { Text(L.s(.no_phone_app)).font(.body) }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 24))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("afterCallCard")
        .sheet(item: $sheet) { s in
            switch s {
            case .block:
                StepsSheet(title: L.s(.block_title), intro: nil, steps: AfterCall.blockSteps.map { L.s($0) })
            case .shared:
                StepsSheet(title: L.s(.shared_title), intro: L.s(.shared_line), steps: AfterCall.sharedSteps.map { L.s($0) },
                           action: (L.s(.btn_call_1799), { call1799() }))
            }
        }
    }

    private func call1799() {
        guard let u = PhoneLinks.telURL(PhoneLinks.helpline) else { noApp = true; return }
        openURL(u) { ok in if !ok { noApp = true } }
    }
}

/// A sheet with numbered steps, an optional big action and a big Close button. Text only.
struct StepsSheet: View {
    let title: String
    let intro: String?
    let steps: [String]
    var action: (String, () -> Void)?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                if let intro { Text(intro).font(.body) }
                ForEach(Array(steps.enumerated()), id: \.offset) { i, s in
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text("\(i + 1).").font(.title3.bold()).foregroundStyle(Theme.brand).frame(minWidth: 28, alignment: .leading)
                        Text(s).font(.body).fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .combine)
                }
                if let action { BigButton(title: action.0, id: "sheetAction", action: action.1) }
                BigButton(title: L.s(.btn_close), id: "sheetClose", prominent: false) { dismiss() }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityIdentifier("stepsSheet")
        .presentationDetents([.large])
    }
}
