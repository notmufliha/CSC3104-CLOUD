package com.example.scamdetect.ondevice

import java.text.Normalizer

// Workstream G.2: the English workers' text cleaning, ported line by line. Each worker has its own cleaning file
// (they are not one shared file), so each has its own port here. If a worker's Python changes, change the port
// and re-run tools/parity_cases.py: the parity tests compare both, sentence by sentence.

/** backend/workers/urgency_en/text_norm.py `normalize` + predict.py `caller_turns` / `windows`. */
object UrgencyText {
    private val TAG_MAP = mapOf(
        "MONEY_AMOUNT" to " moneytoken ", "PHONE_NUMBER" to " phonetoken ", "EMAIL_ADDRESS" to " emailtoken ",
        "URL" to " urltoken ", "DATE" to " timetoken ", "DATE_OF_BIRTH" to " timetoken ", "TIME" to " timetoken ",
        "DURATION" to " numtoken ", "NUMBER" to " numtoken ",
    )
    private val TAG = Py.re("""\[([A-Z_]+)\]""")
    private val SPEAKER = Py.re(
        """^\s*(person [ab]|caller|receiver|agent|customer|suspect|innocent|user|system|assistant)\s*:\s*""", true)
    private val EMAIL = Py.re("""\b[\w.+-]+@[\w-]+\.[\w.]+\b""")
    private val URL = Py.re("""(https?://\S+|www\.\S+|\b[\w-]+\.(com|org|net|gov|sg|cn|io|co)(/\S*)?\b)""", true)
    private val MONEY = Py.re("""([$¥€£]\s?\d[\d,]*(\.\d+)?|\b\d[\d,]*(\.\d+)?\s?(dollars|sgd|usd|yuan|rmb|cny)\b)""", true)
    private val DATE = Py.re("""\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b""")
    private val PHONE_CAND = Py.re("""(?<![\w])\+?\(?\d[\d\s().-]{5,18}\d(?![\w])""")
    private val TIME = Py.re("""\b\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?\b|\b\d{1,2}\s?[ap]\.?m\.?\b""", true)
    private val HOURS = Py.re("""\b\d+\s?(h|hr|hrs)\b""", true)
    private val MINS = Py.re("""\b\d+\s?(m|min|mins)\b""", true)
    private val CODE = Py.re("""\b(?=[a-z]*\d)(?=\d*[a-z])[a-z\d]{3,}\b""", true)
    private val NUM = Py.re("""\d[\d,.]*""")
    private val NOT_LETTERS = Py.re("""[^a-z' ]+""")
    private val STRAY_QUOTE = Py.re("""(?<![a-z])'|'(?![a-z])""")
    private val SPACES = Py.re("""\s+""")

    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        t = SPEAKER.replace(t, "")
        t = TAG.replace(t) { TAG_MAP[it.groupValues[1]] ?: " " }
        t = t.replace('’', '\'').replace('‘', '\'')
        t = EMAIL.replace(t, " emailtoken ")
        t = URL.replace(t, " urltoken ")
        t = MONEY.replace(t, " moneytoken ")
        t = DATE.replace(t, " timetoken ")
        t = TIME.replace(t, " timetoken ")
        t = PHONE_CAND.replace(t) { m -> if (m.value.count { it.isDigit() } in 7..15) " phonetoken " else m.value }
        t = HOURS.replace(t, " numtoken hours ")
        t = MINS.replace(t, " numtoken minutes ")
        t = CODE.replace(t, " numtoken ")
        t = NUM.replace(t, " numtoken ")
        t = Py.lower(t)
        t = NOT_LETTERS.replace(t, " ")
        t = STRAY_QUOTE.replace(t, " ")
        return Py.strip(SPACES.replace(t, " "))
    }

    private val SENTENCE_END = Py.re("""(?<=[.!?])\s+""")
    private const val MAX_WORDS = 40
    private const val WINDOW_WORDS = 20
    private const val WINDOW_STEP = 10
    private val CALLER_TAGS = setOf("caller", "agent", "suspect", "scammer")
    private val LISTENER_TAGS = setOf("receiver", "customer", "innocent", "victim", "user")
    private val TAG_LINE = Py.re("""^\s*([a-z]+)\s*:\s*(.*)$""", true)

    /** The caller's turns if the text is speaker-tagged, else the whole text. */
    fun callerTurns(transcript: String): List<String> {
        val turns = mutableListOf<String>()
        var tagged = false
        var speaker: String? = null
        for (raw in Py.splitLines(transcript)) {
            var line = raw
            val m = TAG_LINE.matchEntire(line)
            if (m != null && Py.lower(m.groupValues[1]) in CALLER_TAGS + LISTENER_TAGS) {
                tagged = true
                speaker = Py.lower(m.groupValues[1])
                line = m.groupValues[2]
            }
            if (speaker == null || speaker in CALLER_TAGS) turns += line
        }
        return if (tagged) turns else listOf(transcript)
    }

    /** Sentences plus pairs of neighbouring sentences; the worker's score is the highest over these. */
    fun windows(transcript: String): List<String> {
        val sentences = mutableListOf<String>()
        for (turn in callerTurns(transcript)) {
            for (s in SENTENCE_END.split(turn)) {
                val words = Py.split(s)
                if (words.size <= MAX_WORDS) sentences += s
                else for (i in 0 until maxOf(words.size - WINDOW_STEP, 1) step WINDOW_STEP) {
                    sentences += words.subList(i, minOf(i + WINDOW_WORDS, words.size)).joinToString(" ")
                }
            }
        }
        val kept = sentences.filter { normalize(it).isNotEmpty() }
        return kept + kept.zipWithNext { a, b -> "$a $b" }
    }
}

