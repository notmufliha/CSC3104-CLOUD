import SwiftUI

// Dashboard (one question: "Am I safe, and what happened recently?"), Insights (the detailed charts), History (every
// call) and Call detail. Everything comes from the database on this phone; nothing is uploaded. Same layout, wording
// and numbers as android-app ui/DashboardScreens.kt.

/// Shared spacing, so every screen uses the same rhythm (the same values on Android).
enum Space {
    static let page: CGFloat = 20
    static let card: CGFloat = 20
    static let gap: CGFloat = 16
    static let corner: CGFloat = 24
}

/// Where the Dashboard tab can navigate.
enum DashboardRoute: Hashable {
    case call(String)
    case insights
    case history
}

private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

private func format(_ ms: Int64, _ pattern: String) -> String {
    let f = DateFormatter()
    f.dateFormat = pattern
    return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
}

private func dayLabel(_ daysAgo: Int) -> String {
    switch daysAgo {
    case 0: return "Today"
    case 1: return "Yesterday"
    default:
        let f = DateFormatter()
        f.dateFormat = "d MMM"
        return f.string(from: Calendar.current.date(byAdding: .day, value: -daysAgo, to: Date())!)
    }
}

/// "12 min ago", "Yesterday", or the date for older calls.
func whenText(_ endedAt: Int64) -> String {
    let now = nowMs()
    return PlainWords.timeAgo(endedAt, now, DashboardStats.daysAgo(endedAt, now, .current)) ?? format(endedAt, "d MMM")
}

struct VerdictDot: View {
    let verdict: String
    var size: CGFloat = 14

    var body: some View {
        Circle().fill(Theme.levelColor(Dashboard.level(verdict))).frame(width: size, height: size)
    }
}

/// The one card style used everywhere.
struct AppCard<Content: View>: View {
    var color: Color = Theme.card
    var padding: CGFloat = Space.card
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(color, in: RoundedRectangle(cornerRadius: Space.corner))
    }
}

/// The ⓘ sheet: 1-2 sentences.
struct InfoSheet: View {
    let title: String
    let text: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title2.weight(.semibold))
            ScrollView { Text(text).font(.body).frame(maxWidth: .infinity, alignment: .leading) }
            HStack { Spacer(); Button("OK") { dismiss() }.font(.body).frame(minWidth: 44, minHeight: 44) }
        }
        .padding(24)
        .presentationDetents([.medium, .large])
    }
}

struct PeriodSwitcher: View {
    @Binding var periodRaw: String

    var body: some View {
        Picker("Period", selection: $periodRaw) {
            ForEach(DashboardStats.Period.allCases) { Text($0.label).tag($0.rawValue) }
        }
        .pickerStyle(.segmented)
        .controlSize(.large)
    }
}

struct EmptyState: View {
    let text: String
    var goHome: (() -> Void)?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "phone.fill")
                .font(.system(size: 44))
                .foregroundStyle(Theme.brand)
                .frame(width: 104, height: 104)
                .background(Theme.brandContainer, in: Circle())
                .accessibilityHidden(true)
            Text(text).font(.title2).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            if let goHome {
                Button(action: goHome) { Text("Go to Home").font(.body).padding(.horizontal, 12).frame(minHeight: 44) }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(Theme.onBrand)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}

/// One call on one line: dot, Safe / Be careful / Scam, optional sparkline, when, chevron. At least 56 pt tall.
struct CallListRow: View {
    let call: CallRow
    var spark: [Float]?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // Very large text: no sparkline and no fixed time column, so the row never grows wider than the screen.
        let big = typeSize.isAccessibilitySize
        HStack(spacing: 10) {
            VerdictDot(verdict: call.finalVerdict, size: 16)
            if big {
                VStack(alignment: .leading, spacing: 2) {
                    Text(PlainWords.verdict(call.finalVerdict)).font(.headline)
                    Text(whenText(call.endedAt)).font(.body).foregroundStyle(Theme.muted)
                }
                Spacer(minLength: 4)
            } else {
                Text(PlainWords.verdict(call.finalVerdict)).font(.headline).lineLimit(1).layoutPriority(1)
                Spacer(minLength: 4)
                if let spark { Sparkline(values: spark) }
                Text(whenText(call.endedAt)).font(.body).foregroundStyle(Theme.muted)
                    .lineLimit(1)
                    .frame(minWidth: 96, alignment: .trailing)
            }
            Image(systemName: "chevron.right").foregroundStyle(Theme.muted)
        }
        .padding(.horizontal, Space.card)
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(PlainWords.verdict(call.finalVerdict)), \(whenText(call.endedAt))")
        .accessibilityAddTraits(.isButton)
    }
}

