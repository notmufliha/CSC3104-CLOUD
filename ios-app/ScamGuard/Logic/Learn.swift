import Foundation

/// "Learn to spot scams": the scam-type cards and the practice quiz. The content is one bundled file shared by both
/// apps (Resources/Learn/learn.json, built by tools/learn/build_learn.py from tools/learn/scam_types.json, the call
/// scripts and tools/testing/expected.json). Pure, kept in step with android-app logic/Learn.kt.
nonisolated struct ScamType: Codable, Equatable, Identifiable, Sendable {
    let id: String
    let title: String
    let signs: [String]
    let todo: String
    /// The script "Hear an example" plays as a practice call.
    let script: String
    let sourceName: String
    let sourceUrl: String

    enum CodingKeys: String, CodingKey {
        case id, title, signs, todo, script
        case sourceName = "source_name", sourceUrl = "source_url"
    }
}

/// One quiz question: a real caller line from our scripts.
nonisolated struct QuizItem: Codable, Equatable, Hashable, Sendable {
    let text: String
    let scam: Bool
    let why: String
    let script: String
    var type: String?
}

nonisolated struct LearnContent: Codable, Equatable, Sendable {
    var version = 1
    var types: [ScamType] = []
    var quiz: [QuizItem] = []
}

nonisolated enum Learn {
    static func parse(_ data: Data) -> LearnContent { (try? JSONDecoder().decode(LearnContent.self, from: data)) ?? LearnContent() }

    static func bundled(_ bundle: Bundle = .main) -> LearnContent {
        guard let u = bundle.url(forResource: "learn", withExtension: "json"), let d = try? Data(contentsOf: u) else { return LearnContent() }
        return parse(d)
    }
}

nonisolated enum Quiz {
    static let size = 5
    /// At least this many of each kind in a round, so a round is never all "Scam" or all "Safe".
    private static let minEach = 2

    /// The lines a round may use: never a learning-loop test script (90+).
    static func pool(_ content: LearnContent) -> [QuizItem] { content.quiz.filter { !DevScripts.isTestCall($0.script) } }

    /// A round of `size` questions in random order: at least 2 scam and 2 safe lines; lines in `avoid` (the last round's)
    /// only when nothing else is left. "Try again" passes the previous round, so it picks new lines.
    static func round<G: RandomNumberGenerator>(_ content: LearnContent, using rng: inout G, avoid: Set<String> = []) -> [QuizItem] {
        let pool = pool(content)
        func pick(_ items: [QuizItem], _ n: Int, _ taken: Set<String>) -> [QuizItem] {
            let fresh = items.filter { !avoid.contains($0.text) && !taken.contains($0.text) }.shuffled(using: &rng)
            let old = items.filter { avoid.contains($0.text) && !taken.contains($0.text) }.shuffled(using: &rng)
            return Array((fresh + old).prefix(max(n, 0)))
        }
        let scam = pick(pool.filter(\.scam), minEach, [])
        let safe = pick(pool.filter { !$0.scam }, minEach, [])
        let taken = Set((scam + safe).map(\.text))
        let rest = pick(pool, size - scam.count - safe.count, taken)
        return (scam + safe + rest).shuffled(using: &rng)
    }

    static func round(_ content: LearnContent, avoid: Set<String> = []) -> [QuizItem] {
        var g = SystemRandomNumberGenerator()
        return round(content, using: &g, avoid: avoid)
    }

    static func isRight(_ item: QuizItem, saidScam: Bool) -> Bool { item.scam == saidScam }

    /// The friendly line under the result (all that Simple mode shows).
    static func resultLine(_ score: Int, of: Int = size) -> K {
        if score >= of { return .quiz_r5 }
        if score == of - 1 { return .quiz_r4 }
        if score == of - 2 { return .quiz_r3 }
        return .quiz_r_low
    }

    /// The best score kept on the phone: the higher one.
    static func best(_ saved: Int?, _ score: Int) -> Int { max(saved ?? 0, score) }
}
