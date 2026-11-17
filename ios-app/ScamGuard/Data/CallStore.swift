import Foundation
import SwiftData

// Port of android-app data/db/CallDatabase.kt (Room, version 3) to SwiftData: the same 4 tables and fields.
// Lives only on the phone; nothing is uploaded. Room's composite keys ((callId, seq), (callId, source)) become a
// unique `key` column, since SwiftData's #Unique needs iOS 18 and this app targets iOS 17.

/// One saved call (the dashboard row).
@Model
final class CallRecord {
    /// The call_id sent to the gateway (a UUID).
    @Attribute(.unique) var id: String
    var startedAt: Int64
    var endedAt: Int64
    /// SAFE | UNSURE | SCAM | NOT CHECKED: what the summary showed.
    var finalVerdict: String
    /// The verdict on the bar when the call ended.
    var liveVerdict: String?
    var topReason: String
    var callScore: Float?
    /// Signals that fired during the call: "urgency", "money", "impersonation".
    var signalsFired: [String]
    /// "bert" | "fallback" | "unavailable" | "on_device"
    var review: String
    /// The caller line that scored highest. Nil unless the user allowed saving it.
    var riskiestSentence: String?
    /// The privacy level the call ran at (PrivacyLevel id).
    var privacyLevel: String = "cloud"
    /// The top reason as a code (Reasons), shown in the server's wording.
    var reasonCode: String?
    /// The call's chart points; deleted with the call (ON DELETE CASCADE).
    @Relationship(deleteRule: .cascade, inverse: \ScorePoint.call) var points: [ScorePoint] = []

    init(_ r: CallRow) {
        id = r.id; startedAt = r.startedAt; endedAt = r.endedAt; finalVerdict = r.finalVerdict
        liveVerdict = r.liveVerdict; topReason = r.topReason; callScore = r.callScore; signalsFired = r.signalsFired
        review = r.review; riskiestSentence = r.riskiestSentence; privacyLevel = r.privacyLevel; reasonCode = r.reasonCode
    }

    var row: CallRow {
        CallRow(id: id, startedAt: startedAt, endedAt: endedAt, finalVerdict: finalVerdict, liveVerdict: liveVerdict,
                topReason: topReason, callScore: callScore, signalsFired: signalsFired, review: review,
                riskiestSentence: riskiestSentence, privacyLevel: privacyLevel, reasonCode: reasonCode)
    }
}

/// One caller line's scores, for the risk-over-time chart. Numbers only. Primary key (callId, seq).
@Model
final class ScorePoint {
    @Attribute(.unique) var key: String
    var callId: String
    var seq: Int
    var secondsIntoCall: Float
    /// The call's combined score after this line.
    var combined: Float?
    /// This line's own scores; nil = that check did not answer.
    var urgency: Float?
    var money: Float?
    var impersonation: Float?
    var call: CallRecord?

    init(_ p: PointRow) {
        key = "\(p.callId)#\(p.seq)"; callId = p.callId; seq = p.seq; secondsIntoCall = p.secondsIntoCall
        combined = p.combined; urgency = p.urgency; money = p.money; impersonation = p.impersonation
    }

    var row: PointRow {
        PointRow(callId: callId, seq: seq, secondsIntoCall: secondsIntoCall, combined: combined, urgency: urgency,
                 money: money, impersonation: impersonation)
    }
}

/// G.12: a label the phone inferred from what the user did (1 = likely scam, 0 = false alarm). Unique (callId, source).
@Model
final class ImplicitLabel {
    @Attribute(.unique) var key: String
    /// Room's autoincrement id: the next number, given out by CallStore.
    var id: Int
    var callId: String
    var label: Int
    var source: String
    var time: Int64

    init(id: Int, callId: String, label: Int, source: String, time: Int64) {
        key = "\(callId)#\(source)"; self.id = id; self.callId = callId; self.label = label; self.source = source
        self.time = time
    }
}

/// G.12/G.13: a labelled call's caller line, masked. Kept only while "Help improve detection" is on. Key (callId, seq).
@Model
final class LabelSentence {
    @Attribute(.unique) var key: String
    var callId: String
    var seq: Int
    var text: String

    init(callId: String, seq: Int, text: String) {
        key = "\(callId)#\(seq)"; self.callId = callId; self.seq = seq; self.text = text
    }
}

/// Port of android-app data/CallHistory.kt + the DAOs: the dashboard's history, on the phone only.
@MainActor
final class CallStore {
    let container: ModelContainer
    var context: ModelContext { container.mainContext }

    static let schema = Schema([CallRecord.self, ScorePoint.self, ImplicitLabel.self, LabelSentence.self])

    init(inMemory: Bool = false) throws {
        let config = ModelConfiguration("calls", schema: Self.schema, isStoredInMemoryOnly: inMemory)
        container = try ModelContainer(for: Self.schema, configurations: [config])
    }

