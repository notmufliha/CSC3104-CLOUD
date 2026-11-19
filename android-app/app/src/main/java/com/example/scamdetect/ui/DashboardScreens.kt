package com.example.scamdetect.ui

import com.example.scamdetect.R
import com.example.scamdetect.logic.Tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ImplicitLabel
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.DashboardStats
import com.example.scamdetect.logic.DashboardStats.Period
import com.example.scamdetect.logic.PlainWords
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Reasons
import com.example.scamdetect.logic.Risk
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

// Dashboard (one question: "Am I safe, and what happened recently?"), Insights (the detailed charts), History (every
// call) and Call detail. Everything comes from the database on this phone; nothing is uploaded. Same layout, wording
// and numbers as ios-app/ScamGuard/UI/DashboardViews.swift.

/** Shared spacing, so every screen uses the same rhythm (the same values on iOS). */
object Space {
    val card = 20.dp
    val gap = 16.dp
    val corner = 24.dp
}

private fun dayLabel(daysAgo: Int): String = when (daysAgo) {
    0 -> Tr.s(R.string.pw_today)
    1 -> Tr.s(R.string.pw_yesterday)
    else -> LocalDate.now().minusDays(daysAgo.toLong()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
}

private fun clock(ms: Long) = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
private fun date(ms: Long) = java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))

/** "12 min ago", "Yesterday", or the date for older calls. */
fun whenText(endedAt: Long, now: Long = System.currentTimeMillis()): String {
    val d = DashboardStats.daysAgo(endedAt, now, ZoneId.systemDefault())
    return PlainWords.timeAgo(endedAt, now, d) ?: date(endedAt)
}

fun verdictWord(verdict: String) = PlainWords.verdict(verdict)

/** A saved verdict as the risk icon (✓ / ! / ✕ / i in its colour), so it never relies on colour alone. */
@Composable
fun VerdictDot(verdict: String, size: Int = 14) {
    RiskIcon(Dashboard.level(verdict), size.dp)
}

