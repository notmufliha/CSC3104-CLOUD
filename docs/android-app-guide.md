# ScamGuard Android App – Build Guide (Workstream 4)

Oct 23, 2026 · @R

## Overview

The app plays a scripted call as a chat (Speaker 1 = caller, Speaker 2 = you), sends each caller line to the gateway with Retrofit, and turns the answer into a risk bar, three chips and a red pop-up when the verdict becomes SCAM. Everything below builds on the `android-app/` folder already in the repo (package `com.example.scamdetect`, which only has `IncomingCallScreen.kt` so far).

- **Talks to:** the Rust gateway over plain HTTP/JSON, `POST /v1/utterance`, one line at a time. Not gRPC: the architecture guide mentions gRPC + JWT, but the gateway in the repo and your task (Retrofit) both use HTTP/JSON, so build against that.
- **Sends:** caller lines only. Receiver lines are shown in the chat but never sent.
- **Stores:** gateway URL and up to 100 history rows (date, verdict, top reason). No transcripts.
- **Done when:** "Simulate scam call" shows the transcript appearing, the bar going yellow then red, and the summary screen at the end.

&#91;embedded content: one call · app screens above, backend below\]

Receiver lines stay on the phone. Each caller line goes down to the gateway, and the answer moves the risk bar. The first SCAM answer opens the pop-up.

All the code in this guide is also in the zip I sent with it. Unzip it over `android-app/` and the app should build.

## Gateway API the app calls

One request per caller line. The answer's `verdict` covers the **whole call so far**, so the app just shows the newest answer. This comes from the repo's `README.md` and `backend/gateway/src/main.rs`.

Request, `POST {gatewayUrl}/v1/utterance`:

```json
{"call_id": "8f1c…uuid", "seq": 4, "speaker": "caller", "text": "Transfer the money now", "lang": "en"}
```

Answer (HTTP 200):

```json
{"call_id": "8f1c…", "seq": 4, "scored": true, "verdict": "SCAM", "combined_score": 0.79,
 "scores": {"urgency": 0.47, "money": 1.0, "impersonation": 0.90}, "missing": [],
 "call_scores": {"urgency": 0.47, "money": 1.0, "impersonation": 0.90}, "caller_lines": 2, "latency_ms": 15}
```

| Field | What the app does with it |
| --- | --- |
| `call_id` | A new random UUID per call. Same value for every line. |
| `seq` | The line number in the script (1, 2, 3…). Caller and receiver share one count, so a caller line can be seq 2, 4, 6. Used to drop late answers. |
| `lang` | `en` / `zh`, taken from the script's file name. Left out for pasted text, and the gateway detects it. |
| `verdict` | `SAFE` → green, `UNSURE` → yellow, `SCAM` → red. |
| `combined_score` | How full the risk bar is (0–1). |
| `scores` | This line only. A score ≥ 0.5 marks the bubble as flagged and lists it on the summary. |
| `call_scores` | Best score per signal over the call. A score ≥ 0.5 lights that chip. |
| HTTP 503 / 502 / no connection | Show "Could not check" in grey. Never show "safe". |

Aggregator thresholds (in `aggregator/src/main.rs`): SCAM ≥ 0.6, UNSURE 0.45–0.6, SAFE < 0.45. The gateway does not send a reason text, so the app builds the reason from which chips are lit (see `Risk.kt`).

Test the gateway from your laptop before touching the app:

```bash
docker compose up --build
curl -X POST http://localhost:8080/v1/utterance -H "Content-Type: application/json" \
  -d '{"call_id":"demo-1","seq":1,"speaker":"caller","lang":"en","text":"This is the police. Transfer the money to the safe account now and read me the OTP."}'
```

## Project setup

The existing project (AGP 9.4.1, Kotlin 2.2.10, Compose BOM 2025.09.00, minSdk 26) needs three libraries and two permissions. Nothing else changes.

**1. `gradle/libs.versions.toml`.** Add under `[versions]`:

```toml
retrofit = "2.11.0"
okhttp = "4.12.0"
```

Add under `[libraries]`:

```toml
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-converter-gson = { group = "com.squareup.retrofit2", name = "converter-gson", version.ref = "retrofit" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
```

**2. `app/build.gradle.kts`.** Add to `dependencies { }`:

