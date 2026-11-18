import Foundation

/// Looks up app wording (Localizable.xcstrings, generated from tools/i18n; English only). Arguments fill {0}, {1}...
/// (%1$@ in the catalog). Same role as Tr on Android.
nonisolated enum L {
    /// The raw text of a key, or nil if the catalog doesn't have it.
    static func raw(_ key: K) -> String? {
        let missing = "\u{1}missing"
        let v = Bundle.main.localizedString(forKey: key.rawValue, value: missing, table: "Localizable")
        return v == missing ? nil : v
    }

    /// The key's text, with the arguments filled in.
    static func s(_ key: K, _ args: String...) -> String {
        let text = raw(key) ?? key.rawValue
        if args.isEmpty { return text }
        return String(format: text, arguments: args.map { $0 as CVarArg })
    }
}
