import Foundation

/// Port of android-app ondevice/WordPieceTokenizer.kt (G.4): BERT's WordPiece tokenizer (Hugging Face BertTokenizer /
/// tokenizers' BertWordPieceTokenizer), for MobileBERT on the phone. Reads the model's own vocab.txt (one token per
/// line, line number = id).
///
/// Steps, as in BERT's reference code: clean the text (drop control characters, whitespace -> space), put spaces
/// around CJK characters, lowercase + strip accents if uncased, split on whitespace and punctuation, then split each
/// word greedily into the longest vocabulary pieces ("##" for word-inner pieces); a word that can't be split, or is
/// longer than 100 characters, becomes [UNK].
nonisolated final class WordPieceTokenizer: @unchecked Sendable {
    static let cls = "[CLS]"
    static let sep = "[SEP]"
    static let unk = "[UNK]"

    private let vocab: [String: Int]
    private let lowerCase: Bool
    private let maxCharsPerWord: Int
    let clsId: Int
    let sepId: Int
    let unkId: Int

    enum TokenizerError: Error, Equatable { case missing(String) }

    init(vocab: [String: Int], lowerCase: Bool = true, maxCharsPerWord: Int = 100) throws {
        self.vocab = vocab
        self.lowerCase = lowerCase
        self.maxCharsPerWord = maxCharsPerWord
        guard let c = vocab[Self.cls] else { throw TokenizerError.missing(Self.cls) }
        guard let s = vocab[Self.sep] else { throw TokenizerError.missing(Self.sep) }
        guard let u = vocab[Self.unk] else { throw TokenizerError.missing(Self.unk) }
        clsId = c; sepId = s; unkId = u
    }

    /// vocab.txt: one token per line; the line number (from 0) is its id. The first occurrence of a token wins.
    static func fromVocab(_ text: String, lowerCase: Bool = true) throws -> WordPieceTokenizer {
        var lines = text.components(separatedBy: "\n")
        if lines.last == "" { lines.removeLast() }
        var vocab = [String: Int](minimumCapacity: lines.count)
        for (i, line) in lines.enumerated() {
            var t = line
            while t.hasSuffix("\r") { t.removeLast() }
            if vocab[t] == nil { vocab[t] = i }
        }
        return try WordPieceTokenizer(vocab: vocab, lowerCase: lowerCase)
    }

    /// Text -> wordpiece tokens (no [CLS]/[SEP]).
    func tokenize(_ text: String) -> [String] { basic(text).flatMap(wordPiece) }

    /// Text -> ids with [CLS] ... [SEP], at most `maxLen` long. Too long: the OLDEST tokens are dropped (left
    /// truncation, like the server's tokenizer), so the newest line is always read.
    func encode(_ text: String, maxLen: Int) -> [Int64] {
        precondition(maxLen >= 3)
        let ids = tokenize(text).map { vocab[$0] ?? unkId }
        let kept = ids.suffix(maxLen - 2)
        return [Int64(clsId)] + kept.map(Int64.init) + [Int64(sepId)]
    }

    // ------------------------------------------------------------ basic tokenizer

    private func basic(_ text: String) -> [String] {
        var cleaned = String.UnicodeScalarView()
        for cp in text.unicodeScalars {
            if cp.value == 0 || cp.value == 0xFFFD || Self.isControl(cp) { continue }
            if Self.isWhitespace(cp) { cleaned.append(" ") }
            else if Self.isCjk(cp.value) { cleaned.append(" "); cleaned.append(cp); cleaned.append(" ") }
            else { cleaned.append(cp) }
        }
        var words: [String] = []
        for raw in String(cleaned).split(separator: " ", omittingEmptySubsequences: true) {
            var w = String(raw)
            if lowerCase { w = Self.stripAccents(w.lowercased()) }
            // split on punctuation: every punctuation character is its own token
            var cur = String.UnicodeScalarView()
            for cp in w.unicodeScalars {
                if Self.isPunctuation(cp) {
                    if !cur.isEmpty { words.append(String(cur)); cur = String.UnicodeScalarView() }
                    words.append(String(cp))
                } else {
                    cur.append(cp)
                }
            }
            if !cur.isEmpty { words.append(String(cur)) }
        }
        return words
    }

    // ------------------------------------------------------------ wordpiece

    private func wordPiece(_ word: String) -> [String] {
        let cps = Array(word.unicodeScalars)
        if cps.count > maxCharsPerWord { return [Self.unk] }
        var out: [String] = []
        var start = 0
        while start < cps.count {
            var end = cps.count
            var piece: String?
            while start < end {
                var s = String.UnicodeScalarView()
                s.append(contentsOf: cps[start..<end])
                let sub = (start > 0 ? "##" : "") + String(s)
                if vocab[sub] != nil { piece = sub; break }
                end -= 1
            }
            guard let piece else { return [Self.unk] }
            out.append(piece)
            start = end
        }
        return out
    }

    private static func stripAccents(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        out.append(contentsOf: s.decomposedStringWithCanonicalMapping.unicodeScalars.filter {
            $0.properties.generalCategory != .nonspacingMark
        })
        return String(out)
    }

    private static func isWhitespace(_ cp: Unicode.Scalar) -> Bool {
        cp == " " || cp == "\t" || cp == "\n" || cp == "\r" || cp.properties.generalCategory == .spaceSeparator
    }

    private static func isControl(_ cp: Unicode.Scalar) -> Bool {
        if cp == "\t" || cp == "\n" || cp == "\r" { return false }
        let c = cp.properties.generalCategory
        return c == .control || c == .format
    }

    /// BERT counts all non-letter/number ASCII (e.g. $, ^, `) as punctuation, plus Unicode P* categories.
    private static func isPunctuation(_ cp: Unicode.Scalar) -> Bool {
        let v = cp.value
        if (33...47).contains(v) || (58...64).contains(v) || (91...96).contains(v) || (123...126).contains(v) { return true }
        switch cp.properties.generalCategory {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation, .initialPunctuation,
             .finalPunctuation, .otherPunctuation:
            return true
        default:
            return false
        }
    }

    private static func isCjk(_ cp: UInt32) -> Bool {
        (0x4E00...0x9FFF).contains(cp) || (0x3400...0x4DBF).contains(cp) || (0x20000...0x2A6DF).contains(cp) ||
            (0x2A700...0x2B73F).contains(cp) || (0x2B740...0x2B81F).contains(cp) || (0x2B820...0x2CEAF).contains(cp) ||
            (0xF900...0xFAFF).contains(cp) || (0x2F800...0x2FA1F).contains(cp)
    }
}
