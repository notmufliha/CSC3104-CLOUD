import Foundation

/// Port of android-app data/ScriptRepository.kt: loads every bundled call script.
/// File name convention: <kind>_<lang>_<nn>_<title>.json, e.g. scam_en_11_bank_otp.json, normal_zh_14_family_call.json
nonisolated enum ScriptRepository {
    static func loadAll(bundle: Bundle = .main) -> [CallScript] {
        let urls = bundle.urls(forResourcesWithExtension: "json", subdirectory: nil) ?? []
        return urls
            .filter { $0.lastPathComponent.hasPrefix("scam_") || $0.lastPathComponent.hasPrefix("normal_") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
            .compactMap { url in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? parse(name: url.lastPathComponent, data: data)
            }
    }

    static func parse(name: String, data: Data) throws -> CallScript {
        let lines = try JSONDecoder().decode([ScriptLine].self, from: data)
        let parts = (name.hasSuffix(".json") ? String(name.dropLast(5)) : name).split(separator: "_", omittingEmptySubsequences: false).map(String.init)
        let kind = parts.first?.lowercased() ?? "unknown"
        let lang = parts.count > 1 && ["en", "zh"].contains(parts[1].lowercased()) ? parts[1].lowercased() : nil
        var title = parts.dropFirst(3).joined(separator: " ")
        if let f = title.first { title = f.uppercased() + title.dropFirst() }
        if title.trimmingCharacters(in: .whitespaces).isEmpty { title = name }
        let kept = lines.filter { !($0.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return CallScript(fileName: name, kind: kind, lang: lang, title: title, lines: kept)
    }
}
