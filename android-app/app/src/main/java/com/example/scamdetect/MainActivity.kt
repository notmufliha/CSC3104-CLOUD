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
import com.example.scamdetect.ui.ModelsPage
import com.example.scamdetect.ui.PrivacyPage
import com.example.scamdetect.ui.ServerPage
import com.example.scamdetect.ui.SettingsMenuScreen
import com.example.scamdetect.ui.SettingsPage
import com.example.scamdetect.ui.SummaryScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.ui.LocalTourTargets
import com.example.scamdetect.ui.SetupFlow
import com.example.scamdetect.ui.TourOverlay
import com.example.scamdetect.ui.TourTargets
import com.example.scamdetect.ui.leaveApp
import com.example.scamdetect.ui.tourDemoCall

enum class Screen {
    HOME, PICK, CALL, PASTE, DASHBOARD, CALL_DETAIL, SETTINGS,
    SETTINGS_PRIVACY, SETTINGS_SERVER, SETTINGS_MODELS, SETTINGS_DATA, SETTINGS_HELP,
    INSIGHTS, HISTORY,
}

private val SETTINGS_PAGES = setOf(
    Screen.SETTINGS_PRIVACY, Screen.SETTINGS_SERVER, Screen.SETTINGS_MODELS, Screen.SETTINGS_DATA, Screen.SETTINGS_HELP,
)

/** The three top-level screens, reached from the bottom tab bar. The app opens on HOME. */
private enum class Tab(val screen: Screen, val label: String, val icon: ImageVector) {
    HOME(Screen.HOME, "Home", Icons.Filled.Home),
    DASHBOARD(Screen.DASHBOARD, "Dashboard", Icons.AutoMirrored.Filled.List),
    SETTINGS(Screen.SETTINGS, "Settings", Icons.Filled.Settings),
}

/** Runtime permission from Android 17 (API 37) to reach private addresses such as the demo gateway. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class MainActivity : ComponentActivity() {
    // Android 17+ local network access is asked on the setup's server screen (and Settings → Server → Test), when
    // it is needed, not at start. If refused, requests fail and the app shows "Could not check" (never safe).

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScamDetectTheme { AppRoot() } }
    }
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
            SetupFlow(
                step = step,
                choices = choices,
                isReplay = vm.setupIsReplay,
                onStep = vm::setSetupStep,
                onChange = vm::updateSetup,
                onTest = vm::testSetupServer,
                onFinish = vm::finishSetup,
                onLeave = { if (vm.setupIsReplay) vm.leaveSetup() else leaveApp(context) },
            )
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
                        TourOverlay(i, onNext = vm::tourNext, onSkip = vm::endTour)
                    }
                }
            }
        }
    }
}

/**
 * Debug builds only, for screenshots and demos:
 *   adb shell am start -n com.example.scamdetect/.MainActivity --es demoSetup PRIVACY   (open the setup at a screen)
 *   --es demoPrivacy on_device   --es demoServer connected|cantReach
 *   --ei demoTour 3   (start the tour at step 3, 0-based)   --ez demoReset true
 */
@Composable
private fun DebugLaunchOptions(vm: CallViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity ?: return
    LaunchedEffect(Unit) {
        if (!vm.isDebugBuild) return@LaunchedEffect
        val i = activity.intent ?: return@LaunchedEffect
        if (i.getBooleanExtra("demoReset", false)) vm.resetOnboarding()
        i.getStringExtra("demoSetup")?.let { name ->
            vm.runSetupAgain()
            i.getStringExtra("demoPrivacy")?.let { id -> vm.updateSetup { it.copy(privacy = com.example.scamdetect.logic.PrivacyLevel.fromId(id)) } }
            when (i.getStringExtra("demoServer")) {
                "connected" -> vm.updateSetup { it.copy(server = com.example.scamdetect.logic.Onboarding.Server.CONNECTED) }
                "cantReach" -> vm.updateSetup { it.copy(server = com.example.scamdetect.logic.Onboarding.Server.CANT_REACH) }
            }
            runCatching { com.example.scamdetect.logic.Onboarding.Step.valueOf(name) }.getOrNull()?.let(vm::setSetupStep)
        }
        if (i.hasExtra("demoTour")) vm.startTour(i.getIntExtra("demoTour", 0))
        // only once: a rotation re-creates the activity with the same intent
        i.removeExtra("demoReset"); i.removeExtra("demoSetup"); i.removeExtra("demoTour")
    }
}

