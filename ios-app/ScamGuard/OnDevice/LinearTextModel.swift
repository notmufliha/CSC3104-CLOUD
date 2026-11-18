import Foundation

/// Port of android-app ondevice/LinearTextModel.kt (G.2): one exported English model (Resources/Models/<name>_en.json,
/// written by tools/export_models.py): TF-IDF vectoriser(s) -> logistic regression -> optional Platt calibration.
/// Reproduces scikit-learn's TfidfVectorizer.transform + LogisticRegression.decision_function + predict_proba.
/// Text must already be cleaned the way the worker cleans it (TextCleaning.swift).
///
/// G.10, format 2: a vectoriser can instead be a HASHING one (the federated models downloaded from the registry):
/// same n-grams, but a token's slot is abs(murmurhash3_32(token)) % n_features, with a frozen idf per slot.
nonisolated final class LinearTextModel: @unchecked Sendable {
    struct Doc: Decodable {
        let format: Int
        let name: String
        let version: String
        let threshold: Double
        let vectorizers: [Vectorizer]
        let intercept: Double
        let calibration: Calibration?
        let orgTypes: [String: [String]]?
        let maskBrands: Bool?
        let brandTypes: [String: [String]]?

        enum CodingKeys: String, CodingKey {
            case format, name, version, threshold, vectorizers, intercept, calibration
            case orgTypes = "org_types", maskBrands = "mask_brands", brandTypes = "brand_types"
        }
    }

    struct Vectorizer: Decodable {
        let analyzer: String
        let ngramRange: [Int]
        let lowercase: Bool
        let tokenPattern: String
        let stopWords: [String]
        let sublinearTf: Bool
        // format 1 (word list)
        let terms: [String]?
        let idf: [Double]?
        let coef: [Double]?
        // format 2 (hashing)
        let nFeatures: Int?
        let idfDefault: Double?
        let idfIndex: [Int]?
        let idfValue: [Double]?
        let coefB64: String?

        enum CodingKeys: String, CodingKey {
            case analyzer, ngramRange = "ngram_range", lowercase, tokenPattern = "token_pattern"
            case stopWords = "stop_words", sublinearTf = "sublinear_tf", terms, idf, coef
            case nFeatures = "n_features", idfDefault = "idf_default", idfIndex = "idf_index"
            case idfValue = "idf_value", coefB64 = "coef_b64"
        }
    }

    struct Calibration: Decodable {
        let a: Double
        let b: Double
    }

    enum LoadError: Error, LocalizedError {
        case unsupportedFormat(Int)
        case inconsistent(String)

        var errorDescription: String? {
            switch self {
            case .unsupportedFormat(let f): return "model file format \(f), this app reads 1 and 2"
            case .inconsistent(let m): return "model file is inconsistent: \(m)"
            }
        }
    }

    static let format = 1
    static let formatHashing = 2

    private let doc: Doc
    private let parts: [Part]

    var name: String { doc.name }
    var version: String { doc.version }
    var threshold: Double { doc.threshold }
    var orgTypes: [String: [String]] { doc.orgTypes ?? [:] }
    /// money_en v2: trained on brand-masked text (money_norm.clean_text(mask_brands=True)).
    var maskBrands: Bool { doc.maskBrands == true }
    var brandTypes: [String: [String]] { doc.brandTypes ?? [:] }
    var featureCount: Int { parts.reduce(0) { $0 + $1.size } }
    /// Format 2 = hashing (federated, downloaded); format 1 = TF-IDF word list (built into the app).
    var isHashing: Bool { doc.format == 2 }

    private init(doc: Doc) throws {
        self.doc = doc
        parts = try doc.vectorizers.map { try Part($0) }
    }

    static func load(_ data: Data) throws -> LinearTextModel {
        let doc = try JSONDecoder().decode(Doc.self, from: data)
        guard doc.format == format || doc.format == formatHashing else { throw LoadError.unsupportedFormat(doc.format) }
        return try LinearTextModel(doc: doc)
    }

    /// Probability (0-1) for one cleaned text.
    func proba(_ cleaned: String) -> Double {
        let z = decision(cleaned)
        if let cal = doc.calibration { return Self.sigmoid(cal.a * z + cal.b) }
        return Self.sigmoid(z)
    }

    /// The logistic regression's raw score (before the sigmoid / calibration).
    func decision(_ cleaned: String) -> Double {
        doc.intercept + parts.reduce(0.0) { $0 + $1.dot(cleaned) }
    }

    private static func sigmoid(_ x: Double) -> Double { 1.0 / (1.0 + exp(-x)) }

    /// float32 little-endian, base64 (export_phone.py) -> doubles.
    static func decodeFloats(_ b64: String) -> [Double]? {
        guard let data = Data(base64Encoded: b64) else { return nil }
        let n = data.count / 4
        var out = [Double](repeating: 0, count: n)
        data.withUnsafeBytes { raw in
            for i in 0..<n {
                let bits = UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: i * 4, as: UInt32.self))
                out[i] = Double(Float(bitPattern: bits))
            }
        }
        return out
    }

    private final class Part {
        let analyzer: String
        let minN: Int
        let maxN: Int
        let lowercase: Bool
        let sublinear: Bool
        let stopWords: Set<String>
        let token: NSRegularExpression
        let hashSlots: Int?
        let index: [String: Int]?
        let idf: [Double]?
        let idfDefault: Double
        let idfSeen: [Int: Double]?
        let coef: [Double]
        var size: Int { coef.count }

        init(_ v: Vectorizer) throws {
            analyzer = v.analyzer
            minN = v.ngramRange[0]
            maxN = v.ngramRange[1]
            lowercase = v.lowercase
            sublinear = v.sublinearTf
            stopWords = Set(v.stopWords)
            // (?u) is Python's default for str patterns anyway
            token = Py.re(v.tokenPattern.hasPrefix("(?u)") ? String(v.tokenPattern.dropFirst(4)) : v.tokenPattern)
            hashSlots = v.nFeatures
            if let terms = v.terms {
                var m = [String: Int](minimumCapacity: terms.count * 2)
                for (i, t) in terms.enumerated() { m[t] = i }
                index = m
            } else {
                index = nil
            }
            idf = v.idf
            idfDefault = v.idfDefault ?? 0
            if let ix = v.idfIndex {
                guard let values = v.idfValue else { throw LoadError.inconsistent("idf_index without idf_value") }
                var m = [Int: Double](minimumCapacity: ix.count * 2)
                for (k, slot) in ix.enumerated() { m[slot] = values[k] }
                idfSeen = m
            } else {
                idfSeen = nil
            }
            if let c = v.coef { coef = c }
            else if let b = v.coefB64, let c = LinearTextModel.decodeFloats(b) { coef = c }
            else { throw LoadError.inconsistent("model file has no weights") }

            guard analyzer == "word" || analyzer == "char_wb" else { throw LoadError.inconsistent("unsupported analyzer \(analyzer)") }
            if let hashSlots {
                guard coef.count == hashSlots else { throw LoadError.inconsistent("\(coef.count) weights for \(hashSlots) slots") }
            } else {
                guard v.terms?.count == coef.count, idf?.count == coef.count else { throw LoadError.inconsistent("sizes differ") }
            }
        }

        private func slotOf(_ gram: String) -> Int? {
            if let hashSlots { return Murmur3.slot(gram, hashSlots) }
            return index![gram]
        }

        private func idfOf(_ i: Int) -> Double {
            if let idf { return idf[i] }
            return idfSeen?[i] ?? idfDefault
        }

        /// tf-idf (sublinear, l2-normalised) of the text, dotted with this part's weights.
        func dot(_ text: String) -> Double {
            features(text).reduce(0.0) { $0 + $1.value * coef[$1.key] }
        }

        /// tf-idf (sublinear, l2-normalised) of the text: slot -> value.
        func features(_ text: String) -> [Int: Double] {
            let doc = lowercase ? Py.lower(text) : text
            var counts: [Int: Int] = [:]
            for gram in analyzer == "word" ? wordNgrams(doc) : charWbNgrams(doc) {
                guard let i = slotOf(gram) else { continue }
                counts[i, default: 0] += 1
            }
            if counts.isEmpty { return [:] }
            var w: [Int: Double] = [:]
            var norm = 0.0
            for (i, c) in counts {
                let v = (sublinear ? log(Double(c)) + 1.0 : Double(c)) * idfOf(i)
                w[i] = v
                norm += v * v
            }
            if norm <= 0 { return [:] }
            let n = norm.squareRoot()
            for k in w.keys { w[k]! /= n }
            return w
        }

        /// sklearn _word_ngrams: tokens minus stop words, then all n-grams joined by a space.
        private func wordNgrams(_ doc: String) -> [String] {
            let tokens = Py.findAll(token, doc).filter { !stopWords.contains($0) }
            var out: [String] = []
            out.reserveCapacity(tokens.count * (maxN - minN + 1))
            if minN <= min(maxN, tokens.count) {
                for n in minN...min(maxN, tokens.count) {
                    for i in 0...(tokens.count - n) {
                        out.append(n == 1 ? tokens[i] : tokens[i..<(i + n)].joined(separator: " "))
                    }
                }
            }
            return out
        }

        /// sklearn _char_wb_ngrams: per whitespace-separated word, padded with one space each side (code points).
        private func charWbNgrams(_ doc: String) -> [String] {
            var out: [String] = []
            for word in Py.split(doc) {
                let w = Array((" " + word + " ").unicodeScalars)
                let len = w.count
                for n in minN...maxN {
                    var offset = 0
                    out.append(Self.string(w, offset, min(n, len)))
                    while offset + n < len {
                        offset += 1
                        out.append(Self.string(w, offset, n))
                    }
                    if offset == 0 { break }   // the word is shorter than n: longer n-grams would repeat it
                }
            }
            return out
        }

        private static func string(_ w: [Unicode.Scalar], _ from: Int, _ count: Int) -> String {
            var s = String.UnicodeScalarView()
            s.append(contentsOf: w[from..<(from + count)])
            return String(s)
        }
    }
}
