import Foundation

/// Debug builds only (Settings → Help & about → Load sample history): about 30 days of made-up calls, so the dashboard
/// can be demoed and screenshotted. Every id starts with "sample-"; "Delete my history" removes them like any call.
///
/// Deterministic and integer-only (a fixed linear congruential generator), and kept line for line in step with
/// android-app logic/SampleHistory.kt, so both apps build exactly the same history from the same seed.
nonisolated enum SampleHistory {
    static let idPrefix = "sample-"
    private static let hour: Int64 = 3_600_000
    private static let minute: Int64 = 60_000

    struct History: Equatable, Sendable {
        var calls: [CallRow]
        var points: [PointRow]
        var labels: [LabelRow]
    }

    private struct Rng {
        var s: Int64
        mutating func next() -> Int {
            s = (s * 1_103_515_245 + 12_345) & 0x7fff_ffff
            return Int(s)
        }
        mutating func int(_ n: Int) -> Int { next() % n }
    }

    /// `todayStartMs` = local midnight today; calls later than `nowMs` are left out.
    static func generate(nowMs: Int64, todayStartMs: Int64, seed: Int64 = 42) -> History {
        var rng = Rng(s: seed)
        var calls: [CallRow] = []
        var points: [PointRow] = []
        var labels: [LabelRow] = []
        for d in 0..<30 {
            let n = 1 + rng.int(3)
            for k in 0..<n {
                let h = 8 + rng.int(14)
                let m = rng.int(60)
                let endedAt = todayStartMs - Int64(d) * DashboardStats.dayMs + Int64(h) * hour + Int64(m) * minute
                let durationS = 30 + rng.int(121)
                let roll = rng.int(100)
                let final: String = roll < 55 ? "SAFE" : roll < 75 ? "UNSURE" : roll < 95 ? "SCAM" : "NOT CHECKED"
                let p = rng.int(100)
                let privacy: String = p < 60 ? "cloud" : p < 85 ? "hybrid" : "on_device"
                let lines = 4 + rng.int(6)
                var sig: [String] = []
                if final == "SCAM" {
                    if rng.int(100) < 70 { sig.append("money") }
                    if rng.int(100) < 60 { sig.append("impersonation") }
                    if rng.int(100) < 50 { sig.append("urgency") }
                    if sig.isEmpty { sig.append("money") }
                } else if final == "UNSURE" {
                    sig.append(Risk.signals[rng.int(3)])
                }
                var live = final
                if final == "SAFE" && rng.int(100) < 15 { live = "UNSURE" }
                let id = "\(idPrefix)\(d)-\(k)"
                var callScore: Float?
                if final != "NOT CHECKED" {
                    for i in 0..<lines {
                        let base: Int
                        switch final {
                        case "SAFE": base = 50 + rng.int(250)
                        case "UNSURE": base = 250 + rng.int(250)
                        default: base = min(990, 200 + i * 700 / (lines - 1) + rng.int(100))
                        }
                        let sc: [Float] = Risk.signals.map { s in Float(sig.contains(s) ? 500 + rng.int(450) : rng.int(300)) / 1000 }
                        let combined = Float(base) / 1000
                        points.append(PointRow(callId: id, seq: 2 * i + 1,
                                               secondsIntoCall: Float((i + 1) * durationS) / Float(lines),
                                               combined: combined, urgency: sc[0], money: sc[1], impersonation: sc[2]))
                        callScore = combined
                    }
                }
                let code: String
                switch final {
                case "SAFE": code = Reasons.none
                case "NOT CHECKED": code = Reasons.serverNotReached
                case "UNSURE": code = sig[0]
                default: code = rng.int(100) < 20 ? Reasons.bert : ["money", "impersonation", "urgency"].first { sig.contains($0) }!
                }
                calls.append(CallRow(
                    id: id, startedAt: endedAt - Int64(durationS) * 1000, endedAt: endedAt, finalVerdict: final,
                    liveVerdict: live, topReason: Reasons.text(code) ?? "", callScore: callScore,
                    signalsFired: Risk.signals.filter { sig.contains($0) },
                    review: final == "NOT CHECKED" ? "unavailable" : privacy == "cloud" ? "bert" : "on_device",
                    riskiestSentence: nil, privacyLevel: privacy, reasonCode: code))
                if final == "SCAM" && rng.int(100) < 40 {
                    labels.append(LabelRow(callId: id, label: 1, source: ImplicitLabels.sourceHungUp, time: endedAt))
                }
                if final == "SAFE" && live == "UNSURE" && rng.int(100) < 50 {
                    labels.append(LabelRow(callId: id, label: 0, source: ImplicitLabels.sourceFine, time: endedAt))
                }
            }
        }
        // a call later today than "now" hasn't happened yet
        let keep = Set(calls.filter { $0.endedAt <= nowMs }.map(\.id))
        return History(calls: calls.filter { keep.contains($0.id) }, points: points.filter { keep.contains($0.callId) },
                       labels: labels.filter { keep.contains($0.callId) })
    }
}
