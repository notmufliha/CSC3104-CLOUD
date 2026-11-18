import Foundation

// Port of android-app ondevice/TextCleaning.kt (G.2): the English workers' text cleaning, line by line. Each worker
// has its own cleaning file, so each has its own port here. The parity tests compare against the Python output
// (android-app/app/src/androidTest/assets/parity/parity_cases.json), sentence by sentence.

/// backend/workers/urgency_en/text_norm.py `normalize` + predict.py `caller_turns` / `windows`.
nonisolated enum UrgencyText {
    private static let tagMap: [String: String] = [
        "MONEY_AMOUNT": " moneytoken ", "PHONE_NUMBER": " phonetoken ", "EMAIL_ADDRESS": " emailtoken ",
        "URL": " urltoken ", "DATE": " timetoken ", "DATE_OF_BIRTH": " timetoken ", "TIME": " timetoken ",
        "DURATION": " numtoken ", "NUMBER": " numtoken ",
    ]
    private static let tag = Py.re(#"\[([A-Z_]+)\]"#)
    private static let speaker = Py.re(
        #"^\s*(person [ab]|caller|receiver|agent|customer|suspect|innocent|user|system|assistant)\s*:\s*"#, true)
    private static let email = Py.re(#"\b[\w.+-]+@[\w-]+\.[\w.]+\b"#)
    private static let url = Py.re(#"(https?://\S+|www\.\S+|\b[\w-]+\.(com|org|net|gov|sg|cn|io|co)(/\S*)?\b)"#, true)
    private static let money = Py.re(#"([$¥€£]\s?\d[\d,]*(\.\d+)?|\b\d[\d,]*(\.\d+)?\s?(dollars|sgd|usd|yuan|rmb|cny)\b)"#, true)
    private static let date = Py.re(#"\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b"#)
    private static let phoneCand = Py.re(#"(?<![\w])\+?\(?\d[\d\s().-]{5,18}\d(?![\w])"#)
    private static let time = Py.re(#"\b\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?\b|\b\d{1,2}\s?[ap]\.?m\.?\b"#, true)
    private static let hours = Py.re(#"\b\d+\s?(h|hr|hrs)\b"#, true)
    private static let mins = Py.re(#"\b\d+\s?(m|min|mins)\b"#, true)
    private static let code = Py.re(#"\b(?=[a-z]*\d)(?=\d*[a-z])[a-z\d]{3,}\b"#, true)
    private static let num = Py.re(#"\d[\d,.]*"#)
    private static let notLetters = Py.re(#"[^a-z' ]+"#)
    private static let strayQuote = Py.re(#"(?<![a-z])'|'(?![a-z])"#)
    private static let spaces = Py.re(#"\s+"#)

    /// Unicode decimal digits (Python str.isdigit for these inputs; Java Character.isDigit).
    static func isDigit(_ s: Unicode.Scalar) -> Bool { s.properties.numericType == .decimal }

    static func normalize(_ text: String) -> String {
        var t = Py.nfkc(text)
        t = Py.sub(speaker, t, "")
        t = Py.sub(tag, t) { m in tagMap[m.group(1) ?? ""] ?? " " }
        t = t.replacingOccurrences(of: "\u{2019}", with: "'").replacingOccurrences(of: "\u{2018}", with: "'")
        t = Py.sub(email, t, " emailtoken ")
        t = Py.sub(url, t, " urltoken ")
        t = Py.sub(money, t, " moneytoken ")
        t = Py.sub(date, t, " timetoken ")
        t = Py.sub(time, t, " timetoken ")
        t = Py.sub(phoneCand, t) { m in
            let digits = m.value.unicodeScalars.filter(isDigit).count
            return (7...15).contains(digits) ? " phonetoken " : m.value
        }
        t = Py.sub(hours, t, " numtoken hours ")
        t = Py.sub(mins, t, " numtoken minutes ")
        t = Py.sub(code, t, " numtoken ")
        t = Py.sub(num, t, " numtoken ")
        t = Py.lower(t)
        t = Py.sub(notLetters, t, " ")
        t = Py.sub(strayQuote, t, " ")
        return Py.strip(Py.sub(spaces, t, " "))
    }

    private static let sentenceEnd = Py.re(#"(?<=[.!?])\s+"#)
    private static let maxWords = 40
    private static let windowWords = 20
    private static let windowStep = 10
    private static let callerTags: Set<String> = ["caller", "agent", "suspect", "scammer"]
    private static let listenerTags: Set<String> = ["receiver", "customer", "innocent", "victim", "user"]
    private static let tagLine = Py.re(#"^\s*([a-z]+)\s*:\s*(.*)$"#, true)

    /// The caller's turns if the text is speaker-tagged, else the whole text.
    static func callerTurns(_ transcript: String) -> [String] {
        var turns: [String] = []
        var tagged = false
        var who: String?
        for raw in Py.splitLines(transcript) {
            var line = raw
            if let m = Py.fullMatch(tagLine, line) {
                let tag = Py.lower(m.group(1) ?? "")
                if callerTags.contains(tag) || listenerTags.contains(tag) {
                    tagged = true
                    who = tag
                    line = m.group(2) ?? ""
                }
            }
            if who == nil || callerTags.contains(who!) { turns.append(line) }
        }
        return tagged ? turns : [transcript]
    }

    /// Sentences plus pairs of neighbouring sentences; the worker's score is the highest over these.
    static func windows(_ transcript: String) -> [String] {
        var sentences: [String] = []
        for turn in callerTurns(transcript) {
            for s in Py.split(sentenceEnd, turn) {
                let words = Py.split(s)
                if words.count <= maxWords {
                    sentences.append(s)
                } else {
                    for i in stride(from: 0, to: max(words.count - windowStep, 1), by: windowStep) {
                        sentences.append(words[i..<min(i + windowWords, words.count)].joined(separator: " "))
                    }
                }
            }
        }
        let kept = sentences.filter { !normalize($0).isEmpty }
        var out = kept
        if kept.count > 1 { for i in 0..<(kept.count - 1) { out.append(kept[i] + " " + kept[i + 1]) } }
        return out
    }
}

/// money_norm.py v2 (money_en_v2c): brand names become their type token (DBS -> orgbank, PayNow -> payapp) and
/// amounts written as words become moneytoken. The brand list comes from the exported model file.
nonisolated final class MoneyBrands: @unchecked Sendable {
    private let toType: [String: String]
    private let brand: NSRegularExpression?

    init(_ brandTypes: [String: [String]]) {
        var m: [String: String] = [:]
        for (type, names) in brandTypes { for n in names { m[n] = type } }   // names are unique across types
        toType = m
        let names = m.keys.sorted { $0.count != $1.count ? $0.count > $1.count : $0 < $1 }
        brand = names.isEmpty ? nil : Py.re(#"\b("# + names.map(Py.escape).joined(separator: "|") + #")(?![\w])"#)
    }

    func apply(_ lowered: String) -> String {
        var t = lowered
        if let brand { t = Py.sub(brand, t) { m in toType[m.group(1) ?? ""] ?? m.value } }
        return Py.sub(Self.wordMoney, t, " moneytoken ")
    }

    private static let numw = "(?:a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
        "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|" +
        "million|and|numtoken)"
    private static let wordMoney = Py.re(#"\b(?:"# + numw + #"[ -]){1,6}(?:dollars?|bucks|sgd|ringgit)\b"# +
        #"|\bnumtoken ?(?:k|grand)\b|\b(?:a|one|two|three|five|ten) grand\b"#)
}

/// backend/workers/money_en/money_norm.py `clean_text` + `split_sentences`.
nonisolated enum MoneyText {
    private static let placeholderRx = Py.re(#"\[([A-Za-z_ ]{2,40})\]"#)

    private static func placeholder(_ name: String) -> String {
        let k = name.uppercased()
        func any(_ w: String...) -> Bool { w.contains { k.contains($0) } }
        if any("MONEY", "AMOUNT", "PRICE", "BALANCE") { return " moneytoken " }
        if any("OTP", "PIN", "CVV", "TAC", "PASSCODE", "PASSWORD", "CODE") { return " codetoken " }
        if any("ORG", "COMPANY", "BUSINESS") { return " orgtoken " }
        if k.contains("NAME") { return " nametoken " }
        if any("DATE", "BIRTH", "DOB") { return " datetoken " }
        if k.contains("PHONE") { return " phonetoken " }
        if any("EMAIL", "URL", "WEBSITE", "LINK") { return " linktoken " }
        if any("ADDRESS", "LOCATION", "CITY", "ZIP", "POSTAL", "STREET") { return " loctoken " }
        if k.contains("CARD") { return " cardtoken " }
        if any("ACCOUNT", "NUMBER", "SSN", "ID") { return " numtoken " }
        if any("TIME", "DURATION") { return " timetoken " }
        return " "
    }

    private static let months = "(?:january|february|march|april|may|june|july|august|september|october|" +
        "november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
    private static let html = Py.re(#"<[^>]+>"#)
    private static let cleanSteps: [(NSRegularExpression, String)] = [
        (Py.re(#"\S+@\S+\.\S+"#), " linktoken "),
        (Py.re(#"(?:https?://|www\.)\S+"#, true), " linktoken "),
        (Py.re(#"\b[\w-]+\.(?:com|net|org|sg|gov|io|co|info|biz)\b\S*"#, true), " linktoken "),
        (Py.re(#"(?:s\$|us\$|\$|£|€|¥|\bsgd|\busd|\brm)\s?\d[\d,]*(?:\.\d+)?"# +
            #"(?:\s?(?:k|m|million|billion|thousand)\b)?"#, true), " moneytoken "),
        (Py.re(#"\b\d[\d,]*(?:\.\d+)?\s?(?:dollars?|bucks|yuan|rmb|cents?|sgd|usd|"# +
            #"ringgit|pounds?|euros?)\b"#, true), " moneytoken "),
        (Py.re(#"\b(?:\d[ -]?){12,19}\b"#), " cardtoken "),
        (Py.re(#"(?:\+?\d{1,3}[\s-]?)?\(?\d{3}\)?X?[\s.-]?\(?\d{3}\)?X?[\s.-]?\(?\d{4}\)?"#), " phonetoken "),
        (Py.re(#"\b[689]\d{3}\s?\d{4}\b"#), " phonetoken "),
        (Py.re(#"\b\d{1,2}(?:st|nd|rd|th)?\s+(?:of\s+)?"# + months + #"\b\.?(?:,?\s+\d{4})?"#, true), " datetoken "),
        (Py.re(#"\b"# + months + #"\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?\b"#, true), " datetoken "),
        (Py.re(#"\b\d{1,4}[/-]\d{1,2}[/-]\d{2,4}\b"#), " datetoken "),
        (Py.re(#"\b\d{1,2}(?::\d{2})?\s?(?:am|pm|a\.m\.|p\.m\.)"#, true), " timetoken "),
        (Py.re(#"\b\d+(?:st|nd|rd|th)\b"#, true), " numtoken "),
        (Py.re(#"\d+(?:[.,]\d+)*"#), " numtoken "),
    ]
    private static let contractions: [(NSRegularExpression, String)] = [
        (Py.re(#"\bwon't\b"#), "will not"), (Py.re(#"\bcan't\b"#), "cannot"),
        (Py.re(#"\bshan't\b"#), "shall not"), (Py.re(#"n't\b"#), " not"),
    ]
    private static let notLetters = Py.re(#"[^a-z\s]"#)
    private static let repeatedToken = Py.re(#"\b(\w+token)(?:\s+\1\b)+"#)
    private static let spaces = Py.re(#"\s+"#)

    private static func lightNorm(_ text: String) -> String {
        let t = text.replacingOccurrences(of: "\u{2019}", with: "'").replacingOccurrences(of: "\u{2018}", with: "'")
            .replacingOccurrences(of: "\u{201C}", with: "\"").replacingOccurrences(of: "\u{201D}", with: "\"")
        return Py.lower(Py.strip(Py.sub(spaces, t, " ")))
    }

    /// `brands` = the v2 brand/amount masking (clean_text(mask_brands=True)); nil = v1's cleaning exactly.
    static func clean(_ text: String, _ brands: MoneyBrands? = nil) -> String {
        var t = Py.sub(placeholderRx, text) { m in placeholder(m.group(1) ?? "") }
        t = Py.sub(html, t, " ")
        for (rx, rep) in cleanSteps { t = Py.sub(rx, t, rep) }
        // unicodedata.normalize("NFKD", t).encode("ascii", "ignore")
        var ascii = String.UnicodeScalarView()
        ascii.append(contentsOf: t.decomposedStringWithCompatibilityMapping.unicodeScalars.filter { $0.value < 128 })
        t = lightNorm(String(ascii))
        if let brands { t = brands.apply(t) }
        for (rx, rep) in contractions { t = Py.sub(rx, t, rep) }
        t = t.replacingOccurrences(of: "'", with: "")
        t = Py.sub(notLetters, t, " ")
        t = Py.sub(repeatedToken, t) { m in m.group(1) ?? "" }
        return Py.strip(Py.sub(spaces, t, " "))
    }

    private static let glued = Py.re(#"([a-z][.!?])([A-Z])"#)
    private static let newlines = Py.re(#"\s*\n+\s*"#)
    private static let sentEnd = Py.re(#"(?<=[.!?])\s+"#)

    /// Sentences; pieces longer than 40 words are cut into 20-word chunks.
    static func splitSentences(_ text: String, maxWords: Int = 40, chunkWords: Int = 20) -> [String] {
        var out: [String] = []
        let t = Py.sub(glued, text) { m in (m.group(1) ?? "") + " " + (m.group(2) ?? "") }
        for block in Py.split(newlines, t) {
            for raw in Py.split(sentEnd, block) {
                let s = Py.strip(raw)
                if s.isEmpty { continue }
                let words = Py.split(s)
                if words.count > maxWords {
                    for i in stride(from: 0, to: words.count, by: chunkWords) {
                        out.append(words[i..<min(i + chunkWords, words.count)].joined(separator: " "))
                    }
                } else {
                    out.append(s)
                }
            }
        }
        return out
    }
}

/// backend/workers/impersonation_en/text_utils.py `prep(mask=True, caller=True)`. The organisation names it masks
/// come from the exported model file.
nonisolated final class ImpersonationText: @unchecked Sendable {
    private let tag = Py.re(#"\[[A-Za-z_ ]{2,30}\]"#)
    private let speaker = Py.re(#"\b(?:caller|receiver|agent|customer|person\s*[ab12]|speaker\s*\d|scammer|victim|user)\s*:"#, true)
    private let digits = Py.re(#"\d{4,}"#)
    private let spaces = Py.re(#"\s+"#)
    private let labelSplit = Py.re(#"\b(caller|scammer|agent|receiver|customer|victim|user)\s*:"#, true)
    private let remote: Set<String> = ["caller", "scammer", "agent"]
    private let nameToType: [String: String]
    private let org: NSRegularExpression?

    init(_ orgTypes: [String: [String]]) {
        var m: [String: String] = [:]
        for (type, names) in orgTypes { for n in names { m[n] = type } }
        nameToType = m
        let names = orgTypes.values.flatMap { $0 }.sorted { $0.count != $1.count ? $0.count > $1.count : $0 < $1 }
        org = names.isEmpty ? nil : Py.re(#"\b("# + names.map(Py.escape).joined(separator: "|") + #")\b"#)
    }

    func cleanText(_ text: String) -> String {
        var t = Py.sub(tag, text, " ")
        t = Py.sub(speaker, t, " ")
        t = Py.lower(t)
        t = Py.sub(digits, t, "longnumber")
        return Py.strip(Py.sub(spaces, t, " "))
    }

    func maskBrands(_ text: String) -> String {
        guard let org else { return text }
        return Py.sub(org, text) { m in nameToType[m.group(1) ?? ""] ?? m.value }
    }

    /// Every word the caller says; the receiver's turns are dropped. Untagged text is returned unchanged.
    func callerOnly(_ text: String) -> String {
        let ms = Py.matches(labelSplit, text)
        if ms.isEmpty { return text }
        let ns = text as NSString
        var kept = [ns.substring(to: ms[0].range.location)]
        for (i, m) in ms.enumerated() {
            let start = m.range.location + m.range.length
            let end = i + 1 < ms.count ? ms[i + 1].range.location : ns.length
            if remote.contains(Py.lower(m.group(1) ?? "")) {
                kept.append(ns.substring(with: NSRange(location: start, length: end - start)))
            }
        }
        return kept.joined(separator: " ")
    }

    func prep(_ text: String) -> String { maskBrands(cleanText(callerOnly(text))) }
}
