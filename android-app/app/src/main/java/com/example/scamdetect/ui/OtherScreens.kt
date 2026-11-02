package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.HistoryEntry
import com.example.scamdetect.logic.Risk
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SCAMSHIELD_URL = "https://www.scamshield.gov.sg/"

@Composable
private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row {
                if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back") }
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

// ---------------------------------------------------------------- 4.1 Home

@Composable
fun HomeScreen(
    onScamCall: () -> Unit,
    onNormalCall: () -> Unit,
    onPickScript: () -> Unit,
    onPasteText: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Page("ScamGuard") {
        Text("Simulate a call and watch the scam check work live.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val wide = Modifier.fillMaxWidth().height(52.dp)
        Button(onClick = onScamCall, modifier = wide) { Text("Simulate scam call") }
        Button(onClick = onNormalCall, modifier = wide) { Text("Simulate normal call") }
        OutlinedButton(onClick = onPickScript, modifier = wide) { Text("Choose a call script…") }
        OutlinedButton(onClick = onPasteText, modifier = wide) { Text("Paste text") }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onHistory) { Text("History") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
    }
}

// ---------------------------------------------------------------- 4.2 Script picker

private enum class ScriptSort(val label: String) { TYPE("Type"), TITLE("Title A–Z"), LANGUAGE("Language"), LENGTH("Shortest") }

private fun kindLabel(kind: String) = when (kind) {
    "scam" -> "SCAM CALL"
    "normal" -> "NORMAL CALL"
    else -> kind.uppercase()
}

private fun kindColor(kind: String) = when (kind) {
    "scam" -> ScamRed
    "normal" -> SafeGreen
    else -> NeutralGrey
}

private fun langLabel(lang: String?) = when (lang) {
    "en" -> "English"
    "zh" -> "Chinese"
    else -> "Auto-detect"
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
fun ScriptPickerScreen(scripts: List<CallScript>, onPick: (CallScript) -> Unit, onBack: () -> Unit) {
    var kind by remember { mutableStateOf<String?>(null) }   // null = all
    var lang by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(ScriptSort.TYPE) }
    var filtersOpen by remember { mutableStateOf(false) }

    val shown = scripts
        .filter { kind == null || it.kind == kind }
        .filter { lang == null || it.lang == lang }
        .let { list ->
            when (sort) {
                ScriptSort.TYPE -> list.sortedWith(compareByDescending<CallScript> { it.kind == "scam" }.thenBy { it.title })
                ScriptSort.TITLE -> list.sortedBy { it.title }
                ScriptSort.LANGUAGE -> list.sortedWith(compareBy<CallScript> { it.lang ?: "~" }.thenBy { it.title })
                ScriptSort.LENGTH -> list.sortedWith(compareBy<CallScript> { it.lines.size }.thenBy { it.title })
            }
        }

    Page("Call scripts", onBack) {
        if (scripts.isEmpty()) Text("No scripts found in assets/scripts/.")
        // Collapsed by default; the header always says what is active, so nothing is hidden silently.
        val active = listOfNotNull(
            kind?.replaceFirstChar { it.uppercase() },
            lang?.let { langLabel(it) },
            sort.label,
        ).joinToString(" · ")
        Row(
            Modifier.fillMaxWidth().clickable { filtersOpen = !filtersOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${if (filtersOpen) "▾" else "▸"}  Filter & sort",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$active · ${shown.size} of ${scripts.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(filtersOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow("Type") {
                    listOf(null to "All", "scam" to "Scam", "normal" to "Normal").forEach { (k, l) ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(l) })
                    }
                }
                ChipRow("Language") {
                    listOf(null to "All", "en" to "English", "zh" to "Chinese").forEach { (k, l) ->
                        FilterChip(selected = lang == k, onClick = { lang = k }, label = { Text(l) })
                    }
                }
                ChipRow("Sort") {
                    ScriptSort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.label) })
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.fileName }) { s ->
                val color = kindColor(s.kind)
                Card(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(color))
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                kindLabel(s.kind),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                            Text(s.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${langLabel(s.lang)} · ${s.lines.size} lines",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Paste text

@Composable
fun PasteTextScreen(onCheck: (String) -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Page("Paste text", onBack) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("What the caller said") },
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )
        Button(onClick = { onCheck(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Check")
        }
    }
}

// ---------------------------------------------------------------- 4.6 After-call summary

/** Uses state.level, the same value as the in-call bar and the saved history row. */
@Composable
fun SummaryScreen(state: CallUiState, onDone: () -> Unit) {
    val context = LocalContext.current
    val level = state.level
    Page("Call summary") {
        Text(
            levelLabel(level).uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = levelColor(level)
        )
        state.banner?.let { InfoBanner(it, levelColor(level)) }
        Text("What triggered it", fontWeight = FontWeight.SemiBold)
        val flagged = state.lines.filter { it.isCaller && it.flags.isNotEmpty() }
        if (flagged.isEmpty()) Text("No caller lines were flagged.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(flagged, key = { it.seq }) { ChatBubble(it) }
        }
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SCAMSHIELD_URL.toUri()))
                } catch (e: ActivityNotFoundException) { // no browser on this device
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White)
        ) { Text("Report to ScamShield") }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

// ---------------------------------------------------------------- 4.7 History

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, onClear: () -> Unit, onBack: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    Page("History", onBack) {
        if (entries.isEmpty()) Text("No calls yet.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->   // stored newest first
                val notChecked = e.verdict == CallViewModel.NOT_CHECKED
                val color = levelColor(Risk.level(e.verdict, if (notChecked) e.topReason else null))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.verdict, color = color, fontWeight = FontWeight.Bold)
                            Text(fmt.format(Date(e.timeMillis)), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(e.topReason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (entries.isNotEmpty()) OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Text("Clear history")
        }
    }
}

// ---------------------------------------------------------------- 4.8 Settings

@Composable
fun SettingsScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
    onTest: suspend (String) -> String,
    onBack: () -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Page("Settings", onBack) {
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Gateway URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val encrypted = url.trim().startsWith("https://", ignoreCase = true)
        Text(
            if (encrypted) "Encrypted (HTTPS)" else "Not encrypted: transcripts are sent as plain text",
            color = if (encrypted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium
        )
        Text("Presets", fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { url = "https://10.0.2.2:8443/api/" }) { Text("Emulator (HTTPS)") }
            OutlinedButton(onClick = { url = "https://192.168.1.23:8443/api/" }) { Text("Phone (HTTPS)") }
            OutlinedButton(onClick = { url = "http://10.0.2.2:8080/" }) { Text("Emulator (HTTP)") }
        }
        Text(
            "Kubernetes: run demo/expose on the laptop; it prints the address to save here " +
                "(https://<laptop Wi-Fi IP>:8443/api/). It stays the same when the cluster or the gateway restarts. " +
                "Emulator: 10.0.2.2 is your laptop. First time only: install demo/tls/ca.crt on this device. " +
                "HTTP: docker compose or demo/port-forward.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(url); result = "Saved" }) { Text("Save") }
            OutlinedButton(onClick = { scope.launch { result = "Testing…"; result = onTest(url) } }) {
                Text("Test connection")
            }
        }
        result?.let { Text(it) }
    }
}
