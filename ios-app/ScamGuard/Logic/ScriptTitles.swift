import Foundation

/// How the call scripts are named and grouped in the app (the files themselves are never renamed: the tools match
/// them by name). Pure; the same rules as android-app logic/ScriptTitles.kt.
///
/// - Workstream H's learning-loop test calls ("new_scam_test" in the file name, see demo/LEARNING_LOOP.md) get clear
///   titles, sit in their own picker section and are never chosen by "Simulate scam call".
/// - Acronyms and names in file names are written properly ("Bank OTP", "ICA to China police").
/// - A Chinese script with the same title as an English one gets " (Chinese)".
nonisolated enum ScriptTitles {
    /// File-name marker of H's learning-loop tests (BenchmarkTest, OnDeviceCallTests and endcall_check.py use it too).
    static let learningTestMarker = "new_scam_test"

    static let testSection = "Test calls (learning loop)"
    static let testSectionNote = "Meant to be missed until the models learn this scam."

    /// Titles given by hand, by file name without ".json".
    private static let overrides = [
        "scam_en_90_new_scam_test_A_report_this": "Learning test A (new scam type)",
        "scam_en_91_new_scam_test_B_same_scam_new_words": "Learning test B (same scam, new words)",
    ]

    /// Words written in capitals, or with a capital, whatever the file name says.
    private static let words = [
        "otp": "OTP", "ica": "ICA", "mas": "MAS", "spf": "SPF", "dbs": "DBS", "ocbc": "OCBC", "uob": "UOB",
        "cpf": "CPF", "iras": "IRAS", "mom": "MOM", "moh": "MOH", "usdt": "USDT", "atm": "ATM", "pin": "PIN",
        "ic": "IC", "nric": "NRIC", "sms": "SMS",
        "china": "China", "apple": "Apple", "singtel": "Singtel", "singapore": "Singapore",
    ]

    static func isLearningTest(_ fileName: String) -> Bool { fileName.contains(learningTestMarker) }

    /// One file's title, before the "(Chinese)" rule.
    static func baseTitle(_ fileName: String) -> String {
        let name = fileName.hasSuffix(".json") ? String(fileName.dropLast(5)) : fileName
        if let t = overrides[name] { return t }
        let parts = name.split(separator: "_", omittingEmptySubsequences: false).dropFirst(3)
            .filter { !$0.isEmpty }.map { words[$0.lowercased()] ?? String($0) }
        guard !parts.isEmpty else { return fileName }
        let t = parts.joined(separator: " ")
        return t.prefix(1).uppercased() + t.dropFirst()
    }

    /// Every script's title: when an English and a Chinese script share one, the Chinese one says "(Chinese)".
    static func withTitles(_ scripts: [CallScript]) -> [CallScript] {
        let base = scripts.map { ($0, baseTitle($0.fileName)) }
        let english = Set(base.filter { $0.0.lang == "en" }.map(\.1))
        return base.map { s, t in
            CallScript(fileName: s.fileName, kind: s.kind, lang: s.lang,
                       title: s.lang == "zh" && english.contains(t) ? "\(t) (Chinese)" : t, lines: s.lines)
        }
    }

    /// The picker's two groups: the normal demo calls, then the learning-loop tests (shown last, apart).
    static func sections(_ scripts: [CallScript]) -> (regular: [CallScript], tests: [CallScript]) {
        (scripts.filter { !isLearningTest($0.fileName) }, scripts.filter { isLearningTest($0.fileName) })
    }

    /// "Simulate scam call" / "Simulate normal call": a random script of this kind, never a learning-loop test, and
    /// not the one picked last time when there is another to choose.
    static func pickRandom<G: RandomNumberGenerator>(_ scripts: [CallScript], kind: String, last: String?,
                                                     using rng: inout G) -> CallScript? {
        let pool = scripts.filter { $0.kind == kind && !isLearningTest($0.fileName) }
        let others = pool.filter { $0.fileName != last }
        return (others.isEmpty ? pool : others).randomElement(using: &rng)
    }

    static func pickRandom(_ scripts: [CallScript], kind: String, last: String?) -> CallScript? {
        var rng = SystemRandomNumberGenerator()
        return pickRandom(scripts, kind: kind, last: last, using: &rng)
    }
}