```kotlin
implementation(libs.androidx.lifecycle.viewmodel.compose)
implementation(libs.retrofit)
implementation(libs.retrofit.converter.gson)
implementation(libs.okhttp.logging)
```

Gson (pulled in by `converter-gson`) also reads the script files and the history, so you don't need a serialization plugin, Room or KSP. Gradle sync, done.

**3. `AndroidManifest.xml`.** Add above `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.VIBRATE" />
```

On `<application>`, set `android:usesCleartextTraffic="true"` (the demo gateway is plain `http://`) and `android:allowBackup="false"` (privacy point from the architecture guide).

**4. File layout** (all under `app/src/main/`):

```text
assets/scripts/            scam_en_01_police.json, normal_en_01_clinic.json, … (10 files from Workstream 6)
java/com/example/scamdetect/
  MainActivity.kt          screen switching (Home, Call, History, Settings…)
  data/Models.kt           ScriptLine, CallScript, UtteranceRequest/Response, HistoryEntry
  data/GatewayApi.kt       Retrofit interface + client that rebuilds when the URL changes
  data/ScriptRepository.kt loads assets/scripts/*.json
  data/AppPrefs.kt         gateway URL + history (SharedPreferences)
  logic/Risk.kt            verdict → colour, chips, reason, suggested action
  ui/CallViewModel.kt      plays the script, calls the gateway, holds the call state
  ui/IncomingCallScreen.kt (already in the repo, unchanged)
  ui/InCallScreen.kt       chat, risk bar, chips, banner, red pop-up
  ui/OtherScreens.kt       Home, script picker, paste text, summary, history, settings
```

## Data layer: scripts, Retrofit, settings, history

Five small files. Copy them as they are.

**4.2 Call scripts.** Agree this file-name rule with Workstream 6: `<kind>_<lang>_<nn>_<title>.json`, for example `scam_en_01_police.json` or `normal_zh_03_delivery.json`. The app reads scam/normal and en/zh from the name, so the JSON stays exactly `[{speaker, text, delay_ms}]`. `speaker` is `caller` or `receiver`. `delay_ms` is the wait **before** that line appears. Five scam + five normal files go in `app/src/main/assets/scripts/`. Example:

```json
[
  {"speaker": "receiver", "text": "Hello?", "delay_ms": 800},
  {"speaker": "caller", "text": "Good afternoon, this is Officer Tan from the Singapore Police Force.", "delay_ms": 1500},
  {"speaker": "receiver", "text": "Oh, what is this about?", "delay_ms": 2000},
  {"speaker": "caller", "text": "Your bank account is linked to a money laundering case. You must act now or a warrant will be issued today.", "delay_ms": 2500},
  {"speaker": "receiver", "text": "What? I didn't do anything.", "delay_ms": 2000},
  {"speaker": "caller", "text": "To clear your name, transfer your savings to a safe account and read me the OTP you just received.", "delay_ms": 2500}
]
```

`data/Models.kt`:

```kotlin
package com.example.scamdetect.data

import com.google.gson.annotations.SerializedName

/** One line of a call script (assets/scripts/*.json), the format Workstream 6 writes. */
data class ScriptLine(
    val speaker: String? = null,          // "caller" or "receiver"
    val text: String? = null,
    @SerializedName("delay_ms") val delayMs: Long? = null, // wait before this line appears
) {
    val isCaller: Boolean
        get() = speaker?.trim()?.lowercase() in setOf("caller", "speaker 1", "speaker1", "speaker_1")
}

/** A whole script plus what we read from its file name, e.g. scam_en_01_police.json. */
data class CallScript(
    val fileName: String,
    val kind: String,        // "scam" | "normal" | "pasted"
    val lang: String?,       // "en" | "zh" | null (let the gateway detect)
    val title: String,
    val lines: List<ScriptLine>,
)

/** Body of POST /v1/utterance. Gson leaves out null fields, so lang = null means "detect". */
data class UtteranceRequest(
    @SerializedName("call_id") val callId: String,
    val seq: Long,
    val speaker: String,
    val text: String,
    val lang: String? = null,
)

/** Answer from POST /v1/utterance. Everything nullable: Gson does not respect Kotlin defaults. */
data class UtteranceResponse(
    @SerializedName("call_id") val callId: String?,
    val seq: Long?,
    val scored: Boolean?,
    val duplicate: Boolean?,
    val verdict: String?,                                   // SAFE | UNSURE | SCAM
    @SerializedName("combined_score") val combinedScore: Float?,
    val language: String?,
    val scores: Map<String, Float?>?,                       // this line only
    val missing: List<String>?,
    @SerializedName("call_scores") val callScores: Map<String, Float>?, // best per signal, whole call
    @SerializedName("caller_lines") val callerLines: Int?,
    @SerializedName("latency_ms") val latencyMs: Long?,
)

/** One row of the History screen. No transcript, no number. */
data class HistoryEntry(
    val timeMillis: Long,
    val verdict: String,
    val topReason: String,
    val combinedScore: Float?,
)
```

