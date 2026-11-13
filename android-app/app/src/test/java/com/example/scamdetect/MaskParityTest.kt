package com.example.scamdetect

import com.example.scamdetect.logic.Mask
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Mask.kt must mask exactly like the review service (backend/review/mask.py) and the web app: all three are checked
 * against the same cases, backend/review/mask_cases.json (written from mask.py; see backend/review/test_mask.py).
 */
class MaskParityTest {
    private fun casesFile(): File =
        listOf("../../backend/review/mask_cases.json", "../backend/review/mask_cases.json", "backend/review/mask_cases.json")
            .map(::File).first { it.isFile }

    @Test
    fun masksLikeTheReviewService() {
        val cases = JsonParser.parseString(casesFile().readText()).asJsonObject.getAsJsonArray("cases")
        cases.forEach { c ->
            val o = c.asJsonObject
            val text = o.get("text").asString
            assertEquals(text, o.get("masked").asString, Mask.mask(text))
        }
    }
}
