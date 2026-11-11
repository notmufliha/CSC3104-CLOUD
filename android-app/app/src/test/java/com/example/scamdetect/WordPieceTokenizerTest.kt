package com.example.scamdetect

import com.example.scamdetect.ondevice.MobileBert
import com.example.scamdetect.ondevice.WordPieceTokenizer
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Workstream G.4: the phone's WordPiece tokenizer against Hugging Face `tokenizers` (BertWordPieceTokenizer,
 * lowercase, left truncation), on the windows tools/mobilebert_fixtures.py wrote; plus the "not installed" path.
 */
class WordPieceTokenizerTest {
    private val dir = File("src/androidTest/assets/mobilebert_dummy")
    private val tokenizer = WordPieceTokenizer.fromVocabLines(File(dir, "vocab.txt").readLines().asSequence(), lowerCase = true)
    private val fixture = JsonParser.parseString(File(dir, "cases.json").readText()).asJsonObject
    private val maxLen = fixture["max_len"].asInt
    private val cases = fixture["cases"].asJsonArray.map { it.asJsonObject }

    @Test fun idsMatchHuggingFaceTokenizers() {
        assertTrue(cases.size >= 30)
        val failures = cases.mapNotNull { c ->
            val window = c["window"].asString
            val want = c["ids"].asJsonArray.map { it.asLong }.toLongArray()
            val got = tokenizer.encode(window, maxLen)
            if (got.contentEquals(want)) null else "\"$window\"\n  hf:    ${want.toList()}\n  phone: ${got.toList()}"
        }
        assertTrue("${failures.size} of ${cases.size} differ\n" + failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    @Test fun windowMatchesTheServersMakeWindow() {
        cases.forEach { c ->
            val lines = c["lines"].asJsonArray.map { it.asString }
            assertEquals(c["window"].asString, MobileBert.window(lines))
        }
    }

    @Test fun leftTruncationKeepsTheNewestWords() {
        val long = (1..60).joinToString(" ") { "please" } + " transfer money now"
        val ids = WordPieceTokenizer(mapOf("[CLS]" to 0, "[SEP]" to 1, "[UNK]" to 2, "please" to 3, "transfer" to 4, "money" to 5, "now" to 6))
            .encode(long, 8)
        assertArrayEquals(longArrayOf(0, 3, 3, 3, 4, 5, 6, 1), ids)
    }

    @Test fun softmaxWithTemperature() {
        val p = MobileBert.softmax(floatArrayOf(0f, 2f), 2.0)
        assertEquals(1.0 / (1.0 + Math.exp(-1.0)), p[1], 1e-9)
        assertEquals(1.0, p.sum(), 1e-12)
    }

    @Test fun noModelFileMeansNotInstalled() {
        val none = MobileBert.load { null }
        assertTrue(none is MobileBert.Status.NotInstalled)
        assertTrue((none as MobileBert.Status.NotInstalled).reason.startsWith("MobileBERT not installed"))
    }

    @Test fun aModelWithoutItsVocabIsNotInstalledEither() {
        val onlyModel = MobileBert.load { name -> if (name == "model.onnx") "not really onnx".byteInputStream() else null }
        assertEquals("MobileBERT not installed (no vocab.txt)", (onlyModel as MobileBert.Status.NotInstalled).reason)
    }

    @Test fun theAppShipsNoMobileBertYet() {
        // Until Goutham's export is added, assets/models/mobilebert/ has no model.onnx and level 3 is small models only.
        assertTrue(!File("src/main/assets/models/mobilebert/model.onnx").exists())
    }
}
