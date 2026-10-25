package com.example.scamdetect.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scamdetect.data.AppPrefs
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.GatewayApi
import com.example.scamdetect.data.GatewayClient
import com.example.scamdetect.data.HistoryEntry
import com.example.scamdetect.data.ScriptLine
import com.example.scamdetect.data.ScriptRepository
import com.example.scamdetect.data.UtteranceRequest
import com.example.scamdetect.logic.Risk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
) {
    /** The one level used by the in-call bar, the summary and the saved history row. */
    val level get() = Risk.level(verdict, gatewayError)
    val lit get() = Risk.litSignals(callScores)
    val flaggedTexts get() = lines.filter { it.flags.isNotEmpty() }.map { it.text }
    val banner get() = Risk.banner(level, lit, flaggedTexts)
}

class CallViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = AppPrefs(app)
    val scripts: List<CallScript> = ScriptRepository(app).loadAll()

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow(prefs.history())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _gatewayUrl = MutableStateFlow(prefs.gatewayUrl)
    val gatewayUrl: StateFlow<String> = _gatewayUrl.asStateFlow()

    private var playJob: Job? = null
    private var callId = ""
    /** Highest seq whose answer (or error) has been applied. Older outcomes only update their own bubble. */
    private var lastOutcomeSeq = 0L
    private var saved = false

    fun firstScript(kind: String): CallScript? = scripts.firstOrNull { it.kind == kind }

    // ---------------------------------------------------------------- call lifecycle

    /** 4.3: show the incoming-call screen for this script. */
    fun ring(script: CallScript) {
        playJob?.cancel()
        _state.value = CallUiState(script = script, phase = Phase.RINGING)
    }

    fun decline() = reset()

    fun reset() {
        playJob?.cancel()
        _state.value = CallUiState()
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
        saved = false
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
            endCall()
        }
    }

    /** User pressed the red button. */
    fun hangUp() {
        playJob?.cancel()
        endCall()
    }

    private fun endCall() {
        if (_state.value.phase != Phase.IN_CALL) return
        _state.update { st ->
            st.copy(
                phase = Phase.ENDED,
                lines = st.lines.map { if (it.pending) it.copy(pending = false, failed = true) else it },
            )
        }
        val s = _state.value
        if (!saved && s.script?.kind != "pasted") {
            saved = true
            prefs.addHistory(
                HistoryEntry(
                    timeMillis = System.currentTimeMillis(),
                    verdict = historyVerdict(s.level),
                    topReason = historyReason(s),
                    combinedScore = s.combinedScore,
                )
            )
            _history.value = prefs.history()
        }
    }

    /** Same rule as the bar: SCAM / UNSURE stick, SAFE only if the call ended without an error. */
    private fun historyVerdict(level: Risk.Level) = when (level) {
        Risk.Level.RED -> "SCAM"
        Risk.Level.YELLOW -> "UNSURE"
        Risk.Level.GREEN -> "SAFE"
        Risk.Level.ERROR, Risk.Level.LISTENING -> NOT_CHECKED
    }

    private fun historyReason(s: CallUiState) = when (s.level) {
        Risk.Level.RED, Risk.Level.YELLOW -> Risk.reason(s.lit, s.flaggedTexts)
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
        if (body.verdict !in KNOWN_VERDICTS) {
            markError(seq, "Server gave no verdict")       // e.g. VERDICT_UNSPECIFIED: not checked, never safe
            return
        }

        val flags = Risk.SIGNALS.filter { (body.scores?.get(it) ?: 0f) >= Risk.SIGNAL_ON }
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

    // ---------------------------------------------------------------- settings + history

    fun saveGatewayUrl(url: String) {
        prefs.gatewayUrl = url
        _gatewayUrl.value = prefs.gatewayUrl
    }

    /** GET /healthz on the given URL; returns a short message for the Settings screen. */
    suspend fun testConnection(url: String): String = try {
        val resp = GatewayClient.get(url).health()
        if (resp.isSuccessful) "Connected: ${resp.body()?.get("status") ?: "ok"}" else "Server answered ${resp.code()}"
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        "Not a valid address"
    } catch (e: Exception) {
        "Could not connect: ${e.javaClass.simpleName}"
    }

    fun clearHistory() {
        prefs.clearHistory()
        _history.value = emptyList()
    }

    companion object {
        private const val DEFAULT_DELAY_MS = 1_500L
        const val NOT_CHECKED = "NOT CHECKED"
        private val KNOWN_VERDICTS = setOf("SAFE", "UNSURE", "SCAM")
    }
}
