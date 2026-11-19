package com.example.scamdetect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scamdetect.ui.CallViewModel
import com.example.scamdetect.ui.CallDetailScreen
import com.example.scamdetect.ui.DashboardScreen
import com.example.scamdetect.ui.HistoryScreen
import com.example.scamdetect.ui.HomeScreen
import com.example.scamdetect.ui.InsightsScreen
import com.example.scamdetect.ui.InCallScreen
import com.example.scamdetect.ui.IncomingCallScreen
import com.example.scamdetect.ui.PasteTextScreen
import com.example.scamdetect.ui.Phase
import com.example.scamdetect.ui.ScriptPickerScreen
import com.example.scamdetect.ui.DataPage
import com.example.scamdetect.ui.HelpPage
import com.example.scamdetect.ui.PrivacyPage
import com.example.scamdetect.ui.DeveloperScreen
import com.example.scamdetect.ui.DevScoreOverlay
import com.example.scamdetect.ui.DeveloperRow
import com.example.scamdetect.ui.rememberLocalNetworkAsk
import com.example.scamdetect.logic.DevOverlay
import com.example.scamdetect.logic.DevUnlock
import com.example.scamdetect.ui.SettingsMenuScreen
import com.example.scamdetect.ui.SettingsPage
import com.example.scamdetect.ui.SummaryScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.logic.Tr
import com.example.scamdetect.logic.Txt
import com.example.scamdetect.ui.LocalTourTargets
import com.example.scamdetect.ui.SetupFlow
import com.example.scamdetect.ui.TourOverlay
import com.example.scamdetect.ui.TourTargets
import com.example.scamdetect.ui.leaveApp
import com.example.scamdetect.ui.tourDemoCall
import com.example.scamdetect.ui.SetupActions
import com.example.scamdetect.ui.PinGate
import com.example.scamdetect.ui.SimpleHomeScreen
import com.example.scamdetect.ui.AppStyleTipCard
import com.example.scamdetect.ui.AppStylePage
import com.example.scamdetect.ui.SimpleSettingsMenu
import com.example.scamdetect.ui.AdvancedPage
import com.example.scamdetect.ui.AdvancedRows
import com.example.scamdetect.ui.TextSizePage
import com.example.scamdetect.ui.FamilyPage
import com.example.scamdetect.ui.PinSettingsPage
import com.example.scamdetect.ui.homeStatus
import com.example.scamdetect.ui.WarningActions
import com.example.scamdetect.ui.WarningScreen
import com.example.scamdetect.ui.SCAMSHIELD_HELPLINE
import com.example.scamdetect.ui.dial
import com.example.scamdetect.ui.textMessage

enum class Screen {
    HOME, PICK, CALL, PASTE, DASHBOARD, CALL_DETAIL, SETTINGS,
    SETTINGS_PRIVACY, SETTINGS_DATA, SETTINGS_HELP,
    SETTINGS_STYLE, SETTINGS_TEXT, SETTINGS_FAMILY, SETTINGS_ADVANCED, SETTINGS_PIN,
    /** The hidden Developer view: only once unlocked (Help & about → tap the version 7 times). */
    SETTINGS_DEVELOPER,
    INSIGHTS, HISTORY,
}

private val SETTINGS_PAGES = setOf(
    Screen.SETTINGS_PRIVACY, Screen.SETTINGS_DATA, Screen.SETTINGS_HELP,
    Screen.SETTINGS_STYLE, Screen.SETTINGS_TEXT, Screen.SETTINGS_FAMILY, Screen.SETTINGS_ADVANCED, Screen.SETTINGS_PIN,
    Screen.SETTINGS_DEVELOPER,
)

/** Simple mode: the pages inside Advanced settings (Back goes to it, and the PIN guards them). */
private val ADVANCED_PAGES = setOf(
    Screen.SETTINGS_PRIVACY, Screen.SETTINGS_DATA, Screen.SETTINGS_PIN, Screen.SETTINGS_DEVELOPER,
)

/** Simple mode with a PIN: these need it ("For family" and Advanced settings). */
private val PIN_SCREENS = ADVANCED_PAGES + setOf(Screen.SETTINGS_ADVANCED, Screen.DASHBOARD, Screen.INSIGHTS, Screen.HISTORY, Screen.CALL_DETAIL)