`data/ScriptRepository.kt`:

```kotlin
package com.example.scamdetect.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Loads every assets/scripts/*.json file.
 * File name convention: <kind>_<lang>_<nn>_<title>.json, e.g. scam_en_01_police.json, normal_zh_02_delivery.json
 */
class ScriptRepository(private val context: Context) {
    private val gson = Gson()
    private val listType = object : TypeToken<List<ScriptLine>>() {}.type

    fun loadAll(): List<CallScript> {
        val files = context.assets.list("scripts")
            ?.filter { it.endsWith(".json") }
            ?.sorted()
            .orEmpty()
        return files.mapNotNull { name ->
            try {
                parse(name)
            } catch (e: Exception) {
                Log.w("ScriptRepository", "Skipping bad script $name: ${e.message}")
                null
            }
        }
    }

    private fun parse(name: String): CallScript {
        val json = context.assets.open("scripts/$name").bufferedReader().use { it.readText() }
        val lines: List<ScriptLine> = gson.fromJson(json, listType)
        val parts = name.removeSuffix(".json").split("_")
        val kind = parts.getOrNull(0)?.lowercase() ?: "unknown"
        val lang = parts.getOrNull(1)?.lowercase()?.takeIf { it == "en" || it == "zh" }
        val title = parts.drop(3).joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { name }
        return CallScript(name, kind, lang, title, lines.filter { !it.text.isNullOrBlank() })
    }
}
```

`data/GatewayApi.kt`. The client is rebuilt whenever the URL changes, which is what makes 4.8 work:

```kotlin
package com.example.scamdetect.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface GatewayApi {
    @POST("v1/utterance")
    suspend fun utterance(@Body body: UtteranceRequest): Response<UtteranceResponse>

    @GET("healthz")
    suspend fun health(): Response<Map<String, Any>>
}

/** Builds one Retrofit client per base URL, so changing the URL in Settings takes effect at once. */
object GatewayClient {
    private var cachedUrl: String? = null
    private var cachedApi: GatewayApi? = null

    /** "192.168.1.23:8080" -> "http://192.168.1.23:8080/" (Retrofit needs the scheme and the trailing /). */
    fun normalize(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    /** Throws IllegalArgumentException if the URL is not valid. */
    @Synchronized
    fun get(baseUrl: String): GatewayApi {
        val url = normalize(baseUrl)
        cachedApi?.let { if (url == cachedUrl) return it }

        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)   // gateway promises an answer within 2 s
            .addInterceptor(logging)            // logs method, URL, status only (no body)
            .build()

        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GatewayApi::class.java)

        cachedUrl = url
        cachedApi = api
        return api
    }
}
```

`data/AppPrefs.kt`. Gateway URL + history (4.7, 4.8). SharedPreferences is enough for 100 rows; Room is optional later.

```kotlin
package com.example.scamdetect.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Gateway URL + call history, stored in SharedPreferences. History keeps at most 100 rows. */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("scamguard", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val historyType = object : TypeToken<List<HistoryEntry>>() {}.type

    var gatewayUrl: String
        get() = sp.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = sp.edit { putString(KEY_URL, GatewayClient.normalize(value)) }

    fun history(): List<HistoryEntry> {
        val json = sp.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            gson.fromJson<List<HistoryEntry>>(json, historyType).orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addHistory(entry: HistoryEntry) {
        val updated = (listOf(entry) + history()).take(MAX_HISTORY) // newest first
        sp.edit { putString(KEY_HISTORY, gson.toJson(updated)) }
    }

    fun clearHistory() = sp.edit { remove(KEY_HISTORY) }

    companion object {
        const val DEFAULT_URL = "http://10.0.2.2:8080/" // emulator -> laptop's localhost
        private const val KEY_URL = "gateway_url"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 100
    }
}
```

