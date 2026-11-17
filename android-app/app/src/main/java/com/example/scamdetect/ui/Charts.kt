package com.example.scamdetect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Dashboard charts drawn with Compose Canvas (no chart library), matching the iOS app's Swift Charts look:
// same colours, rounded bars, donut style, legends below. Kept in step with ios-app/ScamGuard/UI/ChartViews.swift.

/** Privacy-level colours (purple family), the same on iOS. */
val PrivacyCloud = Color(0xFF6650A4)
val PrivacyHybrid = Color(0xFF9A82DB)
val PrivacyOnDevice = Color(0xFFCDB9FF)

fun privacyColor(id: String) = when (id) {
    "hybrid" -> PrivacyHybrid
    "on_device" -> PrivacyOnDevice
    else -> PrivacyCloud
}

/** One coloured slice / segment with its label and value. */
data class Slice(val label: String, val value: Int, val color: Color)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(slices: List<Slice>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(s.color, CircleShape))
                Text("  ${s.label} ${s.value}", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Donut with the total in the middle. */
@Composable
fun DonutChart(slices: List<Slice>, centerLabel: String, size: Dp = 180.dp, compact: Boolean = false) {
    val total = slices.sumOf { it.value }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val desc = "$centerLabel: $total. " + slices.joinToString { "${it.label} ${it.value}" }
    Box((if (compact) Modifier.size(size) else Modifier.fillMaxWidth()).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.16f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            if (total == 0) {
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                return@Canvas
            }
            val parts = slices.filter { it.value > 0 }
            val gap = if (parts.size > 1) 2f else 0f
            var start = -90f
            parts.forEach { s ->
                val sweep = 360f * s.value / total
                drawArc(s.color, start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$total", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold)
            if (!compact) Text(centerLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Vertical bars, each stacked from the bottom in the given slice order. */
@Composable
fun StackedBarChart(bars: List<List<Slice>>, description: String, height: Dp = 160.dp) {
    val max = (bars.maxOfOrNull { b -> b.sumOf { it.value } } ?: 0).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val n = bars.size.coerceAtLeast(1)
        val slot = size.width / n
        val w = (slot * 0.62f).coerceAtLeast(2f)
        val r = CornerRadius(minOf(w / 2, 4.dp.toPx()))
        drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        bars.forEachIndexed { i, b ->
            val total = b.sumOf { it.value }
            if (total == 0) return@forEachIndexed
            val x = i * slot + (slot - w) / 2
            val h = size.height * total / max
            val top = size.height - h
            val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(x, top, x + w, size.height, r)) }
            clipPath(clip) {
                var y = size.height
                b.forEach { s ->
                    if (s.value == 0) return@forEach
                    val sh = size.height * s.value / max
                    drawRect(s.color, Offset(x, y - sh), Size(w, sh))
                    y -= sh
                }
            }
        }
    }
}

/** Horizontal bars, one per row: label on the left above the bar, count on the right. */
@Composable
fun HorizontalBars(rows: List<Pair<String, Int>>, color: Color, description: String) {
    val max = (rows.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, n) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("$n", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(track)) {
                    if (n > 0) Box(Modifier.fillMaxWidth(n.toFloat() / max).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
                }
            }
        }
    }
}

/** One bar split into coloured parts (privacy modes, double-check results). */
@Composable
fun SegmentedBar(slices: List<Slice>, description: String) {
    val total = slices.sumOf { it.value }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(12.dp)).background(track)
                .semantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (total > 0) slices.filter { it.value > 0 }.forEach { s ->
                Box(Modifier.weight(s.value.toFloat()).height(24.dp).background(s.color))
            }
        }
        Legend(slices)
    }
}

/**
 * A line of values (null = no point) on a 0-1 scale, with dashed lines at the aggregator's 0.45 / 0.6 thresholds and
 * points coloured by level. `marker` = index of a point to circle (the riskiest moment).
 */