/** The three top-level screens, reached from the bottom tab bar. The app opens on HOME. */
private enum class Tab(val screen: Screen, val label: Int, val simpleLabel: Int, val icon: ImageVector) {
    HOME(Screen.HOME, R.string.tab_home, R.string.tab_home, Icons.Filled.Home),
    DASHBOARD(Screen.DASHBOARD, R.string.tab_dashboard, R.string.tab_family, Icons.AutoMirrored.Filled.List),
    SETTINGS(Screen.SETTINGS, R.string.tab_settings, R.string.tab_settings, Icons.Filled.Settings),
}

/** Runtime permission from Android 17 (API 37) to reach private addresses such as the demo gateway. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class MainActivity : ComponentActivity() {
    // Android 17+ local network access is asked on the setup's server screen (and Settings → Server → Test), when
    // it is needed, not at start. If refused, requests fail and the app shows "Could not check" (never safe).

    override fun onCreate(savedInstanceState: Bundle?) {
        applyDebugTextSize()
        applyDebugDeveloper()
        // Code outside Compose (view model, logic) reads the app's wording through Tr.
        Tr.txt = Txt { id, a -> applicationContext.getString(id, *a) }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: CallViewModel = viewModel()
            val textSize by vm.textSize.collectAsState()
            ScamDetectTheme(
                textScale = if (textSize == com.example.scamdetect.data.AppPrefs.TEXT_XLARGE) 1.15f else 1f,
                seniorType = textSize != com.example.scamdetect.data.AppPrefs.TEXT_NORMAL,
            ) { AppRoot(vm) }
        }
    }

    override fun onDestroy() {
        // The Developer view's logs live for this run only: closing the app (not a rotation) forgets them.
        if (isFinishing) com.example.scamdetect.data.Dev.log.clear()
        super.onDestroy()
    }
}

/** Debug builds only: --ez demoDev true unlocks the Developer view before the view model reads the flag. */
private fun MainActivity.applyDebugDeveloper() {
    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    if (intent?.hasExtra("demoDev") != true) return
    com.example.scamdetect.data.AppPrefs(this).developerUnlocked = intent.getBooleanExtra("demoDev", false)
    intent.removeExtra("demoDev")
}

/** Debug builds only: --es demoTextSize xlarge, saved before anything is drawn. */
private fun MainActivity.applyDebugTextSize() {
    if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val size = intent?.getStringExtra("demoTextSize") ?: return
    com.example.scamdetect.data.AppPrefs(this).textSize = size
    intent.removeExtra("demoTextSize")
}

