package com.example.scamdetect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.scamdetect.data.Dev
import com.example.scamdetect.logic.DevEntry
import com.example.scamdetect.logic.DevFormat
import com.example.scamdetect.logic.DevLine
import com.example.scamdetect.logic.PlaybackSpeed
import kotlinx.coroutines.launch

// The hidden Developer view (Settings → Help & about → tap the version 7 times). For demos and debugging; English
// only. Same sections, order and wording as ios-app/ScamGuard/UI/DeveloperViews.swift. Read-only except Tools and
// the server address; nothing here is saved (except the unlocked flag) or sent.

private val Mono = FontFamily.Monospace

@Composable
fun DeveloperScreen(vm: CallViewModel, version: String, build: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val tick by vm.devTick.collectAsState()
    val unlocked by vm.devUnlocked.collectAsState()
    val overlay by vm.devOverlay.collectAsState()
    val speed by vm.playbackSpeed.collectAsState()
    val gatewayUrl by vm.gatewayUrl.collectAsState()
    val connection by vm.connection.collectAsState()
    val modelCheck by vm.modelCheck.collectAsState()
    @Suppress("UNUSED_VARIABLE") val modelStatus by vm.modelStatus.collectAsState()   // redraw after an update
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var sampleNote by remember { mutableStateOf<String?>(null) }

    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied $label", Toast.LENGTH_SHORT).show()
    }

    // Read again on every change (tick): the log lives in memory, outside Compose.
    val connectionRows = remember(tick, gatewayUrl, connection) { vm.devConnectionRows(version, build) }
    val modelRows = remember(tick, modelCheck, modelStatus) { vm.devModelRows() }
    val call = remember(tick) { Dev.log.lastCall() }
    val entries = remember(tick) { Dev.log.entries() }

    Page("Developer view", onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("developerView"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("For demos and debugging. Only the unlocked flag is saved; logs stay in memory and caller text is masked.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Group {
                ToggleRow("Developer view", "Off hides this page and the server settings again, and forgets the logs.", unlocked) { on ->
                    vm.setDeveloperUnlocked(on)
                    if (!on) onBack()
                }
            }

            DevSection("1. Connection", onCopy = { copy("connection", rows(connectionRows)) }) {
                KeyValues(connectionRows)
                HorizontalDivider()
                ServerAddress(gatewayUrl, connection, vm::saveGatewayUrl) { vm.testConnection(it) }
            }

            DevSection("2. Models", onCopy = { copy("models", rows(modelRows)) }) {
                KeyValues(modelRows)
                Button(onClick = { scope.launch { vm.checkModelUpdates(); vm.refreshDevView() } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Check for model updates")
                }
                if (modelCheck.isNotEmpty()) MonoText(modelCheck)
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Train on my calls") }
                Text("Training on the phone comes in a later version.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            DevSection("3. Last call", onCopy = { copy("last call", vm.devDiagnostics(version, build).substringAfter("3. Last call\n")) }) {
                if (call == null) Text("No call since unlocking. Calls are kept in memory only.", style = MaterialTheme.typography.bodyMedium)
                else {
                    KeyValues(listOf("call_id" to call.callId, "privacy" to call.privacy, "ended_by" to (call.endedBy ?: "still on")))
                    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("devTable")) { MonoText(DevFormat.table(call.lines), 12) }
                    Text("End-of-call reply", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MonoText(call.end?.let { DevFormat.end(it) } ?: if (call.privacy == "cloud") "none yet" else "none (not sent at this privacy level)")
                }
            }

            DevSection("4. Raw replies (${entries.size} of ${com.example.scamdetect.logic.DevLog.CAPACITY})", onCopy = null) {
                if (entries.isEmpty()) Text("No requests since unlocking.", style = MaterialTheme.typography.bodyMedium)
                entries.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider()
                    RawEntry(e) { copy("reply", entryText(e)) }
                }
            }

            DevSection("5. Tools", onCopy = null) {
                Text("Call playback speed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackSpeed.entries.forEach { s -> Box(Modifier.testTag("speed_${s.id}")) { PillChip(s.label, selected = s == speed) { vm.setPlaybackSpeed(s) } } }
                }
                Text("Scripted and pasted calls. Instant keeps a ${com.example.scamdetect.logic.Playback.INSTANT_GAP_MS} ms gap so lines stay in order. Back to 1× when locked.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToggleRow("Show live scores during calls", "A small panel at the top of the call screen. Hidden while the red warning shows.", overlay, vm::setDevOverlay)
                HorizontalDivider()
                ToolButton("Load sample history") { vm.loadSampleHistory(); sampleNote = "Added about 60 sample calls." }
                ToolButton("Delete sample history") { vm.deleteSampleHistory(); sampleNote = "Sample calls deleted (real calls kept)." }
                sampleNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                ToolButton("Copy diagnostics") { copy("diagnostics", vm.devDiagnostics(version, build)) }
                ToolButton("Reset setup only") { vm.resetOnboarding() }
                Text("Keeps the history and settings; shows \"Who will use this phone?\" again.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { confirmReset = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resetApp"),
                ) { Text("Reset app to first launch") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmReset) YesNoDialog(
        title = "Reset app to first launch?",
        text = RESET_APP_TEXT,
        yes = "Reset app",
        no = "Cancel",
        onYes = { confirmReset = false; vm.resetApp() },
        onNo = { confirmReset = false },
        yesIsDanger = true,
    )
}

/** The confirmation for "Reset app to first launch" (same words on iOS). */
const val RESET_APP_TEXT = "This deletes all call history, settings, contact and PIN. The app will start like a new install."

private fun rows(r: List<Pair<String, String>>) = r.joinToString("\n") { "${it.first}: ${it.second}" }

private fun entryText(e: DevEntry) =
    "${e.method} ${e.path} ${e.status} ${e.ms} ms ${DevFormat.time(e.timeMs)}\nRequest:\n${e.request.ifEmpty { "(none)" }}\nReply:\n${e.response.ifEmpty { "(none)" }}"

@Composable
private fun DevSection(title: String, onCopy: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (onCopy != null) TextButton(onClick = onCopy) { Text("Copy") }
        }
        Group { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
    }
}

@Composable
private fun KeyValues(rows: List<Pair<String, String>>) {
    rows.forEach { (k, v) ->
        Column {
            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonoText(v)
        }
    }
}

@Composable
private fun MonoText(text: String, size: Int = 13) {
    Text(text, fontFamily = Mono, fontSize = size.sp, lineHeight = (size + 4).sp, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun RawEntry(e: DevEntry, onCopy: () -> Unit) {
    var open by rememberSaveable(e.timeMs, e.path) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp)) {
            MonoText("${if (open) "▾" else "▸"} ${e.method} ${e.path}  ${e.status}  ${e.ms} ms  ${DevFormat.time(e.timeMs)}")
        }
        if (open) {
            Text("Request", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) { MonoText(e.request.ifEmpty { "(none)" }, 12) }
            Text("Reply", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState())) { MonoText(e.response.ifEmpty { "(none)" }, 12) }
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

/** Server address: the field, presets, Save and Test connection with the full error. Developer view only. */
@Composable
private fun ServerAddress(currentUrl: String, connection: Boolean?, onSave: (String) -> Unit, onTest: suspend (String) -> String) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text("Server address", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    StatusChip(if (testing) "Checking…" else connectionLabel(connection), if (testing) MaterialTheme.colorScheme.onSurfaceVariant else connectionColor(connection))
    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Gateway URL") }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("devUrl"))
    val encrypted = url.trim().startsWith("https://", ignoreCase = true)
    Text(if (encrypted) "🔒 Encrypted (HTTPS)" else "⚠︎ Not encrypted (HTTP): caller text is sent as plain text",
        style = MaterialTheme.typography.bodyMedium, color = if (encrypted) SafeGreen else ScamRed)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SERVER_PRESETS.forEach { (label, u) -> PillChip(label, selected = url == u) { url = u } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onSave(url); result = "Saved." }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("devSave")) { Text("Save") }
        val test = withLocalNetwork { scope.launch { testing = true; result = onTest(url); testing = false } }
        OutlinedButton(onClick = test, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("devTest")) { Text("Test connection") }
    }
    result?.let { MonoText(it) }
}

/**
 * The live score overlay over the call screen: the newest caller line's scores, BERT, the rule and where it was
 * checked. Tap to collapse. The caller hides it while the red warning shows (logic/DevOverlay).
 */
@Composable
fun DevScoreOverlay(line: DevLine?, modifier: Modifier = Modifier) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White, shape = RoundedCornerShape(12.dp),
        modifier = modifier.widthIn(max = 420.dp).clickable { collapsed = !collapsed }.testTag("devOverlay"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (collapsed) {
                Text("Live scores ▸", fontFamily = Mono, fontSize = 12.sp, color = Color.White)
                return@Column
            }
            Text("Live scores · tap to hide", fontFamily = Mono, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            val lines = if (line == null) listOf("waiting for a caller line…") else listOf(
                "#${line.seq}  U ${DevFormat.score(line.urgency)}  M ${DevFormat.score(line.money)}  I ${DevFormat.score(line.impersonation)}",
                "combined ${DevFormat.score(line.combined)}  BERT ${DevFormat.score(line.bertScore)} ${line.bertStatus ?: "-"}",
                "rule ${line.rule ?: "-"}  live ${line.liveAlert ?: "-"}  ${line.verdict ?: "-"}",
                "checked: ${line.where} · ${line.latencyMs} ms",
            )
            lines.forEach { Text(it, fontFamily = Mono, fontSize = 12.sp, color = Color.White) }
        }
    }
}
