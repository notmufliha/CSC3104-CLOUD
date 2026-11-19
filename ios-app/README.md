# ScamGuard for iOS

An iOS port of the Android app (`android-app/`): simulated calls are scored live for scam signals, with three privacy
levels (Cloud, Hybrid, On-device), a local dashboard, and MobileBERT on the phone. SwiftUI, iOS 17+, Swift
Concurrency, SwiftData. The only third-party code is ONNX Runtime (Swift package, used for MobileBERT).

Real phone calls are not used: like Android, every call is played from a bundled script or pasted text, so no CallKit
or microphone access is needed.

## Build and run

Requirements: Xcode 27 (the project uses Xcode's text `project.xcproj` format) and the backend running via
`docker compose up` on the Mac.

### Simulator

1. Open `ios-app/ScamGuard.xcodeproj`. On first open Xcode fetches the ONNX Runtime package
   (`microsoft/onnxruntime-swift-package-manager` 1.24.2, pinned in `Package.resolved`).
2. Pick an iPhone simulator, then Run (⌘R). No signing is needed for the Simulator.
3. The default server address is `http://localhost:8080/`. The Simulator shares the Mac's network, so that is the
   gateway in docker compose. The setup has no server screen; afterwards Settings → Connection should say
   **Connected ✓**. Any other address is set in the hidden [Developer view](#developer-view-hidden).

From the terminal:

```sh
cd ios-app
xcrun simctl list devices available                     # pick an iPhone
xcodebuild test -project ScamGuard.xcodeproj -scheme ScamGuard \
  -destination 'platform=iOS Simulator,name=iPhone 17'   # 218 unit tests, about 10 s once built
```

Live calls against the backend are an opt-in test (about 1 minute per call, real script timing):

```sh
TEST_RUNNER_SCAMGUARD_BACKEND=1 xcodebuild test -project ScamGuard.xcodeproj -scheme ScamGuard \
  -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:ScamGuardTests/BackendIntegrationTests
# TEST_RUNNER_SCAMGUARD_SCRIPTS=all  plays all 25 scripts; TEST_RUNNER_SCAMGUARD_LEVEL=hybrid  uses level 2
```

Debug builds also accept launch arguments for screenshots:
`xcrun simctl launch booted com.rhaylene.ScamGuard -demoScript scam_en_11_bank_otp.json` (rings and answers),
`-demoTab dashboard|settings`, `-demoDev YES` (unlock the Developer view), `-demoSettings developer`,
`-demoDevScroll connection|models|call|raw|tools|reset`, `-demoDevExpand YES`, `-demoSpeed 4x`, `-demoOverlay YES`,
`-demoLevel hybrid`, `-demoAfterCall developer` (after a demo call, open the Developer view in the same run),
`-demoSettingsBottom YES`, `-demoResetApp YES`.

### Real iPhone (free Apple ID)

1. Xcode → Settings → Accounts: add your Apple ID (a free "Personal Team" is enough).
2. Target ScamGuard → Signing & Capabilities: Team = your Personal Team. If the bundle id `com.rhaylene.ScamGuard` is
   taken, change it to something unique (for example `com.<you>.ScamGuard`).
3. iPhone: Settings → Privacy & Security → **Developer Mode** → on (the phone restarts). Connect it by cable, trust
   the Mac, select it as the run destination and press Run.
4. The first launch is refused as an untrusted developer. On the iPhone: Settings → General → **VPN & Device
   Management** → your Apple ID → Trust.
5. Free signing expires after **7 days**: after that the app won't open. Run it from Xcode again to re-sign.
6. Server address: the phone can't use `localhost`. Unlock the Developer view (Settings → Help → tap **Version** 7
   times), open Settings → **Developer view** → 1. Connection → Server address, tap **Mac on Wi-Fi** and replace `192.168.1.23` with
   the Mac's Wi-Fi IP (`ipconfig getifaddr en0` on the Mac), e.g. `http://192.168.1.50:8080/`. Phone and Mac must be
   on the same Wi-Fi, and the Mac's firewall must allow incoming connections to Docker.
7. The first request triggers iOS's **Local Network** prompt ("ScamGuard would like to find and connect to devices
   on your local network"): tap Allow. If it was refused: iOS Settings → Privacy & Security → Local Network →
   ScamGuard.

#### HTTPS (Kubernetes, `demo/expose`)

The HTTPS preset is `https://<IP>:8443/api/`. The demo certificate is signed by the demo CA, which the iPhone has to
trust first:

1. Send `demo/tls/ca.crt` to the iPhone (AirDrop, Mail, or Files), open it, and allow the profile download.
2. Settings → General → **VPN & Device Management** → the downloaded profile → Install.
3. Settings → General → About → **Certificate Trust Settings** → turn on full trust for the demo CA.

For the Simulator you can instead run `xcrun simctl keychain booted add-root-cert demo/tls/ca.crt`.

### App Transport Security (demo only)

`ScamGuard-Info.plist` sets `NSAllowsLocalNetworking = YES` and **`NSAllowsArbitraryLoads = YES`** so the app can talk
plain HTTP to the demo gateway on any address. This is for the demo only and must be removed for any real release
(use HTTPS and drop both keys). `NSLocalNetworkUsageDescription` explains the Local Network prompt.

## Developer view (hidden)

Same as Android (see android-app/README.md, "Developer view (hidden)"): same sections, order, wording and rules.
For demos and debugging; normal users never see it.

- **Unlock:** Settings → Help → tap **Version 1.0** 7 times → "Developer view unlocked" (shown under the version for
  2 s and announced by VoiceOver). The row then sits at the bottom of Settings (Standard) or under Advanced settings
  (Simple, behind the PIN). Debug and release builds. The switch at the top of the view locks it again.
- **Saved:** only `developer_view_unlocked` (UserDefaults). The log, last call, overlay switch and playback speed are
  in memory only; cleared when the app quits, by Delete my history and by locking. Caller text is masked
  (`Logic/Mask.swift`) in the view and in Copy diagnostics.
- **Sections:** 1. Connection (gateway URL, last /healthz, privacy level, version/build, iOS version; iOS doesn't
  report the Local Network permission, so a refusal shows as a failed /healthz) with **Server address** (field,
  presets Simulator / Simulator edge / Mac on Wi-Fi / HTTPS, Save, Test connection with the full error);
  2. Models (bundled versions + SHA-256 from `Resources/Models/manifest.json`, downloaded ones, MobileBERT
  status/version/size, the server's `model_versions`; Check for model updates; Train on my calls disabled);
  3. Last call (per caller line: time, U / M / I, combined, BERT + status, rule, live alert, verdict, where it was
  checked, latency; the end-of-call reply); 4. Raw replies (last 20, masked JSON); 5. Tools.
- **Tools:** Call playback speed 1× / 2× / 4× / Instant (scales `delay_ms`; Instant = 150 ms gaps; "2× speed" tag on
  the call screen; the end message is still sent; back to 1× when locked), Show live scores during calls (panel at the
  top, tap to collapse, hidden while the red warning shows), Load / Delete sample history, Copy diagnostics,
  Reset setup only (keeps history), **Reset app to first launch** (asks first; deletes the database, every setting,
  the Keychain PIN, downloaded models and the logs, then shows "Who will use this phone?"; bundled models and scripts
  stay).
- The script picker's **Test calls (learning loop)** section (scripts 90+) shows only while unlocked.

## Features by phase

| Phase | What it does |
|---|---|
| 1. Cloud | Tabs Home / Dashboard / Settings. Simulate scam / normal call (random script of that kind), script picker (25 scripts, filter & sort), paste text; incoming call; in-call risk bar, 3 chips (≥ 0.5 from `recent_scores`, fallback `call_scores`), privacy badge, chat transcript with "flagged: …" / "could not check", yellow banner, red WARNING pop-up (Hang up now / Dismiss) with one error haptic on the first SCAM; summary with the final verdict from `POST /v1/call/end` (3 s wait, else "Final review unavailable"), "During the call: …", reason + action, "What triggered it" (`top_window_seqs`), Report to ScamShield, This was a scam / This was fine with the consent sheet of masked lines → `POST /v1/report`. Developer view (hidden): URL, presets, Save, Test connection; normal Settings shows only Connected ✓ / Can't connect to ScamGuard. Handles duplicate, `ignored: true`, `already_ended`, `UNKNOWN`, HTTP errors and timeouts like Android. |
| 2. Dashboard | SwiftData store with Android's 4 tables (CallRecord, ScorePoint, ImplicitLabel, LabelSentence). Most common reasons (by reason code), past calls (verdict dot, time, reason, privacy badge), call detail with a Swift Charts risk-over-time chart (combined + urgency, money, impersonation; dashed lines at 0.45 and 0.6). "Save the riskiest sentence", "Help improve detection" (off by default; keeps labels and masked caller lines locally), "Delete my history" (all 4 tables). Implicit labels (G.12) as on Android. Nothing is uploaded. |
| 3. Hybrid + On-device | Swift ports of the three workers' text cleaning (Python regex semantics), the linear models (format 1 and the hashing format 2 with Murmur3), OnDeviceScorer, LocalRules (policy.rs) and OnDeviceCall. Hybrid sends only masked 5-line windows to `POST /v1/verify` (from the 3rd caller line, at ≥ 0.45 / a rule BERT can change / the 5th-line spot check, never once red, 1.5 s timeout, English only). On-device makes no network request at all. Privacy-level picker; "Check for model updates" (manifest → download → SHA-256 → Application Support; the bundled models are the fallback). |
| 4. MobileBERT | ONNX Runtime 1.24.2 via Swift Package Manager, bundled `model.onnx` (26.6 MB int8) + `vocab.txt` + `bert_config.json`, WordPiece tokenizer port (uncased, NFKC, last 5 caller lines joined with " \| ", left truncation to 128, softmax(logits / 1.2908)[1]). Level 3 asks MobileBERT by the same rules as Hybrid asks the cloud. Status shown in Settings. About 15 ms per window on the Simulator. |

## Differences from Android

- **Discard call** button on the call screen replaces Android's system Back (ends with `user_discarded`, nothing saved).
- **Haptics**: one `UINotificationFeedbackGenerator(.error)` on the first SCAM, instead of a 600 ms vibration.
- **Two Android bugs fixed**: "What triggered it" is hidden when the final verdict is SAFE; at levels 2–3 the
  summary's reason uses the same reason code and wording as the dashboard (Android used its own older wording there).
  At level 1 the summary shows the server's end-of-call reason and the server's action (policy.rs wording).
- **Model updates** use the edge even when the gateway address is `:8080` (`http://host:8080/` → `http://host:8088/models/`).
  The start-up update check is skipped at level 3, so that level makes no network traffic at all.
- **"Train on my calls and share the update"** is shown disabled ("coming soon"): the federated upload answers 502 on
  Kubernetes (FINAL_RESULTS.md, open problem 1).
- **Timeouts**: URLSession has no separate connect timeout, so requests use 4 s for both idle and total time (Android:
  connect 2 s, call 4 s). Verify still gives up after 1.5 s.
- **Storage**: SwiftData instead of Room. Room's composite keys ((callId, seq), (callId, source)) are a unique `key`
  column (SwiftData's `#Unique` needs iOS 18), and ImplicitLabel's autoincrement id is assigned by the store. There
  is no legacy SharedPreferences history to migrate, and no 2 → 3 migration (fresh installs only).
- A `duplicate: true` answer is applied to the screen but adds no second chart point.
- ONNX Runtime 1.24.2 (Android uses 1.22.0, which has no Swift package release).
