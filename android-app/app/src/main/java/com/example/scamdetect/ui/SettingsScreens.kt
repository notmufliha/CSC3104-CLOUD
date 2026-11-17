package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.launch

// Settings as a grouped menu; each row opens its own page. Same rows, wording and order as
// ios-app/ScamGuard/UI/SettingsView.swift.

enum class SettingsPage { PRIVACY, SERVER, MODELS, DATA, HELP }

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
private fun MenuRow(icon: ImageVector, title: String, value: String, valueColor: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
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

@Composable
fun SettingsMenuScreen(
    privacyLevel: PrivacyLevel,
    connection: Boolean?,
    modelsValue: String,
    callCount: Int,
    version: String,
    onRefresh: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    Page("Settings") {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Group {
                MenuRow(Icons.Filled.Lock, "Privacy level", privacyLevel.shortTitle()) { onOpen(SettingsPage.PRIVACY) }
                HorizontalDivider(Modifier.padding(start = 66.dp))
                MenuRow(Icons.Filled.Share, "Server", connectionLabel(connection), connectionColor(connection)) { onOpen(SettingsPage.SERVER) }
                HorizontalDivider(Modifier.padding(start = 66.dp))
                MenuRow(Icons.Filled.Build, "On-device models", modelsValue) { onOpen(SettingsPage.MODELS) }
            }
            Group {
                MenuRow(Icons.Filled.Person, "My data & history", "$callCount call${if (callCount == 1) "" else "s"}") { onOpen(SettingsPage.DATA) }
                HorizontalDivider(Modifier.padding(start = 66.dp))
                MenuRow(Icons.Filled.Info, "Help & about", "v$version") { onOpen(SettingsPage.HELP) }
            }
        }
    }
}

fun connectionLabel(c: Boolean?) = when (c) {
    true -> "Connected"
    false -> "Can't reach"
    null -> "Not checked"
}

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
            Text("What does this mean?", Modifier.weight(1f).clickable { showInfo = true },
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Filled.Info, contentDescription = "About $title", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
    if (showInfo && info != null) InfoSheet(title, info) { showInfo = false }
}

// ---------------------------------------------------------------- Privacy level

