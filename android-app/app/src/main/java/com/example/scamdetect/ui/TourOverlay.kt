package com.example.scamdetect.ui

import com.example.scamdetect.R
import com.example.scamdetect.logic.Tr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.scamdetect.logic.Tour
import kotlin.math.roundToInt

/** Where each tour target is on screen, in root coordinates (the overlay fills the root). */
class TourTargets {
    val bounds = mutableStateMapOf<String, Rect>()
}

val LocalTourTargets = staticCompositionLocalOf { TourTargets() }

/** Marks a real element the tour can highlight. Costs nothing when no tour is running. */
fun Modifier.tourTarget(target: Tour.Target): Modifier = composed {
    val targets = LocalTourTargets.current
    DisposableEffect(target) { onDispose { targets.bounds.remove(target.id) } }
    onGloballyPositioned { targets.bounds[target.id] = it.boundsInRoot() }
}

/**
 * The coach mark: dims the screen, cuts a rounded hole around the step's element, and shows a bubble with the
 * title, one line, "2 of 7", Skip tour and Next. Taps anywhere else do nothing.
 */
@Composable
fun TourOverlay(steps: List<Tour.Step>, index: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val step = steps[index.coerceIn(0, steps.size - 1)]
    val progress = androidx.compose.ui.res.stringResource(R.string.tour_progress, (index + 1).toString(), steps.size.toString())
    val title = androidx.compose.ui.res.stringResource(step.title)
    val target = LocalTourTargets.current.bounds[step.target.id]
    val density = LocalDensity.current
    val pad = with(density) { 8.dp.toPx() }
    val corner = with(density) { 16.dp.toPx() }
    val ring = MaterialTheme.colorScheme.primary
    val hole = target?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(index) { runCatching { titleFocus.requestFocus() } }

    Box(
        Modifier.fillMaxSize()
            .testTag("tourOverlay")
            .semantics { paneTitle = Tr.s(R.string.a11y_tour, progress) }
            // Swallow every touch that the bubble doesn't take, so the screen underneath can't be used.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                hole?.let { addRoundRect(RoundRect(it, CornerRadius(corner))) }
            }
            drawPath(path, Color.Black.copy(alpha = 0.62f))
            hole?.let {
                drawRoundRect(ring, it.topLeft, Size(it.width, it.height), CornerRadius(corner), style = Stroke(3.dp.toPx()))
            }
        }
        // TalkBack: the highlighted element is its own focusable item, at its place on screen.
        hole?.let { h ->
            Box(
                Modifier.offset { IntOffset(h.left.roundToInt(), h.top.roundToInt()) }
                    .size(with(density) { h.width.toDp() }, with(density) { h.height.toDp() })
                    .semantics { contentDescription = Tr.s(R.string.a11y_highlighted, title) },
            )
        }
        // Dark mode: a lighter card and a thin outline, so the bubble stands out from the dimmed screen.
        val dark = isSystemInDarkTheme()
        BubbleLayout(hole) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                border = if (dark) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 480.dp).testTag("tourBubble"),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progress, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.focusRequester(titleFocus).focusTarget()
                            .semantics { heading(); liveRegion = LiveRegionMode.Polite })
                    Text(androidx.compose.ui.res.stringResource(step.line), style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(androidx.compose.ui.res.stringResource(R.string.btn_skip_tour), style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = onNext, modifier = Modifier.heightIn(min = 56.dp).testTag("tourNext")) {
                            Text(androidx.compose.ui.res.stringResource(if (index >= steps.size - 1) R.string.btn_done else R.string.btn_next),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/** Puts the bubble under the highlight when it is in the top half of the screen, above it otherwise. */
@Composable
private fun BubbleLayout(hole: Rect?, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 16.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val top = insets.getTop(density) + margin
        val bottom = constraints.maxHeight - insets.getBottom(density) - margin
        val width = constraints.maxWidth - 2 * margin
        val p = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = width, minHeight = 0))
        val x = (constraints.maxWidth - p.width) / 2
        val y = when {
            hole == null -> (constraints.maxHeight - p.height) / 2
            hole.center.y < constraints.maxHeight / 2f -> hole.bottom.roundToInt() + gap
            else -> hole.top.roundToInt() - gap - p.height
        }.coerceIn(top, (bottom - p.height).coerceAtLeast(top))
        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
    }
}

/** The pretend call the tour's in-call steps show (the same as the in-call preview). Nothing is sent. */
fun tourDemoCall(redAlert: Boolean) = CallUiState(
    phase = Phase.IN_CALL,
    verdict = "SCAM",
    combinedScore = 0.79f,
    callScores = mapOf("urgency" to 0.47f, "money" to 1f, "impersonation" to 0.9f),
    lines = listOf(
        ChatLine(1, false, Tr.s(R.string.demo_line_hello)),
        ChatLine(2, true, Tr.s(R.string.demo_line_officer), listOf("impersonation")),
        ChatLine(3, true, Tr.s(R.string.demo_line_otp), listOf("money")),
    ),
    scamAlert = redAlert,
    warningOpen = redAlert,
)
