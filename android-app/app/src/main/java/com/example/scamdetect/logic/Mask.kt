package com.example.scamdetect.logic

/**
 * Workstream H.3: removes personal details from a caller line BEFORE the user is asked to share it, so the
 * consent screen shows exactly what leaves the phone. The review service masks again on arrival.
 * Keep in step with backend/review/mask.py (same rules, same tags).
 *
 * Masked: e-mail addresses, links, money amounts, phone numbers, ID numbers (S1234567D), any other digits
 * (OTPs, account numbers), and a name after "my name is" / "this is" / "I'm" / a title such as "Officer".
 */
object Mask {
    private val email = Regex("""\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b""")
    private val url = Regex("""\b(?:https?://|www\.)\S+|\b[\w-]+\.(?:com|sg|net|org|gov|io|co|my|cn)(?:/\S*)?\b""",
        RegexOption.IGNORE_CASE)
    private val money = Regex(
        """(?:(?:S?\$|SGD|USD|RM|MYR|RMB|¥|€|£)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|million|thousand))?)""" +
            """|(?:\d[\d,]*(?:\.\d+)?\s?(?:dollars?|sgd|bucks|元|块))""", RegexOption.IGNORE_CASE)
    private val id = Regex("""\b[STFGM]\d{7}[A-Z]\b""", RegexOption.IGNORE_CASE)
    private val phone = Regex("""(?<![\w])\+?\d[\d\s-]{6,}\d(?![\w])""")
    private val digits = Regex("""\d+""")
    private const val TITLE = """(?:officer|inspector|sergeant|mr|mrs|ms|madam|dr|doctor)\.?"""
    private val name = Regex(
        """((?i:\b(?:my name is|this is|i am|i'm|speaking with)\s+(?:$TITLE\s+)?|\b$TITLE\s+))""" +
            """([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)""")
    private val nameZh = Regex("""(我叫|我姓)([\u4e00-\u9fff]{1,3})""")

    /** Capitalised words after "this is" / "I'm" that are not people (kept so impersonation stays labelable). */
    private val notNames = setOf(
        "police", "singapore", "dbs", "ocbc", "uob", "posb", "maybank", "citibank", "hsbc", "standard", "moh",
        "mom", "iras", "cpf", "ica", "spf", "customs", "immigration", "interpol", "the", "your", "a", "an",
        "calling", "from", "with", "here", "just", "not", "sorry", "sure", "fine", "okay", "ok", "very",
        "shopee", "lazada", "grab", "singtel", "starhub", "m1", "dhl", "fedex", "ninja", "amazon", "apple",
        "microsoft", "google", "manager", "officer", "inspector", "sergeant", "madam", "sir", "doctor",
    )

    fun mask(text: String): String {
        var t = email.replace(text, "[EMAIL_ADDRESS]")
        t = url.replace(t, "[URL]")
        t = money.replace(t, "[MONEY_AMOUNT]")
        t = id.replace(t, "[NUMBER]")
        t = phone.replace(t, "[PHONE_NUMBER]")
        t = digits.replace(t, "[NUMBER]")
        t = name.replace(t) { m ->
            val who = m.groupValues[2]
            m.groupValues[1] + if (who.split(" ")[0].lowercase() in notNames) who else "[PERSON_NAME]"
        }
        t = nameZh.replace(t) { m -> m.groupValues[1] + "[PERSON_NAME]" }
        return t
    }
}