/**
 * money_norm.py v2 (Workstream D, money_en_v2c): brand names become their type token (DBS -> orgbank, PayNow ->
 * payapp; BRAND_RE) and amounts written as words become moneytoken (WORD_MONEY_RE). Only used when the model was
 * trained with mask_brands; the brand list comes from the exported model file (money_norm.BRAND_TYPES).
 */
class MoneyBrands(brandTypes: Map<String, List<String>>) {
    // _BRAND_TO_TYPE = {name: type ...}: a later type wins for a repeated name; insertion order kept
    private val toType = LinkedHashMap<String, String>().apply { brandTypes.forEach { (type, names) -> names.forEach { put(it, type) } } }
    private val brand: Regex? = toType.keys.sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.let { names -> Py.re("""\b(""" + names.joinToString("|") { Regex.escape(it) } + """)(?![\w])""") }

    fun apply(lowered: String): String {
        val t = brand?.replace(lowered) { toType.getValue(it.groupValues[1]) } ?: lowered
        return WORD_MONEY.replace(t, " moneytoken ")
    }

    private companion object {
        const val NUMW = "(?:a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
            "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|" +
            "million|and|numtoken)"
        val WORD_MONEY = Py.re("""\b(?:""" + NUMW + """[ -]){1,6}(?:dollars?|bucks|sgd|ringgit)\b""" +
            """|\bnumtoken ?(?:k|grand)\b|\b(?:a|one|two|three|five|ten) grand\b""")
    }
}

/** backend/workers/money_en/money_norm.py `clean_text` + `split_sentences`. */
object MoneyText {
    private val PLACEHOLDER = Py.re("""\[([A-Za-z_ ]{2,40})\]""")

    private fun placeholder(name: String): String {
        val k = name.uppercase(java.util.Locale.ROOT)
        fun any(vararg w: String) = w.any { it in k }
        return when {
            any("MONEY", "AMOUNT", "PRICE", "BALANCE") -> " moneytoken "
            any("OTP", "PIN", "CVV", "TAC", "PASSCODE", "PASSWORD", "CODE") -> " codetoken "
            any("ORG", "COMPANY", "BUSINESS") -> " orgtoken "
            "NAME" in k -> " nametoken "
            any("DATE", "BIRTH", "DOB") -> " datetoken "
            "PHONE" in k -> " phonetoken "
            any("EMAIL", "URL", "WEBSITE", "LINK") -> " linktoken "
            any("ADDRESS", "LOCATION", "CITY", "ZIP", "POSTAL", "STREET") -> " loctoken "
            "CARD" in k -> " cardtoken "
            any("ACCOUNT", "NUMBER", "SSN", "ID") -> " numtoken "
            any("TIME", "DURATION") -> " timetoken "
            else -> " "
        }
    }

    private const val MONTHS = "(?:january|february|march|april|may|june|july|august|september|october|" +
        "november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
    private val HTML = Py.re("""<[^>]+>""")
    private val CLEAN_STEPS: List<Pair<Regex, String>> = listOf(
        Py.re("""\S+@\S+\.\S+""") to " linktoken ",
        Py.re("""(?:https?://|www\.)\S+""", true) to " linktoken ",
        Py.re("""\b[\w-]+\.(?:com|net|org|sg|gov|io|co|info|biz)\b\S*""", true) to " linktoken ",
        Py.re("""(?:s\$|us\$|\$|£|€|¥|\bsgd|\busd|\brm)\s?\d[\d,]*(?:\.\d+)?""" +
            """(?:\s?(?:k|m|million|billion|thousand)\b)?""", true) to " moneytoken ",
        Py.re("""\b\d[\d,]*(?:\.\d+)?\s?(?:dollars?|bucks|yuan|rmb|cents?|sgd|usd|""" +
            """ringgit|pounds?|euros?)\b""", true) to " moneytoken ",
        Py.re("""\b(?:\d[ -]?){12,19}\b""") to " cardtoken ",
        Py.re("""(?:\+?\d{1,3}[\s-]?)?\(?\d{3}\)?X?[\s.-]?\(?\d{3}\)?X?[\s.-]?\(?\d{4}\)?""") to " phonetoken ",
        Py.re("""\b[689]\d{3}\s?\d{4}\b""") to " phonetoken ",
        Py.re("""\b\d{1,2}(?:st|nd|rd|th)?\s+(?:of\s+)?""" + MONTHS + """\b\.?(?:,?\s+\d{4})?""", true) to " datetoken ",
        Py.re("""\b""" + MONTHS + """\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?\b""", true) to " datetoken ",
        Py.re("""\b\d{1,4}[/-]\d{1,2}[/-]\d{2,4}\b""") to " datetoken ",
        Py.re("""\b\d{1,2}(?::\d{2})?\s?(?:am|pm|a\.m\.|p\.m\.)""", true) to " timetoken ",
        Py.re("""\b\d+(?:st|nd|rd|th)\b""", true) to " numtoken ",
        Py.re("""\d+(?:[.,]\d+)*""") to " numtoken ",
    )
    private val CONTRACTIONS: List<Pair<Regex, String>> = listOf(
        Py.re("""\bwon't\b""") to "will not", Py.re("""\bcan't\b""") to "cannot",
        Py.re("""\bshan't\b""") to "shall not", Py.re("""n't\b""") to " not",
    )
    private val NOT_LETTERS = Py.re("""[^a-z\s]""")
    private val REPEATED_TOKEN = Py.re("""\b(\w+token)(?:\s+\1\b)+""")
    private val SPACES = Py.re("""\s+""")

