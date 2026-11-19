import XCTest
@testable import ScamGuard

/// "Share weekly summary" (Logic/WeeklySummary.swift). Same cases as android-app WeeklySummaryTest.kt, plus the view
/// model with a recording notifier.
final class WeeklySummaryTests: XCTestCase {
    private let sg = TimeZone(identifier: "Asia/Singapore")!

    private func at(_ d: Int, _ h: Int = 12, month: Int = 11) -> Int64 {
        var c = DateComponents(); c.year = 2026; c.month = month; c.day = d; c.hour = h
        var cal = Calendar(identifier: .gregorian); cal.timeZone = sg
        return Int64(cal.date(from: c)!.timeIntervalSince1970 * 1000)
    }
    private var now: Int64 { at(18, 20) }   // Wed 18 Nov 2026, 8 pm

    private func call(_ t: Int64, _ v: String, _ r: String?) -> WeeklySummary.Call { .init(endedAt: t, verdict: v, reasonCode: r) }

    func testCountsReasonsAndDateRange() {
        let calls = [
            call(at(18), "SCAM", "money"), call(at(17), "SCAM", "impersonation"), call(at(16), "SCAM", "money"),
            call(at(15), "UNSURE", "urgency"), call(at(14), "SAFE", "none"), call(at(13), "SAFE", "none"),
            call(at(12, 0), "SAFE", "none"),                                   // 12 Nov 00:00: still in
            call(at(12), "NOT CHECKED", "server_not_reached"),                 // not checked: not counted
            call(at(11), "SCAM", "money"),                                     // 8 days ago: out
        ]
        XCTAssertEqual(WeeklySummary.text(calls, nowMs: now, zone: sg),
                       "ScamGuard weekly summary (12–18 Nov): 7 calls checked. 3 scam calls warned (asked for money or a code; " +
                       "pretended to be an official). 1 'be careful'. Stay safe! Call 1799 if unsure.")
    }

    func testSingularsAndNoScams() {
        XCTAssertEqual(WeeklySummary.text([call(at(16), "SAFE", "none")], nowMs: now, zone: sg),
                       "ScamGuard weekly summary (12–18 Nov): 1 call checked. No scam calls. Stay safe! Call 1799 if unsure.")
        XCTAssertEqual(WeeklySummary.text([call(at(16), "SCAM", "urgency"), call(at(15), "UNSURE", "money")], nowMs: now, zone: sg),
                       "ScamGuard weekly summary (12–18 Nov): 2 calls checked. 1 scam call warned (rushed you). 1 'be careful'. Stay safe! Call 1799 if unsure.")
        XCTAssertTrue(WeeklySummary.text([call(at(16), "SCAM", "bert")], nowMs: now, zone: sg).contains("(sounded like a scam)"))
    }

    func testEmptyWeek() {
        XCTAssertEqual(WeeklySummary.text([], nowMs: now, zone: sg),
                       "ScamGuard weekly summary (12–18 Nov): no calls checked this week. Stay safe! Call 1799 if unsure.")
        XCTAssertEqual(WeeklySummary.text([call(at(1), "SCAM", "money")], nowMs: now, zone: sg), WeeklySummary.text([], nowMs: now, zone: sg))
    }

    func testDateRangeAcrossMonthsAndYears() {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = sg
        func d(_ y: Int, _ m: Int, _ day: Int) -> Date { cal.date(from: DateComponents(year: y, month: m, day: day))! }
        XCTAssertEqual(WeeklySummary.label(d(2026, 10, 28), d(2026, 11, 3), cal), "28 Oct – 3 Nov")
        XCTAssertEqual(WeeklySummary.label(d(2025, 12, 29), d(2026, 1, 4), cal), "29 Dec – 4 Jan")
        XCTAssertEqual(WeeklySummary.week(nowMs: at(3, 9), zone: sg).label, "28 Oct – 3 Nov")
    }

