package com.example.scamdetect

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Tr
import com.example.scamdetect.logic.Txt
import com.example.scamdetect.ui.AdvancedPage
import com.example.scamdetect.ui.AdvancedRows
import com.example.scamdetect.ui.DeveloperRow
import com.example.scamdetect.ui.HelpPage
import com.example.scamdetect.ui.SettingsMenuScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Normal Settings (both app styles) and Help & about show no server address, IP, presets or model versions; the
 * Developer view's row appears only once it is unlocked.
 */
@RunWith(AndroidJUnit4::class)
class DeveloperSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun strings() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Tr.txt = Txt { id, a -> ctx.getString(id, *a) }
    }

    /** Everything any node says (text and content description), the whole tree. */
    private fun SemanticsNodeInteractionsProvider.allText(): String =
        onAllNodes(androidx.compose.ui.test.SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") { n ->
            (n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "") + " " +
                (n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: "")
        }

    private val technical = Regex("""https?://|10\.0\.2\.2|192\.168|localhost|:8080|/api/|Presets|Server|Updates|federated|sha256|MobileBERT|Load sample""", RegexOption.IGNORE_CASE)

    @Test fun standardSettingsHaveNoServerOrModels() {
        val unlocked = mutableStateOf(false)
        rule.setContent {
            ScamDetectTheme {
                SettingsMenuScreen(PrivacyLevel.CLOUD, connection = false, callCount = 3, onRefresh = {}, onOpen = {},
                    textSize = "normal", readAloud = false, onReadAloud = {}, familyName = null, showCareful = true,
                    onShowCareful = {}, pinSet = false, developerUnlocked = unlocked.value)
            }
        }
        val text = rule.allText()
        assertTrue(text, !technical.containsMatchIn(text))
        assertTrue("plain status", text.contains("Can't connect to ScamGuard") && text.contains("Ask the person who set up this phone for help"))
        assertEquals(0, rule.onAllNodesWithTag("rowDeveloper").fetchSemanticsNodes().size)
        unlocked.value = true
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("rowDeveloper").fetchSemanticsNodes().size)
    }

    @Test fun simpleAdvancedSettingsHaveNoServerOrModels() {
        val unlocked = mutableStateOf(false)
        rule.setContent {
            ScamDetectTheme {
                AdvancedPage({
                    AdvancedRows(true, true, {}, false, PrivacyLevel.HYBRID, true, 0, {}, {})
                    if (unlocked.value) DeveloperRow {}
                }, onBack = {})
            }
        }
        val text = rule.allText()
        assertTrue(text, !technical.containsMatchIn(text))
        assertTrue(text.contains("Connected ✓"))
        assertEquals(0, rule.onAllNodesWithTag("rowDeveloper").fetchSemanticsNodes().size)
        unlocked.value = true
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodesWithTag("rowDeveloper").fetchSemanticsNodes().size)
    }

    @Test fun helpHasNoModelVersionsOrDebugTools() {
        var taps = 0
        rule.setContent { ScamDetectTheme { HelpPage("1.0", onBack = {}, onVersionTap = { taps++; null }) } }
        val text = rule.allText()
        assertTrue(text, !technical.containsMatchIn(text))
        rule.onNodeWithTag("aboutVersion", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, taps)
    }
}
