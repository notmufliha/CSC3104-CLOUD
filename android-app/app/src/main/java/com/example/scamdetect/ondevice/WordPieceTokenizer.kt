package com.example.scamdetect.ondevice

import java.text.Normalizer

/**
 * Workstream G.4: BERT's WordPiece tokenizer (what Hugging Face's BertTokenizer / tokenizers' BertWordPieceTokenizer
 * do), for MobileBERT on the phone. Reads the model's own vocab.txt (one token per line, line number = id).
 *
 * Steps, as in BERT's reference code: clean the text (drop control characters, whitespace -> space), put spaces
 * around CJK characters, lowercase + strip accents if the model is uncased, split on whitespace and punctuation,
 * then split each word greedily into the longest vocabulary pieces ("##" for word-inner pieces); a word that can't
 * be split, or is longer than 100 characters, becomes [UNK].
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val lowerCase: Boolean = true,
    private val maxCharsPerWord: Int = 100,
) {
    val clsId = id(CLS)
    val sepId = id(SEP)
    val unkId = id(UNK)

    private fun id(token: String) = requireNotNull(vocab[token]) { "vocab.txt has no $token" }

    /** Text -> wordpiece tokens (no [CLS]/[SEP]). */
    fun tokenize(text: String): List<String> = basic(text).flatMap(::wordPiece)

    /**
     * Text -> ids with [CLS] ... [SEP], at most `maxLen` long. Too long: the OLDEST tokens are dropped (left
     * truncation, like the server's tokenizer), so the newest line is always read.
     */
    fun encode(text: String, maxLen: Int): LongArray {
        require(maxLen >= 3)
        val ids = tokenize(text).map { vocab[it] ?: unkId }
        val kept = ids.takeLast(maxLen - 2)
        return LongArray(kept.size + 2).also { out ->
            out[0] = clsId.toLong()
            kept.forEachIndexed { i, v -> out[i + 1] = v.toLong() }
            out[out.size - 1] = sepId.toLong()
        }
    }

    // ------------------------------------------------------------ basic tokenizer
    private fun basic(text: String): List<String> {
        val cleaned = StringBuilder()
        text.codePoints().forEach { cp ->
            when {
                cp == 0 || cp == 0xFFFD || isControl(cp) -> Unit
                isWhitespace(cp) -> cleaned.append(' ')
                isCjk(cp) -> cleaned.append(' ').appendCodePoint(cp).append(' ')
                else -> cleaned.appendCodePoint(cp)
            }
        }
        val words = mutableListOf<String>()
        for (raw in cleaned.split(' ').filter { it.isNotEmpty() }) {
            var w = raw
            if (lowerCase) w = stripAccents(w.lowercase(java.util.Locale.ROOT))
            // split on punctuation: every punctuation character is its own token
            val cur = StringBuilder()
            w.codePoints().forEach { cp ->
                if (isPunctuation(cp)) {
                    if (cur.isNotEmpty()) { words += cur.toString(); cur.clear() }
                    words += String(Character.toChars(cp))
                } else cur.appendCodePoint(cp)
            }
            if (cur.isNotEmpty()) words += cur.toString()
        }
        return words
    }

    // ------------------------------------------------------------ wordpiece
    private fun wordPiece(word: String): List<String> {
        val cps = word.codePoints().toArray()
        if (cps.size > maxCharsPerWord) return listOf(UNK)
        val out = mutableListOf<String>()
        var start = 0
        while (start < cps.size) {
            var end = cps.size
            var piece: String? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + String(cps, start, end - start)
                if (sub in vocab) { piece = sub; break }
                end -= 1
            }
            if (piece == null) return listOf(UNK)
            out += piece
            start = end
        }
        return out
    }

    companion object {
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        const val UNK = "[UNK]"

        /** vocab.txt: one token per line; the line number (from 0) is its id. */
        fun fromVocabLines(lines: Sequence<String>, lowerCase: Boolean = true): WordPieceTokenizer {
            val vocab = HashMap<String, Int>()
            lines.forEachIndexed { i, line -> vocab.putIfAbsent(line.trimEnd('\r', '\n'), i) }
            return WordPieceTokenizer(vocab, lowerCase)
        }

        private fun stripAccents(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

        private fun isWhitespace(cp: Int) =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            val t = Character.getType(cp)
            return t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt()
        }

        /** BERT counts all non-letter/number ASCII (e.g. $, ^, `) as punctuation, plus Unicode P* categories. */
        private fun isPunctuation(cp: Int): Boolean {
            if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
            return when (Character.getType(cp).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
            cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF ||
            cp in 0x2F800..0x2FA1F
    }
}