    func testNoNumbersSentencesOrUrls() {
        let text = WeeklySummary.text([call(at(16), "SCAM", "Call 91234567 at www.scam.com, my OTP is 123456")], nowMs: now, zone: sg)
        let rest = text.replacingOccurrences(of: "12–18 Nov", with: "").replacingOccurrences(of: "1799", with: "")
        XCTAssertNil(text.range(of: #"https?://|www\.|\.com|@"#, options: .regularExpression), text)
        XCTAssertNil(rest.range(of: #"\d{3,}"#, options: .regularExpression), text)
        XCTAssertFalse(text.contains("OTP is"))
        XCTAssertTrue(text.contains("sounded like a scam"))
    }

    func testTheReminderRuleAsksOnlyWhenTurnedOn() {
        XCTAssertEqual(WeeklyReminder.onToggle(on: true, permitted: false), .askPermission)
        XCTAssertEqual(WeeklyReminder.onToggle(on: true, permitted: true), .schedule)
        XCTAssertEqual(WeeklyReminder.onToggle(on: false, permitted: false), .cancel)
        for on in [true, false] { for p in [true, false] { XCTAssertNotEqual(WeeklyReminder.atLaunch(savedOn: on, permitted: p), .askPermission) } }
        XCTAssertTrue(WeeklyReminder.afterPermission(granted: true) == (true, .schedule))
        XCTAssertTrue(WeeklyReminder.afterPermission(granted: false) == (false, .nothing))
    }

    /// Records what the view model asks the system for.
    final class RecordingNotifier: WeeklyNotifier, @unchecked Sendable {
        private let lock = NSLock()
        var allowed: Bool
        var grant: Bool
        private(set) var asked = 0, scheduled = 0, cancelled = 0
        init(allowed: Bool, grant: Bool) { self.allowed = allowed; self.grant = grant }
        func permitted() async -> Bool { lock.withLock { allowed } }
        func requestPermission() async -> Bool { lock.withLock { asked += 1; allowed = grant; return grant } }
        func scheduleWeekly() { lock.withLock { scheduled += 1 } }
        func cancelWeekly() { lock.withLock { cancelled += 1 } }
    }

    @MainActor
    private func vm(_ n: RecordingNotifier, _ d: UserDefaults = freshDefaults()) throws -> CallViewModel {
        CallViewModel(prefs: AppPrefs(defaults: d), store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                      delayScale: 0, pins: PinStore(service: "test.\(UUID().uuidString)"), startUp: false, devLog: DevLog(), notifier: n)
    }

    @MainActor
    func testTheToggleAsksForPermissionOnlyWhenTurnedOn() async throws {
        let n = RecordingNotifier(allowed: false, grant: true)
        let d = freshDefaults()
        let m = try vm(n, d)
        XCTAssertEqual(n.asked, 0, "starting the app never asks")
        XCTAssertFalse(m.weeklyReminder)
        await m.setWeeklyReminder(false)
        XCTAssertEqual(n.asked, 0, "turning it off never asks")
        let ok = await m.setWeeklyReminder(true)
        XCTAssertTrue(ok)
        XCTAssertEqual(n.asked, 1)
        XCTAssertEqual(n.scheduled, 1)
        XCTAssertTrue(m.weeklyReminder)
        XCTAssertTrue(AppPrefs(defaults: d).weeklyReminder)
        await m.setWeeklyReminder(true)            // already allowed: no second question
        XCTAssertEqual(n.asked, 1)
        await m.setWeeklyReminder(false)
        XCTAssertEqual(n.cancelled, 2, "each \"off\" cancels (the first one had nothing to cancel)")
        XCTAssertFalse(m.weeklyReminder)
        XCTAssertEqual(n.asked, 1)
    }

    @MainActor
    func testRefusedPermissionLeavesItOff() async throws {
        let n = RecordingNotifier(allowed: false, grant: false)
        let m = try vm(n)
        let ok = await m.setWeeklyReminder(true)
        XCTAssertFalse(ok)
        XCTAssertEqual(n.asked, 1)
        XCTAssertEqual(n.scheduled, 0)
        XCTAssertFalse(m.weeklyReminder)
    }
}
