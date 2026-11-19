package com.example.scamdetect.ui

import com.example.scamdetect.logic.Tr

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.selection.selectableGroup

import com.example.scamdetect.logic.Risk

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.stringResource
import com.example.scamdetect.R

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Tour
import kotlinx.coroutines.launch

// Settings as a grouped menu; each row opens its own page. Same rows, wording and order as
// ios-app/ScamGuard/UI/SettingsView.swift.

enum class SettingsPage { PRIVACY, DATA, HELP, APP_STYLE, TEXT_SIZE, FAMILY, ADVANCED, PIN, DEVELOPER }

/** Server addresses offered as one-tap presets (Developer view → Server address only). */
val SERVER_PRESETS = listOf(
    "Emulator" to "http://10.0.2.2:8080/",
    "Emulator HTTPS" to "https://10.0.2.2:8443/api/",
    "Mac on Wi-Fi" to "http://192.168.1.23:8080/",
    "HTTPS" to "https://192.168.1.23:8443/api/",
)

/** One line per privacy level, for the cards and the menu. */
fun PrivacyLevel.oneLine() = when (this) {
    PrivacyLevel.CLOUD -> "Checked by the ScamGuard server"
    PrivacyLevel.HYBRID -> "Checked on this phone, server helps when unsure"
    PrivacyLevel.ON_DEVICE -> "Everything stays on this phone"
}

fun PrivacyLevel.shortTitle() = when (this) {
    PrivacyLevel.CLOUD -> "Cloud"
    PrivacyLevel.HYBRID -> "Hybrid"
    PrivacyLevel.ON_DEVICE -> "On-device"
}

/** Grouped card holding rows, like an iOS inset-grouped list section. */
@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, value: String, valueColor: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The full app's Settings: the everyday rows, everything Advanced settings holds, Help, and Developer view if unlocked. */
@Composable
fun SettingsMenuScreen(
    privacyLevel: PrivacyLevel,
    connection: Boolean?,
    callCount: Int,
    onRefresh: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    textSize: String,
    onRetry: suspend () -> Unit = {},
    readAloud: Boolean,
    onReadAloud: (Boolean) -> Unit,
    familyName: String?,
    showCareful: Boolean,
    onShowCareful: (Boolean) -> Unit,
    pinSet: Boolean,
    developerUnlocked: Boolean = false,
) {
    LaunchedEffect(Unit) { onRefresh() }
    Page(stringResource(R.string.tab_settings)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EverydaySettings(false, textSize, readAloud, familyName, onOpen, onReadAloud)
            AdvancedRows(false, showCareful, onShowCareful, pinSet, privacyLevel, connection, callCount, onOpen, onRetry)
            Group { SettingsRow(Icons.Filled.Info, stringResource(R.string.set_help), tag = "rowHelp") { onOpen(SettingsPage.HELP) } }
            if (developerUnlocked) DeveloperRow(onOpen)
        }
    }
}

/** The hidden Developer view's row: only once unlocked (Help & about → tap the version 7 times). */
@Composable
fun DeveloperRow(onOpen: (SettingsPage) -> Unit) {
    Group { SettingsRow(Icons.Filled.Build, stringResource(R.string.set_developer), tag = "rowDeveloper") { onOpen(SettingsPage.DEVELOPER) } }
}

/** "Read warnings aloud" and "Show 'Be careful' warnings" (the same rows on iOS). */
@Composable
fun WarningSettings(readAloud: Boolean, onReadAloud: (Boolean) -> Unit, showCareful: Boolean, onShowCareful: (Boolean) -> Unit) {
    Group {
        ToggleRow(stringResource(R.string.set_read_aloud), stringResource(R.string.set_read_aloud_line), readAloud, onReadAloud)
        HorizontalDivider(Modifier.padding(start = 16.dp))
        ToggleRow(stringResource(R.string.set_show_careful), stringResource(R.string.set_show_careful_line), showCareful, onShowCareful)
    }
}

fun connectionLabel(c: Boolean?) = com.example.scamdetect.logic.Tr.s(when (c) {
    true -> R.string.status_connected
    false -> R.string.status_cant_reach
    null -> R.string.status_not_checked
})

