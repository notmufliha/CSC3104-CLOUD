import Foundation

/// Port of android-app logic/FinalReview.kt: what the summary shows about the end of a call, built from
/// POST /v1/call/end's answer (or from nothing, when the request failed or took longer than the 3 s wait).
nonisolated struct FinalReview: Equatable, Sendable {
    /// SAFE / UNSURE / SCAM, or nil when the server didn't know the call (UNKNOWN) or wasn't reached.
    var finalVerdict: String?
    var reason: String
    /// The end-of-call review (BERT) answered. False: the summary says "Final review unavailable".
    var reviewed: Bool
    /// Caller lines where "the conversation turned risky" (bubbles to highlight).
    var topWindowSeqs: Set<Int>
    /// False when the server never answered (request failed, or the 3 s wait ran out).
    var answered: Bool = true
    /// G.12: the review's whole-call score (0-1); only meaningful when `reviewed`.
    var callScore: Float? = nil
    /// Privacy levels 2/3: the call was checked on the phone only (no end message was sent).
    var onDevice: Bool = false

    private static let known: Set<String> = ["SAFE", "UNSURE", "SCAM"]

    static let unavailable = FinalReview(finalVerdict: nil, reason: "", reviewed: false, topWindowSeqs: [], answered: false)

    static let onDeviceReview = FinalReview(
        finalVerdict: nil, reason: "", reviewed: false, topWindowSeqs: [], answered: false, onDevice: true)

    static func from(_ resp: EndCallResponse?) -> FinalReview {
        guard let resp else { return .unavailable }
        let verdict = resp.finalVerdict.flatMap { known.contains($0) ? $0 : nil }
        return FinalReview(
            finalVerdict: verdict,
            reason: resp.reason ?? "",
            reviewed: resp.review == "bert" && verdict != nil,
            topWindowSeqs: Set(resp.topWindowSeqs ?? []),
            callScore: resp.callScore
        )
    }

    /// How the dashboard records it: "bert" | "fallback" | "unavailable" | "on_device".
    var reviewType: String {
        if onDevice { return "on_device" }
        if reviewed { return "bert" }
        if answered { return "fallback" }
        return "unavailable"
    }
}

nonisolated enum Summary {
    /// The level the summary leads with: the final verdict when there is one, else the live one. A warning always
    /// wins, and a line that could not be checked beats SAFE.
    static func level(_ final: FinalReview?, _ liveVerdict: String?, _ gatewayError: String?) -> Risk.Level {
        let finalLevel = Risk.level(final?.finalVerdict ?? liveVerdict, gatewayError)
        if final?.reviewed == true { return finalLevel }
        // Never show a calmer verdict than the live one just because the review fell back to an older state.
        let live = Risk.level(liveVerdict, gatewayError)
        return severity(finalLevel) >= severity(live) ? finalLevel : live
    }

    /// Higher = more alarming. ERROR sits between GREEN and YELLOW: "not checked" is never shown as safe.
    static func severity(_ level: Risk.Level) -> Int {
        switch level {
        case .listening: return 0
        case .green: return 1
        case .error: return 2
        case .yellow: return 3
        case .red: return 4
        }
    }
}