    private fun lightNorm(text: String): String {
        val t = text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        return Py.lower(Py.strip(SPACES.replace(t, " ")))
    }

    /** `brands` = the v2 brand/amount masking (clean_text(mask_brands=True)); null = v1's cleaning exactly. */
    fun clean(text: String, brands: MoneyBrands? = null): String {
        var t = PLACEHOLDER.replace(text) { placeholder(it.groupValues[1]) }
        t = HTML.replace(t, " ")
        for ((rx, rep) in CLEAN_STEPS) t = rx.replace(t, rep)
        t = Normalizer.normalize(t, Normalizer.Form.NFKD).filter { it.code < 128 }   // .encode("ascii", "ignore")
        t = lightNorm(t)
        if (brands != null) t = brands.apply(t)
        for ((rx, rep) in CONTRACTIONS) t = rx.replace(t, rep)
        t = t.replace("'", "")
        t = NOT_LETTERS.replace(t, " ")
        t = REPEATED_TOKEN.replace(t) { it.groupValues[1] }
        return Py.strip(SPACES.replace(t, " "))
    }

    private val GLUED = Py.re("""([a-z][.!?])([A-Z])""")
    private val NEWLINES = Py.re("""\s*\n+\s*""")
    private val SENT_END = Py.re("""(?<=[.!?])\s+""")

    /** Sentences; pieces longer than 40 words are cut into 20-word chunks. */
    fun splitSentences(text: String, maxWords: Int = 40, chunkWords: Int = 20): List<String> {
        val out = mutableListOf<String>()
        val t = GLUED.replace(text) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        for (block in NEWLINES.split(t)) for (raw in SENT_END.split(block)) {
            val s = Py.strip(raw)
            if (s.isEmpty()) continue
            val words = Py.split(s)
            if (words.size > maxWords) {
                for (i in words.indices step chunkWords) out += words.subList(i, minOf(i + chunkWords, words.size)).joinToString(" ")
            } else out += s
        }
        return out
    }
}

/**
 * backend/workers/impersonation_en/text_utils.py `prep(mask=True, caller=True)`. The organisation names it masks
 * come from the exported model file (they belong to the model they were trained with).
 */
class ImpersonationText(orgTypes: Map<String, List<String>>) {
    private val tag = Py.re("""\[[A-Za-z_ ]{2,30}\]""")
    private val speaker = Py.re(
        """\b(?:caller|receiver|agent|customer|person\s*[ab12]|speaker\s*\d|scammer|victim|user)\s*:""", true)
    private val digits = Py.re("""\d{4,}""")
    private val spaces = Py.re("""\s+""")
    private val labelSplit = Py.re("""\b(caller|scammer|agent|receiver|customer|victim|user)\s*:""", true)
    private val remote = setOf("caller", "scammer", "agent")

    private val nameToType: Map<String, String> = buildMap { orgTypes.forEach { (type, names) -> names.forEach { put(it, type) } } }
    private val org: Regex? = orgTypes.values.flatten().sortedByDescending { it.length }
        .takeIf { it.isNotEmpty() }
        ?.let { names -> Py.re("""\b(""" + names.joinToString("|") { Regex.escape(it) } + """)\b""") }

    fun cleanText(text: String): String {
        var t = tag.replace(text, " ")
        t = speaker.replace(t, " ")
        t = Py.lower(t)
        t = digits.replace(t, "longnumber")
        return Py.strip(spaces.replace(t, " "))
    }

    fun maskBrands(text: String): String = org?.replace(text) { nameToType.getValue(it.groupValues[1]) } ?: text

    /** Every word the caller says; the receiver's turns are dropped. Untagged text is returned unchanged. */
    fun callerOnly(text: String): String {
        val matches = labelSplit.findAll(text).toList()
        if (matches.isEmpty()) return text
        val kept = mutableListOf(text.substring(0, matches[0].range.first))
        matches.forEachIndexed { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            if (Py.lower(m.groupValues[1]) in remote) kept += text.substring(m.range.last + 1, end)
        }
        return kept.joinToString(" ")
    }

    fun prep(text: String): String = maskBrands(cleanText(callerOnly(text)))
}
