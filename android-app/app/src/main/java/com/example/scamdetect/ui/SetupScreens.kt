package com.example.scamdetect.ui

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.scamdetect.LOCAL_NETWORK_PERMISSION
import com.example.scamdetect.R
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.Onboarding.Path
import com.example.scamdetect.logic.Onboarding.Step
import com.example.scamdetect.logic.PrivacyLevel
import kotlinx.coroutines.launch

// First-launch setup. Simple (the default): Welcome, "Use recommended settings", family contact.
// "Choose myself": the full choices. "Set up for a family member": their settings, the contact, an optional PIN.
// Same screens, wording and look as ios-app/ScamGuard/UI/SetupViews.swift.

/** What the setup screens can do (kept in CallViewModel, so a rotation keeps it). */
data class SetupActions(
    val onStep: (Step) -> Unit,
    val onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit,
    val onFinish: (startTour: Boolean) -> Unit,
    val onLeave: () -> Unit,
    /** Shows a text size while the setup runs (saved only when it finishes). */
    val onTextSize: (String) -> Unit,
)

/**
 * The setup. Swipe or Back / the big buttons between screens; Skip saves what is picked so far (recommended
 * settings for the rest). Android Back goes one screen back; on the first screen it asks before leaving.
 */
@Composable
fun SetupFlow(step: Step, choices: Onboarding.Choices, isReplay: Boolean, a: SetupActions) {
    val steps = Onboarding.steps(choices)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = index) { steps.size }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var askWho by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The view model holds the step (it survives rotation); the pager follows it, and a swipe updates it.
    LaunchedEffect(index, steps.size) { if (pager.currentPage != index) pager.animateScrollToPage(index) }
    LaunchedEffect(pager.settledPage) { steps.getOrNull(pager.settledPage)?.let { if (it != step) a.onStep(it) } }

    fun go(to: Step?) { if (to != null) { a.onStep(to); scope.launch { pager.animateScrollToPage(Onboarding.steps(choices).indexOf(to).coerceAtLeast(0)) } } }
    val previous = Onboarding.previous(step, choices)
    BackHandler { if (previous != null) go(previous) else confirmLeave = true }

    Surface(Modifier.fillMaxSize().testTag("setup"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // top bar: Back on the left, Skip on the right
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (previous != null) TextButton(onClick = { go(previous) }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("‹ " + stringResource(R.string.btn_back), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.weight(1f))
                // Skip on the first screen = Standard mode with recommended settings.
                if (step != Step.DONE && step != Step.HANDOVER) TextButton(onClick = { a.onFinish(false) }, modifier = Modifier.heightIn(min = 56.dp).testTag("setupSkip")) {
                    Text(stringResource(R.string.btn_skip), style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { steps.getOrElse(it) { Step.WHO } }) { page ->
                val s = steps.getOrElse(page) { Step.WHO }
                SetupPage(s, choices) {
                    when (s) {
                        Step.WHO -> WhoChoices(choices.path) { path ->
                            val next = Onboarding.choose(choices, path)
                            a.onChange { Onboarding.choose(it, path) }
                            a.onTextSize(next.textSize)
                        }
                        Step.WELCOME, Step.HANDOVER -> Unit
                        Step.RECOMMENDED -> RecommendedList()
                        Step.PRIVACY -> PrivacyChoices(choices, a.onChange)
                        Step.DATA -> DataChoices(choices, a.onChange)
                        Step.HELPER -> HelperChoices(choices, a)
                        Step.FAMILY -> FamilyFields(choices, a.onChange)
                        Step.PIN -> PinSetup(choices, a.onChange)
                        Step.DONE -> SetupSummary(choices)
                    }
                }
            }
            // bottom: progress dots and the main button(s)
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val (n, of) = Onboarding.progress(step, choices)
                // No dots until the first question is answered (the number of screens depends on it).
                if (choices.path != null) Dots(index, steps.size, stringResource(R.string.setup_step, n.toString(), of.toString()))
                val wide = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(min = 60.dp)
                when (step) {
                    // Always full contrast (a faded button looks broken): tapping it too early says what to do.
                    Step.WHO -> {
                        if (askWho && choices.path == null) Text(stringResource(R.string.who_pick_one), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                        Button(onClick = { if (choices.path == null) askWho = true else go(Onboarding.next(step, choices)) },
                            modifier = wide.testTag("setupNext")) { Text(stringResource(R.string.btn_next), style = BigButtonText) }
                    }
                    Step.WELCOME -> Button(onClick = { go(Onboarding.next(step, choices)) }, modifier = wide.testTag("setupNext")) {
                        Text(stringResource(R.string.btn_get_started), style = BigButtonText)
                    }
                    Step.RECOMMENDED -> {
                        Button(onClick = { a.onChange { Onboarding.recommended(it) }; a.onFinish(true) }, modifier = wide.testTag("setupRecommended")) {
                            Text(stringResource(R.string.btn_use_recommended), style = BigButtonText, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(onClick = { a.onChange { it.copy(path = Path.CUSTOM) }; go(Step.PRIVACY) }, modifier = wide.testTag("setupCustom")) {
                            Text(stringResource(R.string.btn_choose_myself), style = BigButtonText)
                        }
                    }
                    Step.FAMILY -> {
                        val last = Onboarding.next(step, choices) == null
                        Button(onClick = { if (last) a.onFinish(true) else go(Onboarding.next(step, choices)) }, modifier = wide.testTag("setupFamilyNext")) {
                            Text(stringResource(if (last) R.string.btn_save_finish else R.string.btn_next), style = BigButtonText)
                        }
                        TextButton(onClick = {
                            a.onChange { it.copy(familyName = "", familyPhone = "") }
                            if (last) a.onFinish(true) else go(Onboarding.next(step, choices))
                        }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.btn_skip_for_now), style = MaterialTheme.typography.bodyLarge) }
                    }
                    Step.HANDOVER -> {
                        Button(onClick = { a.onFinish(true) }, modifier = wide.testTag("setupTour")) { Text(stringResource(R.string.btn_start_tour), style = BigButtonText) }
                        TextButton(onClick = { a.onFinish(false) }, modifier = Modifier.heightIn(min = 56.dp).testTag("setupNoTour")) {
                            Text(stringResource(R.string.btn_finish_no_tour), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Step.DONE -> {
                        Button(onClick = { a.onFinish(true) }, modifier = wide.testTag("setupTour")) { Text(stringResource(R.string.btn_show_me_around), style = BigButtonText) }
                        OutlinedButton(onClick = { a.onFinish(false) }, modifier = wide.testTag("setupNoTour")) { Text(stringResource(R.string.btn_skip_tour), style = BigButtonText) }
                    }
                    else -> {
                        Button(onClick = { go(Onboarding.next(step, choices)) }, modifier = wide.testTag("setupNext")) {
                            Text(stringResource(R.string.btn_next), style = BigButtonText)
                        }
                    }
                }
            }
        }
    }

    if (confirmLeave) YesNoDialog(
        title = stringResource(R.string.setup_leave_title),
        text = stringResource(if (isReplay) R.string.setup_leave_replay else R.string.setup_leave_first),
        yes = stringResource(R.string.btn_leave),
        no = stringResource(R.string.btn_stay),
        onYes = { confirmLeave = false; a.onLeave() },
        onNo = { confirmLeave = false },
    )
}

private val BigButtonText @Composable get() = MaterialTheme.typography.labelLarge

/** Screen title and one line, by step. Helper path: the family screen talks to the helper. */
private fun titleOf(s: Step, c: Onboarding.Choices) = when (s) {
    Step.WHO -> R.string.who_title
    Step.HANDOVER -> R.string.handover_title_noname
    Step.WELCOME -> R.string.setup_welcome_title
    Step.RECOMMENDED -> R.string.setup_rec_title
    Step.PRIVACY -> R.string.setup_privacy_title
    Step.DATA -> R.string.setup_data_title
    Step.HELPER -> R.string.setup_helper_title
    Step.FAMILY -> if (c.path == Path.HELPER) R.string.setup_helper_family_title else R.string.setup_family_title
    Step.PIN -> R.string.pin_setup_title
    Step.DONE -> R.string.setup_done_title
}

private fun lineOf(s: Step, c: Onboarding.Choices) = when (s) {
    Step.WHO -> R.string.who_line
    Step.HANDOVER -> R.string.handover_line
    Step.WELCOME -> R.string.setup_welcome_line
    Step.RECOMMENDED -> R.string.setup_rec_line
    Step.PRIVACY -> R.string.setup_privacy_line
    Step.DATA -> R.string.setup_data_line
    Step.HELPER -> R.string.setup_helper_line
    Step.FAMILY -> if (c.path == Path.HELPER) R.string.setup_helper_family_line else R.string.setup_family_line
    Step.PIN -> R.string.pin_setup_line
    Step.DONE -> R.string.setup_done_line
}

/** Big icon, title, one line, then the screen's own content; scrolls with very large text. */
@Composable
private fun SetupPage(step: Step, c: Onboarding.Choices, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.widthIn(max = 520.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(if (step == Step.WELCOME || step == Step.WHO) 16.dp else 8.dp))
                SetupIcon(step)
                val name = c.familyName.trim()
                Text(if (step == Step.HANDOVER && name.isNotEmpty()) stringResource(R.string.handover_title, name) else stringResource(titleOf(step, c)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Text(stringResource(lineOf(step, c)), style = SetupLine, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (step == Step.WELCOME || step == Step.HANDOVER) PrivacyPromise()
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

private val SetupLine @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 22.sp, lineHeight = 30.sp)

/** "Your calls stay private. We never record your voice." (Welcome and Help.) */
@Composable
fun PrivacyPromise() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.privacy_promise), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SetupIcon(step: Step) {
    // No picture on the first question: all three answers must fit on the screen, even with large text.
    if (step == Step.WHO) return
    if (step == Step.WELCOME) {
        Image(painterResource(R.drawable.scamguard_logo), contentDescription = null,
            modifier = Modifier.size(104.dp).clip(RoundedCornerShape(24.dp)))
        return
    }
    val icon: ImageVector = when (step) {
        Step.RECOMMENDED -> Icons.Filled.Star
        Step.PRIVACY -> Icons.Filled.Lock
        Step.DATA -> Icons.Filled.Person
        Step.HELPER -> Icons.Filled.Settings
        Step.FAMILY -> Icons.Filled.Phone
        Step.PIN -> Icons.Filled.Lock
        Step.HANDOVER -> Icons.Filled.Favorite
        else -> Icons.Filled.CheckCircle
    }
    Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun Dots(index: Int, count: Int, spoken: String) {
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { i ->
            val on = i == index
            Box(Modifier.size(width = if (on) 24.dp else 10.dp, height = 10.dp)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}

/** A big radio card: one choice in a list (text size, privacy). */
@Composable
fun ChoiceCard(selected: Boolean, title: String, line: String? = null, tag: String = "", onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (line != null) Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Plain yes / no question: big text, nothing pre-selected, both buttons the same size. */
@Composable
fun YesNoDialog(title: String, text: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit, yesIsDanger: Boolean = false) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onYes, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    border = BorderStroke(2.dp, if (yesIsDanger) ScamRed else MaterialTheme.colorScheme.primary)) {
                    Text(yes, style = MaterialTheme.typography.labelLarge, color = if (yesIsDanger) WarningInk else MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
                    Text(no, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
            }
        },
    )
}

// ---------------------------------------------------------------- 1. who will use this phone

/** Three big cards, one line each; nothing is picked until the user taps one. */
@Composable
private fun WhoChoices(current: Path?, onPick: (Path) -> Unit) {
    val picked = when (current) { Path.SIMPLE, Path.CUSTOM -> Path.SIMPLE; else -> current }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceCard(picked == Path.STANDARD, stringResource(R.string.who_me), stringResource(R.string.who_me_line), "who_me") { onPick(Path.STANDARD) }
        ChoiceCard(picked == Path.SIMPLE, stringResource(R.string.who_simpler), stringResource(R.string.who_simpler_line), "who_simpler") { onPick(Path.SIMPLE) }
        ChoiceCard(picked == Path.HELPER, stringResource(R.string.who_family), stringResource(R.string.who_family_line), "who_family") { onPick(Path.HELPER) }
    }
}

/** "Normal", "Large" or "Extra large". */
fun textSizeLabel(size: String) = when (size) {
    AppPrefs.TEXT_XLARGE -> R.string.text_xlarge
    AppPrefs.TEXT_LARGE -> R.string.text_large
    else -> R.string.text_normal
}

// ---------------------------------------------------------------- 2. recommended

@Composable
private fun RecommendedList() {
    Group {
        Column(Modifier.padding(vertical = 8.dp)) {
            listOf(R.string.setup_rec_b1, R.string.setup_rec_b2, R.string.setup_rec_b3).forEach { id ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RiskIcon(com.example.scamdetect.logic.Risk.Level.GREEN, 30.dp)
                    Text(stringResource(id), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- privacy ("Choose myself")

/** Plain name, tag and line for a privacy level (no technical words in Simple mode). */
fun privacyTagRes(p: PrivacyLevel) = when (p) {
    PrivacyLevel.CLOUD -> R.string.privacy_tag_cloud
    PrivacyLevel.HYBRID -> R.string.privacy_tag_hybrid
    PrivacyLevel.ON_DEVICE -> R.string.privacy_tag_on_device
}

fun privacyPlainRes(p: PrivacyLevel) = when (p) {
    PrivacyLevel.CLOUD -> R.string.privacy_plain_cloud
    PrivacyLevel.HYBRID -> R.string.privacy_plain_hybrid
    PrivacyLevel.ON_DEVICE -> R.string.privacy_plain_on_device
}

@Composable
private fun PrivacyChoices(c: Onboarding.Choices, onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit) {
    var info by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrivacyLevel.entries.forEach { level ->
            ChoiceCard(
                selected = level == c.privacy,
                title = if (c.simple) stringResource(privacyTagRes(level)) else "${level.shortTitle()} · ${stringResource(privacyTagRes(level))}",
                line = stringResource(privacyPlainRes(level)),
                tag = "privacy_${level.id}",
            ) { onChange { it.copy(privacy = level) } }
        }
        OutlinedButton(onClick = { info = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.setup_more_about), style = MaterialTheme.typography.labelLarge)
        }
    }
    if (info) InfoSheet(stringResource(R.string.set_how_checked),
        PrivacyLevel.entries.joinToString("\n\n") { "${Tr(privacyTagRes(it))}: ${Tr(privacyPlainRes(it))}" } + "\n\n" +
            Tr(R.string.privacy_english_only)) { info = false }
}

private fun Tr(id: Int) = com.example.scamdetect.logic.Tr.s(id)

fun privacyIcon(level: PrivacyLevel) = when (level) {
    PrivacyLevel.CLOUD -> R.drawable.ic_setup_cloud
    PrivacyLevel.HYBRID -> R.drawable.ic_setup_hybrid
    PrivacyLevel.ON_DEVICE -> R.drawable.ic_setup_phone
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

/**
 * withLocalNetwork for an action chosen when it runs: the setup asks once as it finishes (it no longer has a server
 * screen), so the first check or call doesn't fail on Android 17+. The action goes ahead whatever the answer.
 */
@Composable
fun rememberLocalNetworkAsk(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<() -> Unit>(1) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending[0]?.invoke(); pending[0] = null }
    return { run ->
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) { pending[0] = run; ask.launch(LOCAL_NETWORK_PERMISSION) } else run()
    }
}

// ---------------------------------------------------------------- data

@Composable
private fun DataChoices(choices: Onboarding.Choices, onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit) {
    Group {
        ToggleRow(stringResource(R.string.data_save_sentence), stringResource(R.string.data_save_sentence_line), choices.saveSentence) { v ->
            onChange { it.copy(saveSentence = v) }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp))
        ToggleRow(stringResource(R.string.data_help_improve), stringResource(R.string.data_help_improve_line), choices.helpImprove) { v ->
            onChange { it.copy(helpImprove = v) }
        }
    }
}

// ---------------------------------------------------------------- helper: their settings

@Composable
private fun HelperChoices(c: Onboarding.Choices, a: SetupActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextSizeChoices(c.textSize) { size -> a.onChange { it.copy(textSize = size, textSizeChosen = true) }; a.onTextSize(size) }
        Group {
            ToggleRow(stringResource(R.string.set_read_aloud), stringResource(R.string.set_read_aloud_line), c.readAloud) { v ->
                a.onChange { it.copy(readAloud = v, readAloudChosen = true) }
            }
        }
    }
}

/** "Text size": Normal (the phone's size) / Large / Extra large. */
@Composable
fun TextSizeChoices(current: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.set_text_size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        ChoiceCard(current == AppPrefs.TEXT_NORMAL, stringResource(R.string.text_normal), stringResource(R.string.text_normal_line), "text_normal") {
            onPick(AppPrefs.TEXT_NORMAL)
        }
        ChoiceCard(current == AppPrefs.TEXT_LARGE, stringResource(R.string.text_large), stringResource(R.string.text_size_sample), "text_large") {
            onPick(AppPrefs.TEXT_LARGE)
        }
        ChoiceCard(current == AppPrefs.TEXT_XLARGE, stringResource(R.string.text_xlarge), stringResource(R.string.text_size_sample), "text_xlarge") {
            onPick(AppPrefs.TEXT_XLARGE)
        }
    }
}

// ---------------------------------------------------------------- family contact

@Composable
fun FamilyFields(c: Onboarding.Choices, onChange: ((Onboarding.Choices) -> Onboarding.Choices) -> Unit) {
    ContactFields(c.familyName, c.familyPhone, { v -> onChange { it.copy(familyName = v) } }, { v -> onChange { it.copy(familyPhone = v) } })
}

/** Name + phone number fields (setup and Settings → My family contact). */
@Composable
fun ContactFields(name: String, phone: String, onName: (String) -> Unit, onPhone: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(value = name, onValueChange = onName, label = { Text(stringResource(R.string.field_name)) },
            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().testTag("familyName"))
        OutlinedTextField(value = phone, onValueChange = onPhone, label = { Text(stringResource(R.string.field_phone)) },
            placeholder = { Text(stringResource(R.string.field_phone_hint)) },
            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth().testTag("familyPhone"))
    }
}

// ---------------------------------------------------------------- done

@Composable
private fun SetupSummary(c: Onboarding.Choices) {
    val rows = buildList {
        add(stringResource(R.string.set_app_style) to stringResource(if (c.simple) R.string.style_simple else R.string.style_standard))
        add(stringResource(R.string.set_text_size) to stringResource(textSizeLabel(c.textSize)))
        add(stringResource(R.string.sum_privacy) to stringResource(privacyTagRes(c.privacy)))
        if (c.path == Path.CUSTOM || c.path == Path.STANDARD) {
            add(stringResource(R.string.data_save_sentence) to stringResource(if (c.saveSentence) R.string.on else R.string.off))
            add(stringResource(R.string.data_help_improve) to stringResource(if (c.helpImprove) R.string.on else R.string.off))
        }
        if (c.path == Path.HELPER || c.familyPhone.isNotBlank())
            add(stringResource(R.string.sum_family) to c.familyName.ifBlank { c.familyPhone }.ifBlank { stringResource(R.string.not_set) })
        if (c.path == Path.HELPER) add(stringResource(R.string.sum_pin) to stringResource(if (c.pin.length == 4) R.string.on else R.string.off))
    }
    Group {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
            KeyValueRow(k, v)
        }
    }
}

/** First launch: Back on the first screen, confirmed, closes the app (the setup shows again next time). */
fun leaveApp(context: android.content.Context) { context.findActivity()?.finish() }
