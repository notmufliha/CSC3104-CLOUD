package com.example.scamdetect.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.example.scamdetect.data.db.CallRecord
import com.example.scamdetect.data.db.ScorePoint
import com.example.scamdetect.data.ReportLine
import com.example.scamdetect.data.ReportRequest
import com.example.scamdetect.data.ScriptLine
import com.example.scamdetect.data.ScriptRepository
import com.example.scamdetect.data.UtteranceRequest
import com.example.scamdetect.logic.Dashboard
import com.example.scamdetect.logic.FinalReview
import com.example.scamdetect.logic.Mask
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.logic.Summary
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
) {
    /** The live level: the in-call bar. */
    val level get() = Risk.level(verdict, gatewayError)
    /** What the summary and the saved history row lead with: the final verdict, else the live one. */
    val summaryLevel get() = Summary.level(finalReview, verdict, gatewayError)
    val lit get() = Risk.litSignals(callScores)
    val flaggedTexts get() = lines.filter { it.flags.isNotEmpty() }.map { it.text }
    val banner get() = Risk.banner(level, lit, flaggedTexts)
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
            GatewayClientOrNull()?.let { api -> sendEnd(api, callId, "user_discarded", s) }
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
        callStartedAt = System.currentTimeMillis()
        _state.update { it.copy(phase = Phase.IN_CALL) }

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

    /** User pressed the red button (or "Hang up now" on the red alert). */
    fun hangUp() {
        playJob?.cancel()                                 // also cancels lines still waiting for an answer
        endCall(ENDED_HUNG_UP)
    }

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
        val api = GatewayClientOrNull()
        val answer = api?.let { sendEnd(it, id, endedBy, s) }
        viewModelScope.launch {
            val resp = answer?.let { withTimeoutOrNull(FINAL_REVIEW_WAIT_MS) { it.await() } }
            val review = resp?.let { FinalReview.from(it) } ?: FinalReview.UNAVAILABLE
            if (callId == id) _state.update { it.copy(reviewing = false, finalReview = review) }
            saveHistory(id, s.copy(reviewing = false, finalReview = review), pts, startedAt, endedAt)
        }
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
                callScore = s.combinedScore,
                signalsFired = Risk.SIGNALS.filter { it in fired },
                review = s.finalReview?.reviewType ?: "unavailable",
                riskiestSentence = sentence,
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
            _state.update { it.copy(scamAlert = true) }
            vibrateOnce()
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

    private fun vibrateOnce() {
        val ctx = getApplication<Application>()
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ---------------------------------------------------------------- H.3 report / "this was fine"

    /** Exactly what a report would send: the caller's lines, masked. Shown on the consent screen first. */
    fun reportPreview(): List<ReportLine> =
        _state.value.lines.filter { it.isCaller && it.text.isNotBlank() }.map { ReportLine(it.seq, Mask.mask(it.text)) }

    /** Sends the report after the user agreed. kind = "scam" or "fine". Returns a short message for the screen. */
    suspend fun sendReport(kind: String): String {
        val s = _state.value
        val lines = reportPreview()
        if (lines.isEmpty()) return "Nothing to report: the caller said nothing."
        val text = lines.joinToString(" ") { it.text }
        val chinese = text.count { it in '一'..'鿿' }
        val language = s.script?.lang ?: if (chinese * 10 > text.length * 3) "zh" else "en" // gateway's 30% rule
        return try {
            val resp = GatewayClient.get(prefs.gatewayUrl).report(
                ReportRequest(callId.ifEmpty { UUID.randomUUID().toString() }, kind, s.verdict ?: "", language, true, lines)
            )
            when {
                resp.isSuccessful -> "Thank you. Shared ${resp.body()?.linesStored ?: lines.size} lines; " +
                    "they are deleted after 30 days."
                resp.code() == 503 -> "Reporting is not set up on this server."
                else -> "Could not send the report (server answered ${resp.code()})."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Could not send the report: ${e.javaClass.simpleName}"
        }
    }

    // ---------------------------------------------------------------- settings + history

    fun saveGatewayUrl(url: String) {
        prefs.gatewayUrl = url
        _gatewayUrl.value = prefs.gatewayUrl
    }

    /** GET /healthz on the given URL; returns a short message for the Settings screen. */
    suspend fun testConnection(url: String): String = try {
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

    /** B.7 "Delete my history": every saved call, its chart points and any saved sentence. */
    fun deleteHistory() {
        viewModelScope.launch { callHistory.deleteAll() }
    }

    /** B.7 "Save the riskiest sentence". Turning it off also forgets the sentences already saved. */
    fun setSaveSentence(on: Boolean) {
        prefs.saveRiskiestSentence = on
        _saveSentence.value = on
        if (!on) viewModelScope.launch { callHistory.forgetSentences() }
    }

    companion object {
        private const val DEFAULT_DELAY_MS = 1_500L
        /** B.5: how long the summary waits for the end-of-call answer before using the live verdict. */
        private const val FINAL_REVIEW_WAIT_MS = 3_000L
        private const val ENDED_SCRIPT = "script_finished"
        private const val ENDED_HUNG_UP = "user_hung_up"
        const val NOT_CHECKED = "NOT CHECKED"
        private val KNOWN_VERDICTS = setOf("SAFE", "UNSURE", "SCAM")
        private val WARNING_VERDICTS = setOf("SCAM", "UNSURE")
    }
}
