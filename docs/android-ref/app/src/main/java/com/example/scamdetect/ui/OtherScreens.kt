package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

@Composable
fun ScriptPickerScreen(scripts: List<CallScript>, onPick: (CallScript) -> Unit, onBack: () -> Unit) {
    Page("Call scripts", onBack) {
        if (scripts.isEmpty()) Text("No scripts found in assets/scripts/.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scripts, key = { it.fileName }) { s ->
                Card(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${s.kind.uppercase()} · ${(s.lang ?: "auto").uppercase()} · ${s.lines.size} lines",
                            style = MaterialTheme.typography.labelMedium
                        )
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
        val flagged = state.lines.filter { it.flags.isNotEmpty() }
        if (flagged.isEmpty()) Text("No caller lines were flagged.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(flagged, key = { it.seq }) { ChatBubble(it) }
        }
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SCAMSHIELD_URL)))
                } catch (e: ActivityNotFoundException) { /* no browser on this device */ }
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
            items(entries) { e ->
                val color = levelColor(Risk.level(e.verdict, if (e.verdict == "NOT CHECKED") "x" else null))
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
        Text("Presets", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { url = "http://10.0.2.2:8080/" }) { Text("Emulator") }
            OutlinedButton(onClick = { url = "http://192.168.1.23:8080/" }) { Text("Phone (laptop IP)") }
        }
        Text(
            "Emulator: 10.0.2.2 is your laptop. Real phone: use the laptop's Wi-Fi IP. " +
                "Kubernetes: run kubectl port-forward --address 0.0.0.0 svc/gateway 8080:8080 first.",
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
