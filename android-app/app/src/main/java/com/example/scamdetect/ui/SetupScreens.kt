package com.example.scamdetect.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.scamdetect.LOCAL_NETWORK_PERMISSION
import com.example.scamdetect.R
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.Onboarding.Server
import com.example.scamdetect.logic.Onboarding.Step
import com.example.scamdetect.logic.PrivacyLevel
import kotlinx.coroutines.launch

// First-launch setup: up to five screens, one decision each. Same screens, wording and look as
// ios-app/ScamGuard/UI/SetupViews.swift. The wording lives in logic/Onboarding.kt.

/**
 * The setup. Swipe or Back / Next between screens; Skip saves what is picked so far (defaults for the rest).
 * Android Back goes one screen back; on the first screen it asks before leaving.
 */
@Composable
fun SetupFlow(
    step: Step,
    choices: Onboarding.Choices,
    isReplay: Boolean,
    onStep: (Step) -> Unit,
    onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit,
    onTest: suspend (String) -> Boolean,
    onFinish: (startTour: Boolean) -> Unit,
    onLeave: () -> Unit,
) {
    val steps = Onboarding.steps(choices.privacy)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = index) { steps.size }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The view model holds the step (it survives rotation); the pager follows it, and a swipe updates it.
    LaunchedEffect(index) { if (pager.currentPage != index) pager.animateScrollToPage(index) }
    LaunchedEffect(pager.settledPage) { steps.getOrNull(pager.settledPage)?.let { if (it != step) onStep(it) } }

    fun go(to: Step?) { if (to != null) { onStep(to); scope.launch { pager.animateScrollToPage(steps.indexOf(to)) } } }
    val previous = Onboarding.previous(step, choices.privacy)
    BackHandler { if (previous != null) go(previous) else confirmLeave = true }

    Surface(Modifier.fillMaxSize().testTag("setup"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // top bar: Back on the left, Skip on the right
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (previous != null) TextButton(onClick = { go(previous) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("‹ Back", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.weight(1f))
                if (step != Step.DONE) TextButton(onClick = { onFinish(false) }, modifier = Modifier.heightIn(min = 48.dp).testTag("setupSkip")) {
                    Text("Skip", style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { steps[it] }) { page ->
                SetupPage(steps[page]) {
                    when (steps[page]) {
                        Step.WELCOME -> {}
                        Step.PRIVACY -> PrivacyChoices(choices.privacy) { p -> onChange { it.copy(privacy = p) } }
                        Step.SERVER -> ServerChoice(choices, onChange, onTest)
                        Step.DATA -> DataChoices(choices, onChange)
                        Step.DONE -> SetupSummary(choices)
                    }
                }
            }
            // bottom: progress dots and the main button
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Dots(index, steps.size, Onboarding.progress(step, choices.privacy))
                val wide = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 56.dp)
                when (step) {
                    Step.WELCOME -> Button(onClick = { go(Onboarding.next(step, choices.privacy)) }, modifier = wide.testTag("setupNext")) {
                        Text("Get started", style = BigButtonText)
                    }
                    Step.DONE -> {
                        Button(onClick = { onFinish(true) }, modifier = wide.testTag("setupTour")) { Text("Show me around", style = BigButtonText) }
                        OutlinedButton(onClick = { onFinish(false) }, modifier = wide.testTag("setupNoTour")) { Text("Skip tour", style = BigButtonText) }
                    }
                    else -> {
                        Button(onClick = { go(Onboarding.next(step, choices.privacy)) }, modifier = wide.testTag("setupNext")) {
                            Text("Next", style = BigButtonText)
                        }
                        if (step == Step.SERVER) TextButton(
                            onClick = { onChange { it.copy(server = Server.LATER) }; go(Onboarding.next(step, choices.privacy)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Set up later", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }
    }

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Leave setup?") },
        text = {
            Text(if (isReplay) "Nothing will change." else "You'll see it again next time.", style = MaterialTheme.typography.bodyLarge)
        },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onLeave() }) { Text("Leave") } },
        dismissButton = { Button(onClick = { confirmLeave = false }) { Text("Stay") } },
    )
}

private val BigButtonText @Composable get() = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)

/** Big icon, title, one line, then the screen's own content; scrolls with very large text. */
@Composable
private fun SetupPage(step: Step, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.widthIn(max = 520.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(if (step == Step.WELCOME) 40.dp else 8.dp))
                SetupIcon(step)
                Text(Onboarding.title(step), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text(Onboarding.line(step), style = SetupLine, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

private val SetupLine @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp)

@Composable
private fun SetupIcon(step: Step) {
    if (step == Step.WELCOME) {
        Image(painterResource(R.drawable.scamguard_logo), contentDescription = null,
            modifier = Modifier.size(128.dp).clip(RoundedCornerShape(28.dp)))
        return
    }
    val icon: ImageVector = when (step) {
        Step.PRIVACY -> Icons.Filled.Lock
        Step.SERVER -> ImageVector.vectorResource(R.drawable.ic_setup_server)
        Step.DATA -> Icons.Filled.Person
        else -> Icons.Filled.CheckCircle
    }
    Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun Dots(index: Int, count: Int, spoken: String) {
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val on = i == index
            Box(Modifier.size(width = if (on) 24.dp else 10.dp, height = 10.dp)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

// ---------------------------------------------------------------- 2. privacy

@Composable
private fun PrivacyChoices(current: PrivacyLevel, onPick: (PrivacyLevel) -> Unit) {
    var info by rememberSaveable { mutableStateOf<PrivacyLevel?>(null) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrivacyLevel.entries.forEach { level ->
            val selected = level == current
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                modifier = Modifier.fillMaxWidth().testTag("privacy_${level.id}")
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(level) },
            ) {
                Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(52.dp).background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(ImageVector.vectorResource(privacyIcon(level)), contentDescription = null, modifier = Modifier.size(28.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Onboarding.privacyTitle(level), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(Onboarding.privacyTag(level), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold)
                        Text(Onboarding.privacyLine(level), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { info = level }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = "About ${Onboarding.privacyTitle(level)}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    info?.let { l -> InfoSheet(Onboarding.privacyTitle(l), l.explanation) { info = null } }
}

fun privacyIcon(level: PrivacyLevel) = when (level) {
    PrivacyLevel.CLOUD -> R.drawable.ic_setup_cloud
    PrivacyLevel.HYBRID -> R.drawable.ic_setup_hybrid
    PrivacyLevel.ON_DEVICE -> R.drawable.ic_setup_phone
}

// ---------------------------------------------------------------- 3. server

@Composable
private fun ServerChoice(
    choices: Onboarding.Choices,
    onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit,
    onTest: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var testing by rememberSaveable { mutableStateOf(false) }
    fun runTest() {
        testing = true
        scope.launch {
            val ok = onTest(choices.serverUrl)
            onChange { it.copy(server = if (ok) Server.CONNECTED else Server.CANT_REACH) }
            testing = false
        }
    }
    val test = withLocalNetwork(::runTest)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = choices.serverUrl,
            onValueChange = { v -> onChange { it.copy(serverUrl = v, server = Server.NOT_CHECKED) } },
            label = { Text("Server address") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("setupUrl"),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SERVER_PRESETS.forEach { (label, u) ->
                PillChip(label, selected = choices.serverUrl == u) { onChange { it.copy(serverUrl = u, server = Server.NOT_CHECKED) } }
            }
        }
        OutlinedButton(onClick = test, enabled = !testing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setupTest")) {
            Text("Test connection", style = MaterialTheme.typography.bodyLarge)
        }
        val (label, color) = when {
            testing -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
            choices.server == Server.CONNECTED -> "Connected ✓" to SafeGreen
            choices.server == Server.CANT_REACH -> "Can't reach" to ScamRed
            else -> null to Color.Unspecified
        }
        if (label != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { StatusChip(label, color) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(Onboarding.NETWORK_HEADS_UP, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Android 17+: asks for local network access (when not granted yet) right before [run], so the question comes
 * when the user tests the server, not when the app starts. [run] goes ahead whatever the answer.
 */
@Composable
fun withLocalNetwork(run: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { run() }
    return {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(LOCAL_NETWORK_PERMISSION) else run()
    }
}

// ---------------------------------------------------------------- 4. data

@Composable
private fun DataChoices(choices: Onboarding.Choices, onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit) {
    Group {
        ToggleRow("Save the riskiest sentence", "Keep one line from warned calls", choices.saveSentence) { v ->
            onChange { it.copy(saveSentence = v) }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp))
        ToggleRow("Help improve detection", "Keep labels on this phone", choices.helpImprove) { v ->
            onChange { it.copy(helpImprove = v) }
        }
    }
}

// ---------------------------------------------------------------- 5. done

@Composable
private fun SetupSummary(choices: Onboarding.Choices) {
    Group {
        Onboarding.summary(choices).forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
            KeyValueRow(k, v)
        }
    }
}

/** First launch: Back on the first screen, confirmed, closes the app (the setup shows again next time). */
fun leaveApp(context: android.content.Context) { (context as? Activity)?.finish() }