`logic/Risk.kt`. Everything about colours, chips and banner wording lives here, so the team can tune the 0.5 cut-off or the messages in one place:

```kotlin
package com.example.scamdetect.logic

/** Turns gateway numbers into colours, chips and the words on the banner. */
object Risk {
    val SIGNALS = listOf("urgency", "money", "impersonation")

    /** A chip lights up when that signal's best score in the call reaches this. Tune with the team. */
    const val SIGNAL_ON = 0.5f

    enum class Level { LISTENING, GREEN, YELLOW, RED, ERROR }

    fun level(verdict: String?, error: String?): Level = when (verdict) {
        "SAFE" -> Level.GREEN
        "UNSURE" -> Level.YELLOW
        "SCAM" -> Level.RED
        else -> if (error != null) Level.ERROR else Level.LISTENING
    }

    fun litSignals(callScores: Map<String, Float>): List<String> =
        SIGNALS.filter { (callScores[it] ?: 0f) >= SIGNAL_ON }

    fun chipLabel(signal: String) = when (signal) {
        "urgency" -> "Urgency"
        "money" -> "Money / OTP"
        "impersonation" -> "Impersonation"
        else -> signal
    }

    /** Strongest reason first: money, then impersonation, then urgency. */
    fun reason(lit: List<String>, flaggedTexts: List<String>): String {
        val all = flaggedTexts.joinToString(" ").lowercase()
        return when {
            "money" in lit && ("otp" in all || "one-time" in all || "验证码" in all) -> "asking for your OTP"
            "money" in lit -> "asking you to transfer money"
            "impersonation" in lit -> "pretending to be an official (police, bank, government)"
            "urgency" in lit -> "pressuring you to act right now"
            else -> "several small warning signs together"
        }
    }

    fun action(lit: List<String>): String = when {
        "money" in lit -> "Hang up and call your bank on the number on your card."
        "impersonation" in lit -> "Hang up and call the agency back on its official number."
        else -> "Don't share personal details. Real organisations give you time to check."
    }

    fun banner(level: Level, lit: List<String>, flaggedTexts: List<String>): String? = when (level) {
        Level.RED -> "Likely scam: ${reason(lit, flaggedTexts)}. ${action(lit)}"
        Level.YELLOW -> "Be careful: ${reason(lit, flaggedTexts)}. ${action(lit)}"
        Level.ERROR -> "Could not check this call. The server can't be reached — this does NOT mean it is safe."
        else -> null
    }
}
```

## Screens (4.1, 4.3–4.8)

Navigation is one `enum` in `MainActivity`, with no navigation library. During a call, the call phase (ringing → in call → ended) picks the screen.

| Task | Screen | What the user sees |
| --- | --- | --- |
| 4.1 | `HomeScreen` | Simulate scam call, Simulate normal call, Choose a script, Paste text, History, Settings |
| 4.3 | `IncomingCallScreen` (existing) | "Unknown caller", Answer / Decline |
| 4.4 | `InCallScreen` | Risk bar, 3 chips, yellow banner with reason + action, chat bubbles: Speaker 1 · Caller on the left, Speaker 2 · You on the right; flagged caller lines get a red border |
| 4.5 | `ScamAlert` inside `InCallScreen` | Full-width red pop-up slides down from the top, one vibration, "Hang up now" / "Dismiss" |
| 4.6 | `SummaryScreen` | Verdict, reason, the flagged sentences, Report to ScamShield, Done |
| 4.7 | `HistoryScreen` | Date, verdict, top reason per call; Clear history |
| 4.8 | `SettingsScreen` | Gateway URL field, Emulator / Phone presets, Save, Test connection (`GET /healthz`) |

`MainActivity.kt` (replace the existing one):

