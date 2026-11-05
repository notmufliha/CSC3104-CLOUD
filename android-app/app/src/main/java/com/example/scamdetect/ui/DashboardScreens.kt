package com.example.scamdetect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.Risk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Workstream B.7: the user dashboard. Everything here comes from the Room database on this phone; nothing is
// uploaded. Replaces the old History screen.

private fun dateFormat() = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

@Composable
private fun VerdictDot(verdict: String, size: Int = 12) {
    Box(Modifier.size(size.dp).background(levelColor(Dashboard.level(verdict)), CircleShape))
}

@Composable
fun DashboardScreen(
    calls: List<CallRecord>,
    saveSentence: Boolean,
    onSaveSentence: (Boolean) -> Unit,
    onDeleteHistory: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val fmt = remember { dateFormat() }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete my history?") },
            text = {
                Text("Deletes every saved call on this phone: verdicts, charts and any saved sentences. This can't be undone.")
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; onDeleteHistory() },
                    colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    Page("Dashboard", onBack) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val top = Dashboard.topReasons(calls)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Most common warning reasons", fontWeight = FontWeight.SemiBold)
                        if (top.isEmpty()) Text("No warnings yet.", style = MaterialTheme.typography.bodySmall)
                        top.forEach { (reason, n) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(reason, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("$n×", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            item { Text("Past calls (${calls.size})", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            if (calls.isEmpty()) item { Text("No calls yet.") }
            items(calls, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(c.id) }) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        VerdictDot(c.finalVerdict)
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.finalVerdict, color = levelColor(Dashboard.level(c.finalVerdict)), fontWeight = FontWeight.Bold)
                                Text(fmt.format(Date(c.endedAt)), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(c.topReason, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text("Privacy", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Save the riskiest sentence")
                            Text(
                                "Keeps the one caller line that scored highest in calls that ended with a warning. " +
                                    "Off: no words from your calls are kept, only verdicts and scores.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = saveSentence, onCheckedChange = onSaveSentence)
                    }
                    Text(
                        "Your history stays on this phone. Nothing on this screen is uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = calls.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete my history") }
                }
            }
        }
    }
}

@Composable
fun CallDetailScreen(call: CallRecord?, points: List<ScorePoint>, onBack: () -> Unit) {
    Page("Call details", onBack) {
        if (call == null) {
            Text("This call is no longer saved.")
            return@Page
        }
        val fmt = remember { dateFormat() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VerdictDot(call.finalVerdict, 16)
                Text(
                    call.finalVerdict,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = levelColor(Dashboard.level(call.finalVerdict)),
                )
            }
            Text(call.topReason)
            val seconds = ((call.endedAt - call.startedAt) / 1000).coerceAtLeast(0)
            Text(
                fmt.format(Date(call.endedAt)) + (if (seconds > 0) " · ${seconds / 60} min ${seconds % 60} s" else "") +
                    (call.liveVerdict?.let { " · during the call: $it" } ?: "") + " · " + reviewLabel(call.review),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Risk over time", fontWeight = FontWeight.SemiBold)
            RiskChart(points)

            Text("Signals that fired", fontWeight = FontWeight.SemiBold)
            if (call.signalsFired.isEmpty()) Text("None.", style = MaterialTheme.typography.bodyMedium)
            else SignalChips(call.signalsFired)

            call.riskiestSentence?.let {
                Text("Riskiest sentence", fontWeight = FontWeight.SemiBold)
                InfoBanner("“$it”", levelColor(Dashboard.level(call.finalVerdict)))
            }
        }
    }
}

private fun reviewLabel(review: String) = when (review) {
    "bert" -> "final review"
    "fallback" -> "final review unavailable"
    "unavailable" -> "server not reached at the end"
    "migrated" -> "from the old history"
    else -> review
}

/**
 * The call's combined score after each caller line, drawn with Compose Canvas (no chart library). Dashed lines at
 * the aggregator's UNSURE (0.45) and SCAM (0.6) thresholds; each point is coloured by the level it reached.
 */
@Composable
fun RiskChart(points: List<ScorePoint>) {
    val data = Dashboard.chart(points)
    if (data.isEmpty()) {
        Text("No scores were saved for this call.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val maxT = data.maxOf { it.first }.coerceAtLeast(1f)
    val axis = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.onSurface
    val description = "Risk over time: ${data.size} points, highest ${"%.2f".format(data.maxOf { it.second })}"
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
            val w = size.width
            val h = size.height
            val pad = 12f   // keeps the dots at the edges (first line, score 1.0) fully inside the chart
            fun x(t: Float) = pad + t / maxT * (w - 2 * pad)
            fun y(v: Float) = h - pad - v * (h - 2 * pad)
            // axes + thresholds
            drawLine(axis, Offset(0f, h), Offset(w, h), strokeWidth = 2f)
            drawLine(axis, Offset(0f, 0f), Offset(0f, h), strokeWidth = 2f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            drawLine(WarnYellow, Offset(0f, y(Dashboard.UNSURE_LINE)), Offset(w, y(Dashboard.UNSURE_LINE)), 3f, pathEffect = dash)
            drawLine(ScamRed, Offset(0f, y(Dashboard.SCAM_LINE)), Offset(w, y(Dashboard.SCAM_LINE)), 3f, pathEffect = dash)
            // the score line
            val path = Path()
            data.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(x(t), y(v)) else path.lineTo(x(t), y(v)) }
            drawPath(path, line, style = Stroke(width = 4f))
            data.forEach { (t, v) ->
                val c = when {
                    v >= Dashboard.SCAM_LINE -> ScamRed
                    v >= Dashboard.UNSURE_LINE -> WarnYellow
                    else -> SafeGreen
                }
                drawCircle(c, radius = 9f, center = Offset(x(t), y(v)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 s", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("- - suspicious", style = MaterialTheme.typography.labelSmall, color = WarnYellow)
                Text("- - likely scam", style = MaterialTheme.typography.labelSmall, color = ScamRed)
            }
            Text("${maxT.toInt()} s", style = MaterialTheme.typography.labelSmall)
        }
    }
}
