package com.example.scamdetect

import com.example.scamdetect.data.CallScript
import com.example.scamdetect.logic.ScriptTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** How scripts are titled and grouped in the picker. The same cases as iOS ScriptTitlesTests. */
class ScriptTitlesTest {
    /** The app's real script files, with no lines (only the names matter here). */
    private val files = File("src/main/assets/scripts").list()!!.filter { it.endsWith(".json") }.sorted()

    private fun script(name: String): CallScript {
        val p = name.removeSuffix(".json").split("_")
        return CallScript(name, p[0], p[1].takeIf { it == "en" || it == "zh" }, "", emptyList())
    }

    private val all = ScriptTitles.withTitles(files.map(::script))
    private fun title(name: String) = all.first { it.fileName == "$name.json" }.title

    @Test fun learningTestsGetClearTitles() {
        assertEquals("Learning test A (new scam type)", title("scam_en_90_new_scam_test_A_report_this"))
        assertEquals("Learning test B (same scam, new words)", title("scam_en_91_new_scam_test_B_same_scam_new_words"))
    }

    @Test fun acronymsAndNames() {
        assertEquals("Bank OTP", title("scam_en_11_bank_otp"))
        assertEquals("ICA to China police", title("scam_en_20_ica_to_china_police"))
        assertEquals("Telco to MAS officer", title("scam_en_21_telco_to_mas_officer"))
        assertEquals("ICA passport collection", title("normal_en_20_ica_passport_collection"))
        assertEquals("Apple support crypto", title("scam_en_22_apple_support_crypto"))
        assertEquals("Bank reminder", title("normal_en_10_bank_reminder"))
        assertEquals("weird.json", ScriptTitles.baseTitle("weird.json"))   // no title part: the file name
    }

    @Test fun chineseTwinsSayChinese() {
        assertEquals("Parcel customs", title("scam_en_12_parcel_customs"))
        assertEquals("Parcel customs (Chinese)", title("scam_zh_12_parcel_customs"))
        assertEquals("ICA to China police (Chinese)", title("scam_zh_20_ica_to_china_police"))
        assertEquals("Gov official (Chinese)", title("scam_zh_10_gov_official"))
        assertEquals("Bank reminder (Chinese)", title("normal_zh_10_bank_reminder"))
        assertEquals("Family call (Chinese)", title("normal_zh_14_family_call"))
        // no two scripts share a title any more
        assertEquals(all.size, all.map { it.title }.toSet().size)
        // a Chinese script with no English twin keeps its title
        val lone = ScriptTitles.withTitles(listOf(script("scam_zh_99_only_chinese.json")))
        assertEquals("Only chinese", lone.single().title)
    }

    @Test fun learningTestsAreGroupedSeparately() {
        val (regular, tests) = ScriptTitles.sections(all)
        assertEquals(listOf("scam_en_90_new_scam_test_A_report_this.json", "scam_en_91_new_scam_test_B_same_scam_new_words.json"),
            tests.map { it.fileName })
        assertTrue(regular.none { ScriptTitles.isLearningTest(it.fileName) })
        assertEquals(all.size, regular.size + tests.size)
        assertTrue("the real demo calls are all still there", regular.size >= 23)
    }

    /** "Simulate scam call" / "Simulate normal call" never play a learning-loop test, however often they're pressed. */
    @Test fun randomPickNeverReturnsATestScript() {
        val rnd = Random(42)
        var last: String? = null
        val seen = mutableSetOf<String>()
        repeat(5_000) {
            val kind = if (it % 2 == 0) "scam" else "normal"
            val pick = ScriptTitles.pickRandom(all, kind, last, rnd)
            assertNotNull(pick)
            assertEquals(kind, pick!!.kind)
            assertFalse(pick.fileName, ScriptTitles.isLearningTest(pick.fileName))
            assertTrue("no repeat of the last pick", pick.fileName != last || all.count { s -> s.kind == kind } == 1)
            last = pick.fileName
            seen += pick.fileName
        }
        // every other scam and normal script does come up
        assertEquals(all.filter { !ScriptTitles.isLearningTest(it.fileName) }.map { it.fileName }.toSet(), seen)
        // only learning tests of a kind: nothing to pick
        val onlyTests = all.filter { ScriptTitles.isLearningTest(it.fileName) }
        assertEquals(null, ScriptTitles.pickRandom(onlyTests, "scam", null, rnd))
    }
}
