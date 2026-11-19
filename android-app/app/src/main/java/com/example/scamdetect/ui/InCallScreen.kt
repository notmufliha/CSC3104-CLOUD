package com.example.scamdetect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.logic.Tr
import com.example.scamdetect.logic.Warnings
import com.example.scamdetect.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.heightIn
import com.example.scamdetect.ui.theme.ScamDetectTheme

/** Safe green, #2B7530: at least 4.5:1 on cards as text (accessibility audit; was #2E7D32, 4.4:1). Same on iOS. */
val SafeGreen = Color(0xFF2B7530)
val WarnYellow = Color(0xFFF9A825)
val ScamRed = Color(0xFFC62828)
val NeutralGrey = Color(0xFF9E9E9E)

fun levelColor(level: Risk.Level) = when (level) {
    Risk.Level.GREEN -> SafeGreen
    Risk.Level.YELLOW -> WarnYellow
    Risk.Level.RED -> ScamRed
    Risk.Level.ERROR, Risk.Level.LISTENING -> NeutralGrey
}

/** The level's word for the live call ("Safe so far", "Be careful", "Scam", "Could not check", "Listening…"). */
fun levelLabel(level: Risk.Level) = Tr.s(when (level) {
    Risk.Level.LISTENING -> R.string.risk_listening
    Risk.Level.GREEN -> R.string.risk_safe_so_far
    Risk.Level.YELLOW -> R.string.risk_careful
    Risk.Level.RED -> R.string.risk_scam
    Risk.Level.ERROR -> R.string.risk_could_not_check
})

/**
 * 4.4 In-call screen: risk bar (icon + word), warning signs, banner, live transcript. The red warning is drawn over
 * it by the caller (WarningScreen). showCareful = false: a "Be careful" moment shows as "Listening…" (it is still
 * saved as "Be careful").
 */
@Composable
fun InCallScreen(state: CallUiState, onHangUp: () -> Unit, showCareful: Boolean = true, simple: Boolean = false) {
    // Very large text: everything scrolls as one page (the status stays at the top). Same rule as iOS.
    val bigText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.6f
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (bigText) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CallStatus(state, showCareful, simple)
                    state.lines.forEach { ChatBubble(it, showNotes = !simple) }
                }
            } else {
                CallStatus(state, showCareful, simple)
                Transcript(state.lines, Modifier.weight(1f), showNotes = !simple)
            }
            Button(
                onClick = onHangUp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White)
            ) {
                Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.rotate(135f))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.btn_hang_up), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The call's status: who, privacy badge, risk bar (icon + word), warning signs, banner. */
@Composable
private fun CallStatus(state: CallUiState, showCareful: Boolean, simple: Boolean) {
    val shown = Risk.shown(state.level, showCareful)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.call_on_call),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // Developer view's playback speed, when it isn't 1×
            state.speedTag?.let { SpeedTag(it) }
            // Simple mode: no technical words (no Cloud / Hybrid / On-device), no score, no chips.
            if (!simple) PrivacyBadge(state.privacyLevel)
        }
        Box(Modifier.tourTarget(Tour.Target.RISK_BAR)) { RiskBar(shown, state.combinedScore, showScore = !simple) }
        if (!simple) Box(Modifier.tourTarget(Tour.Target.WARNING_CHIPS)) { SignalChips(state.lit) }
        when (shown) {
            Risk.Level.YELLOW -> CarefulBanner(state.warningKind)
            Risk.Level.RED -> if (!state.warningOpen) StateBanner(Risk.Level.RED,
                stringResource(R.string.warn_title) + ". " + stringResource(Warnings.message(state.warningKind)))
            Risk.Level.ERROR -> StateBanner(Risk.Level.ERROR, stringResource(R.string.error_banner))
            else -> {}
        }
    }
}