@Composable
fun RiskLineChart(
    values: List<Float?>,
    description: String,
    height: Dp = 170.dp,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    extraLines: List<Pair<List<Float?>, Color>> = emptyList(),
    marker: Int? = null,
) {
    val axis = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val pad = 8.dp.toPx()
        val n = values.size
        fun x(i: Int) = if (n <= 1) size.width / 2 else pad + i * (size.width - 2 * pad) / (n - 1)
        fun y(v: Float) = size.height - pad - v.coerceIn(0f, 1f) * (size.height - 2 * pad)
        drawLine(axis, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), 1.dp.toPx())
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        drawLine(WarnYellow, Offset(0f, y(0.45f)), Offset(size.width, y(0.45f)), 1.5.dp.toPx(), pathEffect = dash)
        drawLine(ScamRed, Offset(0f, y(0.6f)), Offset(size.width, y(0.6f)), 1.5.dp.toPx(), pathEffect = dash)
        fun line(vs: List<Float?>, c: Color, width: Float, dashed: Boolean) {
            val path = Path()
            var started = false
            vs.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
            }
            drawPath(path, c, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null))
        }
        extraLines.forEach { (vs, c) -> line(vs, c, 1.5.dp.toPx(), true) }
        line(values, lineColor, 3.dp.toPx(), false)
        marker?.let { m -> values.getOrNull(m)?.let { v ->
            drawLine(markerColor.copy(alpha = 0.5f), Offset(x(m), pad), Offset(x(m), size.height - pad), 1.dp.toPx(), pathEffect = dash)
            drawCircle(markerColor, 9.dp.toPx(), Offset(x(m), y(v)), style = Stroke(2.dp.toPx()))
        } }
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val c = when { v >= 0.6f -> ScamRed; v >= 0.45f -> WarnYellow; else -> SafeGreen }
            drawCircle(c, 4.5.dp.toPx(), Offset(x(i), y(v)))
        }
    }
}

/** 24 small bars, one per hour of the day. */
@Composable
fun HourChart(hours: List<Int>, color: Color, description: String) {
    val max = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = description }) {
            val slot = size.width / 24
            val w = slot * 0.66f
            val r = CornerRadius(minOf(w / 2, 3.dp.toPx()))
            hours.forEachIndexed { i, n ->
                val x = i * slot + (slot - w) / 2
                drawRoundRect(track, Offset(x, size.height - 3.dp.toPx()), Size(w, 3.dp.toPx()), r)
                if (n > 0) {
                    val h = size.height * n / max
                    drawRoundRect(color, Offset(x, size.height - h), Size(w, h), r)
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("12 am", "6 am", "12 pm", "6 pm").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Dots of (x, y) with y on a 0-1 scale, coloured by level. */
@Composable
fun ScatterChart(points: List<Pair<Int, Double>>, description: String) {
    val maxX = (points.maxOfOrNull { it.first } ?: 1).coerceAtLeast(1)
    val axis = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }) {
        val pad = 8.dp.toPx()
        drawLine(axis, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), 1.dp.toPx())
        points.forEach { (sx, v) ->
            val x = pad + sx.toFloat() / maxX * (size.width - 2 * pad)
            val y = size.height - pad - v.toFloat().coerceIn(0f, 1f) * (size.height - 2 * pad)
            val c = when { v >= 0.6 -> ScamRed; v >= 0.45 -> WarnYellow; else -> SafeGreen }
            drawCircle(c.copy(alpha = 0.85f), 5.dp.toPx(), Offset(x, y))
        }
    }
}

/** A tiny risk line for a past-call row. */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier.width(64.dp).height(26.dp)) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val pad = 2.dp.toPx()
        fun x(i: Int) = if (values.size == 1) size.width / 2 else pad + i * (size.width - 2 * pad) / (values.size - 1)
        fun y(v: Float) = size.height - pad - v.coerceIn(0f, 1f) * (size.height - 2 * pad)
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val last = values.last()
        drawCircle(when { last >= 0.6f -> ScamRed; last >= 0.45f -> WarnYellow; else -> SafeGreen }, 3.dp.toPx(),
            Offset(x(values.lastIndex), y(last)))
    }
}

/** Unused-space helper so cards line up. */
@Composable
fun ChartCaption(text: String) {
    Text(text, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
