import XCTest
@testable import ScamGuard

/// Real calls against the docker compose backend on this Mac (the Simulator shares its network), played with the
/// scripts' own timing (the server's 15 s warm-up depends on it). Opt-in, because each call takes about a minute:
///
///   TEST_RUNNER_SCAMGUARD_BACKEND=1 xcodebuild test ... -only-testing:ScamGuardTests/BackendIntegrationTests
///
/// TEST_RUNNER_SCAMGUARD_SCRIPTS=all (or a comma-separated list of file names) plays more scripts; the default is one
/// scam and one normal call. TEST_RUNNER_SCAMGUARD_LEVEL=hybrid plays them at level 2 (POST /v1/verify).
@MainActor
final class BackendIntegrationTests: XCTestCase {
    private let gateway = ProcessInfo.processInfo.environment["SCAMGUARD_GATEWAY"] ?? "http://localhost:8080/"

    struct Result {
        let script: String
        let live: String
        let final: String
        let summary: String
        let review: String
        let reason: String
        let firstRedLine: Int?
    }

    private func play(_ fileName: String, level: PrivacyLevel) async throws -> Result {
        let prefs = AppPrefs(defaults: freshDefaults())
        prefs.gatewayUrl = gateway
        prefs.privacyLevel = level
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [],
                               session: URLSession(configuration: Network.gatewayConfiguration()),
                               delayScale: 1, haptic: {}, startUp: false)
        vm.ring(try Fixtures.script(fileName))
        vm.answer()
        var firstRed: Int?
        let deadline = Date().addingTimeInterval(180)
        while !(vm.state.phase == .ended && !vm.state.reviewing) {
            if firstRed == nil && vm.state.verdict == "SCAM" {
                firstRed = vm.state.lines.filter(\.isCaller).filter { !$0.pending }.count
            }
            if Date() > deadline { XCTFail("\(fileName): call did not finish"); break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        let s = vm.state
        return Result(script: fileName, live: s.verdict ?? (s.gatewayError.map { "ERROR(\($0))" } ?? "none"),
                      final: s.finalReview?.finalVerdict ?? "-", summary: Risk.historyVerdict(s.summaryLevel),
                      review: s.finalReview?.reviewType ?? "-", reason: s.summaryReason ?? "-", firstRedLine: firstRed)
    }

    func testRealCallsAgainstTheBackend() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["SCAMGUARD_BACKEND"] == "1", "set TEST_RUNNER_SCAMGUARD_BACKEND=1 to call the real backend")
        let api = try GatewayClient(baseUrl: gateway, session: URLSession(configuration: Network.gatewayConfiguration()))
        let health = try? await api.health()
        try XCTSkipUnless(health?.body?.status == "ok", "backend not reachable at \(gateway)")

        let level = PrivacyLevel.fromId(env["SCAMGUARD_LEVEL"] ?? "cloud")
        var names = ["scam_en_11_bank_otp.json", "normal_en_10_bank_reminder.json"]
        if let list = env["SCAMGUARD_SCRIPTS"] {
            names = list == "all" ? ScriptRepository.loadAll().map(\.fileName) : list.split(separator: ",").map(String.init)
        }
        // play up to 4 at a time (separate call ids), so a full run takes minutes, not half an hour
        var results: [Result] = []
        for batch in stride(from: 0, to: names.count, by: 4).map({ Array(names[$0..<min($0 + 4, names.count)]) }) {
            let rs = try await withThrowingTaskGroup(of: Result.self) { g in
                for n in batch { g.addTask { try await self.play(n, level: level) } }
                var out: [Result] = []
                for try await r in g { out.append(r) }
                return out
            }
            results += rs
        }
        results.sort { $0.script < $1.script }
        print("BACKEND-RESULTS level=\(level.rawValue) gateway=\(gateway)")
        for r in results {
            print("BACKEND-RESULT \(r.script) live=\(r.live) final=\(r.final) summary=\(r.summary) review=\(r.review) " +
                  "firstRedCallerLine=\(r.firstRedLine.map(String.init) ?? "-") reason=\"\(r.reason)\"")
        }
        if level == .cloud, let scam = results.first(where: { $0.script == "scam_en_11_bank_otp.json" }) {
            XCTAssertEqual(scam.live, "SCAM")
            XCTAssertEqual(scam.final, "SCAM")
        }
        if level == .cloud, let normal = results.first(where: { $0.script == "normal_en_10_bank_reminder.json" }) {
            XCTAssertNotEqual(normal.live, "SCAM")
            XCTAssertNotEqual(normal.final, "SCAM")
        }
    }
}
