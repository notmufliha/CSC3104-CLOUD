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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val state by vm.state.collectAsState()
    val calls by vm.calls.collectAsState()
    val saveSentence by vm.saveSentence.collectAsState()
    var openCallId by rememberSaveable { mutableStateOf<String?>(null) }
    val gatewayUrl by vm.gatewayUrl.collectAsState()
    val privacyLevel by vm.privacyLevel.collectAsState()
    val helpImprove by vm.helpImprove.collectAsState()
    val labelCount by vm.labelCount.collectAsState()
    val mobileBertStatus by vm.mobileBertStatus.collectAsState()
    val modelStatus by vm.modelStatus.collectAsState()
    val modelCheck by vm.modelCheck.collectAsState()
    val trainResult by vm.trainResult.collectAsState()

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.CALL) vm.discard()   // B.5: during a call, tells the server; no summary, nothing saved
        screen = if (screen == Screen.CALL_DETAIL) Screen.DASHBOARD else Screen.HOME
    }

    fun ringKind(kind: String) {
        vm.randomScript(kind)?.let { vm.ring(it); screen = Screen.CALL }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onScamCall = { ringKind("scam") },
            onNormalCall = { ringKind("normal") },
            onPickScript = { screen = Screen.PICK },
            onPasteText = { screen = Screen.PASTE },
            onDashboard = { screen = Screen.DASHBOARD },
            onSettings = { screen = Screen.SETTINGS },
        )

        Screen.PICK -> ScriptPickerScreen(
            scripts = vm.scripts,
            onPick = { vm.ring(it); screen = Screen.CALL },
            onBack = { screen = Screen.HOME },
        )

        Screen.PASTE -> PasteTextScreen(
            onCheck = { vm.startPasted(it); screen = Screen.CALL },
            onBack = { screen = Screen.HOME },
        )

        Screen.CALL -> when (state.phase) {
            Phase.RINGING -> IncomingCallScreen(
                callerName = "Unknown caller",
                callerNumber = "Private number",
                onAnswer = vm::answer,
                onDecline = { vm.decline(); screen = Screen.HOME },
            )
            Phase.IN_CALL -> InCallScreen(state, onHangUp = vm::hangUp)
            Phase.ENDED -> SummaryScreen(
                state,
                reportPreview = { vm.reportPreview() },
                onReport = { kind -> vm.sendReport(kind) },
                onDone = { vm.reset(); screen = Screen.HOME },
                onThisWasFine = vm::thisWasFine,
            )
            Phase.IDLE -> LaunchedEffect(Unit) { screen = Screen.HOME }
        }

        Screen.DASHBOARD -> DashboardScreen(
            calls = calls,
            saveSentence = saveSentence,
            onSaveSentence = vm::setSaveSentence,
            onDeleteHistory = vm::deleteHistory,
            onOpen = { openCallId = it; screen = Screen.CALL_DETAIL },
            onBack = { screen = Screen.HOME },
            helpImprove = helpImprove,
            onHelpImprove = vm::setHelpImprove,
            labelCount = labelCount,
        )

        Screen.CALL_DETAIL -> {
            val id = openCallId.orEmpty()
            val call by remember(id) { vm.call(id) }.collectAsState(initial = null)
            val points by remember(id) { vm.points(id) }.collectAsState(initial = emptyList())
            CallDetailScreen(call, points, onBack = { screen = Screen.DASHBOARD })
        }

        Screen.SETTINGS -> SettingsScreen(
            currentUrl = gatewayUrl,
            onSave = vm::saveGatewayUrl,
            onTest = { vm.testConnection(it) },
            onBack = { screen = Screen.HOME },
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
