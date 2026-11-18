import XCTest
@testable import ScamGuard

/// The dashboard's plain-word sentences. The SAME sentences are asserted by the Android app
/// (android-app/app/src/test/.../PlainWordsTest.kt) on the same sample history.
final class PlainWordsTests: XCTestCase {
    private let now: Int64 = 1_795_435_200_000
    private let utc = TimeZone(identifier: "UTC")!
    private lazy var h = SampleHistory.generate(nowMs: now, todayStartMs: 1_795_392_000_000)
    private func stats(_ p: DashboardStats.Period) -> DashboardStats.Stats { DashboardStats.compute(h.calls, h.labels, p, nowMs: now, tz: utc) }
    private var empty: DashboardStats.Stats { DashboardStats.compute([], [], .week, nowMs: now, tz: utc) }

    func testHeroSentenceForNoneOneAndMany() {
        XCTAssertEqual(PlainWords.hero(0, 0, .week), "No calls this week")
        XCTAssertEqual(PlainWords.hero(0, 0, .month), "No calls this month")
        XCTAssertEqual(PlainWords.hero(0, 0, .all), "No calls yet")
        XCTAssertEqual(PlainWords.hero(0, 5, .week), "No scam calls this week 🎉")
        XCTAssertEqual(PlainWords.hero(1, 5, .month), "1 scam call caught this month")
        XCTAssertEqual(PlainWords.hero(2, 9, .week), "2 scam calls caught this week")
        XCTAssertEqual(PlainWords.hero(12, 61, .all), "12 scam calls caught so far")
    }

    func testSampleWeek() {
        let s = stats(.week)
        XCTAssertEqual(PlainWords.hero(s.scam, s.total, .week), "5 scam calls caught this week")
        XCTAssertEqual(PlainWords.topWarning(s), "Most warnings asked for your OTP or money")
        XCTAssertEqual(PlainWords.overTime(s), "36% of calls were scams")
        XCTAssertEqual(PlainWords.signals(s), "Pressure to act fast came up most often")
        XCTAssertEqual(PlainWords.trend(s), "Risk is going down")
        XCTAssertEqual(PlainWords.hours(s), "Most risky calls happen in the morning")
        XCTAssertEqual(PlainWords.doubleCheck(s), "2 warnings turned out safe after a second look")
        XCTAssertEqual(PlainWords.whereChecked(s), "Most calls were checked by the ScamGuard server")
        XCTAssertEqual(PlainWords.answers(s), "You marked 2 warnings as fine")
        XCTAssertEqual(PlainWords.length(s), "An average call lasted 1 min 37 s")
    }

    func testSampleMonthAndAll() {
        let m = stats(.month)
        XCTAssertEqual(PlainWords.hero(m.scam, m.total, .month), "12 scam calls caught this month")
        XCTAssertEqual(PlainWords.overTime(m), "20% of calls were scams")
        XCTAssertEqual(PlainWords.signals(m), "Talk of money or codes came up most often")
        XCTAssertEqual(PlainWords.trend(m), "Risk is staying about the same")
        XCTAssertEqual(PlainWords.doubleCheck(m), "12 warnings turned out safe after a second look")
        let a = stats(.all)
        XCTAssertEqual(PlainWords.hero(a.scam, a.total, .all), "12 scam calls caught so far")
        XCTAssertEqual(PlainWords.trend(a), "Risk is going down")
    }

    func testEmptyPeriodSentences() {
        XCTAssertNil(PlainWords.topWarning(empty))
        XCTAssertEqual(PlainWords.reasons(empty), "No calls were flagged")
        XCTAssertEqual(PlainWords.overTime(empty), "No scam calls in this period")
        XCTAssertEqual(PlainWords.signals(empty), "No warning signs heard")
        XCTAssertEqual(PlainWords.trend(empty), "Not enough calls to see a trend")
        XCTAssertEqual(PlainWords.hours(empty), "No risky calls in this period")
        XCTAssertEqual(PlainWords.doubleCheck(empty), "No warnings to double-check")
        XCTAssertEqual(PlainWords.whereChecked(empty), "No calls in this period")
        XCTAssertEqual(PlainWords.answers(empty), "No answers from you yet")
        XCTAssertEqual(PlainWords.length(empty), "No calls in this period")
    }

    func testPlainWordsForResultsAndPlaces() {
        XCTAssertEqual(["SAFE", "UNSURE", "SCAM", "NOT CHECKED"].map(PlainWords.verdict), ["Safe", "Be careful", "Scam", "Not checked"])
        XCTAssertEqual(PlainWords.checkedWhere("on_device"), "Checked on this phone")
        XCTAssertEqual(PlainWords.checkedWhere("hybrid"), "Checked on this phone with server help")
        XCTAssertEqual(PlainWords.checkedWhere("cloud"), "Checked by the ScamGuard server")
        XCTAssertEqual([8, 13, 19, 23, 3].map(PlainWords.partOfDay), ["morning", "afternoon", "evening", "night", "night"])
        XCTAssertEqual(PlainWords.reasonSentence("impersonation"), "Most warnings came from fake police, banks or officials")
        XCTAssertEqual(PlainWords.reasonSentence(nil), "Most warnings had several small warning signs")
    }

    func testTimeAgoAndHistoryGroups() {
        let min: Int64 = 60_000
        XCTAssertEqual(PlainWords.timeAgo(now - 20_000, now, 0), "Just now")
        XCTAssertEqual(PlainWords.timeAgo(now - 12 * min, now, 0), "12 min ago")
        XCTAssertEqual(PlainWords.timeAgo(now - 190 * min, now, 0), "3 h ago")
        XCTAssertEqual(PlainWords.timeAgo(now - 20 * 60 * min, now, 1), "Yesterday")
        XCTAssertEqual(PlainWords.timeAgo(now - 4 * 1440 * min, now, 4), "4 days ago")
        XCTAssertNil(PlainWords.timeAgo(now - 9 * 1440 * min, now, 9))
        XCTAssertEqual([0, 1, 6, 7].map(PlainWords.historyGroup), ["Today", "This week", "This week", "Earlier"])
    }
}
