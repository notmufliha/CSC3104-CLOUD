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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.ReportLine
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Risk
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SCAMSHIELD_URL = "https://www.scamshield.gov.sg/"

@Composable
internal fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
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
    onDashboard: () -> Unit,
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
            TextButton(onClick = onDashboard) { Text("Dashboard") }
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

/**
 * B.5: leads with state.summaryLevel (the final verdict, else the live one), the same value as the saved history
 * row; the live verdict is shown small underneath. While the end-of-call answer is on its way (up to 3 s):
 * "Final review…".
 */
@Composable
fun SummaryScreen(
    state: CallUiState,
    reportPreview: () -> List<ReportLine>,
    onReport: suspend (kind: String) -> String,
    onDone: () -> Unit,
    /** G.12: "This was fine" tapped (kept as a local label; sharing still goes through the consent screen). */
    onThisWasFine: () -> Unit = {},
) {
    val context = LocalContext.current
    // H.3: which button opened the consent screen ("scam" / "fine"), and the result once sent.
    var asking by remember { mutableStateOf<String?>(null) }
    var reportResult by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    asking?.let { kind ->
        ConsentDialog(
            kind = kind,
            lines = remember(kind) { reportPreview() },
            onShare = {
                asking = null
                sending = true
                scope.launch { reportResult = onReport(kind); sending = false }
            },
            onCancel = { asking = null; reportResult = "Nothing was shared." },
        )
    }
    if (state.reviewing) {
        Page("Call summary") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.height(24.dp).width(24.dp), strokeWidth = 3.dp)
                Text("Final review…", style = MaterialTheme.typography.titleMedium)
            }
            Text("Checking the whole call once more.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val level = state.summaryLevel
    val review = state.finalReview
    val risky = review?.topWindowSeqs.orEmpty()
    Page("Call summary") {
        Text(
            levelLabel(level).uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = levelColor(level)
        )
        Text(
            "During the call: " + levelLabel(state.level) + when {
                review?.onDevice == true -> " · Checked on this phone"
                review?.reviewed == true -> ""
                else -> " · Final review unavailable"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val reason = review?.reason?.takeIf { it.isNotBlank() && level in WARNING_LEVELS }
        val banner = Risk.banner(level, state.lit, state.flaggedTexts)
        if (reason != null) InfoBanner("$reason. ${Risk.action(state.lit)}", levelColor(level))
        else banner?.let { InfoBanner(it, levelColor(level)) }
        Text("What triggered it", fontWeight = FontWeight.SemiBold)
        val shown = state.lines.filter { it.isCaller && (it.flags.isNotEmpty() || it.seq in risky) }
        if (shown.isEmpty()) Text("No caller lines were flagged.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.seq }) { ChatBubble(it, riskyHere = it.seq in risky) }
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
        // H.3: help ScamGuard learn. Disabled once sent, so one call is shared at most once.
        val sent = reportResult?.startsWith("Thank you") == true
        // G: at levels 2 and 3 calls aren't shared, so there is no consent screen and nothing is sent
        val canShare = state.privacyLevel.cloudCall
        fun share(kind: String) { if (canShare) asking = kind else reportResult = CallViewModel.NOTHING_SHARED }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { share("scam") }, enabled = !sent && !sending, modifier = Modifier.weight(1f)) {
                Text("This was a scam")
            }
            OutlinedButton(onClick = { onThisWasFine(); share("fine") }, enabled = !sent && !sending, modifier = Modifier.weight(1f)) {
                Text("This was fine")
            }
        }
        if (sending) Text("Sending…", style = MaterialTheme.typography.bodySmall)
        reportResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

/** Shows exactly what would leave the phone before anything is sent. Nothing is sent on "Don't share". */
@Composable
private fun ConsentDialog(kind: String, lines: List<ReportLine>, onShare: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (kind == "scam") "Share this scam call?" else "Share this call as a false alarm?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Helps ScamGuard learn new scams. A team member reads it, labels it, and it may be used to " +
                        "retrain the detectors.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Sent: the caller's ${lines.size} lines below. We try to remove numbers, amounts, links and " +
                    "names, but some can slip through, such as a name said in passing or digits read out as words. " +
                    "Check the text; if you see anything private, tap Don't share.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Not sent: your phone number, the caller's number, anything you said.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Kept: deleted after 30 days, except lines a reviewer approves as training examples. Those " +
                    "stay encrypted with no link to you, so they can't be withdrawn later.",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                LazyColumn(Modifier.height(180.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(lines, key = { it.seq }) { Text("“${it.text}”", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { Button(onClick = onShare, enabled = lines.isNotEmpty()) { Text("Share") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Don't share") } },
    )
}

private val WARNING_LEVELS = setOf(Risk.Level.RED, Risk.Level.YELLOW)

// 4.7 History -> replaced by the Room dashboard (Workstream B.7): DashboardScreens.kt

// ---------------------------------------------------------------- 4.8 Settings

@Composable
fun SettingsScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
    onTest: suspend (String) -> String,
    onBack: () -> Unit,
    privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
    onPrivacyLevel: (PrivacyLevel) -> Unit = {},
    mobileBertStatus: String = "",
    modelStatus: String = "",
    modelCheck: String = "",
    onCheckModels: suspend () -> String = { "" },
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Page("Settings", onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            PrivacyLevelPicker(privacyLevel, onPrivacyLevel, mobileBertStatus)

            // Workstream G.10: which on-device models this phone runs, and a manual update check
            HorizontalDivider()
            Text("On-device models", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Used at privacy levels 2 and 3. Improved versions come from federated learning: phones train on " +
                    "their own calls and share only model changes, never the calls. The app checks for new versions " +
                    "when it starts.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(modelStatus, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { scope.launch { onCheckModels() } }) { Text("Check for model updates") }
            if (modelCheck.isNotEmpty()) Text(
                modelCheck, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Workstream G: the privacy level setting, one line of explanation per level. Applies from the next call. */
@Composable
private fun PrivacyLevelPicker(current: PrivacyLevel, onPick: (PrivacyLevel) -> Unit, mobileBertStatus: String) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text("Privacy level", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        PrivacyLevel.entries.forEach { level ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = level == current, enabled = level.available, role = Role.RadioButton) { onPick(level) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = level == current, onClick = null, enabled = level.available)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(level.title, color = if (level.available) Color.Unspecified else NeutralGrey)
                    Text(
                        level.explanation + if (level.available) "" else " (Not available in this version yet.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // G.4: whether level 3 has its MobileBERT second opinion
                    if (level == PrivacyLevel.ON_DEVICE && mobileBertStatus.isNotEmpty()) Text(
                        mobileBertStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text("A change applies from the next call.", style = MaterialTheme.typography.bodySmall)
    }
}
