package com.example.scamdetect.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.scamdetect.R
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.Pin
import kotlinx.coroutines.launch

// The optional PIN: setting it (setup, Advanced settings) and asking for it (Advanced settings, For family).
// Same screens and wording as iOS UI/PinViews.swift.

/** A 4-number field: digits only, hidden, big. */
@Composable
fun PinField(value: String, label: String, onChange: (String) -> Unit, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(Pin.LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Choose a PIN twice; [onPin] gets it when both match (or "" while they don't). */
@Composable
fun NewPinFields(onPin: (String) -> Unit) {
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    fun update(a: String, b: String) { first = a; second = b; onPin(if (Pin.isValid(a) && a == b) a else "") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PinField(first, stringResource(R.string.pin_new), { update(it, second) }, "pinNew")
        PinField(second, stringResource(R.string.pin_again), { update(first, it) }, "pinAgain")
        val note = when {
            first.isEmpty() && second.isEmpty() -> null
            first.length < Pin.LENGTH -> stringResource(R.string.pin_need_4)
            second.length == Pin.LENGTH && first != second -> stringResource(R.string.pin_mismatch)
            Pin.isValid(first) && first == second -> stringResource(R.string.pin_ok) + " ✓"
            else -> null
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** Setup (family helper): an optional PIN. Leaving the fields empty means no PIN. */
@Composable
fun PinSetup(c: Onboarding.Choices, onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit) {
    NewPinFields { pin -> onChange { it.copy(pin = pin) } }
}

/**
 * Asks for the PIN before Advanced settings / For family. Nothing times out. "Forgot PIN" removes only the PIN,
 * after a clear warning (the call history is not touched).
 */
@Composable
fun PinGate(onCheck: suspend (String) -> Boolean, onForgot: () -> Unit, onBack: (() -> Unit)?) {
    var pin by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    var forgot by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize().testTag("pinGate"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (onBack != null) Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("‹ " + stringResource(R.string.btn_back), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            }
            Text(stringResource(R.string.pin_enter_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.pin_enter_line), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.widthIn(max = 360.dp)) { PinField(pin, stringResource(R.string.field_pin), { pin = it; wrong = false }, "pinEnter") }
            var short by rememberSaveable { mutableStateOf(false) }
            if (short && !Pin.isValid(pin)) Text(stringResource(R.string.pin_need_4), style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            if (wrong) Text(stringResource(R.string.pin_wrong), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = WarningInkFor(), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            Button(
                // Always full contrast: tapping too early says "Use 4 numbers" instead of a faded button.
                onClick = { if (!Pin.isValid(pin)) short = true else scope.launch { if (!onCheck(pin)) { wrong = true; pin = "" } } },
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 64.dp).testTag("pinUnlock"),
            ) { Text(stringResource(R.string.btn_unlock), style = MaterialTheme.typography.labelLarge) }
            TextButton(onClick = { forgot = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.btn_forgot_pin), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (forgot) YesNoDialog(
        title = stringResource(R.string.pin_forgot_title),
        text = stringResource(R.string.pin_forgot_text),
        yes = stringResource(R.string.btn_remove_pin),
        no = stringResource(R.string.btn_keep_pin),
        onYes = { forgot = false; onForgot() },
        onNo = { forgot = false },
        yesIsDanger = true,
    )
}

/** Red text that keeps its contrast in dark mode. */
@Composable
fun WarningInkFor() = if (androidx.compose.foundation.isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFFFFB4AB) else WarningInk