/** First-launch setup, or the app with the guided tour drawn over it. */
@Composable
fun AppRoot(vm: CallViewModel = viewModel()) {
    val gate by vm.gate.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    DebugLaunchOptions(vm)
    when (gate) {
        CallViewModel.Gate.LOADING -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        CallViewModel.Gate.SETUP -> {
            val step by vm.setupStep.collectAsState()
            val choices by vm.setupChoices.collectAsState()
            // No server screen any more: Android 17+ asks for local network access as the setup finishes (not at
            // On-device, which never uses the network).
            val askNetwork = rememberLocalNetworkAsk()
            SetupFlow(step, choices, vm.setupIsReplay, SetupActions(
                onStep = vm::setSetupStep,
                onChange = vm::updateSetup,
                onFinish = { tour ->
                    if (com.example.scamdetect.logic.Onboarding.answered(vm.setupChoices.value).privacy == com.example.scamdetect.logic.PrivacyLevel.ON_DEVICE) vm.finishSetup(tour)
                    else askNetwork { vm.finishSetup(tour) }
                },
                onLeave = { if (vm.setupIsReplay) vm.leaveSetup() else leaveApp(context) },
                onTextSize = vm::previewTextSize,
            ))
        }
        CallViewModel.Gate.APP -> {
            val targets = remember { TourTargets() }
            val tour by vm.tourStep.collectAsState()
            CompositionLocalProvider(LocalTourTargets provides targets) {
                Box(Modifier.fillMaxSize()) {
                    // While the tour runs, TalkBack reads only the tour (the highlighted element is described there).
                    Box(Modifier.fillMaxSize().then(if (tour != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                        ScamGuardApp(vm, tour)
                    }
                    tour?.let { i ->
                        BackHandler { vm.tourBack() }
                        TourOverlay(Tour.steps(vm.tourSimple), i, onNext = vm::tourNext, onSkip = vm::endTour)
                    }
                }
            }
        }
    }
}

/**
 * Debug builds only, for screenshots and demos:
 *   adb shell am start -n com.example.scamdetect/.MainActivity --es demoSetup PRIVACY   (open the setup at a screen)
 *   --es demoPrivacy on_device   --ez demoDev true|false (unlock / lock the Developer view)
 *   --es demoSpeed 1x|2x|4x|instant   --ez demoOverlay true   (Developer view's playback speed and live scores)
 *   --es demoLevel cloud|hybrid|on_device   --es demoScript scam_en_11_bank_otp.json (ring and answer it)
 *   --ei demoTour 3   (start the tour at step 3, 0-based)   --ez demoReset true
 *   --es demoWarning red|yellow (a pretend call with the red warning / yellow banner; nothing sent or saved)
 *   --es demoFamily "Mei Ling|91234567"   --es demoConnection ok|fail
 *   --es demoTextSize large|xlarge   --es demoSimple on|off   --ez demoSample true   --es demoPin 2468|off
 *   --es demoScreen SETTINGS|SETTINGS_ADVANCED|DASHBOARD…   --es demoSetup WHO|WELCOME|HELPER|HANDOVER…
 *   --es demoPath standard|simple|custom|helper (with demoSetup; none = nothing picked on "Who will use this phone?")
 *   --ez demoTip true (show the one-time App style tip again)
 */
@Composable
private fun DebugLaunchOptions(vm: CallViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    LaunchedEffect(Unit) {
        if (!vm.isDebugBuild) return@LaunchedEffect
        val i = activity.intent ?: return@LaunchedEffect
        if (i.getBooleanExtra("demoReset", false)) vm.resetOnboarding()
        // --es demoSimple on|off: finish a pending setup and set Simple mode;  --ez demoSample true: sample history
        i.getStringExtra("demoSimple")?.let { v ->
            if (vm.gate.value != CallViewModel.Gate.APP) vm.finishSetup(startTour = false)
            vm.setAppStyle(v == "on")
        }
        if (i.getBooleanExtra("demoSample", false)) vm.loadSampleHistory()
        i.getStringExtra("demoPin")?.let { p -> if (p.isEmpty() || p == "off") vm.clearPin() else vm.setPin(p) }
        i.getStringExtra("demoSetup")?.let { name ->
            vm.runSetupAgain()
            i.getStringExtra("demoPrivacy")?.let { id -> vm.updateSetup { it.copy(privacy = com.example.scamdetect.logic.PrivacyLevel.fromId(id)) } }
            i.getStringExtra("demoPath")?.let { name ->
                runCatching { com.example.scamdetect.logic.Onboarding.Path.valueOf(name.uppercase()) }.getOrNull()?.let { path ->
                    vm.updateSetup { com.example.scamdetect.logic.Onboarding.choose(it, path) }
                    vm.previewTextSize(vm.setupChoices.value.textSize)
                }
            }
            runCatching { com.example.scamdetect.logic.Onboarding.Step.valueOf(name) }.getOrNull()?.let(vm::setSetupStep)
        }
        if (i.getBooleanExtra("demoTip", false)) vm.demoAppStyleTip()
        if (i.hasExtra("demoTour")) vm.startTour(i.getIntExtra("demoTour", 0))
        i.getStringExtra("demoFamily")?.let { f -> vm.setFamily(f.substringBefore("|"), f.substringAfter("|", "")) }
        when (i.getStringExtra("demoConnection")) { "ok" -> vm.demoConnection(true); "fail" -> vm.demoConnection(false) }
        i.getStringExtra("demoLevel")?.let { vm.setPrivacyLevel(com.example.scamdetect.logic.PrivacyLevel.fromId(it)) }
        i.getStringExtra("demoSpeed")?.let { id -> com.example.scamdetect.logic.PlaybackSpeed.entries.firstOrNull { it.id == id }?.let(vm::setPlaybackSpeed) }
        if (i.getBooleanExtra("demoOverlay", false)) vm.setDevOverlay(true)
        when (i.getStringExtra("demoWarning")) { "red" -> vm.demoWarning(true); "yellow" -> vm.demoWarning(false) }
        // only once: a rotation re-creates the activity with the same intent
        i.removeExtra("demoReset"); i.removeExtra("demoSetup"); i.removeExtra("demoTour"); i.removeExtra("demoWarning")
        i.removeExtra("demoFamily"); i.removeExtra("demoConnection"); i.removeExtra("demoSimple"); i.removeExtra("demoSample")
        i.removeExtra("demoPin"); i.removeExtra("demoTip"); i.removeExtra("demoSpeed"); i.removeExtra("demoOverlay")
        i.getStringExtra("demoScreen")?.let { vm.demoScreen.value = it; i.removeExtra("demoScreen") }
        // --es demoScript scam_en_11_bank_otp.json: ring and answer that script (as iOS -demoScript)
        i.getStringExtra("demoScript")?.let { n -> vm.scripts.firstOrNull { it.fileName == n }?.let { vm.ring(it); vm.answer() }; i.removeExtra("demoScript") }
        i.removeExtra("demoLevel")
    }
}

@Composable
fun ScamGuardApp(vm: CallViewModel = viewModel(), tour: Int? = null) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var openCallId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Where the call detail page goes back to (Dashboard or History). */
    var detailBack by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    var period by rememberSaveable { mutableStateOf(com.example.scamdetect.logic.DashboardStats.Period.WEEK) }

    // A call started anywhere (Home, the script picker, a debug launch) takes the whole screen.
    val phase = vm.state.collectAsState().value.phase
    LaunchedEffect(phase) { if (phase != Phase.IDLE && screen != Screen.CALL) screen = Screen.CALL }
    val simple by vm.simpleMode.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    // Debug builds only: --es demoScreen opens a screen (screenshots).
    val demoScreen by vm.demoScreen.collectAsState()
    LaunchedEffect(demoScreen) {
        demoScreen?.let { name -> runCatching { Screen.valueOf(name) }.getOrNull()?.let { screen = it }; vm.demoScreen.value = null }
    }
    /** Simple mode with a PIN: unlocked until the user goes back Home. */
    var unlocked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(screen) { if (screen == Screen.HOME) unlocked = false }

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.CALL) vm.discard()   // B.5: during a call, tells the server; no summary, nothing saved
        screen = when (screen) {
            Screen.CALL_DETAIL -> detailBack
            Screen.INSIGHTS, Screen.HISTORY -> Screen.DASHBOARD
            in ADVANCED_PAGES -> if (simple) Screen.SETTINGS_ADVANCED else Screen.SETTINGS
            in SETTINGS_PAGES -> Screen.SETTINGS
            else -> Screen.HOME
        }
    }

    // The tour moves between tabs itself; its call steps show a pretend call (nothing starts, nothing is sent).
    val tourSteps = Tour.steps(vm.tourSimple)
    val tourPlace = tour?.let { tourSteps.getOrNull(it)?.place }
    var tourWasOn by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tour) {
        when (tourPlace) {
            Tour.Place.HOME -> screen = Screen.HOME
            Tour.Place.DASHBOARD -> screen = Screen.DASHBOARD
            Tour.Place.SETTINGS -> screen = Screen.SETTINGS
            Tour.Place.CALL -> {}
            null -> if (tourWasOn) screen = Screen.HOME
        }
        tourWasOn = tour != null
    }
    if (tourPlace == Tour.Place.CALL) {
        val target = tourSteps[tour!!].target
        val demo = tourDemoCall(redAlert = target == Tour.Target.RED_WARNING || target == Tour.Target.WARNING_BUTTONS)
        Box(Modifier.fillMaxSize()) {
            InCallScreen(demo, onHangUp = {}, simple = vm.tourSimple)
            if (demo.warningOpen) WarningScreen(demo.warningKind, callEnded = false, familyName = vm.family.collectAsState().value?.name,
                actions = WarningActions(), demo = true)
        }
        return
    }

    val locked = simple && pinSet && !unlocked && screen in PIN_SCREENS
    val gate: @Composable () -> Unit = {
        PinGate(
            onCheck = { pin -> vm.verifyPin(pin).also { if (it) unlocked = true } },
            onForgot = { vm.clearPin(); unlocked = true },
            onBack = if (screen == Screen.DASHBOARD) null else ({ screen = Screen.SETTINGS }),
        )
    }
    val content: @Composable () -> Unit = {
        if (locked) gate()
        else ScreenContent(screen, vm, { screen = it }, openCallId, { id -> detailBack = if (screen == Screen.HISTORY) Screen.HISTORY else Screen.DASHBOARD; openCallId = id }, period, { period = it }, detailBack)
    }
    val tab = Tab.entries.firstOrNull { it.screen == screen }
    if (tab == null) {
        // Calls, the script picker, paste text, call details and settings pages take the whole screen.
        content()
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { screen = t.screen },
                        icon = { Icon(t.icon, contentDescription = null) },
                        // Tab labels stay on one line: their size is capped (~23 sp) at the largest font sizes.
                        label = {
                            val fs = androidx.compose.ui.platform.LocalDensity.current.fontScale
                            Text(androidx.compose.ui.res.stringResource(if (simple) t.simpleLabel else t.label),
                                fontSize = (18f / maxOf(1f, fs / 1.3f)).sp, maxLines = 1, softWrap = false)
                        },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) { content() }
    }
}