/** The one card style used everywhere. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    padding: Dp = Space.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Space.corner),
        color = color,
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** The ⓘ sheet: 1-2 sentences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(title: String, text: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 56.dp)) { Text(Tr.s(R.string.btn_ok)) }
        }
    }
}

/** Pill-shaped segmented control (the same look as iOS's segmented picker). */
@Composable
fun PeriodSwitcher(period: Period, onPick: (Period) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(4.dp)
            .selectableGroup(),
    ) {
        Period.entries.forEach { p ->
            val selected = p == period
            Box(
                Modifier.weight(1f).heightIn(min = 56.dp)
                    .background(if (selected) MaterialTheme.colorScheme.surfaceBright else Color.Transparent, CircleShape)
                    .selectable(selected = selected, role = Role.Tab) { onPick(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(PlainWords.periodLabel(p), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** A pill-shaped filter chip (the same look as iOS): filled purple when selected. */
@Composable
fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 56.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun EmptyState(text: String, onGoHome: (() -> Unit)? = null,
               icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Phone, compact: Boolean = false) {
    // The one empty state everywhere a list or chart can be empty: a friendly icon + one line (same on iOS).
    // compact: inside a card (smaller icon, less space).
    Column(Modifier.fillMaxWidth().padding(vertical = if (compact) 12.dp else 48.dp).testTag("emptyState")
        .semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
        Box(Modifier.size(if (compact) 64.dp else 104.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (compact) 32.dp else 52.dp))
        }
        Text(text, style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onGoHome != null) Button(onClick = onGoHome, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(Tr.s(R.string.btn_go_home), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** One call on one line: dot, Safe / Be careful / Scam, optional sparkline, when, chevron. At least 56 dp tall. */
@Composable
fun CallListRow(c: CallRecord, spark: List<Float>?, onClick: () -> Unit) {
    // Very large text: no sparkline and no fixed time column, so the row never grows wider than the screen.
    val big = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.card, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${verdictWord(c.finalVerdict)}, ${whenText(c.endedAt)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VerdictDot(c.finalVerdict, 28)
        if (big) {
            Column(Modifier.weight(1f)) {
                Text(verdictWord(c.finalVerdict), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(whenText(c.endedAt), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(verdictWord(c.finalVerdict), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            if (spark != null) Sparkline(spark)
            Text(whenText(c.endedAt), Modifier.widthIn(min = 96.dp), maxLines = 1, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An icon in a soft circle of its colour. */
@Composable
private fun IconBadge(icon: ImageVector, color: Color) {
    Box(Modifier.size(48.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
    }
}

private fun resultSlices(s: DashboardStats.Stats) = listOf(
    Slice(Tr.s(R.string.risk_safe), s.safe, SafeGreen, Risk.Level.GREEN), Slice(Tr.s(R.string.risk_careful), s.unsure, WarnYellow, Risk.Level.YELLOW),
    Slice(Tr.s(R.string.risk_scam), s.scam, ScamRed, Risk.Level.RED),
)

// ---------------------------------------------------------------- main dashboard (4 cards)

@Composable
fun DashboardScreen(
    calls: List<CallRecord>,
    labels: List<ImplicitLabel>,
    period: Period,
    onPeriod: (Period) -> Unit,
    onOpen: (String) -> Unit,
    onInsights: () -> Unit,
    onHistory: () -> Unit,
    onGoHome: () -> Unit,
    /** Simple mode: this is the "For family" tab. */
    simple: Boolean = false,
    /** "Share weekly summary". */
    onWeekly: () -> Unit = {},
    /** The history was read (until then: a placeholder). */
    loaded: Boolean = true,
) {
    val now = System.currentTimeMillis()
    val stats = remember(calls, labels, period) { DashboardStats.compute(calls, labels, period, now, ZoneId.systemDefault()) }
    Page(Tr.s(if (simple) R.string.tab_family else R.string.dash_title)) {
        if (!loaded) { LoadingPlaceholder(); return@Page }
        if (calls.isEmpty()) {
            Box(Modifier.tourTarget(Tour.Target.DASHBOARD_HERO)) { EmptyState(Tr.s(R.string.dash_empty), onGoHome) }
            WeeklyButton(onWeekly)
            return@Page
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
            PeriodSwitcher(period, onPeriod)
            // 1. hero
            AppCard(Modifier.tourTarget(Tour.Target.DASHBOARD_HERO)) {
                Text(PlainWords.hero(stats.scam, stats.total, period), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(Modifier.size(112.dp)) { DonutChart(resultSlices(stats), Tr.s(R.string.dash_calls_center), size = 112.dp, compact = true) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        resultSlices(stats).forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SliceKey(s)
                                Text("  ${s.value} ${s.label}", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            WeeklyButton(onWeekly)
            // 2. recent calls
            AppCard(padding = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(start = Space.card, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Tr.s(R.string.home_recent), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onHistory, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(Tr.s(R.string.btn_see_all), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    calls.take(3).forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 50.dp))
                        CallListRow(c, null) { onOpen(c.id) }
                    }
                }
            }
            // 3. top warning sign
            val top = PlainWords.topWarning(stats)
            AppCard(onClick = onInsights) {
                Text(Tr.s(R.string.dash_top_sign), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (top != null) IconBadge(Icons.Filled.Warning, ScamRed) else IconBadge(Icons.Filled.CheckCircle, SafeGreen)
                    Text(top ?: Tr.s(R.string.dash_no_signs_period, PlainWords.periodPhrase(period)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 4. insights
            AppCard(color = MaterialTheme.colorScheme.primaryContainer, onClick = onInsights) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(Icons.Filled.Star, MaterialTheme.colorScheme.primary)
                    Text(Tr.s(R.string.dash_more_insights), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

// ---------------------------------------------------------------- Insights

/** One Insights section: header + ⓘ, the takeaway sentence, then the chart in a card. */
@Composable
private fun InsightSection(title: String, takeaway: String, info: String, content: @Composable ColumnScope.() -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Info, contentDescription = Tr.s(R.string.a11y_about, title), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(takeaway, style = MaterialTheme.typography.bodyLarge)
        AppCard(content = content)
    }
    if (showInfo) InfoSheet(title, info) { showInfo = false }
}

@Composable
private fun AxisEnds(left: String, right: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(left, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(right, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A legend without counts (colour key only). */
@Composable
private fun Key(slices: List<Slice>) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        slices.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SliceKey(s)
                Text("  ${s.label}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun InsightsScreen(
    calls: List<CallRecord>,
    labels: List<ImplicitLabel>,
    period: Period,
    onPeriod: (Period) -> Unit,
    onBack: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val s = remember(calls, labels, period) { DashboardStats.compute(calls, labels, period, now, ZoneId.systemDefault()) }
    Page(Tr.s(R.string.ins_title), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            PeriodSwitcher(period, onPeriod)
            if (s.isEmpty) {
                EmptyState(Tr.s(R.string.ins_none_period, PlainWords.periodPhrase(period)))
                return@Column
            }
            val first = dayLabel(s.buckets.first().daysAgo)
            val last = Tr.s(if (s.weekly) R.string.pw_this_week else R.string.pw_today)
            InsightSection(Tr.s(R.string.ins_over_time), PlainWords.overTime(s),
                Tr.s(if (s.weekly) R.string.ins_over_time_info_week else R.string.ins_over_time_info_day)) {
                StackedBarChart(s.buckets.map {
                    listOf(Slice(Tr.s(R.string.risk_safe), it.safe, SafeGreen, Risk.Level.GREEN), Slice(Tr.s(R.string.risk_careful), it.unsure, WarnYellow, Risk.Level.YELLOW),
                        Slice(Tr.s(R.string.risk_scam), it.scam, ScamRed, Risk.Level.RED), Slice(Tr.s(R.string.risk_not_checked), it.notChecked, NeutralGrey, Risk.Level.ERROR))
                }, Tr.s(R.string.a11y_chart_list, Tr.s(R.string.ins_over_time), s.buckets.joinToString { "${dayLabel(it.daysAgo)} ${it.total}" }))
                AxisEnds(first, last)
                Key(resultSlices(s))
            }
            if (s.reasons.isNotEmpty()) InsightSection(Tr.s(R.string.ins_why), PlainWords.reasons(s), Tr.s(R.string.ins_why_info)) {
                val rows = s.reasons.map { Reasons.localized(it.first) to it.second }
                HorizontalBars(rows, ScamRed, Tr.s(R.string.a11y_chart_list, Tr.s(R.string.ins_why), rows.joinToString { "${it.first} ${it.second}" }))
            }
            InsightSection(Tr.s(R.string.ins_signs), PlainWords.signals(s), Tr.s(R.string.ins_signs_info)) {
                val rows = s.signals.map { PlainWords.signalName(it.first) to it.second }
                HorizontalBars(rows, MaterialTheme.colorScheme.primary, Tr.s(R.string.a11y_chart_list, Tr.s(R.string.ins_signs), rows.joinToString { "${it.first} ${it.second}" }))
            }
            InsightSection(Tr.s(R.string.ins_trend), PlainWords.trend(s), Tr.s(R.string.ins_trend_info)) {
                RiskLineChart(s.buckets.map { it.avgRisk?.toFloat() }, Tr.s(R.string.ins_trend) + ": " +
                    s.buckets.joinToString { "${dayLabel(it.daysAgo)} ${it.avgRisk?.let { v -> "%.2f".format(v) } ?: Tr.s(R.string.a11y_none)}" })
                AxisEnds(first, last)
            }
            InsightSection(Tr.s(R.string.ins_hours), PlainWords.hours(s), Tr.s(R.string.ins_hours_info)) {
                HourChart(s.riskyHours, ScamRed, Tr.s(R.string.ins_hours) + ": " +
                    s.riskyHours.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:00 ${it.value}" })
            }
            InsightSection(Tr.s(R.string.ins_double), PlainWords.doubleCheck(s), Tr.s(R.string.ins_double_info)) {
                SegmentedBar(listOf(Slice(Tr.s(R.string.ins_confirmed), s.confirmed, ScamRed, Risk.Level.RED), Slice(Tr.s(R.string.ins_turned_safe), s.calmed, SafeGreen, Risk.Level.GREEN)),
                    Tr.s(R.string.a11y_double, s.confirmed.toString(), s.calmed.toString()))
            }
            InsightSection(Tr.s(R.string.ins_where), PlainWords.where(s), Tr.s(R.string.ins_where_info)) {
                val slices = s.privacy.map { (id, n) -> Slice(PrivacyLevel.badgeFor(id), n, privacyColor(id)) }
                SegmentedBar(slices, Tr.s(R.string.a11y_chart_list, Tr.s(R.string.ins_where), slices.joinToString { "${it.label} ${it.value}" }))
            }
            InsightSection(Tr.s(R.string.ins_answers), PlainWords.answers(s), Tr.s(R.string.ins_answers_info)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BigNumber(Modifier.weight(1f), "${s.markedFine}", Tr.s(R.string.ins_marked_fine), SafeGreen)
                    BigNumber(Modifier.weight(1f), "${s.hungUpAfterWarning}", Tr.s(R.string.ins_hung_up), ScamRed)
                }
            }
            if (s.lengthVsRisk.isNotEmpty()) InsightSection(Tr.s(R.string.ins_length), PlainWords.length(s), Tr.s(R.string.ins_length_info)) {
                ScatterChart(s.lengthVsRisk, Tr.s(R.string.a11y_length_chart, s.lengthVsRisk.size.toString()))
                AxisEnds(Tr.s(R.string.ins_short), Tr.s(R.string.ins_long))
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun BigNumber(modifier: Modifier, value: String, label: String, color: Color) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- History

@Composable
fun HistoryScreen(calls: List<CallRecord>, points: List<ScorePoint>, onOpen: (String) -> Unit, onBack: () -> Unit, onGoHome: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf<String?>(null) }   // null = all, else the saved result
    val byCall = remember(points) { points.groupBy { it.callId } }
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val shown = calls.filter { filter == null || it.finalVerdict == filter }
    val groups = shown.groupBy { PlainWords.historyGroup(DashboardStats.daysAgo(it.endedAt, now, zone)) }
    Page(Tr.s(R.string.hist_title), onBack) {
        if (calls.isEmpty()) {
            Box(Modifier.tourTarget(Tour.Target.DASHBOARD_HERO)) { EmptyState(Tr.s(R.string.dash_empty), onGoHome) }
            return@Page
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(null to Tr.s(R.string.pick_all), "SCAM" to Tr.s(R.string.risk_scam), "UNSURE" to Tr.s(R.string.risk_careful), "SAFE" to Tr.s(R.string.risk_safe))) { (v, label) ->
                        PillChip(label, filter == v) { filter = v }
                    }
                }
            }
            if (shown.isEmpty()) item { EmptyState(Tr.s(R.string.hist_none), icon = Icons.AutoMirrored.Filled.List, compact = true) }
            listOf(Tr.s(R.string.pw_today), Tr.s(R.string.pw_this_week), Tr.s(R.string.pw_earlier)).forEach { g ->
                val list = groups[g] ?: return@forEach
                item(key = "h-$g") {
                    Text(g, Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                }
                item(key = "c-$g") {
                    AppCard(padding = 0.dp) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            list.forEachIndexed { i, c ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 50.dp))
                                CallListRow(c, byCall[c.id].orEmpty().sortedBy { it.seq }.mapNotNull { it.combined }) { onOpen(c.id) }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Call detail

private fun reviewWords(review: String) = when (review) {
    "bert" -> Tr.s(R.string.det_rechecked)
    "fallback" -> Tr.s(R.string.det_no_final)
    "unavailable" -> Tr.s(R.string.det_not_reached)
    "on_device" -> Tr.s(R.string.pw_checked_on_device)
    "migrated" -> Tr.s(R.string.det_old)
    else -> review
}

@Composable
fun CallDetailScreen(call: CallRecord?, points: List<ScorePoint>, onBack: () -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    Page("", onBack) {
        if (call == null) {
            Text(Tr.s(R.string.det_gone), style = MaterialTheme.typography.bodyLarge)
            return@Page
        }
        val sorted = points.sortedBy { it.seq }
        val riskiest = Dashboard.riskiestSeq(sorted)?.let { s -> sorted.indexOfFirst { it.seq == s } }?.takeIf { it >= 0 }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                VerdictDot(call.finalVerdict, 26)
                Text(verdictWord(call.finalVerdict), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                    color = levelColor(Dashboard.level(call.finalVerdict)), modifier = Modifier.semantics { heading() })
            }
            Text(DashboardStats.whatHappened(call.finalVerdict, call.liveVerdict, Reasons.localized(Reasons.display(call))), style = MaterialTheme.typography.titleLarge)
            Text(Tr.s(R.string.det_when_at, whenText(call.endedAt), clock(call.endedAt)), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppCard {
                Text(Tr.s(R.string.det_risk), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (sorted.isEmpty()) EmptyState(Tr.s(R.string.det_no_scores), icon = Icons.Filled.Info, compact = true)
                else {
                    RiskLineChart(sorted.map { it.combined },
                        "Risk during the call: ${sorted.size} points, highest ${"%.2f".format(sorted.mapNotNull { it.combined }.maxOrNull() ?: 0f)}",
                        marker = riskiest)
                    if (riskiest != null) ChartCaption(Tr.s(R.string.det_circle))
                }
            }
            call.riskiestSentence?.let {
                AppCard {
                    Text(Tr.s(R.string.det_sentence), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("“$it”", style = MaterialTheme.typography.bodyLarge)
                }
            }
            AppCard(padding = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { details = !details }.padding(horizontal = Space.card),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Tr.s(R.string.det_details), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Icon(if (details) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = Tr.s(if (details) R.string.det_hide else R.string.det_show))
                }
                AnimatedVisibility(details) {
                    Column(Modifier.padding(start = Space.card, end = Space.card, bottom = Space.card), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DetailRow(Tr.s(R.string.det_length), DashboardStats.duration(((call.endedAt - call.startedAt) / 1000).coerceAtLeast(0).toInt()))
                        DetailRow(Tr.s(R.string.det_checked), PlainWords.checkedWhere(call.privacyLevel))
                        DetailRow(Tr.s(R.string.det_during), call.liveVerdict?.let { verdictWord(it) } ?: "-")
                        DetailRow(Tr.s(R.string.det_final), reviewWords(call.review))
                        if (sorted.isNotEmpty()) {
                            Text(Tr.s(R.string.det_signs), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val rows = Risk.SIGNALS.map { s ->
                                val v = sorted.mapNotNull { p -> when (s) { "urgency" -> p.urgency; "money" -> p.money; else -> p.impersonation } }.maxOrNull() ?: 0f
                                PlainWords.signalName(s) to (v * 100).toInt()
                            }
                            HorizontalBars(rows, MaterialTheme.colorScheme.primary, Tr.s(R.string.ins_signs) + ": " + rows.joinToString { "${it.first} ${it.second}" })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** "Share weekly summary" (Dashboard and "For family"). */
@Composable
private fun WeeklyButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("weeklyButton").pressFeedback(),
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Spacer(Modifier.size(10.dp))
        Text(Tr.s(R.string.btn_weekly), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Placeholder while the history loads (Room answers a moment after the screen opens): grey blocks in the shape of the
 * page, so it is never blank and never says "No calls yet" too early. A soft pulse unless reduce motion is on.
 */
@Composable
fun LoadingPlaceholder(rows: Int = 3) {
    val reduce = reduceMotion()
    val pulse = if (reduce) null else androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha = pulse?.animateFloat(0.45f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "alpha")?.value ?: 0.7f
    val c = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Column(Modifier.fillMaxWidth().testTag("loading").semantics { contentDescription = Tr.s(R.string.loading) },
        verticalArrangement = Arrangement.spacedBy(Space.gap)) {
        Box(Modifier.fillMaxWidth().height(140.dp).background(c, RoundedCornerShape(Space.corner)))
        repeat(rows) { Box(Modifier.fillMaxWidth().height(64.dp).background(c, RoundedCornerShape(Space.corner))) }
    }
}
