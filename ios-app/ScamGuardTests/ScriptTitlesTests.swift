import XCTest
@testable import ScamGuard

/// How scripts are titled and grouped in the picker. The same cases as Android ScriptTitlesTest.
final class ScriptTitlesTests: XCTestCase {
    /// The app's real bundled scripts.
    private let all = ScriptRepository.loadAll()
    private func title(_ name: String) -> String? { all.first { $0.fileName == "\(name).json" }?.title }

    private struct SplitMix: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }

    func testLearningTestsGetClearTitles() {
        XCTAssertEqual(title("scam_en_90_new_scam_test_A_report_this"), "Learning test A (new scam type)")
        XCTAssertEqual(title("scam_en_91_new_scam_test_B_same_scam_new_words"), "Learning test B (same scam, new words)")
    }

    func testAcronymsAndNames() {
        XCTAssertEqual(title("scam_en_11_bank_otp"), "Bank OTP")
        XCTAssertEqual(title("scam_en_20_ica_to_china_police"), "ICA to China police")
        XCTAssertEqual(title("scam_en_21_telco_to_mas_officer"), "Telco to MAS officer")
        XCTAssertEqual(title("normal_en_20_ica_passport_collection"), "ICA passport collection")
        XCTAssertEqual(title("scam_en_22_apple_support_crypto"), "Apple support crypto")
        XCTAssertEqual(title("normal_en_10_bank_reminder"), "Bank reminder")
        XCTAssertEqual(ScriptTitles.baseTitle("weird.json"), "weird.json")   // no title part: the file name
    }

    func testChineseTwinsSayChinese() {
        XCTAssertEqual(title("scam_en_12_parcel_customs"), "Parcel customs")
        XCTAssertEqual(title("scam_zh_12_parcel_customs"), "Parcel customs (Chinese)")
        XCTAssertEqual(title("scam_zh_20_ica_to_china_police"), "ICA to China police (Chinese)")
        XCTAssertEqual(title("scam_zh_10_gov_official"), "Gov official (Chinese)")
        XCTAssertEqual(title("normal_zh_10_bank_reminder"), "Bank reminder (Chinese)")
        XCTAssertEqual(title("normal_zh_14_family_call"), "Family call (Chinese)")
        XCTAssertEqual(Set(all.map(\.title)).count, all.count, "no two scripts share a title")
        let lone = ScriptTitles.withTitles([CallScript(fileName: "scam_zh_99_only_chinese.json", kind: "scam", lang: "zh", title: "", lines: [])])
        XCTAssertEqual(lone.first?.title, "Only chinese")
    }

    func testLearningTestsAreGroupedSeparately() {
        let g = ScriptTitles.sections(all)
        XCTAssertEqual(g.tests.map(\.fileName), ["scam_en_90_new_scam_test_A_report_this.json",
                                                 "scam_en_91_new_scam_test_B_same_scam_new_words.json"])
        XCTAssertFalse(g.regular.contains { ScriptTitles.isLearningTest($0.fileName) })
        XCTAssertEqual(g.regular.count + g.tests.count, all.count)
        XCTAssertGreaterThanOrEqual(g.regular.count, 23, "the real demo calls are all still there")
    }

    /// "Simulate scam call" / "Simulate normal call" never play a learning-loop test, however often they're pressed.
    func testRandomPickNeverReturnsATestScript() {
        var rng = SplitMix(state: 42)
        var last: String?
        var seen = Set<String>()
        for i in 0..<5_000 {
            let kind = i % 2 == 0 ? "scam" : "normal"
            guard let pick = ScriptTitles.pickRandom(all, kind: kind, last: last, using: &rng) else { return XCTFail("nothing picked") }
            XCTAssertEqual(pick.kind, kind)
            XCTAssertFalse(ScriptTitles.isLearningTest(pick.fileName), pick.fileName)
            XCTAssertNotEqual(pick.fileName, last, "no repeat of the last pick")
            last = pick.fileName
            seen.insert(pick.fileName)
        }
        XCTAssertEqual(seen, Set(all.filter { !ScriptTitles.isLearningTest($0.fileName) }.map(\.fileName)),
                       "every other scam and normal script does come up")
        XCTAssertNil(ScriptTitles.pickRandom(all.filter { ScriptTitles.isLearningTest($0.fileName) }, kind: "scam", last: nil, using: &rng))
    }

    /// The view model's button uses the same rule.
    @MainActor
    func testViewModelRandomScriptSkipsLearningTests() throws {
        let vm = CallViewModel(prefs: AppPrefs(defaults: freshDefaults()), store: try CallStore(inMemory: true), scripts: all,
                               startUp: false)
        for _ in 0..<500 {
            let s = try XCTUnwrap(vm.randomScript("scam"))
            XCTAssertFalse(ScriptTitles.isLearningTest(s.fileName))
        }
    }
}