```kotlin
package com.example.scamdetect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scamdetect.ui.CallViewModel
import com.example.scamdetect.ui.HistoryScreen
import com.example.scamdetect.ui.HomeScreen
import com.example.scamdetect.ui.InCallScreen
import com.example.scamdetect.ui.IncomingCallScreen
import com.example.scamdetect.ui.PasteTextScreen
import com.example.scamdetect.ui.Phase
import com.example.scamdetect.ui.ScriptPickerScreen
import com.example.scamdetect.ui.SettingsScreen
import com.example.scamdetect.ui.SummaryScreen
import com.example.scamdetect.ui.theme.ScamDetectTheme

enum class Screen { HOME, PICK, CALL, PASTE, HISTORY, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScamDetectTheme { ScamGuardApp() } }
    }
}

@Composable
fun ScamGuardApp(vm: CallViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    val gatewayUrl by vm.gatewayUrl.collectAsState()

    BackHandler(enabled = screen != Screen.HOME) {
        if (screen == Screen.CALL) vm.reset()
        screen = Screen.HOME
    }

    fun ringKind(kind: String) {
        vm.firstScript(kind)?.let { vm.ring(it); screen = Screen.CALL }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            onScamCall = { ringKind("scam") },
            onNormalCall = { ringKind("normal") },
            onPickScript = { screen = Screen.PICK },
            onPasteText = { screen = Screen.PASTE },
            onHistory = { screen = Screen.HISTORY },
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
            Phase.ENDED -> SummaryScreen(state, onDone = { vm.reset(); screen = Screen.HOME })
            Phase.IDLE -> LaunchedEffect(Unit) { screen = Screen.HOME }
        }

        Screen.HISTORY -> HistoryScreen(
            entries = history,
            onClear = vm::clearHistory,
            onBack = { screen = Screen.HOME },
        )

        Screen.SETTINGS -> SettingsScreen(
            currentUrl = gatewayUrl,
            onSave = vm::saveGatewayUrl,
            onTest = { vm.testConnection(it) },
            onBack = { screen = Screen.HOME },
        )
    }
}
```

`ui/InCallScreen.kt`. This holds the chat with Speaker 1 / Speaker 2, the risk bar, chips, banner and the red pop-up. The `@Preview` at the bottom shows the SCAM state in Android Studio without running anything.

```kotlin
package com.example.scamdetect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.scamdetect.logic.Risk
import com.example.scamdetect.ui.theme.ScamDetectTheme

val SafeGreen = Color(0xFF2E7D32)
val WarnYellow = Color(0xFFF9A825)
val ScamRed = Color(0xFFC62828)
val NeutralGrey = Color(0xFF9E9E9E)

fun levelColor(level: Risk.Level) = when (level) {
    Risk.Level.GREEN -> SafeGreen
    Risk.Level.YELLOW -> WarnYellow
    Risk.Level.RED -> ScamRed
    Risk.Level.ERROR, Risk.Level.LISTENING -> NeutralGrey
}

fun levelLabel(level: Risk.Level) = when (level) {
    Risk.Level.LISTENING -> "Listening…"
    Risk.Level.GREEN -> "Looks safe so far"
    Risk.Level.YELLOW -> "Suspicious"
    Risk.Level.RED -> "Likely scam"
    Risk.Level.ERROR -> "Could not check"
}

/** 4.4 In-call screen: risk bar, chips, banner, live transcript. 4.5 red alert pops over the top. */
@Composable
fun InCallScreen(state: CallUiState, onHangUp: () -> Unit) {
    var alertDismissed by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "On call · Unknown caller",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RiskBar(state.level, state.combinedScore)
            SignalChips(state.lit)
            // Yellow / grey banner lives in the layout; the red one is the pop-up below.
            if (state.level != Risk.Level.RED || alertDismissed) {
                state.banner?.let { InfoBanner(it, levelColor(state.level)) }
            }
            Transcript(state.lines, Modifier.weight(1f))
            Button(
                onClick = onHangUp,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White)
            ) {
                Icon(Icons.Filled.Call, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Hang up")
            }
        }

        // 4.5 full-width red alert, slides down from the top the first time the verdict is SCAM
        AnimatedVisibility(
            visible = state.scamAlert && !alertDismissed,
            enter = slideInVertically { -it } + fadeIn(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ScamAlert(
                text = state.banner ?: "Likely scam.",
                onHangUp = onHangUp,
                onDismiss = { alertDismissed = true }
            )
        }
    }
}

@Composable
fun RiskBar(level: Risk.Level, score: Float?) {
    val color by animateColorAsState(levelColor(level), label = "riskColor")
    val target = when (level) {
        Risk.Level.LISTENING, Risk.Level.ERROR -> 0f
        else -> (score ?: 0f).coerceIn(0.08f, 1f) // always show a sliver once there is a verdict
    }
    val progress by animateFloatAsState(target, label = "riskProgress")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(levelLabel(level), fontWeight = FontWeight.SemiBold, color = color)
            score?.let { Text("risk ${"%.2f".format(it)}", style = MaterialTheme.typography.labelMedium) }
        }
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxWidth(progress).height(14.dp).background(color))
        }
    }
}

@Composable
fun SignalChips(lit: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Risk.SIGNALS.forEach { signal ->
            val on = signal in lit
            Surface(
                shape = RoundedCornerShape(50),
                color = if (on) ScamRed.copy(alpha = 0.15f) else Color.Transparent,
                border = BorderStroke(1.dp, if (on) ScamRed else NeutralGrey),
            ) {
                Text(
                    Risk.chipLabel(signal),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) ScamRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InfoBanner(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, color)) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ScamAlert(text: String, onHangUp: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = ScamRed, modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(Modifier.size(8.dp))
                Text("WARNING", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onHangUp,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = ScamRed)
                ) { Text("Hang up now") }
                TextButton(onClick = onDismiss) { Text("Dismiss", color = Color.White) }
            }
        }
    }
}

/** Chat view: Speaker 1 (caller) on the left, Speaker 2 (you) on the right. */
@Composable
fun Transcript(lines: List<ChatLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines, key = { it.seq }) { ChatBubble(it) }
    }
}

@Composable
fun ChatBubble(line: ChatLine) {
    val flagged = line.flags.isNotEmpty()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.isCaller) Alignment.Start else Alignment.End
    ) {
        Text(
            if (line.isCaller) "Speaker 1 · Caller" else "Speaker 2 · You",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (line.isCaller) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            border = if (flagged) BorderStroke(2.dp, ScamRed) else null,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        when {
            line.pending -> Text("checking…", style = MaterialTheme.typography.labelSmall, color = NeutralGrey)
            flagged -> Text(
                "flagged: " + line.flags.joinToString { Risk.chipLabel(it) },
                style = MaterialTheme.typography.labelSmall, color = ScamRed
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 760)
@Composable
fun InCallPreview() {
    ScamDetectTheme {
        InCallScreen(
            CallUiState(
                phase = Phase.IN_CALL,
                verdict = "SCAM",
                combinedScore = 0.79f,
                callScores = mapOf("urgency" to 0.47f, "money" to 1f, "impersonation" to 0.9f),
                lines = listOf(
                    ChatLine(1, false, "Hello?"),
                    ChatLine(2, true, "This is Officer Tan from the police.", listOf("impersonation")),
                    ChatLine(3, true, "Read me the OTP you just received.", listOf("money")),
                ),
                scamAlert = true,
            ),
            onHangUp = {}
        )
    }
}
```

