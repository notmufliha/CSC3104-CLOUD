package com.example.scamdetect.ui

import com.example.scamdetect.logic.Tr
import androidx.compose.ui.platform.testTag

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.ReportLine
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.logic.Warnings
import com.example.scamdetect.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SCAMSHIELD_URL = "https://www.scamshield.gov.sg/"

@Composable
internal fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onBack != null) Row {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text("‹ " + Tr.s(R.string.btn_back), style = MaterialTheme.typography.bodyLarge) }
            }
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

// ---------------------------------------------------------------- 4.1 Home

@Composable
fun HomeScreen(
    onScamCall: () -> Unit,
    onNormalCall: () -> Unit,
    onPickScript: () -> Unit,
    /** "Learn to spot scams". */
    onLearn: () -> Unit = {},
    /** An existing user's one-time "Try Simple mode?" card. */
    offer: (@Composable () -> Unit)? = null,
) {
    Page(stringResource(R.string.app_name_text)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            offer?.invoke()
            Text(stringResource(R.string.home_full_line), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            val wide = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            Button(onClick = onScamCall, modifier = wide.tourTarget(Tour.Target.SCAM_CALL)) { Text(stringResource(R.string.btn_sim_scam)) }
            Button(onClick = onNormalCall, modifier = wide) { Text(stringResource(R.string.btn_sim_normal)) }
            OutlinedButton(onClick = onPickScript, modifier = wide.tourTarget(Tour.Target.PICK_SCRIPT)) { Text(stringResource(R.string.btn_pick_script)) }
            OutlinedButton(onClick = onLearn, modifier = wide.testTag("learnButton")) { Text(stringResource(R.string.btn_learn)) }
        }
    }
}

// ---------------------------------------------------------------- 4.2 Script picker

private enum class ScriptSort(val labelRes: Int) {
    TYPE(R.string.pick_sort_type), TITLE(R.string.pick_sort_title), LANGUAGE(R.string.pick_sort_lang), LENGTH(R.string.pick_sort_short);
    val label: String get() = Tr.s(labelRes)
}

private fun kindLabel(kind: String) = when (kind) {
    "scam" -> Tr.s(R.string.pick_kind_scam)
    "normal" -> Tr.s(R.string.pick_kind_normal)
    else -> kind.uppercase()
}

private fun kindColor(kind: String) = when (kind) {
    "scam" -> ScamRed
    "normal" -> SafeGreen
    else -> NeutralGrey
}