@Composable
fun RiskBar(level: Risk.Level, score: Float?, showScore: Boolean = true) {
    val barMs = com.example.scamdetect.logic.Motion.riskBarMs(reduceMotion())
    val color by animateColorAsState(levelColor(level), androidx.compose.animation.core.tween(barMs), label = "riskColor")
    val hasVerdict = level != Risk.Level.LISTENING && level != Risk.Level.ERROR
    val target = if (hasVerdict) (score ?: 0f).coerceIn(0.08f, 1f) else 0f // always a sliver once there is a verdict
    val progress by animateFloatAsState(target, androidx.compose.animation.core.tween(barMs), label = "riskProgress")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RiskBadge(level, levelLabel(level))
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(progress).height(14.dp).background(color))
        }
        // Under the bar, so the word above never has to squeeze. An old score next to "Could not check" would read
        // like a current result, so it is hidden then.
        if (hasVerdict && showScore) score?.let {
            Text(stringResource(R.string.call_risk_score, "%.2f".format(it)), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
fun SignalChips(lit: List<String>) {
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Risk.SIGNALS.forEach { signal ->
            val on = signal in lit
            val label = chipLabel(signal)
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) ScamRed.copy(alpha = 0.15f) else Color.Transparent,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) ScamRed else NeutralGrey),
                modifier = Modifier.semantics { contentDescription = if (on) Tr.s(R.string.a11y_flagged_suffix, label) else label },
            ) {
                Text(
                    chipLabel(signal),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Workstream G: the privacy level this call runs at, as a small pill on the in-call screen. */
@Composable
fun PrivacyBadge(level: PrivacyLevel) {
    Surface(shape = RoundedCornerShape(50), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Text(
            level.badge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics { contentDescription = Tr.s(R.string.a11y_privacy_badge, level.badge) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Developer view: "2× speed" / "4× speed" / "Instant" on the call screen. */
@Composable
fun SpeedTag(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.padding(end = 6.dp).semantics { contentDescription = "Playback $text" }) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoBanner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Chat view: Speaker 1 (caller) on the left, Speaker 2 (you) on the right. */
@Composable
fun Transcript(lines: List<ChatLine>, modifier: Modifier = Modifier, showNotes: Boolean = true) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines, key = { it.seq }) { ChatBubble(it, showNotes = showNotes) }
    }
}

/** riskyHere = B.5: one of the lines where the end-of-call review says "the conversation turned risky here". */
@Composable
fun ChatBubble(line: ChatLine, riskyHere: Boolean = false, showNotes: Boolean = true) {
    val flagged = line.flags.isNotEmpty()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.isCaller) Alignment.Start else Alignment.End
    ) {
        Text(
            stringResource(if (line.isCaller) R.string.call_caller else R.string.call_you),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (line.isCaller) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            border = if (flagged || riskyHere) BorderStroke(2.dp, ScamRed) else null,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        if (riskyHere) Text(
            stringResource(R.string.call_risky_here),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold
        )
        if (showNotes) line.cloudNote?.let {
            Text(noteText(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        when {
            line.pending -> Text(stringResource(R.string.call_checking), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            line.failed -> Text(stringResource(R.string.risk_could_not_check), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            flagged -> Text(
                stringResource(R.string.call_flagged, line.flags.joinToString { chipLabel(it) }),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** A bubble's note ("checked with cloud"…) in the app's wording; the view model keeps the English constants. */
fun noteText(note: String) = when (note) {
    CallViewModel.CLOUD_CHECKED -> Tr.s(R.string.note_cloud_checked)
    CallViewModel.CLOUD_UNAVAILABLE -> Tr.s(R.string.note_cloud_unavailable)
    CallViewModel.MOBILEBERT_CHECKED -> Tr.s(R.string.note_mb_checked)
    CallViewModel.MOBILEBERT_FAILED -> Tr.s(R.string.note_mb_failed)
    else -> note
}

/** A warning sign's short name. */
fun chipLabel(signal: String) = when (signal) {
    "urgency" -> Tr.s(R.string.chip_urgency)
    "money" -> Tr.s(R.string.chip_money)
    "impersonation" -> Tr.s(R.string.chip_impersonation)
    else -> signal
}

@Preview(showBackground = true, heightDp = 760)
@Composable
fun InCallPreview() {
    ScamDetectTheme {
        InCallScreen(
            tourDemoCall(redAlert = true),
            onHangUp = {}
        )
    }
}