/// An icon in a soft circle of its colour.
private struct IconBadge: View {
    let systemName: String
    let color: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 22, weight: .semibold))
            .foregroundStyle(color)
            .frame(width: 48, height: 48)
            .background(color.opacity(0.16), in: Circle())
            .accessibilityHidden(true)
    }
}

private func resultSlices(_ s: DashboardStats.Stats) -> [Slice] {
    [Slice(label: "Safe", value: s.safe, color: Theme.safeGreen),
     Slice(label: "Be careful", value: s.unsure, color: Theme.warnYellow),
     Slice(label: "Scam", value: s.scam, color: Theme.scamRed)]
}

// ---------------------------------------------------------------- main dashboard (4 cards)

struct DashboardView: View {
    @Environment(CallViewModel.self) private var vm
    @AppStorage("dashboardPeriod") private var periodRaw = DashboardStats.Period.week.rawValue
    var goHome: () -> Void = {}

    var body: some View {
        let _ = vm.historyVersion          // re-read the database after every change
        let period = DashboardStats.Period(rawValue: periodRaw) ?? .week
        let calls = vm.store.calls()
        let stats = DashboardStats.compute(calls, vm.store.labelRows(), period, nowMs: nowMs(), tz: .current)
        ScrollView {
            VStack(alignment: .leading, spacing: Space.gap) {
                if calls.isEmpty {
                    EmptyState(text: "No calls yet. Try a demo call from Home.", goHome: goHome)
                        .tourTarget(.dashboardHero)
                } else {
                    PeriodSwitcher(periodRaw: $periodRaw)
                    hero(stats, period).tourTarget(.dashboardHero)
                    recent(calls)
                    topWarning(stats, period)
                    NavigationLink(value: DashboardRoute.insights) {
                        AppCard(color: Theme.brandContainer) {
                            HStack(spacing: 14) {
                                IconBadge(systemName: "star.fill", color: Theme.brand)
                                Text("See more insights").font(.title2.weight(.semibold))
                                Spacer(minLength: 4)
                                Image(systemName: "chevron.right")
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(Space.page)
        }
        .background(Theme.background)
        .navigationTitle("Dashboard")
        .navigationDestination(for: DashboardRoute.self) { route in
            switch route {
            case .call(let id): CallDetailView(callId: id)
            case .insights: InsightsView()
            case .history: HistoryView(goHome: goHome)
            }
        }
    }

    private func hero(_ stats: DashboardStats.Stats, _ period: DashboardStats.Period) -> some View {
        AppCard {
            Text(PlainWords.hero(stats.scam, stats.total, period))
                .font(.largeTitle.bold())
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 20) {
                DonutChart(slices: resultSlices(stats), centerLabel: "calls", size: 112, compact: true)
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(resultSlices(stats)) { s in
                        HStack(spacing: 8) {
                            Circle().fill(s.color).frame(width: 12, height: 12)
                            Text("\(s.value) \(s.label)").font(.body)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func recent(_ calls: [CallRow]) -> some View {
        AppCard(padding: 0) {
            HStack {
                Text("Recent calls").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                NavigationLink(value: DashboardRoute.history) { Text("See all").font(.body).frame(minHeight: 44) }
            }
            .padding(.horizontal, Space.card)
            .padding(.top, 8)
            VStack(spacing: 0) {
                ForEach(Array(calls.prefix(3).enumerated()), id: \.element.id) { i, c in
                    if i > 0 { Divider().padding(.leading, 50) }
                    NavigationLink(value: DashboardRoute.call(c.id)) { CallListRow(call: c) }.buttonStyle(.plain)
                }
            }
            .padding(.bottom, 8)
        }
    }

    private func topWarning(_ stats: DashboardStats.Stats, _ period: DashboardStats.Period) -> some View {
        let top = PlainWords.topWarning(stats)
        return NavigationLink(value: DashboardRoute.insights) {
            AppCard {
                Text("Top warning sign").font(.title2.weight(.semibold))
                HStack(spacing: 14) {
                    IconBadge(systemName: top == nil ? "checkmark.circle.fill" : "exclamationmark.triangle.fill",
                              color: top == nil ? Theme.safeGreen : Theme.scamRed)
                    Text(top ?? "No warning signs \(PlainWords.periodPhrase(period))").font(.headline)
                        .multilineTextAlignment(.leading)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.right").foregroundStyle(Theme.muted)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// ---------------------------------------------------------------- Insights

/// One Insights section: header + ⓘ, the takeaway sentence, then the chart in a card.
private struct InsightSection<Content: View>: View {
    let title: String
    let takeaway: String
    let info: String
    @ViewBuilder let content: Content
    @State private var showInfo = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button { showInfo = true } label: {
                    Image(systemName: "info.circle.fill").font(.title3).foregroundStyle(Theme.brand).frame(width: 44, height: 44)
                }
                .accessibilityLabel("About \(title)")
            }
            Text(takeaway).font(.body)
            AppCard { content }
        }
        .id(title)
        .sheet(isPresented: $showInfo) { InfoSheet(title: title, text: info) }
    }
}

private struct AxisEnds: View {
    let left: String
    let right: String

    var body: some View {
        HStack { Text(left); Spacer(); Text(right) }.font(.body).foregroundStyle(Theme.muted)
    }
}

/// A colour key without counts.
private struct Key: View {
    let slices: [Slice]

    var body: some View {
        FlowLayout(spacing: 16, lineSpacing: 4) {
            ForEach(slices) { s in
                HStack(spacing: 8) {
                    Circle().fill(s.color).frame(width: 12, height: 12)
                    Text(s.label).font(.body)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

private struct BigNumber: View {
    let value: String
    let label: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.largeTitle.bold()).foregroundStyle(color)
            Text(label).font(.body).foregroundStyle(Theme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

struct InsightsView: View {
    @Environment(CallViewModel.self) private var vm
    @AppStorage("dashboardPeriod") private var periodRaw = DashboardStats.Period.week.rawValue

    var body: some View {
        let _ = vm.historyVersion
        let period = DashboardStats.Period(rawValue: periodRaw) ?? .week
        let s = DashboardStats.compute(vm.store.calls(), vm.store.labelRows(), period, nowMs: nowMs(), tz: .current)
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    PeriodSwitcher(periodRaw: $periodRaw)
                    if s.isEmpty {
                        EmptyState(text: "No calls \(PlainWords.periodPhrase(period)).")
                    } else {
                        sections(s)
                    }
                }
                .padding(Space.page)
            }
            #if DEBUG
            .onAppear {   // screenshots: -demoScroll "<section title>"
                if let t = UserDefaults.standard.string(forKey: "demoScroll") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { proxy.scrollTo(t, anchor: .top) }
                }
            }
            #endif
        }
        .background(Theme.background)
        .navigationTitle("Insights")
    }

    @ViewBuilder
    private func sections(_ s: DashboardStats.Stats) -> some View {
        let first = dayLabel(s.buckets.first!.daysAgo)
        let last = s.weekly ? "This week" : "Today"
        InsightSection(title: "Calls over time", takeaway: PlainWords.overTime(s),
                       info: "Each bar is one \(s.weekly ? "week" : "day"). The colours show safe calls, calls that needed care, and scams.") {
            StackedBarChart(bars: s.buckets.map {
                [Slice(label: "Safe", value: $0.safe, color: Theme.safeGreen), Slice(label: "Be careful", value: $0.unsure, color: Theme.warnYellow),
                 Slice(label: "Scam", value: $0.scam, color: Theme.scamRed), Slice(label: "Not checked", value: $0.notChecked, color: Theme.neutralGrey)]
            }, description: "Calls over time: " + s.buckets.map { "\(dayLabel($0.daysAgo)) \($0.total)" }.joined(separator: ", "))
            AxisEnds(left: first, right: last)
            Key(slices: resultSlices(s))
        }
        if !s.reasons.isEmpty {
            InsightSection(title: "Why calls were flagged", takeaway: PlainWords.reasons(s),
                           info: "The main reason behind each warning or scam, most common first.") {
                HorizontalBars(rows: s.reasons, color: Theme.scamRed,
                               description: "Why calls were flagged: " + s.reasons.map { "\($0.0) \($0.1)" }.joined(separator: ", "))
            }
        }
        InsightSection(title: "Warning signs heard", takeaway: PlainWords.signals(s),
                       info: "How many calls had each kind of warning sign. One call can have more than one.") {
            let rows = s.signals.map { (PlainWords.signalName($0.0), $0.1) }
            HorizontalBars(rows: rows, color: Theme.brand,
                           description: "Warning signs heard: " + rows.map { "\($0.0) \($0.1)" }.joined(separator: ", "))
        }
        InsightSection(title: "Risk trend", takeaway: PlainWords.trend(s),
                       info: "The average risk of your calls over time. Above the yellow line needs care; above the red line looks like a scam.") {
            RiskLineChart(values: s.buckets.map { $0.avgRisk.map(Float.init) },
                          description: "Average risk: " + s.buckets.map {
                              "\(dayLabel($0.daysAgo)) \($0.avgRisk.map { String(format: "%.2f", $0) } ?? "none")"
                          }.joined(separator: ", "))
            AxisEnds(left: first, right: last)
        }
        InsightSection(title: "Time of day", takeaway: PlainWords.hours(s), info: "When the calls that needed care, or were scams, came in.") {
            HourChart(hours: s.riskyHours, color: Theme.scamRed, description: "Risky calls by hour: " +
                      s.riskyHours.enumerated().filter { $0.element > 0 }.map { "\($0.offset):00 \($0.element)" }.joined(separator: ", "))
        }
        InsightSection(title: "Double-checked warnings", takeaway: PlainWords.doubleCheck(s),
                       info: "When a call ends, ScamGuard checks the whole call once more. Some warnings turn out safe.") {
            SegmentedBar(slices: [Slice(label: "Confirmed", value: s.confirmed, color: Theme.scamRed),
                                  Slice(label: "Turned out safe", value: s.calmed, color: Theme.safeGreen)],
                         description: "Double-checked warnings: \(s.confirmed) confirmed, \(s.calmed) turned out safe")
        }
        InsightSection(title: "Checked where", takeaway: PlainWords.whereChecked(s),
                       info: "Cloud: checked by the ScamGuard server. Hybrid: on this phone, with server help. On-device: only on this phone.") {
            let slices = s.privacy.map { Slice(label: PrivacyLevel.badgeFor($0.0), value: $0.1, color: Theme.privacyColor($0.0)) }
            SegmentedBar(slices: slices, description: "Checked where: " + slices.map { "\($0.label) \($0.value)" }.joined(separator: ", "))
        }
        InsightSection(title: "Your answers", takeaway: PlainWords.answers(s), info: "What you told ScamGuard after calls. These stay on this phone.") {
            HStack(alignment: .top, spacing: 16) {
                BigNumber(value: "\(s.markedFine)", label: "Marked as fine", color: Theme.safeGreen)
                BigNumber(value: "\(s.hungUpAfterWarning)", label: "Hung up after a warning", color: Theme.scamRed)
            }
        }
        if !s.lengthVsRisk.isEmpty {
            InsightSection(title: "Call length", takeaway: PlainWords.length(s),
                           info: "Each dot is one call: further right is longer, higher up is riskier.") {
                ScatterChart(points: s.lengthVsRisk, description: "Call length and risk: \(s.lengthVsRisk.count) calls")
                AxisEnds(left: "Short", right: "Long")
            }
        }
    }
}

// ---------------------------------------------------------------- History

struct HistoryView: View {
    @Environment(CallViewModel.self) private var vm
    var goHome: () -> Void = {}
    @State private var filter: String?          // nil = all, else the saved result

    private static let filters: [(String?, String)] = [(nil, "All"), ("SCAM", "Scam"), ("UNSURE", "Be careful"), ("SAFE", "Safe")]

    var body: some View {
        let _ = vm.historyVersion
        let calls = vm.store.calls()
        let byCall = Dictionary(grouping: vm.store.allPoints(), by: \.callId)
        let now = nowMs()
        let shown = calls.filter { filter == nil || $0.finalVerdict == filter }
        let groups = Dictionary(grouping: shown) { PlainWords.historyGroup(DashboardStats.daysAgo($0.endedAt, now, .current)) }
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if calls.isEmpty {
                    EmptyState(text: "No calls yet. Try a demo call from Home.", goHome: goHome)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Self.filters, id: \.1) { v, label in
                                Button { filter = v } label: {
                                    Text(label).font(.body).padding(.horizontal, 16).frame(minHeight: 44)
                                        .foregroundStyle(filter == v ? Theme.onBrand : Color.primary)
                                        .background(filter == v ? Theme.brand : Theme.card, in: Capsule())
                                }
                                .accessibilityAddTraits(filter == v ? [.isSelected] : [])
                            }
                        }
                    }
                    if shown.isEmpty { Text("No calls here.").font(.body).foregroundStyle(Theme.muted) }
                    ForEach(["Today", "This week", "Earlier"], id: \.self) { g in
                        if let list = groups[g] {
                            Text(g).font(.title2.weight(.semibold)).padding(.top, 8).accessibilityAddTraits(.isHeader)
                            AppCard(padding: 0) {
                                VStack(spacing: 0) {
                                    ForEach(Array(list.enumerated()), id: \.element.id) { i, c in
                                        if i > 0 { Divider().padding(.leading, 50) }
                                        NavigationLink(value: DashboardRoute.call(c.id)) {
                                            CallListRow(call: c, spark: (byCall[c.id] ?? []).sorted { $0.seq < $1.seq }.compactMap(\.combined))
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                }
            }
            .padding(Space.page)
        }
        .background(Theme.background)
        .navigationTitle("History")
    }
}

// ---------------------------------------------------------------- Call detail

private func reviewWords(_ review: String) -> String {
    switch review {
    case "bert": return "Checked again at the end"
    case "fallback": return "Final check not available"
    case "unavailable": return "Server not reached at the end"
    case "on_device": return "Checked on this phone"
    case "migrated": return "From the old history"
    default: return review
    }
}

private func checkedShort(_ id: String) -> String {
    let t = PlainWords.checkedWhere(id).replacingOccurrences(of: "Checked ", with: "")
    return t.prefix(1).uppercased() + t.dropFirst()
}

struct CallDetailView: View {
    @Environment(CallViewModel.self) private var vm
    let callId: String
    #if DEBUG
    @State private var details = UserDefaults.standard.bool(forKey: "demoDetails")   // screenshots: -demoDetails YES
    #else
    @State private var details = false
    #endif

    var body: some View {
        let _ = vm.historyVersion
        let call = vm.store.call(callId)?.row
        let points = vm.store.points(callId)
        ScrollView {
            VStack(alignment: .leading, spacing: Space.gap) {
                if let call {
                    content(call, points)
                } else {
                    Text("This call is no longer saved.").font(.body)
                }
            }
            .padding(Space.page)
        }
        .background(Theme.background)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func content(_ call: CallRow, _ points: [PointRow]) -> some View {
        let riskiest = Dashboard.riskiestSeq(points).flatMap { s in points.firstIndex { $0.seq == s } }
        HStack(spacing: 14) {
            VerdictDot(verdict: call.finalVerdict, size: 26)
            Text(PlainWords.verdict(call.finalVerdict)).font(.system(.largeTitle, weight: .bold))
                .foregroundStyle(Theme.levelColor(Dashboard.level(call.finalVerdict)))
                .accessibilityAddTraits(.isHeader)
        }
        Text(DashboardStats.whatHappened(call.finalVerdict, call.liveVerdict, call.displayReason)).font(.title2)
        Text("\(whenText(call.endedAt)) at \(format(call.endedAt, "HH:mm"))").font(.body).foregroundStyle(Theme.muted)
        AppCard {
            Text("Risk during the call").font(.title2.weight(.semibold))
            if points.isEmpty {
                Text("No scores were saved for this call.").font(.body)
            } else {
                RiskLineChart(values: points.map(\.combined),
                              description: "Risk during the call: \(points.count) points, highest \(String(format: "%.2f", points.compactMap(\.combined).max() ?? 0))",
                              marker: riskiest)
                if riskiest != nil { ChartCaption(text: "The circle marks the riskiest moment") }
            }
        }
        if let s = call.riskiestSentence {
            AppCard {
                Text("Riskiest sentence").font(.title2.weight(.semibold))
                Text("“\(s)”").font(.body)
            }
        }
        AppCard(padding: 0) {
            Button { withAnimation { details.toggle() } } label: {
                HStack {
                    Text("Details").font(.title2.weight(.semibold))
                    Spacer()
                    Image(systemName: details ? "chevron.up" : "chevron.down")
                }
                .padding(.horizontal, Space.card)
                .frame(minHeight: 64)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(details ? "Hide details" : "Show details")
            if details {
                VStack(alignment: .leading, spacing: 12) {
                    DetailRow(key: "Length", value: DashboardStats.duration(Int(max((call.endedAt - call.startedAt) / 1000, 0))))
                    DetailRow(key: "Checked", value: checkedShort(call.privacyLevel))
                    DetailRow(key: "During the call", value: call.liveVerdict.map(PlainWords.verdict) ?? "-")
                    DetailRow(key: "Final check", value: reviewWords(call.review))
                    if !points.isEmpty {
                        Text("Warning signs (highest score out of 100)").font(.body).foregroundStyle(Theme.muted)
                        let rows: [(String, Int)] = Risk.signals.map { s in
                            let v = points.compactMap { p -> Float? in s == "urgency" ? p.urgency : s == "money" ? p.money : p.impersonation }.max() ?? 0
                            return (PlainWords.signalName(s), Int(v * 100))
                        }
                        HorizontalBars(rows: rows, color: Theme.brand,
                                       description: "Warning signs in this call: " + rows.map { "\($0.0) \($0.1)" }.joined(separator: ", "))
                    }
                }
                .padding([.horizontal, .bottom], Space.card)
            }
        }
    }
}

private struct DetailRow: View {
    let key: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(key).foregroundStyle(Theme.muted)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
        .font(.body)
        .accessibilityElement(children: .combine)
    }
}