@Composable
fun PrivacyPage(current: PrivacyLevel, onPick: (PrivacyLevel) -> Unit, onBack: () -> Unit) {
    val info = PrivacyLevel.entries.joinToString("\n\n") { "${it.shortTitle()}: ${it.explanation}" }
    SubPage("Privacy level", onBack, info) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrivacyLevel.entries.forEach { level ->
                val selected = level == current
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected, enabled = level.available, role = Role.RadioButton) { onPick(level) },
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.weight(1f)) {
                            Text(level.shortTitle() + if (level == PrivacyLevel.DEFAULT) " (default)" else "",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(level.oneLine(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text("Applies from the next call.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Server

@Composable
fun ServerPage(
    currentUrl: String,
    connection: Boolean?,
    onSave: (String) -> Unit,
    onTest: suspend (String) -> String,
    onBack: () -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val info = "The address of the ScamGuard server. Emulator: 10.0.2.2 is your laptop (docker compose: :8080). " +
        "Kubernetes: run demo/expose on the laptop; it prints https://<laptop Wi-Fi IP>:8443/api/. First time only: " +
        "install demo/tls/ca.crt on this device. Without HTTPS, what callers say is sent as plain text."
    SubPage("Server", onBack, info) {
        StatusChip(if (testing) "Checking…" else connectionLabel(connection), if (testing) MaterialTheme.colorScheme.onSurfaceVariant else connectionColor(connection))
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server address") }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
        val encrypted = url.trim().startsWith("https://", ignoreCase = true)
        Text(if (encrypted) "🔒 Encrypted" else "⚠︎ Not encrypted",
            style = MaterialTheme.typography.bodyLarge, color = if (encrypted) SafeGreen else ScamRed)
        Text("Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Emulator" to "http://10.0.2.2:8080/", "Emulator HTTPS" to "https://10.0.2.2:8443/api/",
                "Phone HTTPS" to "https://192.168.1.23:8443/api/").forEach { (label, u) ->
                OutlinedButton(onClick = { url = u }) { Text(label, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSave(url); result = "Saved" }, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Save", style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = { scope.launch { testing = true; result = onTest(url); testing = false } },
                modifier = Modifier.weight(1f).height(52.dp)) { Text("Test", style = MaterialTheme.typography.bodyLarge) }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
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

// ---------------------------------------------------------------- On-device models

@Composable
fun ModelsPage(modelStatus: String, mobileBertStatus: String, modelCheck: String, onCheck: suspend () -> String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info = "Used at privacy levels 2 and 3. Newer versions come from federated learning: phones train on their own " +
        "calls and share only model changes, never the calls. The app checks for new versions when it starts."
    SubPage("On-device models", onBack, info) {
        Group {
            modelStatus.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                val name = line.substringBefore(":").replaceFirstChar { it.uppercase() }
                val value = if ("federated" in line) "Updated " + line.substringAfter("federated ").substringBefore(" (") else "Built-in"
                KeyValueRow(if (name == "Money") "Money / OTP" else name, value)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            KeyValueRow("MobileBERT", if (mobileBertStatus.startsWith("MobileBERT installed")) "Installed" else "Not installed")
        }
        Button(onClick = { scope.launch { onCheck() } }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Check for updates", style = MaterialTheme.typography.bodyLarge)
        }
        if (modelCheck.isNotEmpty()) Text(friendlyModelCheck(modelCheck), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Train on my calls", style = MaterialTheme.typography.bodyLarge)
        }
        Text("Coming soon", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The update check's result in plain words (same wording on iOS). */
fun friendlyModelCheck(msg: String): String = when {
    msg.startsWith("Checking") -> "Checking…"
    msg.startsWith("No model service") || msg.startsWith("Bad server address") -> "Can't reach the update service"
    msg.startsWith("The registry has no phone models") -> "No updates yet"
    msg.startsWith("Kept the current models") -> "Update failed, kept the current models"
    msg.startsWith("Updated") -> "Models updated"
    else -> msg
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
    val info = "Save the riskiest sentence: keeps the one caller line that scored highest in calls that ended with a " +
        "warning. Off: no words from your calls are kept, only verdicts and scores.\n\n" +
        "Help improve detection: the app keeps simple labels on this phone (for example, you hung up right after a " +
        "warning, or tapped \"This was fine\"). On: the caller's lines of those calls are kept too, with numbers and " +
        "names removed. Nothing is uploaded.\n\nYour history stays on this phone."
    SubPage("My data & history", onBack, info) {
        Group {
            ToggleRow("Save the riskiest sentence", "Keep one line from warned calls", saveSentence, onSaveSentence)
            HorizontalDivider(Modifier.padding(start = 16.dp))
            ToggleRow("Help improve detection", "Keep labels on this phone", helpImprove, onHelpImprove)
        }
        Group {
            KeyValueRow("Calls saved", "$callCount")
            HorizontalDivider(Modifier.padding(start = 16.dp))
            KeyValueRow("Labels kept", "$labelCount")
        }
        Text("Nothing here is uploaded.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { confirm = true },
            enabled = callCount > 0 || labelCount > 0,
            colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Delete my history", style = MaterialTheme.typography.bodyLarge) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete my history?") },
        text = { Text("Deletes every saved call, chart, saved sentence and label on this phone. This can't be undone.",
            style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(onClick = { confirm = false; onDelete() },
                colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White)) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ToggleRow(title: String, line: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- Help & about

@Composable
fun HelpPage(version: String, modelStatus: String, mobileBertStatus: String, debug: Boolean, onLoadSample: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    SubPage("Help & about", onBack) {
        HelpSection("How ScamGuard works") {
            listOf(
                "It listens to what the caller says.",
                "Each sentence is checked for scam signs.",
                "The bar turns yellow or red when it's risky.",
                "A red alert tells you to hang up.",
            ).forEach { Bullet(it) }
        }
        HelpSection("What the colours mean") {
            ColourRow(SafeGreen, "Safe", "No warning signs")
            ColourRow(WarnYellow, "Warning", "Be careful")
            ColourRow(ScamRed, "Scam", "Hang up now")
            ColourRow(NeutralGrey, "Grey", "Couldn't check")
        }
        HelpSection("Privacy levels") {
            PrivacyLevel.entries.forEach { Bullet("${it.shortTitle()}: ${it.oneLine().replaceFirstChar { c -> c.lowercase() }}") }
        }
        HelpSection("Report a scam") {
            Button(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, SCAMSHIELD_URL.toUri())) } catch (e: ActivityNotFoundException) { }
            }, colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Open ScamShield", style = MaterialTheme.typography.bodyLarge) }
        }
        HelpSection("About") {
            KeyValueRow("Version", version)
            modelStatus.lines().filter { it.isNotBlank() }.forEach { line ->
                KeyValueRow(line.substringBefore(":").replaceFirstChar { it.uppercase() } + " model",
                    if ("federated" in line) line.substringAfter("federated ").substringBefore(" (") else "built-in")
            }
            KeyValueRow("MobileBERT", Regex("\\((mobilebert[^)]*)\\)").find(mobileBertStatus)?.groupValues?.get(1) ?: "not installed")
        }
        if (debug) HelpSection("Debug build") {
            OutlinedButton(onClick = { onLoadSample(); loaded = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Load sample history", style = MaterialTheme.typography.bodyLarge)
            }
            if (loaded) Text("Added about 60 sample calls.", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))
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
private fun ColourRow(color: Color, name: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).background(color, CircleShape))
        Text("  $name", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(meaning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