    // ------------------------------------------------------------ calls

    /// One call and its chart points, all or nothing. An existing id is left alone (Room's OnConflict IGNORE).
    func save(_ call: CallRow, points: [PointRow]) {
        guard self.call(call.id) == nil else { return }
        let rec = CallRecord(call)
        context.insert(rec)
        for p in points {
            let sp = ScorePoint(p)
            context.insert(sp)
            sp.call = rec
        }
        try? context.save()
    }

    func calls() -> [CallRow] {
        let d = FetchDescriptor<CallRecord>(sortBy: [SortDescriptor(\.endedAt, order: .reverse)])
        return ((try? context.fetch(d)) ?? []).map(\.row)
    }

    func call(_ id: String) -> CallRecord? {
        var d = FetchDescriptor<CallRecord>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    func points(_ callId: String) -> [PointRow] {
        let d = FetchDescriptor<ScorePoint>(predicate: #Predicate { $0.callId == callId }, sortBy: [SortDescriptor(\.seq)])
        return ((try? context.fetch(d)) ?? []).map(\.row)
    }

    func pointCount() -> Int { (try? context.fetchCount(FetchDescriptor<ScorePoint>())) ?? 0 }

    /// "Save the riskiest sentence" turned off: forget the ones already saved too.
    func forgetSentences() {
        for c in (try? context.fetch(FetchDescriptor<CallRecord>())) ?? [] where c.riskiestSentence != nil {
            c.riskiestSentence = nil
        }
        try? context.save()
    }

    /// "Delete my history": every call, chart point, implicit label and stored sentence (all 4 tables).
    func deleteHistory() {
        for m in (try? context.fetch(FetchDescriptor<ScorePoint>())) ?? [] { context.delete(m) }
        for m in (try? context.fetch(FetchDescriptor<CallRecord>())) ?? [] { context.delete(m) }
        for m in (try? context.fetch(FetchDescriptor<ImplicitLabel>())) ?? [] { context.delete(m) }
        for m in (try? context.fetch(FetchDescriptor<LabelSentence>())) ?? [] { context.delete(m) }
        try? context.save()
    }

    // ------------------------------------------------------------ G.12 labels

    func labels() -> [ImplicitLabel] {
        (try? context.fetch(FetchDescriptor<ImplicitLabel>(sortBy: [SortDescriptor(\.time), SortDescriptor(\.id)]))) ?? []
    }

    func labelCount() -> Int { (try? context.fetchCount(FetchDescriptor<ImplicitLabel>())) ?? 0 }

    func sentences(_ callId: String) -> [LabelSentence] {
        let d = FetchDescriptor<LabelSentence>(predicate: #Predicate { $0.callId == callId }, sortBy: [SortDescriptor(\.seq)])
        return (try? context.fetch(d)) ?? []
    }

    func sentenceCount() -> Int { (try? context.fetchCount(FetchDescriptor<LabelSentence>())) ?? 0 }

    /// Inserts a label unless (callId, source) is already there. Returns whether it was added.
    @discardableResult
    func insertLabel(callId: String, label: Int, source: String, time: Int64) -> Bool {
        let key = "\(callId)#\(source)"
        var d = FetchDescriptor<ImplicitLabel>(predicate: #Predicate { $0.key == key })
        d.fetchLimit = 1
        if let found = try? context.fetch(d), !found.isEmpty { return false }
        var last = FetchDescriptor<ImplicitLabel>(sortBy: [SortDescriptor(\.id, order: .reverse)])
        last.fetchLimit = 1
        let next = ((try? context.fetch(last).first?.id) ?? 0) + 1
        context.insert(ImplicitLabel(id: next, callId: callId, label: label, source: source, time: time))
        try? context.save()
        return true
    }

    /// G.12: keep these labels for a call. The caller's lines are stored with them (masked) only while
    /// "Help improve detection" is on; otherwise just the labels.
    func recordLabels(callId: String, labels: [ImplicitLabels.Label], callerLines: [(Int, String)], helpImprove: Bool) {
        guard !callId.isEmpty, !labels.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let added = labels.filter { insertLabel(callId: callId, label: $0.label, source: $0.source, time: now) }.count
        guard added > 0, helpImprove else { return }
        for (seq, text) in callerLines where !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let key = "\(callId)#\(seq)"
            var d = FetchDescriptor<LabelSentence>(predicate: #Predicate { $0.key == key })
            d.fetchLimit = 1
            if let found = try? context.fetch(d), !found.isEmpty { continue }
            context.insert(LabelSentence(callId: callId, seq: seq, text: Mask.mask(text)))
        }
        try? context.save()
    }

    /// "Help improve detection" turned off: delete the stored sentences; the labels (numbers only) stay.
    func forgetLabelSentences() {
        for m in (try? context.fetch(FetchDescriptor<LabelSentence>())) ?? [] { context.delete(m) }
        try? context.save()
    }
}
