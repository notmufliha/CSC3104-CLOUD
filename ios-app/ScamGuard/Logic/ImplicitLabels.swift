import Foundation

/// Port of android-app logic/ImplicitLabels.kt (G.12): labels the phone infers from what the user did, kept only
/// in the local database. Nothing is uploaded. label 1 = likely scam, 0 = false alarm.
nonisolated enum ImplicitLabels {
    /// Hung up this soon after the red alert first appeared -> likely scam.
    static let hangUpWindowMs: Int64 = 10_000
    /// End-of-call review at least this sure either way -> use its answer as the label.
    static let confidentScam: Float = 0.9
    static let confidentSafe: Float = 0.1

    static let sourceHungUp = "hung_up_after_alert"
    static let sourceFine = "this_was_fine"
    static let sourceReview = "final_review"

    struct Label: Equatable, Sendable {
        let label: Int
        let source: String
    }

    /// Labels known when the call ends.
    /// - hungUp: the user ended the call (Hang up / Hang up now), not the script running out
    /// - alertAt: when the red alert first showed (ms), nil if it never did
    /// - review: only a real review (`reviewed`) counts, never the live-verdict fallback
    static func atEnd(hungUp: Bool, alertAt: Int64?, endedAt: Int64, review: FinalReview?) -> [Label] {
        var out: [Label] = []
        if hungUp, let alertAt, (0...hangUpWindowMs).contains(endedAt - alertAt) {
            out.append(Label(label: 1, source: sourceHungUp))
        }
        if let review, review.reviewed, let score = review.callScore {
            if score > confidentScam { out.append(Label(label: 1, source: sourceReview)) }
            else if score < confidentSafe { out.append(Label(label: 0, source: sourceReview)) }
        }
        return out
    }

    /// "This was fine" on the summary: a false alarm, but only if the call ended with a warning.
    static func thisWasFine(_ summaryLevel: Risk.Level) -> Label? {
        summaryLevel == .red || summaryLevel == .yellow ? Label(label: 0, source: sourceFine) : nil
    }
}