@Composable
private fun ScreenContent(
    screen: Screen,
    vm: CallViewModel,
    go: (Screen) -> Unit,
    openCallId: String?,
    setOpenCallId: (String) -> Unit,
    period: com.example.scamdetect.logic.DashboardStats.Period,
    setPeriod: (com.example.scamdetect.logic.DashboardStats.Period) -> Unit,
    detailBack: Screen,
) {
    val state by vm.state.collectAsState()
    val calls by vm.calls.collectAsState()
    val saveSentence by vm.saveSentence.collectAsState()
    val gatewayUrl by vm.gatewayUrl.collectAsState()
    val privacyLevel by vm.privacyLevel.collectAsState()
    val helpImprove by vm.helpImprove.collectAsState()
    val labelCount by vm.labelCount.collectAsState()
    val mobileBertStatus by vm.mobileBertStatus.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()
    val modelCheck by vm.modelCheck.collectAsState()
    val allPoints by vm.allPoints.collectAsState()
    val labels by vm.labels.collectAsState()
    val connection by vm.connection.collectAsState()
    val family by vm.family.collectAsState()
    val showCareful by vm.showBeCareful.collectAsState()
    val simple by vm.simpleMode.collectAsState()
    val styleTip by vm.appStyleTip.collectAsState()
    val pinSet by vm.pinSet.collectAsState()
    val textSize by vm.textSize.collectAsState()
    val readAloud by vm.readAloud.collectAsState()
    val devUnlocked by vm.devUnlocked.collectAsState()
    val devOverlay by vm.devOverlay.collectAsState()
    val devLatest by vm.devLatest.collectAsState()
    var noPhoneApp by remember { mutableStateOf(false) }
    if (noPhoneApp) androidx.compose.material3.AlertDialog(
        onDismissRequest = { noPhoneApp = false },
        text = { Text(androidx.compose.ui.res.stringResource(R.string.no_phone_app), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { androidx.compose.material3.Button(onClick = { noPhoneApp = false }) { Text(androidx.compose.ui.res.stringResource(R.string.btn_ok)) } },
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0" }
    val build = remember {
        runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toString() }
            .getOrNull() ?: "1"
    }
    // Locked (or reset) while on the Developer view: back to Settings.
    LaunchedEffect(devUnlocked, screen) { if (screen == Screen.SETTINGS_DEVELOPER && !devUnlocked) go(Screen.SETTINGS) }
    /** Help & about: 7 taps on the version unlock the Developer view. */
    var versionTaps by remember { mutableStateOf(0) }
    fun versionTap(): String? {
        val t = vm.developerTap(versionTaps)
        versionTaps = t.count
        return when (t.outcome) {
            DevUnlock.Outcome.NONE -> null
            DevUnlock.Outcome.STEPS_LEFT -> context.getString(R.string.dev_taps_left, t.left.toString())
            DevUnlock.Outcome.UNLOCKED -> context.getString(R.string.dev_unlocked)
            DevUnlock.Outcome.ALREADY -> context.getString(R.string.dev_already)
        }
    }
    fun back() = go(Screen.SETTINGS)
    /** Pages inside Advanced settings go back to it in Simple mode. */
    fun backAdvanced() = go(if (simple) Screen.SETTINGS_ADVANCED else Screen.SETTINGS)
    fun open(page: SettingsPage) = go(when (page) {
        SettingsPage.PRIVACY -> Screen.SETTINGS_PRIVACY
        SettingsPage.DATA -> Screen.SETTINGS_DATA
        SettingsPage.HELP -> Screen.SETTINGS_HELP
        SettingsPage.APP_STYLE -> Screen.SETTINGS_STYLE
        SettingsPage.TEXT_SIZE -> Screen.SETTINGS_TEXT
        SettingsPage.FAMILY -> Screen.SETTINGS_FAMILY
        SettingsPage.ADVANCED -> Screen.SETTINGS_ADVANCED
        SettingsPage.PIN -> Screen.SETTINGS_PIN
        SettingsPage.DEVELOPER -> if (devUnlocked) Screen.SETTINGS_DEVELOPER else Screen.SETTINGS
    })

    fun ringKind(kind: String) {
        vm.randomScript(kind)?.let { vm.ring(it); go(Screen.CALL) }
    }

    when (screen) {
        Screen.HOME -> if (simple) SimpleHomeScreen(
            status = homeStatus(privacyLevel, connection),
            calls = calls,
            onPractice = { ringKind("scam") },
            onFix = { vm.recheckConnection(); vm.connection.value == true },
            onRefresh = vm::refreshConnection,
        ) else HomeScreen(
            onScamCall = { ringKind("scam") },
            onNormalCall = { ringKind("normal") },
            onPickScript = { go(Screen.PICK) },
            onPasteText = { go(Screen.PASTE) },
            offer = if (styleTip) ({ AppStyleTipCard(onDismiss = vm::dismissAppStyleTip) }) else null,
        )

        Screen.PICK -> ScriptPickerScreen(
            allScripts = vm.scripts,
            onPick = { vm.ring(it); go(Screen.CALL) },
            onBack = { go(Screen.HOME) },
            showTestCalls = devUnlocked,
        )

        Screen.PASTE -> PasteTextScreen(
            onCheck = { vm.startPasted(it); go(Screen.CALL) },
            onBack = { go(Screen.HOME) },
        )

        Screen.CALL -> Box(Modifier.fillMaxSize()) {
            when (state.phase) {
                Phase.RINGING -> IncomingCallScreen(
                    callerName = androidx.compose.ui.res.stringResource(R.string.call_unknown_caller),
                    callerNumber = androidx.compose.ui.res.stringResource(R.string.call_private_number),
                    onAnswer = vm::answer,
                    onDecline = { vm.decline(); go(Screen.HOME) },
                )
                Phase.IN_CALL -> InCallScreen(state, onHangUp = vm::hangUp, showCareful = showCareful, simple = simple)
                Phase.ENDED -> SummaryScreen(
                    state,
                    reportPreview = { vm.reportPreview() },
                    onReport = { kind -> vm.sendReport(kind) },
                    onDone = { vm.reset(); go(Screen.HOME) },
                    onThisWasFine = vm::thisWasFine,
                    showCareful = showCareful,
                    simple = simple,
                )
                Phase.IDLE -> LaunchedEffect(Unit) { go(Screen.HOME) }
            }
            // Developer view: live scores at the top; never over the red warning (it hides while that shows).
            if (DevOverlay.visible(devOverlay, devUnlocked, state.phase == Phase.IN_CALL, state.warningOpen)) {
                DevScoreOverlay(devLatest, Modifier.align(androidx.compose.ui.Alignment.TopCenter).safeDrawingPadding().padding(top = 4.dp))
            }
            // The red warning covers the call (and its summary, if the call ended first) until the user acts.
            if (state.warningOpen && (state.phase == Phase.IN_CALL || state.phase == Phase.ENDED)) {
                BackHandler { }   // only its buttons close it
                val fam = family
                WarningScreen(
                    kind = state.warningKind,
                    callEnded = state.phase == Phase.ENDED,
                    familyName = fam?.name,
                    actions = WarningActions(
                        onHangUp = vm::hangUp,
                        onCallFamily = { vm.leaveCallForHelp(); if (fam != null && !dial(context, fam.phone)) noPhoneApp = true },
                        onCall1799 = { vm.leaveCallForHelp(); if (!dial(context, SCAMSHIELD_HELPLINE)) noPhoneApp = true },
                        onTellFamily = { if (fam != null && !textMessage(context, fam.phone, vm.familyMessage())) noPhoneApp = true },
                        onClose = vm::closeWarning,
                    ),
                )
            }
        }

        Screen.DASHBOARD -> DashboardScreen(
            calls = calls,
            labels = labels,
            period = period,
            onPeriod = setPeriod,
            onOpen = { setOpenCallId(it); go(Screen.CALL_DETAIL) },
            onInsights = { go(Screen.INSIGHTS) },
            onHistory = { go(Screen.HISTORY) },
            onGoHome = { go(Screen.HOME) },
            simple = simple,
        )

        Screen.INSIGHTS -> InsightsScreen(calls, labels, period, setPeriod, onBack = { go(Screen.DASHBOARD) })

        Screen.HISTORY -> HistoryScreen(
            calls = calls,
            points = allPoints,
            onOpen = { setOpenCallId(it); go(Screen.CALL_DETAIL) },
            onBack = { go(Screen.DASHBOARD) },
            onGoHome = { go(Screen.HOME) },
        )

        Screen.CALL_DETAIL -> {
            val id = openCallId.orEmpty()
            val call by remember(id) { vm.call(id) }.collectAsState(initial = null)
            val points by remember(id) { vm.points(id) }.collectAsState(initial = emptyList())
            CallDetailScreen(call, points, onBack = { go(detailBack) })
        }

        Screen.SETTINGS -> if (simple) SimpleSettingsMenu(
            textSize = textSize, readAloud = readAloud, familyName = family?.name, pinSet = pinSet,
            onOpen = ::open, onReadAloud = vm::setReadAloud,
        ) else SettingsMenuScreen(
            privacyLevel = privacyLevel,
            connection = connection,
            callCount = calls.size,
            onRefresh = vm::refreshConnection,
            onRetry = vm::recheckConnection,
            onOpen = ::open,
            textSize = textSize, readAloud = readAloud, onReadAloud = vm::setReadAloud,
            familyName = family?.name, showCareful = showCareful, onShowCareful = vm::setShowBeCareful,
            pinSet = pinSet,
            developerUnlocked = devUnlocked,
        )

        Screen.SETTINGS_ADVANCED -> AdvancedPage({
            LaunchedEffect(Unit) { vm.refreshConnection() }
            AdvancedRows(true, showCareful, vm::setShowBeCareful,
                pinSet, privacyLevel, connection, calls.size, ::open, vm::recheckConnection)
            // Simple mode: the Developer view sits here, behind the PIN when one is set.
            if (devUnlocked) DeveloperRow(::open)
        }, ::back)
        Screen.SETTINGS_STYLE -> AppStylePage(
            simple = simple, needsPin = vm::appStyleNeedsPin, onPick = vm::setAppStyle,
            onCheckPin = vm::verifyPin, onForgotPin = vm::clearPin, onBack = ::back,
        )
        Screen.SETTINGS_TEXT -> TextSizePage(textSize, vm::setTextSize, ::back)
        Screen.SETTINGS_FAMILY -> FamilyPage(family?.name.orEmpty().takeIf { family?.name != family?.phone }.orEmpty(), family?.phone.orEmpty(),
            vm::setFamily, ::back)
        Screen.SETTINGS_PIN -> PinSettingsPage(pinSet, vm::setPin, vm::clearPin, ::backAdvanced)

        Screen.SETTINGS_PRIVACY -> PrivacyPage(privacyLevel, vm::setPrivacyLevel, ::backAdvanced, simple)
        Screen.SETTINGS_DEVELOPER -> if (devUnlocked) DeveloperScreen(vm, version, build, ::backAdvanced)
        Screen.SETTINGS_DATA -> DataPage(
            saveSentence = saveSentence, onSaveSentence = vm::setSaveSentence,
            helpImprove = helpImprove, onHelpImprove = vm::setHelpImprove,
            labelCount = labelCount, callCount = calls.size,
            onDelete = vm::deleteHistory, onBack = ::backAdvanced,
        )
        Screen.SETTINGS_HELP -> HelpPage(
            version, ::back,
            onReplayTour = { vm.startTour() },
            onRunSetup = vm::runSetupAgain,
            simple = simple,
            onVersionTap = ::versionTap,
        )
    }
}
