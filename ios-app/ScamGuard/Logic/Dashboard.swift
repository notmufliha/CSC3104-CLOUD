import Foundation

/// A value copy of one saved call (CallRecord), so the dashboard logic can be tested without SwiftData.
nonisolated struct CallRow: Equatable, Sendable {
    var id: String
    var startedAt: Int64
    var endedAt: Int64
    var finalVerdict: String
    var liveVerdict: String?
    var topReason: String
    var callScore: Float?
    var signalsFired: [String]
    var review: String
    var riskiestSentence: String?
    var privacyLevel: String = "cloud"
    var reasonCode: String?

    var displayReason: String { Reasons.display(reasonCode: reasonCode, topReason: topReason) }
}

/// A value copy of one ScorePoint (one caller line's scores).
nonisolated struct PointRow: Equatable, Sendable {
    var callId: String
    var seq: Int
    var secondsIntoCall: Float
    var combined: Float?
    var urgency: Float?
    var money: Float?
    var impersonation: Float?
}

/// Port of android-app logic/Dashboard.kt: the dashboard's numbers.
nonisolated enum Dashboard {
    private static let warnings: Set<String> = ["SCAM", "UNSURE"]

    /// The aggregator's default thresholds, drawn on the chart.
    static let unsureLine: Float = 0.45
    static let scamLine: Float = 0.6

    /// A saved verdict -> the colour level used everywhere else ("NOT CHECKED" -> grey).
    static func level(_ verdict: String) -> Risk.Level {
        ["SAFE", "UNSURE", "SCAM"].contains(verdict) ? Risk.level(verdict, nil) : .error
    }

    /// (seconds into the call, combined score 0-1), in time order; lines with no score left out.
    static func chart(_ points: [PointRow]) -> [(Float, Float)] {
        points.compactMap { p in p.combined.map { (p.secondsIntoCall, min(max($0, 0), 1)) } }
            .enumerated().sorted { a, b in a.element.0 != b.element.0 ? a.element.0 < b.element.0 : a.offset < b.offset }
            .map { $0.element }
    }

    /// "Most common warning reasons": the top `n` reasons (by the shown wording) of calls that ended with a warning.
    static func topReasons(_ calls: [CallRow], n: Int = 3) -> [(String, Int)] {
        var counts: [String: Int] = [:]
        for c in calls where warnings.contains(c.finalVerdict) {
            let r = c.displayReason
            if !r.trimmingCharacters(in: .whitespaces).isEmpty { counts[r, default: 0] += 1 }
        }
        return counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
            .prefix(n).map { ($0.key, $0.value) }
    }

    /// A line's strongest signal score (nil = no check answered).
    static func lineMax(_ p: PointRow) -> Float? {
        [p.urgency, p.money, p.impersonation].compactMap { $0 }.max()
    }

    /// The seq of the riskiest caller line: the highest single-signal score, the later line on a tie. Nil when no
    /// line reached Risk.signalOn.
    static func riskiestSeq(_ points: [PointRow]) -> Int? {
        var best: (seq: Int, v: Float)?
        for p in points {
            guard let v = lineMax(p), v >= Risk.signalOn else { continue }
            if best == nil || v > best!.v || (v == best!.v && p.seq > best!.seq) { best = (p.seq, v) }
        }
        return best?.seq
    }
}