private fun langLabel(lang: String?) = when (lang) {
    "en" -> Tr.s(R.string.pick_english)
    "zh" -> Tr.s(R.string.pick_chinese)
    else -> Tr.s(R.string.pick_auto)
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
fun ScriptPickerScreen(
    allScripts: List<CallScript>,
    onPick: (CallScript) -> Unit,
    onBack: () -> Unit,
    /** Developer view unlocked: the learning-loop test calls get their own section at the end. */
    showTestCalls: Boolean = false,
) {
    val scripts = allScripts.filterNot { com.example.scamdetect.logic.DevScripts.isTestCall(it.fileName) }
    val testCalls = if (showTestCalls) allScripts.filter { com.example.scamdetect.logic.DevScripts.isTestCall(it.fileName) } else emptyList()
    var kind by remember { mutableStateOf<String?>(null) }   // null = all
    var lang by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(ScriptSort.TYPE) }
    var filtersOpen by remember { mutableStateOf(false) }

    val shown = scripts
        .filter { kind == null || it.kind == kind }
        .filter { lang == null || it.lang == lang }
        .let { list ->
            when (sort) {
                ScriptSort.TYPE -> list.sortedWith(compareByDescending<CallScript> { it.kind == "scam" }.thenBy { it.title })
                ScriptSort.TITLE -> list.sortedBy { it.title }
                ScriptSort.LANGUAGE -> list.sortedWith(compareBy<CallScript> { it.lang ?: "~" }.thenBy { it.title })
                ScriptSort.LENGTH -> list.sortedWith(compareBy<CallScript> { it.lines.size }.thenBy { it.title })
            }
        }

    Page(Tr.s(R.string.pick_title), onBack) {
        if (scripts.isEmpty()) EmptyState(Tr.s(R.string.pick_none), icon = androidx.compose.material.icons.Icons.Filled.Info, compact = true)
        // Collapsed by default; the header always says what is active, so nothing is hidden silently.
        val active = listOfNotNull(
            kind?.replaceFirstChar { it.uppercase() },
            lang?.let { langLabel(it) },
            sort.label,
        ).joinToString(" · ")
        Row(
            Modifier.fillMaxWidth().clickable { filtersOpen = !filtersOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${if (filtersOpen) "▾" else "▸"}  " + Tr.s(R.string.pick_filter),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                Tr.s(R.string.pick_count, active, shown.size.toString(), scripts.size.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(filtersOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(Tr.s(R.string.pick_type)) {
                    listOf(null to Tr.s(R.string.pick_all), "scam" to Tr.s(R.string.pick_scam), "normal" to Tr.s(R.string.pick_normal)).forEach { (k, l) ->
                        PillChip(l, kind == k) { kind = k }
                    }
                }
                ChipRow(Tr.s(R.string.pick_language)) {
                    listOf(null to Tr.s(R.string.pick_all), "en" to Tr.s(R.string.pick_english), "zh" to Tr.s(R.string.pick_chinese)).forEach { (k, l) ->
                        PillChip(l, lang == k) { lang = k }
                    }
                }
                ChipRow(Tr.s(R.string.pick_sort)) {
                    ScriptSort.entries.forEach { s ->
                        PillChip(s.label, sort == s) { sort = s }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown + testCalls, key = { it.fileName }) { s ->
                if (s == testCalls.firstOrNull()) Text("Test calls (learning loop)", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).testTag("testCallsHeader"))
                val color = kindColor(s.kind)
                Card(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(color))
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                kindLabel(s.kind),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Text(s.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                Tr.s(R.string.pick_lines, langLabel(s.lang), s.lines.size.toString()),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 4.6 After-call summary

/**
 * B.5: leads with state.summaryLevel (the final verdict, else the live one), the same value as the saved history
 * row; the live verdict is shown small underneath. While the end-of-call answer is on its way (up to 3 s):
 * "Final review…".
 */
@Composable
fun SummaryScreen(
    state: CallUiState,
    onDone: () -> Unit,
    /** "Show 'Be careful' warnings": off = a "Be careful" call ends as plain "Call ended" (still saved). */
    showCareful: Boolean = true,
    /** Simple mode: no technical lines. */
    simple: Boolean = false,
    /** The trusted family contact ("Tell my family" on the after-call card); null = none. */
    family: CallViewModel.Family? = null,
    /** "Save the riskiest sentence": only then does the copied report summary include the (masked) caller lines. */
    includeLines: Boolean = false,
) {
    val context = LocalContext.current
    if (state.reviewing) {
        Page(stringResource(R.string.summary_title)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.height(32.dp).width(32.dp), strokeWidth = 4.dp)
                Text(stringResource(R.string.sum_checking), style = MaterialTheme.typography.titleMedium)
            }
            Text(stringResource(R.string.sum_checking_line), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val level = state.summaryLevel
    val review = state.finalReview
    val risky = review?.topWindowSeqs.orEmpty()
    val hiddenYellow = level == Risk.Level.YELLOW && !showCareful
    Page(stringResource(R.string.summary_title)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (hiddenYellow) RiskBadge(Risk.Level.LISTENING, stringResource(R.string.risk_call_ended), 40.dp, MaterialTheme.typography.headlineSmall)
            else RiskBadge(level, riskWord(level), 40.dp, MaterialTheme.typography.headlineSmall)
            if (!simple) Text(
                Tr.s(when {
                    review?.onDevice == true -> R.string.sum_during_phone
                    review?.reviewed == true -> R.string.sum_during
                    else -> R.string.sum_during_unavailable
                }, levelLabel(state.level)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The reason in plain words (the server's English text is only a fallback on the dashboard)
            when {
                hiddenYellow -> {}
                level == Risk.Level.RED -> StateBanner(Risk.Level.RED,
                    stringResource(Warnings.message(state.warningKind)) + " " + stringResource(Warnings.doLine(state.warningKind)))
                level == Risk.Level.YELLOW -> CarefulBanner(state.warningKind)
                level == Risk.Level.ERROR -> StateBanner(Risk.Level.ERROR, stringResource(R.string.error_banner))
            }
            // After a Scam or "Be careful" call: what to do now (nothing extra after a safe call)
            val afterCard = com.example.scamdetect.logic.AfterCall.shows(level, showCareful)
            if (afterCard) AfterCallCard(state, family, includeLines)
            // Android bug fixed (as iOS): "What triggered it" only when the call didn't end safe
            if (level != Risk.Level.GREEN && !hiddenYellow) {
                Text(stringResource(R.string.sum_triggered), style = MaterialTheme.typography.titleMedium)
                val shown = state.lines.filter { it.isCaller && (it.flags.isNotEmpty() || it.seq in risky) }
                if (shown.isEmpty()) Text(stringResource(R.string.sum_no_lines), style = MaterialTheme.typography.bodyLarge)
                shown.forEach { ChatBubble(it, riskyHere = it.seq in risky, showNotes = !simple) }
            }
            val wide = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            Button(onClick = onDone, modifier = wide.testTag("summaryDone")) {
                Text(stringResource(R.string.btn_done), style = MaterialTheme.typography.labelLarge)
            }
            // The card above already has "Report to ScamShield"; other calls keep the plain button.
            if (!afterCard) OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SCAMSHIELD_URL.toUri()))
                    } catch (e: ActivityNotFoundException) { // no browser on this device
                    }
                },
                modifier = wide,
                border = androidx.compose.foundation.BorderStroke(2.dp, ScamRed),
            ) { Text(stringResource(R.string.btn_report_scamshield), style = MaterialTheme.typography.labelLarge, color = WarningInkFor()) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val WARNING_LEVELS = setOf(Risk.Level.RED, Risk.Level.YELLOW)

// 4.7 History -> replaced by the Room dashboard (Workstream B.7): DashboardScreens.kt

// 4.8 Settings -> SettingsScreens.kt (grouped menu with one page per topic)
