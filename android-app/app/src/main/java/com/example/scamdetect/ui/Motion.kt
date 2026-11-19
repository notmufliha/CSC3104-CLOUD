package com.example.scamdetect.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.example.scamdetect.logic.Motion

/** The system's "Remove animations" (Settings → Accessibility): animator duration scale 0. */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * Subtle press feedback for big buttons: shrinks slightly while pressed (not with reduce motion) and gives a light tap
 * on release. Only watches the touch; the button still handles the click.
 */
fun Modifier.pressFeedback(): Modifier = composed {
    val reduce = reduceMotion()
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) Motion.pressScale(reduce) else 1f, tween(if (reduce) 0 else 90), label = "press")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    when (e.type) {
                        PointerEventType.Press -> pressed = true
                        PointerEventType.Release -> { if (pressed) haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap); pressed = false }
                        PointerEventType.Exit -> pressed = false
                    }
                }
            }
        }
}
