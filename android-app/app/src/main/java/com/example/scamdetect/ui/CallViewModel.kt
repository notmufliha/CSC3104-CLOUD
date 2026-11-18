package com.example.scamdetect.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scamdetect.LOCAL_NETWORK_PERMISSION
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.data.CallHistory
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.EndCallRequest
import com.example.scamdetect.data.EndCallResponse
import com.example.scamdetect.data.EndLine
import com.example.scamdetect.data.GatewayApi
import com.example.scamdetect.data.GatewayClient
import com.example.scamdetect.data.ModelUpdater
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.data.ReportLine
import com.example.scamdetect.data.ReportRequest
import com.example.scamdetect.data.ScriptLine
import com.example.scamdetect.data.ScriptRepository
import com.example.scamdetect.data.UtteranceRequest
import com.example.scamdetect.data.UtteranceResponse
import com.example.scamdetect.data.VerifyRequest
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.FinalReview
import com.example.scamdetect.logic.ImplicitLabels
import com.example.scamdetect.logic.Mask
import com.example.scamdetect.logic.Onboarding
import com.example.scamdetect.logic.PrivacyLevel
import com.example.scamdetect.logic.Reasons
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Summary
import com.example.scamdetect.logic.Tour
import com.example.scamdetect.logic.Tr
import com.example.scamdetect.R
import com.example.scamdetect.logic.Warnings
import com.example.scamdetect.ondevice.MobileBert
import com.example.scamdetect.ondevice.OnDeviceCall
import com.example.scamdetect.ondevice.OnDeviceScorer
import com.example.scamdetect.ondevice.PhoneTrainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * One chat bubble. flags = signals this line scored high on; pending = waiting for the gateway;
 * failed = this caller line could not be checked.
 */
data class ChatLine(
    val seq: Long,
    val isCaller: Boolean,
    val text: String,
    val flags: List<String> = emptyList(),
    val pending: Boolean = false,
    val failed: Boolean = false,
    /** G.3 Hybrid: set when this line's masked window was sent to the cloud ("checked with cloud"). */
    val cloudNote: String? = null,
)

enum class Phase { IDLE, RINGING, IN_CALL, ENDED }

data class CallUiState(
    val script: CallScript? = null,
    val phase: Phase = Phase.IDLE,
    val lines: List<ChatLine> = emptyList(),
    val verdict: String? = null,
    val combinedScore: Float? = null,
    val callScores: Map<String, Float> = emptyMap(),
    val gatewayError: String? = null,
    val scamAlert: Boolean = false,
    /** Workstream B.5: waiting (up to 3 s) for the end-of-call answer; the summary shows "Final review…". */
    val reviewing: Boolean = false,
    /** Workstream B.5: the end-of-call answer, once it arrived (or UNAVAILABLE). */
    val finalReview: FinalReview? = null,
    /** Workstream G: the privacy level this call runs at, fixed when it is answered (in-call badge, dashboard). */
    val privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
    /** The full-screen red warning is showing. Only the user closes it (a button), even after the call ends. */
    val warningOpen: Boolean = false,
    /** The yellow "Be careful" buzz was given in this call (once per call). */
    val carefulAlerted: Boolean = false,
) {
    /** The live level: the in-call bar. */
    val level get() = Risk.level(verdict, gatewayError)
    /** What the summary and the saved history row lead with: the final verdict, else the live one. */
    val summaryLevel get() = Summary.level(finalReview, verdict, gatewayError)
    val lit get() = Risk.litSignals(callScores)
    val flaggedTexts get() = lines.filter { it.flags.isNotEmpty() }.map { it.text }
    val banner get() = Risk.banner(level, lit, flaggedTexts)
    /** What the red warning / yellow banner say (no scores): the strongest reason heard so far. */
    val warningKind get() = Warnings.kind(lit, flaggedTexts, Reasons.codeForText(finalReview?.reason))
}

class CallViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    val scripts: List<CallScript> = ScriptRepository(app).loadAll()

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    /** B.7: past calls, on the phone only (Room). Newest first. */
    private val callHistory = CallHistory(app, prefs)
    val calls: StateFlow<List<CallRecord>> =
        callHistory.calls.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveSentence = MutableStateFlow(prefs.saveRiskiestSentence)
    val saveSentence: StateFlow<Boolean> = _saveSentence.asStateFlow()

    init {
        viewModelScope.launch { callHistory.migrateLegacy() }   // the old SharedPreferences History, once
    }

    /** Red warning sound, vibration and speech; the yellow buzz. */
    private val alerts = Alerts(app).also { it.warmUp() }

    /** "Read warnings aloud" and "Show 'Be careful' warnings" (Settings). */
    private val _readAloud = MutableStateFlow(prefs.readAloud)
    val readAloud: StateFlow<Boolean> = _readAloud.asStateFlow()
    private val _showBeCareful = MutableStateFlow(prefs.showBeCareful)
    val showBeCareful: StateFlow<Boolean> = _showBeCareful.asStateFlow()

    /** "Text size": AppPrefs.TEXT_LARGE (default) or TEXT_XLARGE. */
    private val _textSize = MutableStateFlow(prefs.textSize)
    val textSize: StateFlow<String> = _textSize.asStateFlow()
    fun setTextSize(size: String) { prefs.textSize = size; _textSize.value = prefs.textSize }

    fun setReadAloud(on: Boolean) { prefs.readAloud = on; _readAloud.value = on }
    fun setShowBeCareful(on: Boolean) { prefs.showBeCareful = on; _showBeCareful.value = on }

    /** The trusted family contact (name, phone), null = none set. */
    data class Family(val name: String, val phone: String)
    private fun savedFamily() = prefs.familyPhone.takeIf { it.isNotBlank() }?.let { Family(prefs.familyName.ifBlank { it }, it) }
    private val _family = MutableStateFlow(savedFamily())
    val family: StateFlow<Family?> = _family.asStateFlow()

    fun setFamily(name: String, phone: String) {
        prefs.familyName = name
        prefs.familyPhone = phone
        _family.value = savedFamily()
    }

    /** Simple mode (Home, For family, simple Settings). An install from before it keeps the full app. */
    private val _simple = MutableStateFlow(Onboarding.simpleModeAtLaunch(prefs.simpleMode))
    val simpleMode: StateFlow<Boolean> = _simple.asStateFlow()

    /** Home's one-time card for an existing user: "Try Simple mode?". */
    private val _simpleOffer = MutableStateFlow(Onboarding.offerSimpleMode(prefs.simpleMode, prefs.simpleModeOffered))
    val simpleOffer: StateFlow<Boolean> = _simpleOffer.asStateFlow()

    fun setSimpleMode(on: Boolean) {
        prefs.simpleMode = on
        prefs.simpleModeOffered = true
        _simple.value = on
        _simpleOffer.value = false
    }

    /** "No, thanks" on the card: never asked again, the full app stays. */
    fun declineSimpleOffer() {
        prefs.simpleModeOffered = true
        _simpleOffer.value = false
    }

    /** The optional PIN (only its hash is stored, data/PinStore.kt). */
    private val pinStore = com.example.scamdetect.data.PinStore(app)
    val pinSet: StateFlow<Boolean> = pinStore.isSet.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    suspend fun verifyPin(pin: String): Boolean = pinStore.verify(pin)
    fun setPin(pin: String) { viewModelScope.launch { pinStore.set(pin) } }
    /** "Forgot PIN" / "Turn off the PIN": removes only the PIN; the call history is not touched. */
    fun clearPin() { viewModelScope.launch { pinStore.clear() } }

    override fun onCleared() {
        alerts.shutdown()
        super.onCleared()
    }

    /** G: Settings' privacy level. A change applies from the next call. */
    private val _privacyLevel = MutableStateFlow(prefs.privacyLevel)
    val privacyLevel: StateFlow<PrivacyLevel> = _privacyLevel.asStateFlow()

    /** G.13: "Help improve detection" on the dashboard, and how many labels the phone holds. */
    private val _helpImprove = MutableStateFlow(prefs.helpImproveDetection)
    val helpImprove: StateFlow<Boolean> = _helpImprove.asStateFlow()
    val labelCount: StateFlow<Int> =
        callHistory.labelCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Dashboard: every chart point (sparklines) and every label ("you marked N warnings as fine"). */
    val allPoints: StateFlow<List<ScorePoint>> =
        callHistory.allPoints.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val labels: StateFlow<List<com.example.scamdetect.data.db.ImplicitLabel>> =
        callHistory.labels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Debug builds only (Help & about): sample history for demos and screenshots. */
    val isDebugBuild: Boolean =
        (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Debug builds only (screenshots): a pretend call showing the red warning (red = true) or the yellow banner.
     * Nothing is sent and nothing is saved (no call id).
     */
    fun demoWarning(red: Boolean) {
        if (!isDebugBuild) return
        playJob?.cancel()
        callId = ""
        val base = tourDemoCall(redAlert = red)
        _state.value = if (red) base.copy(privacyLevel = prefs.privacyLevel) else base.copy(
            verdict = "UNSURE", combinedScore = 0.52f, privacyLevel = prefs.privacyLevel,
            callScores = mapOf("urgency" to 0.2f, "money" to 0.62f, "impersonation" to 0.1f),
            lines = listOf(
                ChatLine(1, false, Tr.s(R.string.demo_line_hello)),
                ChatLine(2, true, Tr.s(R.string.demo_line_parcel), listOf("money")),
            ),
        )
    }

    /** Debug builds only (screenshots): a screen to open (MainActivity's Screen name). */
    val demoScreen = MutableStateFlow<String?>(null)

    /** Debug builds only (screenshots): the server check's result without a server. */
    fun demoConnection(ok: Boolean?) { if (isDebugBuild) { _connection.value = ok; connectionFixed = true } }
    /** Debug builds only: a demo connection state stays (no real check replaces it). */
    private var connectionFixed = false

    fun loadSampleHistory() {
        if (!isDebugBuild) return
        viewModelScope.launch {
            val zone = java.time.ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val todayStart = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            callHistory.insertSample(com.example.scamdetect.logic.SampleHistory.generate(now, todayStart))
        }
    }

    /** Settings: the server's state from the last check ("Connected" / "Can't reach"), null = not checked yet. */
    private val _connection = MutableStateFlow<Boolean?>(null)
    val connection: StateFlow<Boolean?> = _connection.asStateFlow()

    /** Quiet GET /healthz on the saved address, for the Settings list. */
    fun refreshConnection() {
        if (connectionFixed) return
        viewModelScope.launch { _connection.value = testConnection(prefs.gatewayUrl).startsWith("Connected") }
    }

    init {
        if (prefs.privacyLevel.scoresOnPhone) preloadModels()
    }

    /** G.4: "MobileBERT installed (version)" or why not, for the Settings screen and the benchmark. */
    private val _mobileBert = MutableStateFlow("Checking MobileBERT…")
    val mobileBertStatus: StateFlow<String> = _mobileBert.asStateFlow()

    init {
        viewModelScope.launch {
            _mobileBert.value = when (val s = MobileBert.get(getApplication())) {
                is MobileBert.Status.Installed -> "MobileBERT installed (${s.model.config.version}): second opinion on this phone"
                is MobileBert.Status.NotInstalled -> s.reason + ": level 3 uses the small models only"
            }
        }
    }

    /** G.10: which on-device model versions this phone runs, and the result of the last update check. */
    private val _modelStatus = MutableStateFlow(ModelUpdater.describe(getApplication()))
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()
    private val _modelCheck = MutableStateFlow("")
    val modelCheck: StateFlow<String> = _modelCheck.asStateFlow()

    /** G.10: ask the model-download service for newer versions; also the Settings button. */
    suspend fun checkModelUpdates(): String {
        _modelCheck.value = "Checking for model updates…"
        val msg = ModelUpdater.update(getApplication(), prefs.gatewayUrl)
        _modelCheck.value = msg
        _modelStatus.value = ModelUpdater.describe(getApplication())
        if (prefs.privacyLevel.scoresOnPhone) preloadModels()
        return msg
    }

    /** Real-phone training: what the last "Train on my calls" did. */
    private val _trainResult = MutableStateFlow("")
    val trainResult: StateFlow<String> = _trainResult.asStateFlow()

    /**
     * Real-phone training (federated learning on this phone): train the downloaded models on this phone's labelled
     * calls and upload only the change to the models' numbers (PhoneTrainer). A real system would do this by itself
     * when the phone is idle, charging and on Wi-Fi; the demo has a button.
     */
    fun trainOnPhone() {
        viewModelScope.launch {
            _trainResult.value = "Training on this phone…"
            _trainResult.value = try {
                val calls = withContext(Dispatchers.IO) { callHistory.labelledCalls() }
                val versions = ModelUpdater.baseVersions(getApplication())
                when {
                    !prefs.helpImproveDetection -> "Turn on \"Help improve detection\" on the dashboard first: without it the phone keeps no lines to learn from."
                    calls.isEmpty() -> "No labelled calls with saved lines yet. With \"Help improve detection\" on, play a scam call and hang up within 10 s of the red alert, or tap \"This was fine\" after a warning."
                    versions.isEmpty() -> "Download the federated models first (Check for model updates): the built-in models can't be averaged."
                    else -> withContext(Dispatchers.Default) {
                        val scorer = OnDeviceScorer.get(getApplication())
                        val trainer = PhoneTrainer(scorer)
                        val examples = trainer.examples(calls)
                        val updates = trainer.train(examples.filterKeys { it in versions })
                        if (updates.isEmpty()) "Nothing to learn yet: none of ${calls.size} labelled calls had lines the models flagged."
                        else {
                            val sent = ModelUpdater.upload(prefs.gatewayUrl, trainer.uploadJson(updates, prefs.flPhoneId, versions))
                            "Trained on ${calls.size} labelled calls: " +
                                updates.joinToString(", ") { "${it.signal} ${it.lines} lines (on ${versions[it.signal]})" } + ". " + sent
                        }
                    }
                }
            } catch (e: Exception) {
                "Training failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}"
            }
        }
    }

    /** G.2: load the on-device models in the background, so the first call doesn't wait for them. */
    private fun preloadModels() {
        viewModelScope.launch { runCatching { OnDeviceScorer.get(getApplication()) } }
    }

    private val _gatewayUrl = MutableStateFlow(prefs.gatewayUrl)
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()

    private var playJob: Job? = null
    /**
     * Workstream B: end messages are sent here, not in viewModelScope, so leaving the call screen (Back, Done)
     * never cancels them: the server must hear about every end to clear the call's memory.
     */
    private val endScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callId = ""
    /** Highest seq whose answer (or error) has been applied. Older outcomes only update their own bubble. */
    private var lastOutcomeSeq = 0L
    /** B.7: this call's chart points (one per answered caller line) and when it started. Main thread only. */
    private val points = mutableListOf<ScorePoint>()
    private var callStartedAt = 0L
    /** G.12: when the red alert first showed in this call (ms), null if it hasn't. Main thread only. */
    private var alertAt: Long? = null

    private var lastRandom: String? = null

    /** A random script of this kind, avoiding the one picked last time when there is another to choose. */
    fun randomScript(kind: String): CallScript? {
        val pool = scripts.filter { it.kind == kind }
        val pick = pool.filter { it.fileName != lastRandom }.randomOrNull() ?: pool.randomOrNull()
        lastRandom = pick?.fileName
        return pick
    }

    // ---------------------------------------------------------------- call lifecycle

    /** 4.3: show the incoming-call screen for this script. */
    fun ring(script: CallScript) {
        playJob?.cancel()
        _state.value = CallUiState(script = script, phase = Phase.RINGING)
    }

    fun decline() = reset()

    fun reset() {
        alerts.stop()
        playJob?.cancel()
        callId = ""               // a late end answer for the old call must not touch the next one
        _state.value = CallUiState()
    }

    /**
     * Workstream B.5: Back pressed on the call screen. During a call: tell the server (ended_by
     * "user_discarded", so it clears the call's memory), show no summary, save nothing. Otherwise just leave.
     */
    fun discard() {
        val s = _state.value
        if (s.phase == Phase.IN_CALL) {
            playJob?.cancel()
            // G: at levels 2 and 3 the server keeps no memory of the call, so there is nothing to clear and nothing is sent
            if (s.privacyLevel.cloudCall) GatewayClientOrNull()?.let { api -> sendEnd(api, callId, "user_discarded", s) }
        }
        reset()
    }

    /** Paste-text mode: a one-line "call" that skips ringing. */
    fun startPasted(text: String) {
        val script = CallScript("pasted", "pasted", null, "Pasted text", listOf(ScriptLine("caller", text, 0)))
        ring(script)
        answer()
    }

    /** 4.3: play the script line by line and send caller lines to the gateway. */
    fun answer() {
        val script = _state.value.script ?: return
        playJob?.cancel()
        callId = UUID.randomUUID().toString()
        lastOutcomeSeq = 0L
        points.clear()
        alertAt = null
        callStartedAt = System.currentTimeMillis()
        val level = prefs.privacyLevel
        _state.update { it.copy(phase = Phase.IN_CALL, privacyLevel = level) }
        if (level.scoresOnPhone) {
            playOnDevice(script, level)
            return
        }

        val api: GatewayApi? = try {
            GatewayClient.get(prefs.gatewayUrl)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(gatewayError = "Bad server address. Check Settings.") }
            null
        }

        playJob = viewModelScope.launch {
            val sends = mutableListOf<Job>()
            script.lines.forEachIndexed { i, line ->
                delay(line.delayMs ?: DEFAULT_DELAY_MS)
                val seq = (i + 1).toLong()           // caller and receiver share one count, as the gateway expects
                val text = line.text.orEmpty()
                val waiting = line.isCaller && api != null
                _state.update {
                    it.copy(lines = it.lines + ChatLine(seq, line.isCaller, text, pending = waiting, failed = line.isCaller && api == null))
                }
                if (waiting) sends += launch { send(api!!, seq, text, script.lang) }
            }
            withTimeoutOrNull(5_000) { sends.joinAll() } // let the last answers arrive
            sends.forEach { it.cancel() }                 // nothing may change the call after this point
            delay(1_500)                                  // a moment to read the final state
            endCall(ENDED_SCRIPT)
        }
    }

    /**
     * G.2 / G.3, privacy levels 2 and 3: the same playback, but every caller line is scored on the phone
     * (OnDeviceCall). Level 3 makes no request at all. Level 2 (Hybrid) asks the cloud's BERT only at unsure moments
     * or on the spot check, with the caller's last 5 lines masked, and keeps its own answer if the cloud is slow or
     * down. Answers go through applyAnswer, like the gateway's, so the screen behaves the same. Lines are handled one
     * at a time, in order (the rules need that), without holding up the script. English only; other lines show
     * "could not check".
     */
    private fun playOnDevice(script: CallScript, level: PrivacyLevel) {
        val id = callId
        val hybrid = level == PrivacyLevel.HYBRID
        val api = if (hybrid) GatewayClientOrNull() else null       // level 3: never even built
        playJob = viewModelScope.launch {
            val loading = async { runCatching { OnDeviceCall(OnDeviceScorer.get(getApplication())) }.getOrNull() }
            // G.4: level 3's second opinion, only if a MobileBERT model is bundled
            val mobileBert = if (hybrid) null else (MobileBert.get(getApplication()) as? MobileBert.Status.Installed)?.model
            val inOrder = Mutex()
            val jobs = mutableListOf<Job>()
            script.lines.forEachIndexed { i, line ->
                delay(line.delayMs ?: DEFAULT_DELAY_MS)
                val seq = (i + 1).toLong()
                val text = line.text.orEmpty()
                _state.update { it.copy(lines = it.lines + ChatLine(seq, line.isCaller, text, pending = line.isCaller)) }
                if (!line.isCaller) return@forEachIndexed
                jobs += launch {
                    inOrder.withLock {
                        val local = loading.await()
                        when {
                            local == null -> markError(seq, "On-device models could not be loaded")
                            !OnDeviceCall.isEnglish(script.lang, text) -> markError(seq, "On-device checks are English only")
                            hybrid -> {
                                val a = withContext(Dispatchers.Default) {
                                    local.answerHybrid(id, seq, text) { lines -> verifyInCloud(api, lines) }
                                }
                                applyAnswer(seq, a.response)
                                a.second?.let { c -> markCloud(seq, if (c.bertScore != null) CLOUD_CHECKED else CLOUD_UNAVAILABLE) }
                            }
                            mobileBert != null -> {
                                // G.4: same rule as Hybrid, but MobileBERT reads the lines on the phone (not masked:
                                // they never leave it)
                                val a = withContext(Dispatchers.Default) {
                                    local.answerWithSecondOpinion(id, seq, text, { it }) { lines -> runCatching { mobileBert.score(lines) }.getOrNull() }
                                }
                                applyAnswer(seq, a.response)
                                a.second?.let { c -> markCloud(seq, if (c.bertScore != null) MOBILEBERT_CHECKED else MOBILEBERT_FAILED) }
                            }
                            else -> applyAnswer(seq, withContext(Dispatchers.Default) { local.answer(id, seq, text) })
                        }
                    }
                }
            }
            withTimeoutOrNull(5_000) { jobs.joinAll() }  // let the last answers arrive
            jobs.forEach { it.cancel() }
            delay(1_500)                                  // a moment to read the final state
            endCall(ENDED_SCRIPT)
        }
    }

    /**
     * G.3: POST /v1/verify with the masked lines; BERT's score, or null if the cloud didn't answer within 1.5 s
     * (or isn't reachable / set up), in which case the phone keeps its own answer.
     */
    private suspend fun verifyInCloud(api: GatewayApi?, maskedLines: List<String>): Float? {
        if (api == null || maskedLines.isEmpty()) return null
        return withTimeoutOrNull(OnDeviceCall.VERIFY_TIMEOUT_MS) {
            try {
                val resp = api.verify(VerifyRequest(maskedLines))
                if (resp.isSuccessful) resp.body()?.score?.takeIf { it in 0f..1f } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    /** G.3: the small "checked with cloud" note under a bubble. */
    private fun markCloud(seq: Long, note: String) =
        _state.update { st -> st.copy(lines = st.lines.map { if (it.seq == seq) it.copy(cloudNote = note) else it }) }

    /** User pressed the red button (or "Hang up now" on the red alert). */
    fun hangUp() {
        alerts.stop()
        _state.update { it.copy(warningOpen = false) }
        playJob?.cancel()                                 // also cancels lines still waiting for an answer
        endCall(ENDED_HUNG_UP)
    }

    /** "Close this warning": back to the call (or its summary, if it has ended). The call goes on. */
    fun closeWarning() {
        alerts.stop()
        _state.update { it.copy(warningOpen = false) }
    }

    /**
     * "Call my family" / "Call 1799" on the red warning: hang up the scam call first (if it is still on), then the
     * screen opens the phone app with the number filled in. The user taps Call.
     */
    fun leaveCallForHelp() {
        if (_state.value.phase == Phase.IN_CALL) hangUp() else closeWarning()
    }

    /** The message for "Tell [name]" (the SMS app opens with it; the user taps Send). */
    fun familyMessage(): String = Warnings.familyMessage()

    /**
     * B.5: the call is over. Shows "Final review…", sends POST /v1/call/end with the caller lines and waits up to
     * 3 s for the final verdict, then shows the summary and saves the history row. If the server can't be reached
     * the summary uses the live verdict ("Final review unavailable").
     */
    private fun endCall(endedBy: String) {
        if (_state.value.phase != Phase.IN_CALL) return
        _state.update { st ->
            st.copy(
                phase = Phase.ENDED,
                reviewing = true,
                lines = st.lines.map { if (it.pending) it.copy(pending = false, failed = true) else it },
            )
        }
        val id = callId
        val s = _state.value
        val pts = points.toList()
        val startedAt = callStartedAt
        val endedAt = System.currentTimeMillis()
        val onPhone = !s.privacyLevel.cloudCall
        // G: levels 2 and 3 send no end message (it would carry every caller line, unmasked)
        val api = if (onPhone) null else GatewayClientOrNull()
        val answer = api?.let { sendEnd(it, id, endedBy, s) }
        val firstAlert = alertAt
        viewModelScope.launch {
            val resp = answer?.let { withTimeoutOrNull(FINAL_REVIEW_WAIT_MS) { it.await() } }
            val review = resp?.let { FinalReview.from(it) } ?: if (onPhone) FinalReview.ON_DEVICE else FinalReview.UNAVAILABLE
            if (callId == id) _state.update { it.copy(reviewing = false, finalReview = review) }
            saveHistory(id, s.copy(reviewing = false, finalReview = review), pts, startedAt, endedAt)
            // G.12: hung up soon after the red alert, or a very sure end-of-call review
            recordLabels(id, s, ImplicitLabels.atEnd(endedBy == ENDED_HUNG_UP, firstAlert, endedAt, review))
        }
    }

    /** G.12: "This was fine" tapped on the summary of a call that ended with a warning -> a false-alarm label. */
    fun thisWasFine() {
        val s = _state.value
        val label = ImplicitLabels.thisWasFine(s.summaryLevel) ?: return
        val id = callId
        viewModelScope.launch { recordLabels(id, s, listOf(label)) }
    }

    /** G.12: labels go only to the phone's database, like the history row (never for pasted text). */
    private suspend fun recordLabels(id: String, s: CallUiState, labels: List<ImplicitLabels.Label>) {
        if (s.script?.kind == "pasted") return
        callHistory.recordLabels(id, labels, s.lines.filter { it.isCaller }.map { it.seq to it.text })
    }

    /** Sends the end message on endScope (survives the screen); the answer is null if it failed. */
    private fun sendEnd(api: GatewayApi, id: String, endedBy: String, s: CallUiState) = endScope.async {
        if (id.isEmpty()) return@async null
        val callerLines = s.lines.filter { it.isCaller && it.text.isNotBlank() }.map { EndLine(it.seq, it.text) }
        val request = EndCallRequest(
            callId = id,
            endedBy = endedBy,
            lastSeq = s.lines.maxOfOrNull { it.seq } ?: 0L,
            callerLines = callerLines,
            lang = s.script?.lang,
        )
        try {
            val resp = api.endCall(request)
            if (resp.isSuccessful) resp.body() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null                                          // the summary falls back to the live verdict
        }
    }

    /** The gateway client, or null when the address in Settings is not valid. */
    @Suppress("FunctionName")
    private fun GatewayClientOrNull(): GatewayApi? = try {
        GatewayClient.get(prefs.gatewayUrl)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** B.7: one dashboard row + its chart points, in Room. endCall runs once per call, so this saves once. */
    private suspend fun saveHistory(id: String, s: CallUiState, pts: List<ScorePoint>, startedAt: Long, endedAt: Long) {
        if (s.script?.kind == "pasted" || id.isEmpty()) return
        val verdict = historyVerdict(s.summaryLevel)
        // Text is kept only if the user allowed it, and only for a call that ended with a warning.
        val sentence = if (prefs.saveRiskiestSentence && verdict in WARNING_VERDICTS) {
            Dashboard.riskiestSeq(pts)?.let { seq -> s.lines.firstOrNull { it.seq == seq }?.text }
        } else null
        val fired = (s.lit + s.lines.flatMap { it.flags }).toSet()
        callHistory.save(
            CallRecord(
                id = id,
                startedAt = startedAt,
                endedAt = endedAt,
                finalVerdict = verdict,
                liveVerdict = historyVerdict(s.level),
                topReason = historyReason(s),
                reasonCode = Reasons.codeFor(s.summaryLevel, s.callScores, s.finalReview?.reason),
                callScore = s.combinedScore,
                signalsFired = Risk.SIGNALS.filter { it in fired },
                review = s.finalReview?.reviewType ?: "unavailable",
                riskiestSentence = sentence,
                privacyLevel = s.privacyLevel.id,
            ),
            pts,
        )
    }

    fun points(callId: String): Flow<List<ScorePoint>> = callHistory.points(callId)
    fun call(callId: String): Flow<CallRecord?> = callHistory.call(callId)

    /** Same rule as the bar: SCAM / UNSURE stick, SAFE only if the call ended without an error. */
    private fun historyVerdict(level: Risk.Level) = when (level) {
        Risk.Level.RED -> "SCAM"
        Risk.Level.YELLOW -> "UNSURE"
        Risk.Level.GREEN -> "SAFE"
        Risk.Level.ERROR, Risk.Level.LISTENING -> NOT_CHECKED
    }

    /** The reason text saved with the call (shown only if its code is unknown); the code is what the dashboard uses. */
    private fun historyReason(s: CallUiState) = when (s.summaryLevel) {
        Risk.Level.RED, Risk.Level.YELLOW ->
            s.finalReview?.reason?.takeIf { it.isNotBlank() } ?: Risk.reason(s.lit, s.flaggedTexts)
        Risk.Level.GREEN -> "no warning signs"
        Risk.Level.ERROR -> "server not reached"
        Risk.Level.LISTENING -> "ended before any line was checked"
    }

    // ---------------------------------------------------------------- networking

    private suspend fun send(api: GatewayApi, seq: Long, text: String, lang: String?) {
        val resp = try {
            api.utterance(UtteranceRequest(callId, seq, "caller", text, lang))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markError(seq, "Could not reach the server")
            return
        }
        if (!resp.isSuccessful) {
            markError(seq, "Server error ${resp.code()}")   // 503 = no worker answered, 502 = aggregator failed
            return
        }
        val body = resp.body()
        if (body == null) {
            markError(seq, "Empty answer from server")
            return
        }
        applyAnswer(seq, body)
    }

    /**
     * One caller line's answer -> bubble flags, chart point, bar, red alert. Used for the gateway's answer (cloud)
     * and for the phone's own (on-device), so both drive the screen the same way.
     */
    private fun applyAnswer(seq: Long, body: UtteranceResponse) {
        if (body.ignored == true) {
            // B.6: the call had already ended on the server. No change: just stop the bubble's "checking…".
            _state.update { st -> st.copy(lines = st.lines.map { if (it.seq == seq) it.copy(pending = false) else it }) }
            return
        }
        if (body.verdict !in KNOWN_VERDICTS) {
            markError(seq, "Server gave no verdict")       // e.g. VERDICT_UNSPECIFIED: not checked, never safe
            return
        }

        val flags = Risk.SIGNALS.filter { (body.scores?.get(it) ?: 0f) >= Risk.SIGNAL_ON }
        // B.7: one chart point per answered caller line (numbers only)
        points += ScorePoint(
            callId = callId,
            seq = seq,
            secondsIntoCall = (System.currentTimeMillis() - callStartedAt) / 1000f,
            combined = body.combinedScore,
            urgency = body.scores?.get("urgency"),
            money = body.scores?.get("money"),
            impersonation = body.scores?.get("impersonation"),
        )
        val isNewest = seq >= lastOutcomeSeq       // answers can arrive out of order; newest wins
        if (isNewest) lastOutcomeSeq = seq

        _state.update { st ->
            val lines = st.lines.map { if (it.seq == seq) it.copy(pending = false, flags = flags) else it }
            if (!isNewest) st.copy(lines = lines)
            else st.copy(
                lines = lines,
                verdict = body.verdict,
                combinedScore = body.combinedScore ?: st.combinedScore,
                callScores = body.callScores ?: st.callScores,
                gatewayError = null,                   // a newer line got through: an earlier blip no longer counts
            )
        }

        // 4.5: first time the call turns SCAM -> vibrate once + red pop-up. scamAlert is never cleared during a
        // call and this block has no suspension point, so two answers cannot both get here.
        if (_state.value.verdict == "SCAM" && !_state.value.scamAlert) {
            alertAt = System.currentTimeMillis()          // G.12: for "hung up within 10 s of a red alert"
            _state.update { it.copy(scamAlert = true, warningOpen = true) }
            alerts.red(if (prefs.readAloud) speech(_state.value.warningKind) else null)
        } else if (_state.value.verdict == "UNSURE" && !_state.value.scamAlert && !_state.value.carefulAlerted) {
            // Yellow: one short buzz, no sound, no pop-up; nothing at all when "Be careful" warnings are off (the
            // call is still saved as "Be careful").
            _state.update { it.copy(carefulAlerted = true) }
            if (prefs.showBeCareful) alerts.yellow()
        }
    }

    private fun markError(seq: Long, msg: String) {
        val isNewest = seq >= lastOutcomeSeq       // an old line failing late does not override a newer answer
        if (isNewest) lastOutcomeSeq = seq
        _state.update { st ->
            val lines = st.lines.map { if (it.seq == seq) it.copy(pending = false, failed = true) else it }
            if (isNewest) st.copy(gatewayError = msg, lines = lines) else st.copy(lines = lines)
        }
    }

    /** The spoken warning (English). */
    private fun speech(kind: Warnings.Kind) = Alerts.Speech(Warnings.spoken(kind, Tr.txt))

    // ---------------------------------------------------------------- H.3 report / "this was fine"

    /** Exactly what a report would send: the caller's lines, masked. Shown on the consent screen first. */
    fun reportPreview(): List<ReportLine> =
        _state.value.lines.filter { it.isCaller && it.text.isNotBlank() }.map { ReportLine(it.seq, Mask.mask(it.text)) }

    /** Sends the report after the user agreed. kind = "scam" or "fine". Returns a short message for the screen. */
    suspend fun sendReport(kind: String): Pair<String, Boolean> {
        val s = _state.value
        if (!s.privacyLevel.cloudCall) return nothingShared() to false
        val lines = reportPreview()
        if (lines.isEmpty()) return Tr.s(R.string.report_nothing) to false
        val text = lines.joinToString(" ") { it.text }
        val chinese = text.count { it in '一'..'鿿' }
        val language = s.script?.lang ?: if (chinese * 10 > text.length * 3) "zh" else "en" // gateway's 30% rule
        return try {
            val resp = GatewayClient.get(prefs.gatewayUrl).report(
                ReportRequest(callId.ifEmpty { UUID.randomUUID().toString() }, kind, s.verdict ?: "", language, true, lines)
            )
            when {
                resp.isSuccessful -> Tr.s(R.string.report_thanks, (resp.body()?.linesStored ?: lines.size).toString()) to true
                resp.code() == 503 -> Tr.s(R.string.report_not_set_up) to false
                else -> Tr.s(R.string.report_failed) to false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Tr.s(R.string.report_failed) to false
        }
    }

    // ---------------------------------------------------------------- settings + history

    fun saveGatewayUrl(url: String) {
        if (GatewayClient.normalize(url) != prefs.gatewayUrl) _connection.value = null
        prefs.gatewayUrl = url
        _gatewayUrl.value = prefs.gatewayUrl
    }

    /** GET /healthz on the given URL; returns a short message for the Settings screen. */
    suspend fun testConnection(url: String): String = checkConnection(url).also {
        if (GatewayClient.normalize(url) == prefs.gatewayUrl) _connection.value = it.startsWith("Connected")
    }

    private suspend fun checkConnection(url: String): String = try {
        val resp = GatewayClient.get(url).health()
        val status = resp.body()?.get("status")
        when {
            !resp.isSuccessful -> "Server answered ${resp.code()}" +
                if (resp.code() == 404) ": check the address (Kubernetes HTTPS addresses end in /api/)" else ""
            // Only the gateway's own JSON counts. A proxy in front of it (e.g. nginx's built-in /healthz)
            // can answer 200 with an empty body even when the gateway is down.
            status == "ok" -> "Connected: ok"
            else -> "Something answered, but not the ScamGuard gateway: check the address"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        "Not a valid address"
    } catch (e: SSLPeerUnverifiedException) {
        // Connected, but the certificate doesn't list this address (e.g. the laptop moved to a new Wi-Fi).
        "Certificate doesn't cover this address: re-run demo/expose script on the laptop"
    } catch (e: SSLHandshakeException) {
        // Usually: the demo CA isn't installed on this device, so the certificate isn't trusted.
        "Certificate not trusted: install demo/tls/ca.crt on this device (Settings > Security > Encryption & credentials)"
    } catch (e: Exception) {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(getApplication(), LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            "Could not connect: allow Local network access for this app in Android Settings"
        } else {
            "Could not connect: ${e.javaClass.simpleName}"
        }
    }

    /** B.7 "Delete my history": every saved call, its chart points and any saved sentence; G.12's labels too. */
    fun deleteHistory() {
        viewModelScope.launch { callHistory.deleteAll() }
    }

    /** B.7 "Save the riskiest sentence". Turning it off also forgets the sentences already saved. */
    fun setSaveSentence(on: Boolean) {
        prefs.saveRiskiestSentence = on
        _saveSentence.value = on
        if (!on) viewModelScope.launch { callHistory.forgetSentences() }
    }

    /** G: Settings' privacy level. Levels that aren't built yet are refused (AppPrefs reads them back as Cloud). */
    fun setPrivacyLevel(level: PrivacyLevel) {
        if (!level.available) return
        prefs.privacyLevel = level
        _privacyLevel.value = prefs.privacyLevel
        if (level.scoresOnPhone) preloadModels()
    }

    /** G.13 "Help improve detection". Turning it off deletes the sentences already stored with labels. */
    fun setHelpImprove(on: Boolean) {
        prefs.helpImproveDetection = on
        _helpImprove.value = on
        if (!on) viewModelScope.launch { callHistory.forgetLabelSentences() }
    }

    // ---------------------------------------------------------------- first-launch setup + guided tour
    // Last in the class: the gate is decided in an init block, after every other property is set up.


    /** What the app shows: nothing yet (checking for old history), the setup, or the app itself. */
    enum class Gate { LOADING, SETUP, APP }

    private val _gate = MutableStateFlow(Gate.LOADING)
    val gate: StateFlow<Gate> = _gate.asStateFlow()

    /** The setup screen showing, and the choices made so far. Kept here so a rotation keeps them. */
    private val _setupStep = MutableStateFlow(Onboarding.Step.WELCOME)
    val setupStep: StateFlow<Onboarding.Step> = _setupStep.asStateFlow()
    private val _setupChoices = MutableStateFlow(Onboarding.defaults(prefs.gatewayUrl))
    val setupChoices: StateFlow<Onboarding.Choices> = _setupChoices.asStateFlow()
    /** true = opened from Settings ("Run setup again"): leaving it goes back to the app without saving. */
    var setupIsReplay = false
        private set

    /** The tour step showing (0-6), null = no tour. */
    private val _tourStep = MutableStateFlow<Int?>(null)
    val tourStep: StateFlow<Int?> = _tourStep.asStateFlow()

    init {
        val completed = prefs.onboardingCompleted
        if (completed != null || prefs.hasAnySetting()) {
            decideGate(Onboarding.atLaunch(completed, prefs.hasAnySetting(), hasHistory = false))
        } else {
            // No flag and no settings: a new install, unless an older version left call history.
            viewModelScope.launch {
                val hasHistory = callHistory.calls.first().isNotEmpty()
                decideGate(Onboarding.atLaunch(null, false, hasHistory))
            }
        }
    }

    private fun decideGate(launch: Onboarding.Launch) {
        when (launch) {
            Onboarding.Launch.SHOW_SETUP -> _gate.value = Gate.SETUP
            Onboarding.Launch.EXISTING_USER -> { Onboarding.markCompleted(prefs); openApp() }
            Onboarding.Launch.DONE -> openApp()
        }
    }

    /** The app opens: now (not during the setup) it may use the network. */
    private fun openApp() {
        _gate.value = Gate.APP
        // G.10: on every app start, quietly fetch newer federated models (only model files come down). Not at
        // level 3, which makes no network traffic at all.
        if (prefs.privacyLevel != PrivacyLevel.ON_DEVICE) viewModelScope.launch { checkModelUpdates() }
    }

    fun setSetupStep(step: Onboarding.Step) { _setupStep.value = step }

    fun updateSetup(change: (Onboarding.Choices) -> Onboarding.Choices) { _setupChoices.value = change(_setupChoices.value) }

    /** Setup's Test connection: tries the typed address without saving it. */
    suspend fun testSetupServer(url: String): Boolean = checkConnection(url).startsWith("Connected")

    /** Skip, "Show me around" or "Skip tour": saves the choices (Skip = what is picked so far, defaults for the rest). */
    fun finishSetup(startTour: Boolean) {
        val c = _setupChoices.value
        val urlBefore = prefs.gatewayUrl
        Onboarding.apply(c, prefs)
        if (com.example.scamdetect.logic.Pin.isValid(c.pin)) setPin(c.pin)
        _privacyLevel.value = prefs.privacyLevel
        _gatewayUrl.value = prefs.gatewayUrl
        if (prefs.gatewayUrl != urlBefore) _connection.value = null
        _saveSentence.value = c.saveSentence
        _helpImprove.value = c.helpImprove
        _textSize.value = prefs.textSize
        _readAloud.value = prefs.readAloud
        _simple.value = c.simple
        _simpleOffer.value = false
        _family.value = savedFamily()
        if (!c.saveSentence) viewModelScope.launch { callHistory.forgetSentences() }
        if (!c.helpImprove) viewModelScope.launch { callHistory.forgetLabelSentences() }
        if (c.privacy.scoresOnPhone) preloadModels()
        val wasReplay = setupIsReplay
        setupIsReplay = false
        if (wasReplay) _gate.value = Gate.APP else openApp()
        if (startTour) startTour() else _tourStep.value = null
    }

    /** Back on the first screen of "Run setup again", confirmed: nothing is saved. */
    fun leaveSetup() {
        setupIsReplay = false
        _gate.value = Gate.APP
    }

    /** Help & about: "Run setup again", starting from the current settings. */
    fun runSetupAgain() {
        _setupChoices.value = Onboarding.fromSaved(prefs)
        _setupStep.value = Onboarding.Step.WELCOME
        setupIsReplay = true
        _tourStep.value = null
        _gate.value = Gate.SETUP
    }

    /** Debug builds only: forget that the setup was done and show it as on a first launch. */
    fun resetOnboarding() {
        if (!isDebugBuild) return
        Onboarding.reset(prefs)
        _setupChoices.value = Onboarding.defaults(prefs.gatewayUrl)
        _setupStep.value = Onboarding.Step.WELCOME
        setupIsReplay = false
        _tourStep.value = null
        _gate.value = Gate.SETUP
    }

    /** The tour shown: Simple mode's 3 steps or the full app's 7, fixed when it starts. */
    var tourSimple = false
        private set

    fun startTour(at: Int = 0) {
        tourSimple = _simple.value
        _tourStep.value = at.coerceIn(0, Tour.steps(tourSimple).size - 1)
    }

    fun tourNext() {
        val i = _tourStep.value ?: return
        _tourStep.value = if (Tour.isLast(i, tourSimple)) null else i + 1
    }

    fun tourBack() {
        val i = _tourStep.value ?: return
        _tourStep.value = if (i == 0) null else i - 1
    }

    fun endTour() { _tourStep.value = null }

    companion object {
        private const val DEFAULT_DELAY_MS = 1_500L
        /** B.5: how long the summary waits for the end-of-call answer before using the live verdict. */
        private const val FINAL_REVIEW_WAIT_MS = 3_000L
        private const val ENDED_SCRIPT = "script_finished"
        private const val ENDED_HUNG_UP = "user_hung_up"
        const val NOT_CHECKED = "NOT CHECKED"
        /** G: what the summary says instead of sharing a report at levels 2 and 3. */
        fun nothingShared() = Tr.s(R.string.nothing_shared)
        /** G.3: notes under a Hybrid caller line whose last 5 lines were sent (masked) to the cloud. */
        const val CLOUD_CHECKED = "checked with cloud"
        const val CLOUD_UNAVAILABLE = "cloud check unavailable, checked on this phone"
        /** G.4: the same note for level 3's MobileBERT second opinion. */
        const val MOBILEBERT_CHECKED = "checked with MobileBERT on this phone"
        const val MOBILEBERT_FAILED = "MobileBERT failed, small models only"
        private val KNOWN_VERDICTS = setOf("SAFE", "UNSURE", "SCAM")
        private val WARNING_VERDICTS = setOf("SCAM", "UNSURE")
    }
}
