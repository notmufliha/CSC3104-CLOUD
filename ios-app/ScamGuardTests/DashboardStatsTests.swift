import XCTest
@testable import ScamGuard

/// The redesigned dashboard's numbers. The SAME expected values are asserted by the Android app
/// (android-app/app/src/test/.../DashboardStatsTest.kt) on the same sample history, so both apps show the same numbers.
final class DashboardStatsTests: XCTestCase {
    private let now: Int64 = 1_795_435_200_000          // 2026-11-23 12:00 UTC
    private let todayStart: Int64 = 1_795_392_000_000   // 2026-11-23 00:00 UTC
    private let utc = TimeZone(identifier: "UTC")!
    private lazy var h = SampleHistory.generate(nowMs: now, todayStartMs: todayStart)
    private func stats(_ p: DashboardStats.Period) -> DashboardStats.Stats {
        DashboardStats.compute(h.calls, h.labels, p, nowMs: now, tz: utc)
    }

    private let otp = "Asked for your OTP or money"
    private let claims = "Claims to be police, a bank or the government"
    private let pressure = "Pressuring you to act now"
    private let whole = "The conversation as a whole sounds like a scam"

    private func pairs(_ x: [(String, Int)]) -> [String] { x.map { "\($0.0)=\($0.1)" } }

    func testSampleHistoryIsDeterministic() {
        XCTAssertEqual(h.calls.count, 61)
        XCTAssertEqual(h.points.count, 407)
        XCTAssertEqual(h.labels.count, 11)
        XCTAssertTrue(h.calls.allSatisfy { $0.id.hasPrefix(SampleHistory.idPrefix) })
        XCTAssertEqual(h, SampleHistory.generate(nowMs: now, todayStartMs: todayStart))
        XCTAssertTrue(h.calls.allSatisfy { $0.endedAt <= now })
    }

    func testLastSevenDays() {
        let s = stats(.week)
        XCTAssertEqual([s.total, s.safe, s.unsure, s.scam, s.notChecked], [14, 7, 2, 5, 0])
        XCTAssertEqual(s.safePercent, 50)
        XCTAssertEqual(s.buckets.map(\.daysAgo), [6, 5, 4, 3, 2, 1, 0])
        XCTAssertEqual(s.buckets.map(\.total), [3, 2, 1, 2, 3, 2, 1])
        XCTAssertEqual(s.buckets[0].avgRisk!, 0.4647, accuracy: 1e-4)
        XCTAssertEqual(pairs(s.reasons), pairs([(otp, 4), (claims, 2), (whole, 1)]))
        XCTAssertEqual(pairs(s.signals), ["urgency=4", "money=4", "impersonation=3"])
        XCTAssertEqual(s.riskyHours.indices.filter { s.riskyHours[$0] > 0 }, [8, 10, 11, 12, 17, 18, 20])
        XCTAssertEqual(s.riskyHours.reduce(0, +), 7)
        XCTAssertEqual([s.confirmed, s.calmed, s.raised], [7, 2, 0])
        XCTAssertEqual(pairs(s.privacy), ["cloud=9", "hybrid=3", "on_device=2"])
        XCTAssertEqual(s.markedFine, 2)
        XCTAssertEqual(s.hungUpAfterWarning, 2)
        XCTAssertEqual(s.avgLengthSeconds, 97)
        XCTAssertEqual(s.lengthVsRisk.count, 14)
    }

    func testLastThirtyDays() {
        let s = stats(.month)
        XCTAssertEqual([s.total, s.safe, s.unsure, s.scam, s.notChecked], [61, 36, 11, 12, 2])
        XCTAssertEqual(s.safePercent, 59)
        XCTAssertEqual(s.buckets.count, 30)
        XCTAssertEqual(s.buckets.map(\.total).reduce(0, +), 61)
        XCTAssertEqual(pairs(s.reasons), pairs([(otp, 12), (claims, 7), (pressure, 2), (whole, 2)]))
        XCTAssertEqual(pairs(s.signals), ["urgency=9", "money=13", "impersonation=10"])
        XCTAssertEqual(s.riskyHours, [0, 0, 0, 0, 0, 0, 0, 0, 3, 1, 3, 3, 3, 2, 1, 0, 1, 1, 2, 0, 3, 0, 0, 0])
        XCTAssertEqual([s.confirmed, s.calmed, s.raised], [23, 12, 0])
        XCTAssertEqual(pairs(s.privacy), ["cloud=38", "hybrid=13", "on_device=10"])
        XCTAssertEqual(s.markedFine, 8)
        XCTAssertEqual(s.hungUpAfterWarning, 3)
        XCTAssertEqual(s.avgLengthSeconds, 84)
        XCTAssertNil(s.buckets.first { $0.total == 0 }?.avgRisk)
    }

