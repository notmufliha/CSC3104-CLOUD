package com.example.scamdetect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.logic.FinalReview
import com.example.scamdetect.logic.Learn
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.SampleHistory
import com.example.scamdetect.logic.Tr
import com.example.scamdetect.logic.Txt
import com.example.scamdetect.logic.Warnings
import com.example.scamdetect.ui.CallViewModel
import com.example.scamdetect.ui.DashboardScreen
import com.example.scamdetect.ui.HomeScreen
import com.example.scamdetect.ui.HomeStatus
import com.example.scamdetect.ui.InCallScreen
import com.example.scamdetect.ui.LearnScreen
import com.example.scamdetect.ui.Phase
import com.example.scamdetect.ui.QuizScreen
import com.example.scamdetect.ui.ScamTypeScreen
import com.example.scamdetect.ui.SettingsMenuScreen
import com.example.scamdetect.ui.SimpleHomeScreen
import com.example.scamdetect.ui.SummaryScreen
import com.example.scamdetect.ui.WarningActions
import com.example.scamdetect.ui.WarningScreen
import com.example.scamdetect.ui.WeeklySummaryScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme
import com.example.scamdetect.ui.tourDemoCall
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Accessibility Test Framework checks (touch target size, contrast, labels, …) on the main screens, at the default
 * text size and at the largest (system font scale 2.0 + the app's "Extra large"). A finding fails the test.
 */
@RunWith(Parameterized::class)
class AccessibilityAuditTest(private val largest: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "largest={0}") fun sizes() = listOf(false, true)
    }

    @get:Rule val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val learn by lazy { Learn.parse(app.assets.open("learn/learn.json").bufferedReader().use { it.readText() }) }
    private val calls: List<CallRecord> by lazy {
        val now = System.currentTimeMillis()
        SampleHistory.generate(now, now - 3_600_000).calls.sortedByDescending { it.endedAt }
    }

    @Before fun setUp() {
        Tr.txt = Txt { id, a -> app.getString(id, *a) }
        rule.enableAccessibilityChecks()
    }

    private fun audit(content: @Composable () -> Unit) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, if (largest) 2f else 1f)) {
                ScamDetectTheme(textScale = if (largest) 1.15f else 1f, seniorType = largest) { content() }
            }
        }
        rule.waitForIdle()
        rule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test fun standardHome() = audit { HomeScreen({}, {}, {}) }

    @Test fun simpleHome() = audit { SimpleHomeScreen(HomeStatus.NOT_CONNECTED, calls.take(3), {}, { false }, {}) }

    @Test fun settings() = audit {
        SettingsMenuScreen(PrivacyLevel.CLOUD, connection = false, callCount = 3, onRefresh = {}, onOpen = {}, textSize = "normal",
            readAloud = false, onReadAloud = {}, familyName = "Mei Ling", showCareful = true, onShowCareful = {}, pinSet = false)
    }

    @Test fun dashboard() = audit {
        DashboardScreen(calls, emptyList(), com.example.scamdetect.logic.DashboardStats.Period.WEEK, {}, {}, {}, {}, {})
    }

    @Test fun emptyDashboard() = audit {
        DashboardScreen(emptyList(), emptyList(), com.example.scamdetect.logic.DashboardStats.Period.WEEK, {}, {}, {}, {}, {}, simple = true)
    }

    @Test fun learnList() = audit { LearnScreen(learn, {}, {}, {}) }

    @Test fun scamType() = audit { ScamTypeScreen(learn.types.first(), {}, {}) }

    @Test fun quizQuestion() = audit { QuizScreen(learn, simple = false, best = 3, onFinished = {}, onBack = {}) }

    @Test fun quizAnswered() = audit { QuizScreen(learn, simple = true, best = null, onFinished = {}, onBack = {}, demo = "answered") }

    @Test fun quizResult() = audit { QuizScreen(learn, simple = false, best = 5, onFinished = {}, onBack = {}, demo = "result") }

    @Test fun weeklySummary() = audit {
        WeeklySummaryScreen("ScamGuard weekly summary (12–18 Nov): 9 calls checked. 2 scam calls warned (asked for money or a code). " +
            "1 'be careful'. Stay safe! Call 1799 if unsure.", reminder = false, onReminder = { com.example.scamdetect.logic.WeeklyReminder.Action.NOTHING },
            onPermission = {}, onBack = {})
    }

    @Test fun summaryWithAfterCallCard() = audit {
        val s = tourDemoCall(redAlert = false).copy(phase = Phase.ENDED, scamAlert = true,
            finalReview = FinalReview("SCAM", "", reviewed = true, topWindowSeqs = setOf(2L, 3L), callScore = 0.97f))
        SummaryScreen(s, {}, family = CallViewModel.Family("Mei Ling", "91234567"), includeLines = true)
    }

    @Test fun inCall() = audit { InCallScreen(tourDemoCall(redAlert = false), onHangUp = {}) }

    @Test fun redWarning() = audit { WarningScreen(Warnings.Kind.OTP, callEnded = false, familyName = "Mei Ling", actions = WarningActions(), demo = true) }
}
