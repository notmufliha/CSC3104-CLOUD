package com.example.scamdetect.ondevice

import java.util.regex.Pattern

/**
 * Workstream G.2: just enough of Python's `re` and `str` behaviour to port the workers' text cleaning exactly.
 *
 * Python 3 str patterns are Unicode-aware: \w, \d, \s and \b match any language's letters, digits and spaces, and
 * re.I folds case for all of them. Java's defaults are ASCII-only, so every pattern is compiled with
 * UNICODE_CHARACTER_CLASS (falling back to plain flags if a platform refused it). The parity tests run on the
 * JVM and on the phone, so a difference here shows up as a failed score.
 */
internal object Py {
    private val unicodeOk: Boolean = try {
        Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS); true
    } catch (e: IllegalArgumentException) {
        false
    }

    /** re.compile(pattern[, re.I]). */
    fun re(pattern: String, ignoreCase: Boolean = false): Regex {
        var flags = if (unicodeOk) Pattern.UNICODE_CHARACTER_CLASS else 0
        if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(pattern, flags).toRegex()
    }

    private val WHITESPACE = re("\\s+")

    /** str.split() with no argument: split on runs of whitespace, no empty strings. */
    fun split(s: String): List<String> = WHITESPACE.split(s).filter { it.isNotEmpty() }

    /** str.splitlines(): the same line breaks as Python, no trailing empty line. */
    fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = LINE_BREAK.split(s)
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }
    private val LINE_BREAK = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085  ]")

    /** str.strip() */
    fun strip(s: String): String = s.trim { it.isWhitespace() || it in '\u001C'..'\u001F' }

    /** str.lower() */
    fun lower(s: String): String = s.lowercase(java.util.Locale.ROOT)
}