@Composable
fun ScamGuardApp(vm: CallViewModel = viewModel(), tour: Int? = null) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var openCallId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Where the call detail page goes back to (Dashboard or History). */
    var detailBack by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    var period by rememberSaveable { mutableStateOf(com.example.scamdetect.logic.DashboardStats.Period.WEEK) }

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.CALL) vm.discard()   // B.5: during a call, tells the server; no summary, nothing saved
        screen = when (screen) {
            Screen.CALL_DETAIL -> detailBack
            Screen.INSIGHTS, Screen.HISTORY -> Screen.DASHBOARD
            in SETTINGS_PAGES -> Screen.SETTINGS
            else -> Screen.HOME
        }
    }

    // The tour moves between tabs itself; its call steps show a pretend call (nothing starts, nothing is sent).
    val tourPlace = tour?.let { Tour.steps[it].place }
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
        InCallScreen(tourDemoCall(redAlert = Tour.steps[tour!!].target == Tour.Target.RED_WARNING), onHangUp = {})
        return
    }

    val tab = Tab.entries.firstOrNull { it.screen == screen }
    if (tab == null) {
        // Calls, the script picker, paste text and call details take the whole screen, without the tab bar.
        ScreenContent(screen, vm, { screen = it }, openCallId, { id -> detailBack = if (screen == Screen.HISTORY) Screen.HISTORY else Screen.DASHBOARD; openCallId = id }, period, { period = it }, detailBack)
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
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            ScreenContent(screen, vm, { screen = it }, openCallId, { id -> detailBack = if (screen == Screen.HISTORY) Screen.HISTORY else Screen.DASHBOARD; openCallId = id }, period, { period = it }, detailBack)
        }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0" }
    fun back() = go(Screen.SETTINGS)

    fun ringKind(kind: String) {
        vm.randomScript(kind)?.let { vm.ring(it); go(Screen.CALL) }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onScamCall = { ringKind("scam") },
            onNormalCall = { ringKind("normal") },
            onPickScript = { go(Screen.PICK) },
            onPasteText = { go(Screen.PASTE) },
        )

        Screen.PICK -> ScriptPickerScreen(
            scripts = vm.scripts,
            onPick = { vm.ring(it); go(Screen.CALL) },
            onBack = { go(Screen.HOME) },
        )

        Screen.PASTE -> PasteTextScreen(
            onCheck = { vm.startPasted(it); go(Screen.CALL) },
            onBack = { go(Screen.HOME) },
        )

        Screen.CALL -> when (state.phase) {
            Phase.RINGING -> IncomingCallScreen(
                callerName = "Unknown caller",
                callerNumber = "Private number",
                onAnswer = vm::answer,
                onDecline = { vm.decline(); go(Screen.HOME) },
            )
            Phase.IN_CALL -> InCallScreen(state, onHangUp = vm::hangUp)
            Phase.ENDED -> SummaryScreen(
                state,
                reportPreview = { vm.reportPreview() },
                onReport = { kind -> vm.sendReport(kind) },
                onDone = { vm.reset(); go(Screen.HOME) },
                onThisWasFine = vm::thisWasFine,
            )
            Phase.IDLE -> LaunchedEffect(Unit) { go(Screen.HOME) }
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

        Screen.SETTINGS -> SettingsMenuScreen(
            privacyLevel = privacyLevel,
            connection = connection,
            modelsValue = if ("federated" in modelStatus) "Updated" else "Built-in",
            callCount = calls.size,
            version = version,
            onRefresh = vm::refreshConnection,
            onOpen = { page ->
                go(when (page) {
                    SettingsPage.PRIVACY -> Screen.SETTINGS_PRIVACY
                    SettingsPage.SERVER -> Screen.SETTINGS_SERVER
                    SettingsPage.MODELS -> Screen.SETTINGS_MODELS
                    SettingsPage.DATA -> Screen.SETTINGS_DATA
                    SettingsPage.HELP -> Screen.SETTINGS_HELP
                })
            },
        )

        Screen.SETTINGS_PRIVACY -> PrivacyPage(privacyLevel, vm::setPrivacyLevel, ::back)
        Screen.SETTINGS_SERVER -> ServerPage(gatewayUrl, connection, vm::saveGatewayUrl, { vm.testConnection(it) }, ::back)
        Screen.SETTINGS_MODELS -> ModelsPage(modelStatus, mobileBertStatus, modelCheck, { vm.checkModelUpdates() }, ::back)
        Screen.SETTINGS_DATA -> DataPage(
            saveSentence = saveSentence, onSaveSentence = vm::setSaveSentence,
            helpImprove = helpImprove, onHelpImprove = vm::setHelpImprove,
            labelCount = labelCount, callCount = calls.size,
            onDelete = vm::deleteHistory, onBack = ::back,
        )
        Screen.SETTINGS_HELP -> HelpPage(
            version, modelStatus, mobileBertStatus, vm.isDebugBuild, vm::loadSampleHistory, ::back,
            onReplayTour = { vm.startTour() },
            onRunSetup = vm::runSetupAgain,
            onResetSetup = vm::resetOnboarding,
        )
    }
}
