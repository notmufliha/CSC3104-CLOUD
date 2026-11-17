import Foundation

/// Port of android-app ondevice/PyRegex.kt: just enough of Python's `re` and `str` behaviour to port the workers'
/// text cleaning exactly.
///
/// Python 3 str patterns are Unicode-aware: \w, \d, \s and \b match any language's letters, digits and spaces, and
/// re.I folds case for all of them. ICU (NSRegularExpression) is Unicode-aware by default too, with one gap that is
/// patched here: ICU's \s is [\t\n\f\r\p{Z}], while Python's also has \x0B, \x1C-\x1F and \x85. `re` rewrites \s / \S
/// to Python's set. The parity tests (317 sentences) compare the result with the workers' own Python.
nonisolated enum Py {
    /// Python's str whitespace that ICU's \s lacks, plus ICU's own.
    private static let wsBody = #"\t\n\x{0B}\f\r\x{1C}-\x{1F}\x{85}\p{Z}"#

    /// Rewrites \s / \S (outside and inside [...]) to Python's whitespace set.
    static func translate(_ pattern: String) -> String {
        var out = ""
        var inClass = false
        var it = Array(pattern)
        var i = 0
        while i < it.count {
            let c = it[i]
            if c == "\\" && i + 1 < it.count {
                let n = it[i + 1]
                if n == "s" {
                    out += inClass ? wsBody : "[" + wsBody + "]"
                } else if n == "S" && !inClass {
                    out += "[^" + wsBody + "]"
                } else {
                    out.append(c); out.append(n)
                }
                i += 2
                continue
            }
            if c == "[" && !inClass {
                inClass = true
                out.append(c)
                // a ']' or '^]' right after '[' is a literal
                if i + 1 < it.count && it[i + 1] == "^" { out.append("^"); i += 1 }
                if i + 1 < it.count && it[i + 1] == "]" { out.append("\\]"); i += 1 }
            } else if c == "]" && inClass {
                inClass = false
                out.append(c)
            } else {
                out.append(c)
            }
            i += 1
        }
        it.removeAll()
        return out
    }

    /// re.compile(pattern[, re.I]).
    static func re(_ pattern: String, _ ignoreCase: Bool = false) -> NSRegularExpression {
        do {
            return try NSRegularExpression(pattern: translate(pattern), options: ignoreCase ? [.caseInsensitive] : [])
        } catch {
            fatalError("bad pattern \(pattern): \(error)")
        }
    }

    /// One match: its groups as strings (nil = group didn't take part), plus its UTF-16 range.
    struct Match {
        let result: NSTextCheckingResult
        let source: NSString

        var value: String { source.substring(with: result.range) }
        var range: NSRange { result.range }

        func group(_ i: Int) -> String? {
            guard i < result.numberOfRanges else { return nil }
            let r = result.range(at: i)
            return r.location == NSNotFound ? nil : source.substring(with: r)
        }
    }

    static func matches(_ rx: NSRegularExpression, _ s: String) -> [Match] {
        let ns = s as NSString
        return rx.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { Match(result: $0, source: ns) }
    }

    static func findAll(_ rx: NSRegularExpression, _ s: String) -> [String] { matches(rx, s).map(\.value) }

    /// re.sub with a function.
    static func sub(_ rx: NSRegularExpression, _ s: String, _ f: (Match) -> String) -> String {
        let ns = s as NSString
        let all = rx.matches(in: s, range: NSRange(location: 0, length: ns.length))
        if all.isEmpty { return s }
        var out = ""
        var last = 0
        for r in all {
            out += ns.substring(with: NSRange(location: last, length: r.range.location - last))
            out += f(Match(result: r, source: ns))
            last = r.range.location + r.range.length
        }
        out += ns.substring(from: last)
        return out
    }

    /// re.sub with a literal replacement (no group references).
    static func sub(_ rx: NSRegularExpression, _ s: String, _ replacement: String) -> String {
        sub(rx, s) { _ in replacement }
    }

    /// re.split (no capture groups): keeps empty pieces, like Python and Kotlin's Regex.split.
    static func split(_ rx: NSRegularExpression, _ s: String) -> [String] {
        let ns = s as NSString
        var out: [String] = []
        var last = 0
        for r in rx.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            if r.range.length == 0 && (r.range.location == 0 || r.range.location == ns.length) { continue }
            out.append(ns.substring(with: NSRange(location: last, length: r.range.location - last)))
            last = r.range.location + r.range.length
        }
        out.append(ns.substring(from: last))
        return out
    }

    /// re.fullmatch: the anchored pattern must cover the whole string.
    static func fullMatch(_ rx: NSRegularExpression, _ s: String) -> Match? {
        let ns = s as NSString
        guard let r = rx.firstMatch(in: s, options: [.anchored], range: NSRange(location: 0, length: ns.length)),
              r.range.location == 0, r.range.length == ns.length else { return nil }
        return Match(result: r, source: ns)
    }

    private static let whitespace = re(#"\s+"#)

    /// str.split() with no argument: split on runs of whitespace, no empty strings.
    static func split(_ s: String) -> [String] { split(whitespace, s).filter { !$0.isEmpty } }

    private static let lineBreak = re("\r\n|[\n\r\u{0B}\u{0C}\u{1C}\u{1D}\u{1E}\u{85}\u{2028}\u{2029}]")

    /// str.splitlines(): the same line breaks as Python, no trailing empty line.
    static func splitLines(_ s: String) -> [String] {
        if s.isEmpty { return [] }
        let parts = split(lineBreak, s)
        return parts.last == "" ? Array(parts.dropLast()) : parts
    }

    private static let stripSet: CharacterSet = {
        var cs = CharacterSet.whitespacesAndNewlines
        cs.insert(charactersIn: Unicode.Scalar(0x1C)!...Unicode.Scalar(0x1F)!)
        return cs
    }()

    /// str.strip()
    static func strip(_ s: String) -> String { s.trimmingCharacters(in: stripSet) }

    /// str.lower()
    static func lower(_ s: String) -> String { s.lowercased() }

    /// NFKC (unicodedata.normalize("NFKC", …)).
    static func nfkc(_ s: String) -> String { s.precomposedStringWithCompatibilityMapping }

    /// re.escape for an alternation of literal names.
    static func escape(_ s: String) -> String { NSRegularExpression.escapedPattern(for: s) }

    /// Number of UTF-16 units matching a predicate (Kotlin's String.count over Chars).
    static func countUTF16(_ s: String, _ pred: (UInt16) -> Bool) -> Int { s.utf16.reduce(0) { $0 + (pred($1) ? 1 : 0) } }
}
