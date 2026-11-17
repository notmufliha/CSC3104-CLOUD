import SwiftData
import XCTest
@testable import ScamGuard

/// Phase 2: the SwiftData store (port of android-app's CallDatabaseTest / LabelDatabaseTest).
@MainActor
final class StoreTests: XCTestCase {
    private func row(_ id: String, _ verdict: String = "SCAM", endedAt: Int64 = 1_000) -> CallRow {
        CallRow(id: id, startedAt: 0, endedAt: endedAt, finalVerdict: verdict, liveVerdict: verdict,
                topReason: "Asked for your OTP or money", callScore: 0.9, signalsFired: ["money", "urgency"],
                review: "bert", riskiestSentence: "Read me the OTP", privacyLevel: "hybrid", reasonCode: "money")
    }

    private func pts(_ id: String, _ n: Int) -> [PointRow] {
        (1...n).map { PointRow(callId: id, seq: $0 * 2 - 1, secondsIntoCall: Float($0), combined: 0.5, urgency: 0.1, money: nil, impersonation: 0.3) }
    }

    func testSaveReadAndNewestFirst() throws {
        let s = try CallStore(inMemory: true)
        s.save(row("a", endedAt: 1_000), points: pts("a", 3))
        s.save(row("b", "SAFE", endedAt: 2_000), points: pts("b", 2))
        XCTAssertEqual(s.calls().map(\.id), ["b", "a"])
        let a = try XCTUnwrap(s.call("a")?.row)
        XCTAssertEqual(a, row("a", endedAt: 1_000))         // every field round-trips
        XCTAssertEqual(s.points("a").map(\.seq), [1, 3, 5])
        XCTAssertNil(s.points("a")[0].money)
        XCTAssertEqual(s.pointCount(), 5)
    }

    func testTheSameCallIsSavedOnce() throws {
        let s = try CallStore(inMemory: true)
        s.save(row("a"), points: pts("a", 2))
        s.save(row("a", "SAFE"), points: pts("a", 4))     // OnConflict IGNORE
        XCTAssertEqual(s.calls().count, 1)
        XCTAssertEqual(s.calls()[0].finalVerdict, "SCAM")
        XCTAssertEqual(s.pointCount(), 2)
    }

    func testDeletingACallDeletesItsPoints() throws {
        let s = try CallStore(inMemory: true)
        s.save(row("a"), points: pts("a", 3))
        s.save(row("b"), points: pts("b", 1))
        s.context.delete(try XCTUnwrap(s.call("a")))       // ON DELETE CASCADE
        try s.context.save()
        XCTAssertEqual(s.pointCount(), 1)
        XCTAssertEqual(s.points("b").count, 1)
    }

    func testLabelsAreUniquePerCallAndSourceWithIncreasingIds() throws {
        let s = try CallStore(inMemory: true)
        XCTAssertTrue(s.insertLabel(callId: "a", label: 1, source: "hung_up_after_alert", time: 5))
        XCTAssertFalse(s.insertLabel(callId: "a", label: 1, source: "hung_up_after_alert", time: 6))
        XCTAssertTrue(s.insertLabel(callId: "a", label: 0, source: "this_was_fine", time: 7))
        XCTAssertTrue(s.insertLabel(callId: "b", label: 1, source: "final_review", time: 8))
        XCTAssertEqual(s.labels().map(\.id), [1, 2, 3])
        XCTAssertEqual(s.labels()[0].time, 5)
    }

    func testSentencesOnlyWithTheOptInAndOnlyForNewLabels() throws {
        let s = try CallStore(inMemory: true)
        let lines = [(1, "My name is Sarah, OTP 1234"), (3, "  "), (5, "Transfer $500 now")]
        s.recordLabels(callId: "a", labels: [.init(label: 1, source: "final_review")], callerLines: lines, helpImprove: false)
        XCTAssertEqual(s.labelCount(), 1)
        XCTAssertEqual(s.sentenceCount(), 0)
        s.recordLabels(callId: "b", labels: [.init(label: 1, source: "final_review")], callerLines: lines, helpImprove: true)
        XCTAssertEqual(s.sentences("b").map(\.text), ["My name is [PERSON_NAME], OTP [NUMBER]", "Transfer [MONEY_AMOUNT] now"])
        // the same label again adds nothing, not even sentences
        s.forgetLabelSentences()
        s.recordLabels(callId: "b", labels: [.init(label: 1, source: "final_review")], callerLines: lines, helpImprove: true)
        XCTAssertEqual(s.sentenceCount(), 0)
        XCTAssertEqual(s.labelCount(), 2)
    }

    func testDataSurvivesReopeningTheStore() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("calls-\(UUID().uuidString).store")
        defer { try? FileManager.default.removeItem(at: url) }
        func open() throws -> ModelContainer {
            try ModelContainer(for: CallStore.schema, configurations: [ModelConfiguration(schema: CallStore.schema, url: url)])
        }
        do {
            let c = try open()
            let rec = CallRecord(row("a"))
            c.mainContext.insert(rec)
            let p = ScorePoint(pts("a", 1)[0])
            c.mainContext.insert(p)
            p.call = rec
            c.mainContext.insert(ImplicitLabel(id: 1, callId: "a", label: 1, source: "final_review", time: 1))
            c.mainContext.insert(LabelSentence(callId: "a", seq: 1, text: "x"))
            try c.mainContext.save()
        }
        let c2 = try open()
        XCTAssertEqual(try c2.mainContext.fetchCount(FetchDescriptor<CallRecord>()), 1)
        XCTAssertEqual(try c2.mainContext.fetchCount(FetchDescriptor<ScorePoint>()), 1)
        XCTAssertEqual(try c2.mainContext.fetchCount(FetchDescriptor<ImplicitLabel>()), 1)
        XCTAssertEqual(try c2.mainContext.fetchCount(FetchDescriptor<LabelSentence>()), 1)
    }
}
