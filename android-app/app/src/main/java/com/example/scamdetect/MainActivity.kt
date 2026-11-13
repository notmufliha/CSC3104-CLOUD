package com.example.scamdetect

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scamdetect.ui.CallViewModel
import com.example.scamdetect.ui.CallDetailScreen
import com.example.scamdetect.ui.DashboardScreen
import com.example.scamdetect.ui.HomeScreen
import com.example.scamdetect.ui.InCallScreen
import com.example.scamdetect.ui.IncomingCallScreen
import com.example.scamdetect.ui.PasteTextScreen
import com.example.scamdetect.ui.Phase
import com.example.scamdetect.ui.ScriptPickerScreen
import com.example.scamdetect.ui.SettingsScreen
import com.example.scamdetect.ui.SummaryScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme

enum class Screen { HOME, PICK, CALL, PASTE, DASHBOARD, CALL_DETAIL, SETTINGS }

/** The three top-level screens, reached from the bottom tab bar. The app opens on HOME. */
private enum class Tab(val screen: Screen, val label: String, val icon: ImageVector) {
    HOME(Screen.HOME, "Home", Icons.Filled.Home),
    DASHBOARD(Screen.DASHBOARD, "Dashboard", Icons.AutoMirrored.Filled.List),
    SETTINGS(Screen.SETTINGS, "Settings", Icons.Filled.Settings),
}

/** Runtime permission from Android 17 (API 37) to reach private addresses such as the demo gateway. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class MainActivity : ComponentActivity() {
    // If refused, requests fail and the app shows "Could not check" (never safe).
    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            askLocalNetwork.launch(LOCAL_NETWORK_PERMISSION)
        }
        setContent { ScamDetectTheme { ScamGuardApp() } }
    }
}

@Composable
fun ScamGuardApp(vm: CallViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var openCallId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.CALL) vm.discard()   // B.5: during a call, tells the server; no summary, nothing saved
        screen = if (screen == Screen.CALL_DETAIL) Screen.DASHBOARD else Screen.HOME
    }

    val tab = Tab.entries.firstOrNull { it.screen == screen }
    if (tab == null) {
        // Calls, the script picker, paste text and call details take the whole screen, without the tab bar.
        ScreenContent(screen, vm, { screen = it }, openCallId, { openCallId = it })
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
            ScreenContent(screen, vm, { screen = it }, openCallId, { openCallId = it })
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
    val trainResult by vm.trainResult.collectAsState()

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
            saveSentence = saveSentence,
            onSaveSentence = vm::setSaveSentence,
            onDeleteHistory = vm::deleteHistory,
            onOpen = { setOpenCallId(it); go(Screen.CALL_DETAIL) },
            helpImprove = helpImprove,
            onHelpImprove = vm::setHelpImprove,
            labelCount = labelCount,
        )

        Screen.CALL_DETAIL -> {
            val id = openCallId.orEmpty()
            val call by remember(id) { vm.call(id) }.collectAsState(initial = null)
            val points by remember(id) { vm.points(id) }.collectAsState(initial = emptyList())
            CallDetailScreen(call, points, onBack = { go(Screen.DASHBOARD) })
        }

        Screen.SETTINGS -> SettingsScreen(
            currentUrl = gatewayUrl,
            onSave = vm::saveGatewayUrl,
            onTest = { vm.testConnection(it) },
            privacyLevel = privacyLevel,
            onPrivacyLevel = vm::setPrivacyLevel,
            mobileBertStatus = mobileBertStatus,
            modelStatus = modelStatus,
            modelCheck = modelCheck,
            onCheckModels = { vm.checkModelUpdates() },
            trainResult = trainResult,
            onTrain = vm::trainOnPhone,
        )
    }
}