    func testAllTimeIsGroupedByWeek() {
        let s = stats(.all)
        XCTAssertTrue(s.weekly)
        XCTAssertEqual(s.total, 61)
        XCTAssertEqual(s.buckets.map(\.daysAgo), [34, 27, 20, 13, 6])
        XCTAssertEqual(s.buckets.map(\.total), [5, 13, 14, 15, 14])
        XCTAssertEqual(s.buckets.last!.avgRisk!, 0.4803, accuracy: 1e-4)
    }

    func testEmptyPeriods() {
        let week = DashboardStats.compute([], [], .week, nowMs: now, tz: utc)
        XCTAssertTrue(week.isEmpty)
        XCTAssertEqual(week.safePercent, 0)
        XCTAssertEqual(week.buckets.count, 7)
        XCTAssertTrue(week.buckets.allSatisfy { $0.total == 0 && $0.avgRisk == nil })
        XCTAssertTrue(week.reasons.isEmpty)
        XCTAssertEqual(week.privacy.map(\.1), [0, 0, 0])
        XCTAssertEqual(DashboardStats.compute([], [], .all, nowMs: now, tz: utc).buckets.count, 1)
        let old = h.calls.map { c -> CallRow in var c = c; c.endedAt -= 60 * DashboardStats.dayMs; return c }
        XCTAssertTrue(DashboardStats.compute(old, [], .month, nowMs: now, tz: utc).isEmpty)
    }

    func testPercentRoundsHalfUp() {
        XCTAssertEqual(DashboardStats.percent(1, 3), 33)
        XCTAssertEqual(DashboardStats.percent(2, 3), 67)
        XCTAssertEqual(DashboardStats.percent(1, 2), 50)
        XCTAssertEqual(DashboardStats.percent(1, 8), 13)
        XCTAssertEqual(DashboardStats.percent(0, 0), 0)
    }

    func testDaysAndHoursUseTheLocalTimeZone() {
        let sg = TimeZone(identifier: "Asia/Singapore")!
        let lateUtc = todayStart - 30 * 60_000          // 22 Nov 23:30 UTC = 23 Nov 07:30 in Singapore
        XCTAssertEqual(DashboardStats.daysAgo(lateUtc, now, utc), 1)
        XCTAssertEqual(DashboardStats.daysAgo(lateUtc, now, sg), 0)
        XCTAssertEqual(DashboardStats.hourOf(lateUtc, utc), 23)
        XCTAssertEqual(DashboardStats.hourOf(lateUtc, sg), 7)
    }

    func testNotCheckedCallsCountButAreNotWarnings() {
        let c = CallRow(id: "x", startedAt: now - 60_000, endedAt: now, finalVerdict: "NOT CHECKED", liveVerdict: "NOT CHECKED",
                        topReason: "server not reached", callScore: nil, signalsFired: [], review: "unavailable",
                        riskiestSentence: nil, privacyLevel: "cloud", reasonCode: "server_not_reached")
        let s = DashboardStats.compute([c], [], .week, nowMs: now, tz: utc)
        XCTAssertEqual(s.notChecked, 1)
        XCTAssertEqual(s.riskyHours.reduce(0, +), 0)
        XCTAssertTrue(s.reasons.isEmpty)
        XCTAssertTrue(s.lengthVsRisk.isEmpty)
    }

    func testWhatHappenedWording() {
        XCTAssertEqual(DashboardStats.whatHappened("SCAM", "SCAM", otp), "Likely scam: asked for your OTP or money.")
        XCTAssertEqual(DashboardStats.whatHappened("UNSURE", "UNSURE", pressure), "Something seemed off: pressuring you to act now.")
        XCTAssertEqual(DashboardStats.whatHappened("SAFE", "UNSURE", ""), "A warning came up, but the final check found the call safe.")
        XCTAssertEqual(DashboardStats.whatHappened("SAFE", "SAFE", ""), "No warning signs in this call.")
        XCTAssertEqual(DashboardStats.whatHappened("NOT CHECKED", nil, ""), "This call couldn't be checked.")
        XCTAssertEqual(DashboardStats.duration(84), "1 min 24 s")
        XCTAssertEqual(DashboardStats.duration(45), "45 s")
    }

    @MainActor
    func testSampleHistoryGoesIntoTheStoreAndDeleteRemovesIt() throws {
        let store = try CallStore(inMemory: true)
        store.insertSample(h)
        XCTAssertEqual(store.calls().count, 61)
        XCTAssertEqual(store.pointCount(), 407)
        XCTAssertEqual(store.labelCount(), 11)
        let stats = DashboardStats.compute(store.calls(), store.labelRows(), .month, nowMs: now, tz: utc)
        XCTAssertEqual(stats.total, 61)
        XCTAssertEqual(stats.markedFine, 8)
        store.deleteHistory()
        XCTAssertEqual(store.calls().count + store.pointCount() + store.labelCount() + store.sentenceCount(), 0)
    }
}
