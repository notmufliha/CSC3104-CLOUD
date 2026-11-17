import Charts
import SwiftUI

// Dashboard charts (Swift Charts), matching the Android app's Compose Canvas charts (android-app ui/Charts.kt):
// same colours, rounded bars, donut style, legends below. Every chart has an accessibility label with the numbers.

struct Slice: Identifiable {
    let label: String
    let value: Int
    let color: Color
    var id: String { label }
}

/// Coloured dots with "Label N", wrapping onto more lines.
struct Legend: View {
    let slices: [Slice]

    var body: some View {
        FlowLayout(spacing: 16, lineSpacing: 4) {
            ForEach(slices) { s in
                HStack(spacing: 8) {
                    Circle().fill(s.color).frame(width: 12, height: 12)
                    Text("\(s.label) \(s.value)").font(.body)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A simple wrapping layout (like Compose's FlowRow).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8
    var lineSpacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, lineH: CGFloat = 0, maxX: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += lineH + lineSpacing; lineH = 0 }
            x += s.width + spacing
            maxX = max(maxX, x - spacing)
            lineH = max(lineH, s.height)
        }
        return CGSize(width: proposal.width ?? maxX, height: y + lineH)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, lineH: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX { x = bounds.minX; y += lineH + lineSpacing; lineH = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            lineH = max(lineH, s.height)
        }
    }
}

/// Donut with the total in the middle.
struct DonutChart: View {
    let slices: [Slice]
    let centerLabel: String
    var size: CGFloat = 180
    var compact = false

    var body: some View {
        let total = slices.reduce(0) { $0 + $1.value }
        ZStack {
            Chart(slices.filter { $0.value > 0 }) { s in
                SectorMark(angle: .value("Calls", s.value), innerRadius: .ratio(0.68), angularInset: 1.5)
                    .foregroundStyle(s.color)
            }
            .chartLegend(.hidden)
            if total == 0 { Circle().stroke(Theme.track, lineWidth: size * 0.16).padding(size * 0.08) }
            VStack(spacing: 0) {
                Text("\(total)").font(compact ? .title2.bold() : .largeTitle.bold())
                if !compact { Text(centerLabel).font(.body).foregroundStyle(Theme.muted) }
            }
        }
        .frame(width: compact ? size : nil, height: size)
        .frame(maxWidth: compact ? nil : .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(centerLabel): \(total). " + slices.map { "\($0.label) \($0.value)" }.joined(separator: ", "))
    }
}

/// Vertical bars, each stacked from the bottom in the slice order.
struct StackedBarChart: View {
    let bars: [[Slice]]
    let description: String

    private struct Item: Identifiable {
        let id: String
        let index: Int
        let slice: Slice
    }

    var body: some View {
        let items = bars.enumerated().flatMap { i, b in
            b.enumerated().map { j, s in Item(id: "\(i)-\(j)", index: i, slice: s) }
        }
        Chart(items) { it in
            BarMark(x: .value("Day", "\(it.index)"), y: .value("Calls", it.slice.value), width: .ratio(0.62))
                .foregroundStyle(it.slice.color)
                .clipShape(RoundedRectangle(cornerRadius: 3))
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartXScale(domain: (0..<max(bars.count, 1)).map { "\($0)" })
        .frame(height: 160)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}

/// Horizontal bars: label on the left above the bar, count on the right.
struct HorizontalBars: View {
    let rows: [(String, Int)]
    let color: Color
    let description: String

    var body: some View {
        let maxV = max(rows.map(\.1).max() ?? 0, 1)
        VStack(spacing: 10) {
            ForEach(rows.indices, id: \.self) { i in
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(rows[i].0).font(.body)
                        Spacer()
                        Text("\(rows[i].1)").font(.body.bold())
                    }
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.track)
                            Capsule().fill(color).frame(width: g.size.width * CGFloat(rows[i].1) / CGFloat(maxV))
                        }
                    }
                    .frame(height: 12)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}

/// One bar split into coloured parts, with a legend.
struct SegmentedBar: View {
    let slices: [Slice]
    let description: String

    var body: some View {
        let total = slices.reduce(0) { $0 + $1.value }
        VStack(alignment: .leading, spacing: 10) {
            GeometryReader { g in
                let parts = slices.filter { $0.value > 0 }
                let gaps = CGFloat(max(parts.count - 1, 0)) * 2
                HStack(spacing: 2) {
                    ForEach(parts) { s in
                        Rectangle().fill(s.color)
                            .frame(width: total == 0 ? 0 : (g.size.width - gaps) * CGFloat(s.value) / CGFloat(total))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.track)
                .clipShape(Capsule())
            }
            .frame(height: 24)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(description)
            Legend(slices: slices)
        }
    }
}

/// A line of values (nil = no point) on a 0-1 scale, dashed 0.45 / 0.6 thresholds, points coloured by level;
/// `marker` circles the riskiest moment; `extraLines` are thin dashed lines (the call's signals).
struct RiskLineChart: View {
    let values: [Float?]
    let description: String
    var extraLines: [([Float?], Color)] = []
    var marker: Int?
    var height: CGFloat = 170

    private struct P: Identifiable {
        let id: String
        let x: Int
        let y: Double
        let series: String
    }

    static func dotColor(_ v: Double) -> Color {
        v >= 0.6 ? Theme.scamRed : v >= 0.45 ? Theme.warnYellow : Theme.safeGreen
    }

    var body: some View {
        let main = values.enumerated().compactMap { i, v in v.map { P(id: "m\(i)", x: i, y: Double($0), series: "risk") } }
        let extras = extraLines.enumerated().flatMap { k, line in
            line.0.enumerated().compactMap { i, v in v.map { P(id: "e\(k)-\(i)", x: i, y: Double($0), series: "e\(k)") } }
        }
        Chart {
            RuleMark(y: .value("Suspicious", 0.45)).foregroundStyle(Theme.warnYellow)
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
            RuleMark(y: .value("Likely scam", 0.6)).foregroundStyle(Theme.scamRed)
                .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [5, 4]))
            ForEach(extras) { p in
                LineMark(x: .value("Point", p.x), y: .value("Score", p.y), series: .value("Signal", p.series))
                    .foregroundStyle(extraLines[Int(p.series.dropFirst())!].1)
                    .lineStyle(StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
            }
            ForEach(main) { p in
                LineMark(x: .value("Point", p.x), y: .value("Score", p.y), series: .value("Signal", "risk"))
                    .foregroundStyle(Theme.brand)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round))
            }
            if let m = marker, m < values.count, let v = values[m] {
                RuleMark(x: .value("Riskiest", m)).foregroundStyle(Color.primary.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [5, 4]))
                PointMark(x: .value("Riskiest", m), y: .value("Score", Double(v)))
                    .symbol(Circle().strokeBorder(lineWidth: 2))
                    .symbolSize(320)
                    .foregroundStyle(Color.primary)
            }
            ForEach(main) { p in
                PointMark(x: .value("Point", p.x), y: .value("Score", p.y))
                    .foregroundStyle(Self.dotColor(p.y))
                    .symbolSize(60)
            }
        }
        .chartYScale(domain: 0...1)
        .chartXScale(domain: 0...max(values.count - 1, 1))
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .frame(height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}

/// 24 small bars, one per hour of the day.
struct HourChart: View {
    let hours: [Int]
    let color: Color
    let description: String

    var body: some View {
        VStack(spacing: 4) {
            Chart(Array(hours.enumerated()), id: \.offset) { h, n in
                BarMark(x: .value("Hour", "\(h)"), y: .value("Calls", n), width: .ratio(0.66))
                    .foregroundStyle(color)
                    .clipShape(RoundedRectangle(cornerRadius: 3))
            }
            .chartXScale(domain: (0..<24).map { "\($0)" })
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .frame(height: 110)
            HStack(spacing: 0) {
                ForEach(["12 am", "6 am", "12 pm", "6 pm"], id: \.self) {
                    Text($0).font(.body).foregroundStyle(Theme.muted).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}

/// Dots of (seconds, risk 0-1), coloured by level.
struct ScatterChart: View {
    let points: [(Int, Double)]
    let description: String

    var body: some View {
        Chart(Array(points.enumerated()), id: \.offset) { _, p in
            PointMark(x: .value("Length", p.0), y: .value("Risk", p.1))
                .foregroundStyle(RiskLineChart.dotColor(p.1).opacity(0.85))
                .symbolSize(70)
        }
        .chartYScale(domain: 0...1)
        .chartXScale(domain: 0...max(points.map(\.0).max() ?? 1, 1))
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .frame(height: 150)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description)
    }
}

/// A tiny risk line for a past-call row.
struct Sparkline: View {
    let values: [Float]

    var body: some View {
        Canvas { ctx, size in
            guard !values.isEmpty else { return }
            let pad: CGFloat = 2
            func x(_ i: Int) -> CGFloat { values.count == 1 ? size.width / 2 : pad + CGFloat(i) * (size.width - 2 * pad) / CGFloat(values.count - 1) }
            func y(_ v: Float) -> CGFloat { size.height - pad - CGFloat(min(max(v, 0), 1)) * (size.height - 2 * pad) }
            var path = Path()
            for (i, v) in values.enumerated() {
                if i == 0 { path.move(to: CGPoint(x: x(i), y: y(v))) } else { path.addLine(to: CGPoint(x: x(i), y: y(v))) }
            }
            ctx.stroke(path, with: .color(Theme.brand), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            let last = values[values.count - 1]
            let c = RiskLineChart.dotColor(Double(last))
            ctx.fill(Path(ellipseIn: CGRect(x: x(values.count - 1) - 3, y: y(last) - 3, width: 6, height: 6)), with: .color(c))
        }
        .frame(width: 64, height: 26)
        .accessibilityHidden(true)
    }
}

/// A short muted line under a chart.
struct ChartCaption: View {
    let text: String
    var body: some View { Text(text).font(.body).foregroundStyle(Theme.muted) }
}