`ui/OtherScreens.kt`. This holds Home, the script picker, Paste text, the summary, history and settings. The Report button opens `https://www.scamshield.gov.sg/` in the browser.

```kotlin
package com.example.scamdetect.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scamdetect.data.CallScript
import com.example.scamdetect.data.HistoryEntry
import com.example.scamdetect.logic.Risk
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SCAMSHIELD_URL = "https://www.scamshield.gov.sg/"

@Composable
private fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
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
    onHistory: () -> Unit,
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
            TextButton(onClick = onHistory) { Text("History") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
    }
}

// ---------------------------------------------------------------- 4.2 Script picker

@Composable
fun ScriptPickerScreen(scripts: List<CallScript>, onPick: (CallScript) -> Unit, onBack: () -> Unit) {
    Page("Call scripts", onBack) {
        if (scripts.isEmpty()) Text("No scripts found in assets/scripts/.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scripts, key = { it.fileName }) { s ->
                Card(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${s.kind.uppercase()} · ${(s.lang ?: "auto").uppercase()} · ${s.lines.size} lines",
                            style = MaterialTheme.typography.labelMedium
                        )
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

@Composable
fun SummaryScreen(state: CallUiState, onDone: () -> Unit) {
    val context = LocalContext.current
    val level = state.level
    Page("Call summary") {
        Text(
            levelLabel(level).uppercase(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = levelColor(level)
        )
        state.banner?.let { InfoBanner(it, levelColor(level)) }
        Text("What triggered it", fontWeight = FontWeight.SemiBold)
        val flagged = state.lines.filter { it.flags.isNotEmpty() }
        if (flagged.isEmpty()) Text("No caller lines were flagged.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(flagged, key = { it.seq }) { ChatBubble(it) }
        }
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SCAMSHIELD_URL)))
                } catch (e: ActivityNotFoundException) { /* no browser on this device */ }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ScamRed, contentColor = Color.White)
        ) { Text("Report to ScamShield") }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

// ---------------------------------------------------------------- 4.7 History

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, onClear: () -> Unit, onBack: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    Page("History", onBack) {
        if (entries.isEmpty()) Text("No calls yet.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { e ->
                val color = levelColor(Risk.level(e.verdict, if (e.verdict == "NOT CHECKED") "x" else null))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(e.verdict, color = color, fontWeight = FontWeight.Bold)
                            Text(fmt.format(Date(e.timeMillis)), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(e.topReason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (entries.isNotEmpty()) OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Text("Clear history")
        }
    }
}

// ---------------------------------------------------------------- 4.8 Settings

@Composable
fun SettingsScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
    onTest: suspend (String) -> String,
    onBack: () -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Page("Settings", onBack) {
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Gateway URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Presets", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { url = "http://10.0.2.2:8080/" }) { Text("Emulator") }
            OutlinedButton(onClick = { url = "http://192.168.1.23:8080/" }) { Text("Phone (laptop IP)") }
        }
        Text(
            "Emulator: 10.0.2.2 is your laptop. Real phone: use the laptop's Wi-Fi IP. " +
                "Kubernetes: run kubectl port-forward --address 0.0.0.0 svc/gateway 8080:8080 first.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(url); result = "Saved" }) { Text("Save") }
            OutlinedButton(onClick = { scope.launch { result = "Testing…"; result = onTest(url) } }) {
                Text("Test connection")
            }
        }
        result?.let { Text(it) }
    }
}
```

