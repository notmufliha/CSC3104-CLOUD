# ScamGuard Android app (Workstream 4)

The app makes the scam-detection system visible. It plays a scripted phone call as a chat, sends each caller line to the gateway, and shows the result live: a risk bar, three warning chips, and a red pop-up when the call is likely a scam.

Real phone calls can't be recorded on Android, so the main mode is a **simulated call**.

- Kotlin + Jetpack Compose (Material 3), minSdk 26
- Talks to the gateway over HTTP/JSON with Retrofit (`POST /v1/utterance`)
- Stores only the gateway URL and a short call history on the phone. No transcripts, no phone numbers.

---

## Features

| Task | Screen | What it does |
|---|---|---|
| 4.1 | Home | Simulate scam call, Simulate normal call, Choose a call script, Paste text, Live mic (disabled, later), History, Settings |
| 4.2 | Call scripts | Loads every `.json` file in `assets/scripts/` |
| 4.3 | Incoming call → In call | Answer plays the script line by line. Caller lines are sent to the gateway one at a time. |
| 4.4 | In call | Chat transcript (Speaker 1 · Caller on the left, Speaker 2 · You on the right), risk bar, Urgency / Money / Impersonation chips, banner with reason and suggested action |
| 4.5 | Red alert | On the first SCAM verdict: one vibration and a full-width red pop-up, e.g. *"Likely scam: asking for your OTP. Hang up and call your bank on the number on your card."* |
| 4.6 | Summary | Verdict, the caller lines that triggered it, **Report to ScamShield** (opens https://www.scamshield.gov.sg/) |
| 4.7 | History | Past calls: date, verdict, top reason. Up to 100 rows, newest first. Clear history button. |
| 4.8 | Settings | Gateway URL field, presets, Save, Test connection (`GET /healthz`) |

---

## Requirements

- Android Studio (recent version with AGP 9 support) and its Android SDK
- An emulator or a real Android phone (Android 8.0+)
- The backend running, either with Docker Compose or minikube (see the repo root `README.md` and `demo/`)

---

## Build and run

1. Open the `android-app/` folder in Android Studio and let Gradle sync.
2. Press **Run** on the `app` configuration.

### From the command line

**macOS / Linux:**

```bash
cd android-app
bash gradlew assembleDebug
```

`gradlew` isn't marked executable in the repo, so call it with `bash`. If Gradle can't find the SDK or Java:

```bash
export ANDROID_HOME=~/Library/Android/sdk                                   # macOS default
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

**Windows (PowerShell):**

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

If Gradle can't find the SDK or Java (default install paths; adjust if yours differ):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME    = "C:\Program Files\Android\Android Studio\jbr"
```

In Command Prompt instead of PowerShell, use `gradlew.bat assembleDebug` and `set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` on both systems.

---

## Pointing the app at the backend

**First launch (Android 17 / API 37 and newer):** the app asks for **local network access** (shown as a "nearby devices" prompt). Tap **Allow**. Android 17 blocks apps from reaching private addresses like `10.0.2.2` or `192.168.x.x` without it. If you refused it, every call shows "Could not check" and Test connection says "Could not connect: allow Local network access…". Turn it back on in Android Settings → Apps → ScamDetect → Permissions.

Set the gateway URL in **Settings**, then press **Test connection**. "Connected: ok" means the gateway answered.

| Setup | Gateway URL | Run first |
|---|---|---|
| Emulator + Docker Compose | `http://10.0.2.2:8080/` (default) | `docker compose up --build` |
| Real phone + Docker Compose | `http://<laptop Wi-Fi IP>:8080/` | Same Wi-Fi; allow port 8080 through the firewall |
| Emulator + minikube | `http://10.0.2.2:8080/` | `kubectl -n scamguard port-forward svc/gateway 8080:8080` |
| Real phone + minikube | `http://<laptop Wi-Fi IP>:8080/` | `kubectl -n scamguard port-forward --address 0.0.0.0 svc/gateway 8080:8080` (keep it open) |

- `10.0.2.2` is the emulator's name for the laptop.
- Venue Wi-Fi often blocks phone-to-laptop traffic. Use a phone hotspot, or fall back to the emulator.

**Find the laptop's IP**

| System | Command | Look for |
|---|---|---|
| Windows | `ipconfig` | "IPv4 Address" under **Wireless LAN adapter Wi-Fi** |
| macOS | `ipconfig getifaddr en0` | The printed address |
| Linux | `ip addr` | `inet` under your Wi-Fi interface |

**Windows only: real phone setup**

- **Firewall:** the first time Docker or `kubectl` listens on port 8080, Windows asks to allow access. Tick **Private networks** and click Allow. If you missed it, run this once in an **admin** PowerShell:

  ```powershell
  New-NetFirewallRule -DisplayName "ScamGuard gateway 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private
  ```

- **Network profile:** your Wi-Fi must be set to **Private** (Settings → Network & internet → Wi-Fi → your network → Private network). On a Public network the firewall rule above doesn't apply.
- **Check from the laptop:** in PowerShell, `curl.exe http://localhost:8080/healthz` should print `"status":"ok"`. Use `curl.exe`, not `curl`; in PowerShell, `curl` is a different command.
- **minikube on Windows:** a real phone reaching the laptop through `kubectl port-forward` with the Docker driver hasn't been verified. Test it before demo day; the emulator at `http://10.0.2.2:8080/` is the fallback.

---

## How a call works

1. **Answer** creates a random `call_id` for the call.
2. Each script line waits its `delay_ms`, then appears as a chat bubble.
3. **Caller** lines are sent to the gateway with `call_id`, `seq` (line number; caller and receiver share one count) and `lang`. **Receiver** lines are shown but never sent. Pasted text is sent without `lang`, so the gateway detects the language.
4. The gateway scores the line with the three workers for that language and returns the verdict **for the whole call so far**.
5. The app updates the bar, chips and banner from the newest answer. Answers that arrive late (lower `seq`) only update their own bubble.
6. On the first SCAM verdict, the phone vibrates once and the red pop-up appears.
7. After the last line, the app waits up to 5 s for open requests plus 1.5 s, then opens the summary and saves one history row. **Hang up** (or **Hang up now** on the pop-up) ends the call early and goes to the summary the same way. The system **Back** button during a call discards it and returns Home without saving.

### Request and response

```json
POST /v1/utterance
{"call_id": "8f1c…", "seq": 3, "speaker": "caller", "text": "Transfer the money now", "lang": "en"}
```

```json
{"call_id": "8f1c…", "seq": 3, "scored": true, "verdict": "SCAM", "combined_score": 0.79,
 "scores": {"urgency": 0.47, "money": 1.0, "impersonation": 0.90}, "missing": [],
 "call_scores": {"urgency": 0.47, "money": 1.0, "impersonation": 0.90}, "caller_lines": 2, "latency_ms": 15}
```

### What the user sees

| Situation | Bar | Label |
|---|---|---|
| Verdict `SCAM` | Red | Likely scam |
| Verdict `UNSURE` | Yellow | Suspicious |
| Error (no connection, timeout, HTTP 502/503, empty answer, bad URL, or `VERDICT_UNSPECIFIED`) | Grey | Could not check |
| Verdict `SAFE` | Green | Looks safe so far |
| No answer yet | Grey | Listening… |

The checks run in that order, so:

- **Never safe by mistake.** An error always beats SAFE: if the server can't be reached, the app never shows green.
- **Warnings stick.** Once a call has shown SCAM or UNSURE, a later error doesn't turn it grey.
- **One alert per call.** The pop-up and vibration fire only on the first SCAM. After **Dismiss**, the red banner shows inline instead.
- **Errors clear.** A successful answer for a newer line clears an older error. Lines that failed say "could not check" under the bubble.
- **Call end.** Requests still running when the call ends are cancelled, so they can't change the summary.

**Chips** light up when that signal's best score in the call (`call_scores`) is at least **0.5**. A **bubble is flagged** (red border) when that line's own score (`scores`) is at least 0.5.

**Reason and action** are built by the app (the gateway sends scores only), in this priority. The banner starts with "Likely scam:" (red) or "Be careful:" (yellow).

| Lit chip | Reason | Suggested action |
|---|---|---|
| Money + a flagged line mentions OTP / one-time / 验证码 | asking for your OTP | Hang up and call your bank on the number on your card. |
| Money | asking you to transfer money | Hang up and call your bank on the number on your card. |
| Impersonation | pretending to be an official (police, bank, government) | Hang up and call the agency back on its official number. |
| Urgency | pressuring you to act right now | Don't share personal details. Real organisations give you time to check. |
| None (verdict raised by several lower scores) | several small warning signs together | Don't share personal details. Real organisations give you time to check. |

### What history saves

| End of call | Saved verdict |
|---|---|
| Last verdict was SCAM or UNSURE | That verdict (even if a later line failed) |
| Last line answered SAFE with no error | SAFE (reason: "no warning signs") |
| Call ended with an error | NOT CHECKED (reason: "server not reached") |
| Call ended before any line got an answer | NOT CHECKED (reason: "ended before any line was checked") |

Pasted text is not saved to history.

---

## Call scripts

Scripts live in `app/src/main/assets/scripts/`. Each file is a JSON list:

```json
[
  {"speaker": "caller",   "text": "Good afternoon, I am calling from the courier company about your parcel.", "delay_ms": 1800},
  {"speaker": "receiver", "text": "Okay, which parcel?", "delay_ms": 1800},
  {"speaker": "caller",   "text": "You need to pay a small customs fee today by bank transfer or the parcel will be returned.", "delay_ms": 1800}
]
```

- `speaker`: `caller` (sent and scored) or `receiver` (shown only). `speaker 1` / `speaker1` / `speaker_1` also count as caller; anything else is treated as receiver.
- `delay_ms`: wait **before** the line appears. Defaults to 1500 ms if missing.
- Lines with empty `text` are skipped. A file that isn't valid JSON is skipped and logged under the Logcat tag `ScriptRepository`.
- **File name:** `<kind>_<lang>_<nn>_<title>.json`. The app reads the call type, language and title from it:
  - `kind`: `scam` or `normal`
  - `lang`: `en` or `zh` (sent to the gateway). Anything else is left out, so the gateway detects the language.
  - `title`: the rest of the name, shown in the script picker (`police_soft` → "Police soft")
- "Simulate scam call" and "Simulate normal call" play the first file of that kind in alphabetical order. "Choose a call script…" lists them all.

### Current scripts

| File | Verdict after each caller line | On the bar |
|---|---|---|
| `scam_en_00_police_soft.json` | UNSURE 0.47 → SCAM 0.94 → SCAM 0.94 | Yellow, then red (plays on "Simulate scam call") |
| `scam_en_01_police.json` | SCAM 0.84 → SCAM 0.96 → SCAM 0.96 | Straight to red |
| `scam_en_02_parcel.json` | SAFE 0.10 → SCAM 0.87 | Green, then red |
| `scam_zh_01_police.json` | UNSURE 0.54 → SCAM 0.71 | Yellow, then red |
| `normal_en_01_clinic.json` | SAFE 0.13 → SAFE 0.13 | Green |

Measured against the real backend (`docker compose`) on 25 Oct 2026 with `tools/backend_script_test.py`. Re-run it if the models change.

To measure them yourself with the backend running: `python3 -I tools/backend_script_test.py app/src/main/assets/scripts` (from `android-app/`). It sends each script's caller lines with the same `call_id`, `seq` and `lang` the app uses and prints a verdict table.
---

## Code layout

```text
tools/
└── backend_script_test.py       sends every script to a running gateway and prints the verdicts
app/src/main/
├── AndroidManifest.xml          INTERNET, VIBRATE, ACCESS_LOCAL_NETWORK, cleartext http, backup off
├── assets/scripts/              call scripts (.json)
└── java/com/example/scamdetect/
    ├── MainActivity.kt          screen switching (enum, no navigation library); asks for local network access on API 37+
    ├── data/
    │   ├── Models.kt            ScriptLine, CallScript, UtteranceRequest/Response, HistoryEntry
    │   ├── GatewayApi.kt        Retrofit interface + client rebuilt when the URL changes
    │   ├── ScriptRepository.kt  loads assets/scripts/
    │   └── AppPrefs.kt          gateway URL + history (SharedPreferences)
    ├── logic/
    │   └── Risk.kt              verdict → level, colours, chips, reason, action
    └── ui/
        ├── CallViewModel.kt     plays the script, calls the gateway, holds call state
        ├── IncomingCallScreen.kt
        ├── InCallScreen.kt      chat, risk bar, chips, banner, red pop-up (+ Preview)
        ├── OtherScreens.kt      Home, script picker, paste text, summary, history, settings
        └── theme/
```

**Where to change things**

| To change | Edit |
|---|---|
| Chip / flag cut-off (0.5) | `Risk.SIGNAL_ON` in `logic/Risk.kt` |
| Banner wording, reasons, actions | `logic/Risk.kt` |
| Colours | top of `ui/InCallScreen.kt` |
| Default gateway URL | `AppPrefs.DEFAULT_URL` |
| History size (100) | `AppPrefs.MAX_HISTORY` |
| Network timeouts (connect 2 s, whole request 4 s) | `GatewayClient` in `data/GatewayApi.kt` |
| End-of-call wait, vibration length | `answer()` and `vibrateOnce()` in `ui/CallViewModel.kt` |
| Calls and lines | `assets/scripts/*.json` |

**Dependencies added:** Retrofit 2.11.0 + Gson converter, OkHttp logging 4.12.0, lifecycle-viewmodel-compose. No Room, KSP or serialization plugin.

---

## Privacy

- **No transcripts:** call text is held in memory during the call only and is never written to disk.
- **History:** stores time, verdict, top reason and combined score only.
- **No phone numbers:** the app never sees or sends one.
- **Backups:** `android:allowBackup="false"`, so history isn't copied to cloud backups.
- **Logs:** the network log shows method, URL and status only, not request bodies.

---

## Testing checklist

- [ ] First launch: allow local network access; Settings → Test connection shows "Connected: ok"
- [ ] Preview pane shows `InCallPreview` in the red SCAM state (no backend needed)
- [ ] Backend off → Simulate scam call → bubbles appear on time, grey "Could not check", never green
- [ ] Backend on → Simulate scam call → bar goes yellow, then red; chips light; red pop-up + one vibration
- [ ] Choose a call script → Chinese scam call → yellow, then red; Mandarin bubbles
- [ ] Parcel scam → green, then red mid-call
- [ ] Normal call → stays green, no pop-up
- [ ] Summary lists the flagged lines; Report to ScamShield opens the website
- [ ] History shows each call with the right verdict; Clear history empties it
- [ ] Change the URL in Settings → next call uses it without restarting

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `CLEARTEXT communication not permitted` | `usesCleartextTraffic="true"` missing from the manifest |
| Test connection: "Could not connect: SocketTimeoutException" or "ConnectException" | Wrong IP or port, port-forward closed, or firewall blocking 8080 |
| Test connection: "Could not connect: allow Local network access…" (Android 17+) | Local network access was refused. Allow it in Android Settings → Apps → ScamDetect → Permissions |
| Test connection shows "Connected: ok" but every call shows "Could not check" | The gateway is up but answers HTTP 503: workers still loading. Check `docker compose ps` or `kubectl -n scamguard get pods` |
| Test connection: "Not a valid address" | Fix the URL in Settings, e.g. `http://10.0.2.2:8080/` |
| No vibration | Normal on the emulator; test on a real phone |
| "No scripts found" | Files must be in `assets/scripts/` and end in `.json`; check Logcat tag `ScriptRepository` |
| Gradle can't find the SDK | Open the project in Android Studio once (creates `local.properties`) or set `ANDROID_HOME` |
| Windows: phone can't connect but the emulator can | Firewall rule for port 8080 missing, or the Wi-Fi is set to Public (see "Windows only: real phone setup") |
| Windows: `curl` in PowerShell gives a strange error | Use `curl.exe` |
| Windows: `'bash' is not recognized` | Use `.\gradlew.bat` instead of `bash gradlew` |

---

## Limitations and next steps

- **Simulated calls only.** Live mic is not built. The next step is on-device speech-to-text feeding the same `POST /v1/utterance` call.
- **Plain HTTP.** Debug and demo only. A real release needs TLS and `usesCleartextTraffic` removed.
- **No login.** The gateway has no JWT check yet.
- **Mandarin accuracy.** The Mandarin impersonation model is the weakest worker, so Mandarin verdicts may lag by a line.
- **History storage.** History uses SharedPreferences. Move to Room if the team wants the dashboard or charts from the architecture guide.
