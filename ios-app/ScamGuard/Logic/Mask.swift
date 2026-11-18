import Foundation

/// Port of android-app logic/Mask.kt (Workstream H.3): removes personal details from a caller line BEFORE the user is
/// asked to share it, so the consent screen shows exactly what leaves the phone. Same rules and tags as
/// backend/review/mask.py (checked against backend/review/mask_cases.json in the tests).
nonisolated enum Mask {
    private static func rx(_ p: String, _ ignoreCase: Bool = false) -> NSRegularExpression {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: p, options: ignoreCase ? [.caseInsensitive] : [])
    }

    private static let email = rx(#"\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b"#)
    private static let url = rx(#"\b(?:https?://|www\.)\S+|\b[\w-]+\.(?:com|sg|net|org|gov|io|co|my|cn)(?:/\S*)?\b"#, true)
    private static let money = rx(
        #"(?:(?:S?\$|SGD|USD|RM|MYR|RMB|¥|€|£)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|million|thousand))?)"# +
            #"|(?:\d[\d,]*(?:\.\d+)?\s?(?:dollars?|sgd|bucks|元|块))"#, true)
    private static let id = rx(#"\b[STFGM]\d{7}[A-Z]\b"#, true)
    private static let phone = rx(#"(?<![\w])\+?\d[\d\s-]{6,}\d(?![\w])"#)
    private static let digits = rx(#"\d+"#)
    private static let title = #"(?:officer|inspector|sergeant|mr|mrs|ms|madam|dr|doctor)\.?"#
    private static let name = rx(
        #"((?i:\b(?:my name is|this is|i am|i'm|speaking with)\s+(?:"# + title + #"\s+)?|\b"# + title + #"\s+))"# +
            #"([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)"#)
    private static let nameZh = rx(#"(我叫|我姓)([\u4e00-\u9fff]{1,3})"#)

    /// Capitalised words after "this is" / "I'm" that are not people (kept so impersonation stays labelable).
    private static let notNames: Set<String> = [
        "police", "singapore", "dbs", "ocbc", "uob", "posb", "maybank", "citibank", "hsbc", "standard", "moh",
        "mom", "iras", "cpf", "ica", "spf", "customs", "immigration", "interpol", "the", "your", "a", "an",
        "calling", "from", "with", "here", "just", "not", "sorry", "sure", "fine", "okay", "ok", "very",
        "shopee", "lazada", "grab", "singtel", "starhub", "m1", "dhl", "fedex", "ninja", "amazon", "apple",
        "microsoft", "google", "manager", "officer", "inspector", "sergeant", "madam", "sir", "doctor",
    ]

    static func mask(_ text: String) -> String {
        var t = Py.sub(email, text, "[EMAIL_ADDRESS]")
        t = Py.sub(url, t, "[URL]")
        t = Py.sub(money, t, "[MONEY_AMOUNT]")
        t = Py.sub(id, t, "[NUMBER]")
        t = Py.sub(phone, t, "[PHONE_NUMBER]")
        t = Py.sub(digits, t, "[NUMBER]")
        t = Py.sub(name, t) { m in
            let who = m.group(2) ?? ""
            let first = who.split(separator: " ").first.map(String.init)?.lowercased() ?? ""
            return (m.group(1) ?? "") + (notNames.contains(first) ? who : "[PERSON_NAME]")
        }
        t = Py.sub(nameZh, t) { m in (m.group(1) ?? "") + "[PERSON_NAME]" }
        return t
    }
}
