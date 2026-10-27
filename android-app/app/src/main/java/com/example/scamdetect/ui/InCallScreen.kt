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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.ui.theme.ScamDetectTheme

val SafeGreen = Color(0xFF2E7D32)
val WarnYellow = Color(0xFFF9A825)
val ScamRed = Color(0xFFC62828)
val NeutralGrey = Color(0xFF9E9E9E)

fun levelColor(level: Risk.Level) = when (level) {
    Risk.Level.GREEN -> SafeGreen
    Risk.Level.YELLOW -> WarnYellow
    Risk.Level.RED -> ScamRed
    Risk.Level.ERROR, Risk.Level.LISTENING -> NeutralGrey
}

fun levelLabel(level: Risk.Level) = when (level) {
    Risk.Level.LISTENING -> "Listening…"
    Risk.Level.GREEN -> "Looks safe so far"
    Risk.Level.YELLOW -> "Suspicious"
    Risk.Level.RED -> "Likely scam"
    Risk.Level.ERROR -> "Could not check"
}

/** 4.4 In-call screen: risk bar, chips, banner, live transcript. 4.5 red alert pops over the top. */
@Composable
fun InCallScreen(state: CallUiState, onHangUp: () -> Unit) {
    // Saveable so a screen rotation does not bring a dismissed pop-up back.
    var alertDismissed by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "On call · Unknown caller",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RiskBar(state.level, state.combinedScore)
            SignalChips(state.lit)
            // Yellow / grey banner lives in the layout; the red one is the pop-up below until it is dismissed.
            if (state.level != Risk.Level.RED || alertDismissed || !state.scamAlert) {
                state.banner?.let { InfoBanner(it, levelColor(state.level)) }
            }
            Transcript(state.lines, Modifier.weight(1f))
            Button(
                onClick = onHangUp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White)
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Hang up")
            }
        }

        // 4.5 full-width red alert, slides down from the top the first time the verdict is SCAM
        AnimatedVisibility(
            visible = state.scamAlert && !alertDismissed,
            enter = slideInVertically { -it } + fadeIn(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ScamAlert(
                text = state.banner ?: "Likely scam.",
                onHangUp = onHangUp,
                onDismiss = { alertDismissed = true }
            )
        }
    }
}

@Composable
fun RiskBar(level: Risk.Level, score: Float?) {
    val color by animateColorAsState(levelColor(level), label = "riskColor")
    val hasVerdict = level != Risk.Level.LISTENING && level != Risk.Level.ERROR
    val target = if (hasVerdict) (score ?: 0f).coerceIn(0.08f, 1f) else 0f // always a sliver once there is a verdict
    val progress by animateFloatAsState(target, label = "riskProgress")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(levelLabel(level), fontWeight = FontWeight.SemiBold, color = color)
            // An old score next to "Could not check" would read like a current result, so hide it.
            if (hasVerdict) score?.let { Text("risk ${"%.2f".format(it)}", style = MaterialTheme.typography.labelMedium) }
        }
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(progress).height(14.dp).background(color))
        }
    }
}

@Composable
fun SignalChips(lit: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Risk.SIGNALS.forEach { signal ->
            val on = signal in lit
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) ScamRed.copy(alpha = 0.15f) else Color.Transparent,
                border = BorderStroke(1.dp, if (on) ScamRed else NeutralGrey),
            ) {
                Text(
                    Risk.chipLabel(signal),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) ScamRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InfoBanner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ScamAlert(text: String, onHangUp: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = ScamRed, modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(Modifier.size(8.dp))
                Text("WARNING", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onHangUp,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ScamRed)
                ) { Text("Hang up now") }
                TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.White) }
            }
        }
    }
}

/** Chat view: Speaker 1 (caller) on the left, Speaker 2 (you) on the right. */
@Composable
fun Transcript(lines: List<ChatLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines, key = { it.seq }) { ChatBubble(it) }
    }
}

@Composable
fun ChatBubble(line: ChatLine) {
    val flagged = line.flags.isNotEmpty()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.isCaller) Alignment.Start else Alignment.End
    ) {
        Text(
            if (line.isCaller) "Speaker 1 · Caller" else "Speaker 2 · You",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (line.isCaller) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            border = if (flagged) BorderStroke(2.dp, ScamRed) else null,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        when {
            line.pending -> Text("checking…", style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
            line.failed -> Text("could not check", style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
            flagged -> Text(
                "flagged: " + line.flags.joinToString { Risk.chipLabel(it) },
                style = MaterialTheme.typography.labelSmall, color = ScamRed
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
fun InCallPreview() {
    ScamDetectTheme {
        InCallScreen(
            CallUiState(
                phase = Phase.IN_CALL,
                verdict = "SCAM",
                combinedScore = 0.79f,
                callScores = mapOf("urgency" to 0.47f, "money" to 1f, "impersonation" to 0.9f),
                lines = listOf(
                    ChatLine(1, false, "Hello?"),
                    ChatLine(2, true, "This is Officer Tan from the police.", listOf("impersonation")),
                    ChatLine(3, true, "Read me the OTP you just received.", listOf("money")),
                ),
                scamAlert = true,
            ),
            onHangUp = {}
        )
    }
}