@Composable
fun connectionColor(c: Boolean?) = when (c) {
    true -> SafeGreen
    false -> ScamRed
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A sub-page: back link, title with an optional ⓘ, scrolling content. */
@Composable
private fun SubPage(title: String, onBack: () -> Unit, info: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var showInfo by remember { mutableStateOf(false) }
    Page(title, onBack) {
        if (info != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Tr.s(R.string.what_does_this_mean), Modifier.weight(1f).clickable { showInfo = true },
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Info, contentDescription = Tr.s(R.string.a11y_about, title), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
    if (showInfo && info != null) InfoSheet(title, info) { showInfo = false }
}

// ---------------------------------------------------------------- Privacy level

@Composable
fun PrivacyPage(current: PrivacyLevel, onPick: (PrivacyLevel) -> Unit, onBack: () -> Unit, simple: Boolean = false) {
    var info by remember { mutableStateOf(false) }
    Page(stringResource(R.string.set_how_checked), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrivacyLevel.entries.forEach { level ->
                ChoiceCard(
                    selected = level == current,
                    title = if (simple) stringResource(privacyTagRes(level)) else "${level.shortTitle()} · ${stringResource(privacyTagRes(level))}",
                    line = stringResource(privacyPlainRes(level)),
                    tag = "privacy_${level.id}",
                ) { if (level.available) onPick(level) }
            }
            Text(stringResource(R.string.privacy_applies_next), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.privacy_english_only), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!simple) OutlinedButton(onClick = { info = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.setup_more_about), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (info) InfoSheet(stringResource(R.string.set_how_checked),
        PrivacyLevel.entries.joinToString("\n\n") { "${it.shortTitle()} · ${Tr.s(privacyTagRes(it))}: ${Tr.s(privacyPlainRes(it))}" } +
            "\n\n" + Tr.s(R.string.privacy_english_only)) { info = false }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text("  $text", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- My data & history

@Composable
fun DataPage(
    saveSentence: Boolean,
    onSaveSentence: (Boolean) -> Unit,
    helpImprove: Boolean,
    onHelpImprove: (Boolean) -> Unit,
    labelCount: Int,
    callCount: Int,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val info = Tr.s(R.string.data_info)
    SubPage(Tr.s(R.string.set_my_data), onBack, info) {
        Group {
            ToggleRow(Tr.s(R.string.data_save_sentence), Tr.s(R.string.data_save_sentence_line), saveSentence, onSaveSentence)
            HorizontalDivider(Modifier.padding(start = 16.dp))
            ToggleRow(Tr.s(R.string.data_help_improve), Tr.s(R.string.data_help_improve_line), helpImprove, onHelpImprove)
        }
        Group {
            KeyValueRow(Tr.s(R.string.data_calls_saved), "$callCount")
            HorizontalDivider(Modifier.padding(start = 16.dp))
            KeyValueRow(Tr.s(R.string.data_labels_kept), "$labelCount")
        }
        Text(Tr.s(R.string.data_nothing_uploaded), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { confirm = true },
            enabled = callCount > 0 || labelCount > 0,
            colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(Tr.s(R.string.btn_delete_history), style = MaterialTheme.typography.labelLarge) }
    }
    if (confirm) YesNoDialog(
        title = Tr.s(R.string.delete_title),
        text = Tr.s(R.string.delete_text),
        yes = Tr.s(R.string.btn_yes_delete),
        no = Tr.s(R.string.btn_no_keep),
        onYes = { confirm = false; onDelete() },
        onNo = { confirm = false },
        yesIsDanger = true,
    )
}

@Composable
internal fun ToggleRow(title: String, line: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch: a big target, read as one item ("Read warnings aloud, on, switch").
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---------------------------------------------------------------- Help & about

@Composable
fun HelpPage(
    version: String,
    onBack: () -> Unit,
    onReplayTour: () -> Unit = {},
    onRunSetup: () -> Unit = {},
    simple: Boolean = false,
    /** One tap on the version number (7 unlock the Developer view); returns what to tell the user, or null. */
    onVersionTap: () -> String? = { null },
) {
    Page(stringResource(R.string.set_help), onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PrivacyPromise()
            HelpSection(stringResource(R.string.help_how)) {
                listOf(R.string.help_b1, R.string.help_b2, R.string.help_b3).forEach { Bullet(stringResource(it)) }
            }
            HelpSection(stringResource(R.string.help_colours)) {
                ColourRow(Risk.Level.GREEN, stringResource(R.string.risk_safe), stringResource(R.string.help_safe_line))
                ColourRow(Risk.Level.YELLOW, stringResource(R.string.risk_careful), stringResource(R.string.help_careful_line))
                ColourRow(Risk.Level.RED, stringResource(R.string.risk_scam), stringResource(R.string.help_scam_line))
                ColourRow(Risk.Level.ERROR, stringResource(R.string.risk_not_checked), stringResource(R.string.help_notchecked_line))
            }
            HelpSection(stringResource(R.string.help_again)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onReplayTour, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.btn_replay_tour), style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = onRunSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.btn_run_setup), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            AboutSection(version, onVersionTap)
            if (!simple) {
                HelpSection(stringResource(R.string.help_privacy_levels)) {
                    PrivacyLevel.entries.forEach { Bullet("${it.shortTitle()} (${stringResource(privacyTagRes(it))}): ${stringResource(privacyPlainRes(it))}") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Phase 6: who made the app, that it isn't a government app, and how to check a scam (1799, ScamShield). */
@Composable
fun AboutSection(version: String, onVersionTap: () -> String? = { null }) {
    val context = LocalContext.current
    var toast by remember { mutableStateOf<android.widget.Toast?>(null) }
    HelpSection(stringResource(R.string.about_title)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.about_who), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_not_gov), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.about_verify), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { dial(context, SCAMSHIELD_HELPLINE) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.btn_call_1799), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, SCAMSHIELD_URL.toUri())) } catch (e: ActivityNotFoundException) { }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.btn_open_scamshield), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
            // Tapping the version 7 times unlocks the hidden Developer view (like Android's developer options).
            Text(stringResource(R.string.about_version) + " " + version, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("aboutVersion").clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                ) {
                    onVersionTap()?.let { msg ->
                        toast?.cancel()
                        toast = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).also { it.show() }
                    }
                }.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun HelpSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Group { Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColourRow(level: Risk.Level, name: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RiskIcon(level, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(meaning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
