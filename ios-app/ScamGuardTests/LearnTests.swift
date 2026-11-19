import XCTest
@testable import ScamGuard

/// "Learn to spot scams" from the bundled, shared learn.json. Same cases as android-app LearnTest.kt.
final class LearnTests: XCTestCase {
    private let content = Learn.bundled()

    /// A seeded generator, so a round can be repeated (SplitMix64).
    struct Seeded: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
    }

    func testScamCardsComeFromTheSharedJson() throws {
        // byte-for-byte the same file as Android's
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let android = here.appendingPathComponent("../android-app/app/src/main/assets/learn/learn.json")
        let bundled = try XCTUnwrap(Bundle.main.url(forResource: "learn", withExtension: "json"))
        XCTAssertEqual(try Data(contentsOf: bundled), try Data(contentsOf: android))
        XCTAssertEqual(content.types.count, 10)
        XCTAssertEqual(Set(content.types.map(\.id)).count, content.types.count)
        let scripts = Set(ScriptRepository.loadAll().map(\.fileName))
        for t in content.types {
            XCTAssertEqual(t.signs.count, 3, t.id)
            XCTAssertFalse(t.title.isEmpty || t.todo.isEmpty, t.id)
            XCTAssertTrue(scripts.contains(t.script), "\(t.id): \(t.script) is a bundled script")
            XCTAssertFalse(DevScripts.isTestCall(t.script), t.id)
            XCTAssertTrue(t.sourceUrl.hasPrefix("https://www.scamshield.gov.sg/") || t.sourceUrl.hasPrefix("https://www.police.gov.sg/"), t.id)
        }
        let bank = try XCTUnwrap(content.types.first { $0.id == "bank_code" })
        XCTAssertEqual(bank.title, "A 'bank' asks for your code")
        XCTAssertEqual(bank.sourceName, "ScamShield")
        for s in ["scam_en_20_ica_to_china_police.json", "scam_en_21_telco_to_mas_officer.json", "scam_en_22_apple_support_crypto.json",
                  "scam_en_23_bank_card_handover.json", "scam_en_24_job_task_deposit.json"] {
            XCTAssertTrue(content.types.contains { $0.script == s }, s)
        }
        for s in ["normal_en_20_ica_passport_collection.json", "normal_en_21_bank_fraud_check.json", "normal_en_22_telco_contract_renewal.json"] {
            XCTAssertTrue(content.quiz.contains { $0.script == s && !$0.scam }, s)
        }
    }

    func testQuizLinesAreRealScriptLines() {
        let scripts = Dictionary(uniqueKeysWithValues: ScriptRepository.loadAll().map { ($0.fileName, $0) })
        for q in content.quiz {
            XCTAssertFalse(DevScripts.isTestCall(q.script))
            let lines = scripts[q.script]?.lines ?? []
            XCTAssertTrue(lines.contains { $0.isCaller && $0.text?.trimmingCharacters(in: .whitespaces) == q.text }, "\"\(q.text)\" in \(q.script)")
            XCTAssertEqual(q.scam, q.script.hasPrefix("scam_"), q.script)
            XCTAssertFalse(q.why.isEmpty)
        }
    }

    func testARoundHasFiveQuestionsOfBothKindsAndNeverTestScripts() {
        for seed in 0..<200 {
            var g = Seeded(state: UInt64(seed))
            let r = Quiz.round(content, using: &g)
            XCTAssertEqual(r.count, Quiz.size)
            XCTAssertEqual(Set(r.map(\.text)).count, 5)
            XCTAssertTrue(r.filter(\.scam).count >= 2 && r.filter { !$0.scam }.count >= 2)
            XCTAssertFalse(r.contains { DevScripts.isTestCall($0.script) })
        }
    }

    func testTestScriptsAreNeverUsedEvenIfTheFileHadThem() {
        var sneaky = content
        sneaky.quiz.append(QuizItem(text: "Report this", scam: true, why: "x", script: "scam_en_90_new_scam_test_A_report_this.json"))
        XCTAssertFalse(Quiz.pool(sneaky).contains { $0.script.contains("_90_") })
    }

    func testTryAgainPicksNewLines() {
        var g1 = Seeded(state: 1), g2 = Seeded(state: 2)
        let first = Quiz.round(content, using: &g1)
        let again = Quiz.round(content, using: &g2, avoid: Set(first.map(\.text)))
        XCTAssertTrue(Set(again.map(\.text)).isDisjoint(with: first.map(\.text)))
    }

    func testAnswersAreMarkedRight() throws {
        let scam = try XCTUnwrap(content.quiz.first(where: \.scam))
        let safe = try XCTUnwrap(content.quiz.first { !$0.scam })
        XCTAssertTrue(Quiz.isRight(scam, saidScam: true))
        XCTAssertFalse(Quiz.isRight(scam, saidScam: false))
        XCTAssertTrue(Quiz.isRight(safe, saidScam: false))
        XCTAssertFalse(Quiz.isRight(safe, saidScam: true))
    }

    @MainActor
    func testResultAndBestScoreIsSaved() throws {
        XCTAssertEqual(L.s(Quiz.resultLine(5)), "Excellent! You spotted them all.")
        XCTAssertEqual(L.s(Quiz.resultLine(4)), "Well done!")
        XCTAssertEqual(L.s(Quiz.resultLine(3)), "Good try!")
        XCTAssertEqual(L.s(Quiz.resultLine(0)), "Keep practising. You will get better each time.")
        XCTAssertEqual(L.s(.quiz_score, "4", "5"), "4 of 5")
        XCTAssertEqual(Quiz.best(nil, 3), 3)
        XCTAssertEqual(Quiz.best(4, 2), 4)
        // the view model keeps only the best score, in the settings
        let d = freshDefaults()
        let prefs = AppPrefs(defaults: d)
        let vm = CallViewModel(prefs: prefs, store: try CallStore(inMemory: true), scripts: [], session: StubProtocol.session(),
                               delayScale: 0, pins: PinStore(service: "test.\(UUID().uuidString)"), startUp: false, devLog: DevLog())
        XCTAssertNil(vm.quizBest)
        vm.recordQuiz(3); vm.recordQuiz(2)
        XCTAssertEqual(vm.quizBest, 3)
        XCTAssertEqual(prefs.quizBest, 3)
        XCTAssertEqual(AppPrefs(defaults: d).quizBest, 3)
    }
}
