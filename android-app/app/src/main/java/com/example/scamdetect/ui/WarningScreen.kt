package com.example.scamdetect.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scamdetect.R
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.logic.Warnings
import kotlinx.coroutines.delay

// The red warning (full screen) and the yellow "Be careful" banner. Same layout, wording and order as iOS
// UI/WarningViews.swift.

/** Text on a white button / the warning: the risk red, darker, for contrast (7.4:1 on white). */
val WarningInk = Color(0xFFB71C1C)
/** Dark text on the yellow banner (11:1). */
val OnYellow = Color(0xFF1C1B1F)
/** Grey badge fill: dark enough for a white glyph (6.1:1). */
val BadgeGrey = Color(0xFF616161)

/** What the warning's buttons do. Calls and messages open the phone's own app: the user always taps Call / Send. */
data class WarningActions(
    val onHangUp: () -> Unit = {},
    val onCallFamily: () -> Unit = {},
    val onCall1799: () -> Unit = {},
    val onTellFamily: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The red warning: the whole screen, a strong red, one sentence for what happened and one for what to do, then big
 * buttons in this order: Hang up, Call my family (if a contact is set), Call 1799, Tell [name] (if set), Close.
 * It never closes by itself. demo = the tour's pretend call (no flash, nothing announced).
 */
@Composable
fun WarningScreen(
    kind: Warnings.Kind,
    callEnded: Boolean,
    familyName: String?,
    actions: WarningActions,
    demo: Boolean = false,
) {
    val title = stringResource(R.string.warn_title)
    val message = stringResource(Warnings.message(kind))
    val doLine = stringResource(Warnings.doLine(kind))
    val view = LocalView.current
    val context = LocalContext.current
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (demo) return@LaunchedEffect
        @Suppress("DEPRECATION")
        view.announceForAccessibility("$title. $message $doLine")
        // A gentle flash: three soft pulses, one a second (well under the 3-a-second seizure limit). Off when the
        // phone's animations are turned off.
        val animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (animations == 0f) return@LaunchedEffect
        repeat(3) {
            flash.animateTo(0.9f, tween(300))
            flash.animateTo(0f, tween(450))
            delay(250)
        }
    }

    Box(
        Modifier.fillMaxSize().background(ScamRed)
            .semantics { paneTitle = title }
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).tourTarget(Tour.Target.RED_WARNING)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(84.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = WarningInk, modifier = Modifier.size(60.dp))
                }
                Text(title, color = Color.White, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() })
                Text(message, color = Color.White, fontSize = 26.sp, lineHeight = 34.sp)
                Text(doLine, color = Color.White, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                if (callEnded) Text(stringResource(R.string.warn_call_ended), color = Color.White, fontSize = 22.sp)
            }
            Column(
                Modifier.fillMaxWidth().widthIn(max = 560.dp).tourTarget(Tour.Target.WARNING_BUTTONS),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WarningButton(stringResource(R.string.btn_hang_up), Icons.Filled.Call, actions.onHangUp, testTag = "warnHangUp", hangUpIcon = true)
                if (familyName != null) {
                    WarningButton(stringResource(R.string.btn_call_family), Icons.Filled.Phone, actions.onCallFamily,
                        sub = familyName, hint = stringResource(R.string.a11y_ends_call_first))
                }
                WarningButton(stringResource(R.string.btn_call_1799), Icons.Filled.Phone, actions.onCall1799,
                    hint = stringResource(R.string.a11y_ends_call_first))
                if (familyName != null) {
                    WarningButton(stringResource(R.string.btn_tell_family, familyName), Icons.AutoMirrored.Filled.Send, actions.onTellFamily,
                        hint = stringResource(R.string.a11y_opens_messages))
                }
                Surface(
                    onClick = actions.onClose,
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent,
                    border = BorderStroke(2.dp, Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.btn_close_warning), color = Color.White, fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        // The flash is a white frame around the edge (it takes no touches), so the white text keeps its contrast.
        Box(Modifier.fillMaxSize().alpha(flash.value).border(14.dp, Color.White))
    }
}

@Composable
private fun WarningButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    sub: String? = null,
    hint: String? = null,
    testTag: String? = null,
    hangUpIcon: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) {
                if (hint != null) onClick(label = hint) { onClick(); true }
            },
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // the handset turned down = "hang up" (Material's core icons have no CallEnd)
            Icon(icon, contentDescription = null, tint = WarningInk,
                modifier = Modifier.size(34.dp).then(if (hangUpIcon) Modifier.rotate(135f) else Modifier))
            Column(Modifier.weight(1f)) {
                Text(label, color = WarningInk, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                if (sub != null) Text(sub, color = WarningInk, fontSize = 20.sp, lineHeight = 26.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- risk badge: colour + icon + word, everywhere

/** The word for a risk level. */
@Composable
fun riskWord(level: Risk.Level, live: Boolean = false): String = stringResource(
    when (level) {
        Risk.Level.GREEN -> if (live) R.string.risk_safe_so_far else R.string.risk_safe
        Risk.Level.YELLOW -> R.string.risk_careful
        Risk.Level.RED -> R.string.risk_scam
        Risk.Level.ERROR -> if (live) R.string.risk_could_not_check else R.string.risk_not_checked
        Risk.Level.LISTENING -> R.string.risk_listening
    }
)

/** A filled circle in the risk colour with ✓ / ! / ✕ / i, so the meaning never depends on colour alone. */
@Composable
fun RiskIcon(level: Risk.Level, size: Dp = 28.dp) {
    val (fill, glyph) = when (level) {
        Risk.Level.GREEN -> SafeGreen to Color.White
        Risk.Level.YELLOW -> WarnYellow to OnYellow
        Risk.Level.RED -> ScamRed to Color.White
        Risk.Level.ERROR, Risk.Level.LISTENING -> BadgeGrey to Color.White
    }
    Box(Modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
        when (level) {
            Risk.Level.GREEN -> Icon(Icons.Filled.Check, null, tint = glyph, modifier = Modifier.size(size * 0.7f))
            Risk.Level.RED -> Icon(Icons.Filled.Close, null, tint = glyph, modifier = Modifier.size(size * 0.7f))
            Risk.Level.YELLOW -> Text("!", color = glyph, fontWeight = FontWeight.Black, fontSize = (size.value * 0.62f).sp)
            Risk.Level.LISTENING -> Icon(Icons.Filled.Phone, null, tint = glyph, modifier = Modifier.size(size * 0.6f))
            Risk.Level.ERROR -> Text("i", color = glyph, fontWeight = FontWeight.Black, fontSize = (size.value * 0.62f).sp)
        }
    }
}

/** Icon + word, read as one item by TalkBack. */
@Composable
fun RiskBadge(level: Risk.Level, word: String = riskWord(level), size: Dp = 28.dp, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleLarge) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = word },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RiskIcon(level, size)
        Text(word, style = style, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The yellow "Be careful" banner: small, in the call screen's layout, no sound, no pop-up. */
@Composable
fun CarefulBanner(kind: Warnings.Kind) {
    Surface(color = WarnYellow, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).background(OnYellow, CircleShape), contentAlignment = Alignment.Center) {
                Text("!", color = WarnYellow, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.risk_careful), color = OnYellow, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(Warnings.careful(kind)), color = OnYellow, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Banner for the other states (could not check, or a red warning the user closed). */
@Composable
fun StateBanner(level: Risk.Level, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(2.dp, levelColor(level)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RiskIcon(level, 32.dp)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