## Simulation engine: CallViewModel

One coroutine plays the script. Each line waits its `delay_ms`, appears as a bubble, and, if it is a caller line, starts its own request so the chat never waits for the network.

1. **Answer** makes a new `call_id` (UUID) and builds the Retrofit client from the saved URL.
2. **Each line:** `delay(delay_ms)` → add a bubble → caller line? `launch { send(...) }` and show "checking…" under it.
3. **Each answer:** clear "checking…", flag the bubble if this line's own score is ≥ 0.5, and update the bar, chips and banner. If the answer is older than one already applied (lower `seq`), only its bubble is updated.
4. **First SCAM:** set `scamAlert = true`, which shows the red pop-up, and vibrate once (600 ms). This happens only once per call.
5. **Failure** (no connection, 503, 502): grey "Could not check" banner. The verdict is never set to SAFE.
6. **End:** after the last line, wait up to 5 s for open requests plus 1.5 s, then go to the summary. A history row is saved once (pasted text is not saved).

`ui/CallViewModel.kt`:

```kotlin
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

/** One chat bubble. flags = signals this line scored high on; pending = waiting for the gateway. */
data class ChatLine(
    val seq: Long,
    val isCaller: Boolean,
    val text: String,
    val flags: List<String> = emptyList(),
    val pending: Boolean = false,
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
    private var lastAppliedSeq = 0L
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
        _state.value = CallUiState(script = script, phase = Phase.RINGING)
        answer()
    }

    /** 4.3: play the script line by line and send caller lines to the gateway. */
    fun answer() {
        val script = _state.value.script ?: return
        callId = UUID.randomUUID().toString()
        lastAppliedSeq = 0L
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
                _state.update { it.copy(lines = it.lines + ChatLine(seq, line.isCaller, text, pending = waiting)) }
                if (waiting) sends += launch { send(api!!, seq, text, script.lang) }
            }
            withTimeoutOrNull(5_000) { sends.joinAll() } // let the last answers arrive
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
        val s = _state.value
        if (s.phase != Phase.IN_CALL) return
        _state.update { st -> st.copy(phase = Phase.ENDED, lines = st.lines.map { it.copy(pending = false) }) }
        if (!saved && s.script?.kind != "pasted") {
            saved = true
            prefs.addHistory(
                HistoryEntry(
                    timeMillis = System.currentTimeMillis(),
                    verdict = s.verdict ?: "NOT CHECKED",
                    topReason = if (s.verdict == null) "server not reached" else Risk.reason(s.lit, s.flaggedTexts),
                    combinedScore = s.combinedScore,
                )
            )
            _history.value = prefs.history()
        }
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

        val flags = Risk.SIGNALS.filter { (body.scores?.get(it) ?: 0f) >= Risk.SIGNAL_ON }
        val isNewest = seq >= lastAppliedSeq       // answers can arrive out of order; newest wins
        if (isNewest) lastAppliedSeq = seq

        _state.update { st ->
            val lines = st.lines.map { if (it.seq == seq) it.copy(pending = false, flags = flags) else it }
            if (!isNewest) st.copy(lines = lines)
            else st.copy(
                lines = lines,
                verdict = body.verdict ?: st.verdict,
                combinedScore = body.combinedScore ?: st.combinedScore,
                callScores = body.callScores ?: st.callScores,
                gatewayError = null,
            )
        }

        // 4.5: first time the call turns SCAM -> vibrate once + red banner
        if (_state.value.verdict == "SCAM" && !_state.value.scamAlert) {
            _state.update { it.copy(scamAlert = true) }
            vibrateOnce()
        }
    }

    private fun markError(seq: Long, msg: String) {
        _state.update { st ->
            st.copy(gatewayError = msg, lines = st.lines.map { if (it.seq == seq) it.copy(pending = false) else it })
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
    }
}
```

