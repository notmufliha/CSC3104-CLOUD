import Charts
import SwiftUI

// Port of android-app ui/DashboardScreens.kt (B.7): everything here comes from the database on this phone; nothing
// is uploaded.

private let dashboardDate: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "d MMM yyyy, HH:mm"
    return f
}()

private func date(_ ms: Int64) -> String { dashboardDate.string(from: Date(timeIntervalSince1970: Double(ms) / 1000)) }

struct VerdictDot: View {
    let verdict: String
    var size: CGFloat = 12

    var body: some View {
        Circle().fill(Theme.levelColor(Dashboard.level(verdict))).frame(width: size, height: size)
    }
}

struct DashboardView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var confirmDelete = false

    var body: some View {
        let _ = vm.historyVersion          // re-read the database after every change
        let calls = vm.store.calls()
        let labelCount = vm.store.labelCount()
        List {
            Section {
                let top = Dashboard.topReasons(calls)
                if top.isEmpty { Text("No warnings yet.").font(.footnote).foregroundStyle(.secondary) }
                ForEach(top, id: \.0) { reason, n in
                    HStack {
                        Text(reason)
                        Spacer()
                        Text("\(n)×").fontWeight(.semibold)
                    }
                }
            } header: {
                Text("Most common warning reasons")
            }

            Section {
                if calls.isEmpty { Text("No calls yet.") }
                // G.13: each row also says which privacy level the call ran at
                ForEach(calls, id: \.id) { c in
                    NavigationLink { CallDetailView(callId: c.id) } label: { CallRowView(call: c) }
                }
            } header: {
                Text("Past calls (\(calls.count))")
            }

            Section {
                Toggle(isOn: Binding(get: { vm.saveSentence }, set: { vm.setSaveSentence($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Save the riskiest sentence")
                        Text("Keeps the one caller line that scored highest in calls that ended with a warning. " +
                             "Off: no words from your calls are kept, only verdicts and scores.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                // G.13: opt-in, off by default
                Toggle(isOn: Binding(get: { vm.helpImprove }, set: { vm.setHelpImprove($0) })) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Help improve detection (federated learning)")
                        Text("The app keeps simple labels on this phone (for example, you hung up right after a " +
                             "warning, or tapped \"This was fine\"). On: the caller's lines of those calls are kept " +
                             "too, with numbers and names removed, so a future update could learn from them on " +
                             "this phone. Nothing is uploaded. Off: labels only, no words.")
                            .font(.footnote).foregroundStyle(.secondary)
                        Text("Labels kept on this phone: \(labelCount)").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Text("Your history stays on this phone. Nothing on this screen is uploaded.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Delete my history", role: .destructive) { confirmDelete = true }
                    .disabled(calls.isEmpty && labelCount == 0)
            } header: {
                Text("Privacy")
            }
        }
        .navigationTitle("Dashboard")
        .alert("Delete my history?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) { vm.deleteHistory() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Deletes every saved call on this phone: verdicts, charts, saved sentences, and the labels kept " +
                 "for improving detection. This can't be undone.")
        }
    }
}

private struct CallRowView: View {
    let call: CallRow

    var body: some View {
        HStack(spacing: 12) {
            VerdictDot(verdict: call.finalVerdict)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(call.finalVerdict).fontWeight(.bold).foregroundStyle(Theme.levelColor(Dashboard.level(call.finalVerdict)))
                    Spacer()
                    Text(date(call.endedAt)).font(.caption)
                }
                HStack(alignment: .firstTextBaseline) {
                    Text(call.displayReason).font(.subheadline)
                    Spacer()
                    Text(PrivacyLevel.badgeFor(call.privacyLevel)).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private func reviewLabel(_ review: String) -> String {
    switch review {
    case "bert": return "final review"
    case "fallback": return "final review unavailable"
    case "unavailable": return "server not reached at the end"
    case "on_device": return "checked on this phone"
    case "migrated": return "from the old history"
    default: return review
    }
}

struct CallDetailView: View {
    @Environment(CallViewModel.self) private var vm
    let callId: String

    var body: some View {
        let _ = vm.historyVersion
        let call = vm.store.call(callId)?.row
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let call {
                    HStack(spacing: 8) {
                        VerdictDot(verdict: call.finalVerdict, size: 16)
                        Text(call.finalVerdict).font(.title2.bold())
                            .foregroundStyle(Theme.levelColor(Dashboard.level(call.finalVerdict)))
                    }
                    Text(call.displayReason)
                    Text(meta(call)).font(.footnote).foregroundStyle(.secondary)
                    Text("Risk over time").fontWeight(.semibold)
                    RiskChart(points: vm.store.points(callId))
                    Text("Signals that fired").fontWeight(.semibold)
                    if call.signalsFired.isEmpty { Text("None.") } else { SignalChips(lit: call.signalsFired) }
                    if let sentence = call.riskiestSentence {
                        Text("Riskiest sentence").fontWeight(.semibold)
                        InfoBanner(text: "“\(sentence)”", color: Theme.levelColor(Dashboard.level(call.finalVerdict)))
                    }
                } else {
                    Text("This call is no longer saved.")
                }
            }
            .padding(16)
        }
        .navigationTitle("Call details")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func meta(_ c: CallRow) -> String {
        let seconds = max((c.endedAt - c.startedAt) / 1000, 0)
        var s = date(c.endedAt)
        if seconds > 0 { s += " · \(seconds / 60) min \(seconds % 60) s" }
        if let live = c.liveVerdict { s += " · during the call: \(live)" }
        return s + " · " + reviewLabel(c.review) + " · privacy: " + PrivacyLevel.badgeFor(c.privacyLevel)
    }
}

/// The call's combined score after each caller line plus the line's three signal scores (Swift Charts), with dashed
/// lines at the aggregator's UNSURE (0.45) and SCAM (0.6) thresholds. Combined points are coloured by level.
struct RiskChart: View {
    let points: [PointRow]

    private struct Mark: Identifiable {
        let id = UUID()
        let t: Float
        let v: Float
        let series: String
    }

    var body: some View {
        let combined = Dashboard.chart(points)
        if combined.isEmpty {
            Text("No scores were saved for this call.")
        } else {
            chart(combined)
        }
    }

    private func chart(_ combined: [(Float, Float)]) -> some View {
        let sorted = points.sorted { $0.secondsIntoCall < $1.secondsIntoCall }
        var marks: [Mark] = combined.map { Mark(t: $0.0, v: $0.1, series: "Combined") }
        for p in sorted {
            if let u = p.urgency { marks.append(Mark(t: p.secondsIntoCall, v: u, series: "Urgency")) }
            if let m = p.money { marks.append(Mark(t: p.secondsIntoCall, v: m, series: "Money / OTP")) }
            if let i = p.impersonation { marks.append(Mark(t: p.secondsIntoCall, v: i, series: "Impersonation")) }
        }
        let maxV = combined.map(\.1).max() ?? 0
        return Chart {
                    RuleMark(y: .value("Suspicious", Dashboard.unsureLine))
                        .foregroundStyle(Theme.warnYellow)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                    RuleMark(y: .value("Likely scam", Dashboard.scamLine))
                        .foregroundStyle(Theme.scamRed)
                        .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [6, 5]))
                    ForEach(marks) { m in
                        LineMark(x: .value("Seconds", m.t), y: .value("Score", m.v))
                            .foregroundStyle(by: .value("Signal", m.series))
                            .lineStyle(StrokeStyle(lineWidth: m.series == "Combined" ? 3 : 1.5,
                                                   dash: m.series == "Combined" ? [] : [3, 3]))
                            .interpolationMethod(.linear)
                    }
                    ForEach(combined.indices, id: \.self) { i in
                        PointMark(x: .value("Seconds", combined[i].0), y: .value("Score", combined[i].1))
                            .foregroundStyle(dotColor(combined[i].1))
                            .symbolSize(50)
                    }
                }
                .chartForegroundStyleScale([
                    "Combined": Color.primary, "Urgency": Color.orange, "Money / OTP": Color.purple,
                    "Impersonation": Color.blue,
                ])
                .chartYScale(domain: 0...1)
                .chartXAxisLabel("seconds into the call")
                .chartLegend(position: .bottom)
                .frame(height: 220)
                .accessibilityLabel("Risk over time: \(combined.count) points, highest \(String(format: "%.2f", maxV))")
    }

    private func dotColor(_ v: Float) -> Color {
        if v >= Dashboard.scamLine { return Theme.scamRed }
        if v >= Dashboard.unsureLine { return Theme.warnYellow }
        return Theme.safeGreen
    }
}