## Testing, running and demo checklist

Build order: get the screens right with no backend first, then connect to Compose, then to Kubernetes.

**Where the app points (4.8):**

| Setup | Gateway URL in Settings | Needed first |
| --- | --- | --- |
| Emulator + Docker Compose | `http://10.0.2.2:8080/` (default) | `docker compose up --build` |
| Real phone + Docker Compose | `http://<laptop Wi-Fi IP>:8080/` | Same Wi-Fi; firewall allows port 8080 (`ipconfig` on Windows shows the IP) |
| Emulator + minikube | `http://10.0.2.2:8080/` | `kubectl port-forward svc/gateway 8080:8080` |
| Real phone + minikube | `http://<laptop Wi-Fi IP>:8080/` | `kubectl port-forward --address 0.0.0.0 svc/gateway 8080:8080`, kept open the whole demo |

Press **Test connection** before every demo. "Connected: ok" means `/healthz` answered.

**Checks:**

- [ ] Preview pane shows `InCallPreview` in red (no backend needed)
- [ ] Backend off → Simulate scam call → bubbles appear on time, bar grey, "Could not check" banner, never green
- [ ] Backend on → Simulate scam call → bar goes yellow, then red; chips light; red pop-up + one vibration; summary lists the flagged lines
- [ ] Simulate normal call → bar stays green, no pop-up
- [ ] A `zh` script → Mandarin bubbles; `docker compose logs gateway` shows `lang="zh"`
- [ ] Report to ScamShield opens the website
- [ ] History shows date, verdict, reason; Clear history empties it
- [ ] Change the URL in Settings → next call uses it, no restart

**Things to sort out with the team:**

- **Bar timing.** "Yellow then red at the right moment" depends on the worker scores, not the app. Run each scam script through `curl` first. If it jumps straight to red, ask Workstream 6 to start the script with a softer line. Known weak spot: `impersonation_zh` scored the police scam 0.13 and the clinic call 0.50 in the README test, so Mandarin scripts may stay yellow.
- **Script format.** Ask Workstream 6 to use `caller` / `receiver` for `speaker`, the file-name rule from the Data layer section, and `delay_ms` as the wait before the line.
- **Chip cut-off.** 0.5 for every chip (`Risk.SIGNAL_ON`). Each worker's own threshold differs (urgency\_en 0.368, impersonation\_en 0.47), so tune it if the chips feel wrong.
- **README mismatch.** The README points to `com/csc3104/scamdetect/network/RetrofitClient.kt`, but that file doesn't exist and the package is `com.example.scamdetect`. Update the README once this is merged.

**Troubleshooting:**

| Symptom | Fix |
| --- | --- |
| `CLEARTEXT communication not permitted` | `android:usesCleartextTraffic="true"` is missing from the manifest |
| `SocketTimeoutException` / "Could not reach the server" | Wrong IP or port, port-forward closed, or the firewall is blocking 8080 |
| HTTP 503 on every line | Workers not up yet; `docker compose ps`, wait for the models to load |
| No vibration | Normal on the emulator; test on a real phone |
| "No scripts found" | Files must be in `app/src/main/assets/scripts/` and end in `.json`; check Logcat tag `ScriptRepository` |

I couldn't build the project here (no Android SDK), so the first Gradle sync is the real check. If something doesn't compile, comment on this doc with the error.
