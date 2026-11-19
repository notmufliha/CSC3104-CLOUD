# How to run ScamGuard

ScamGuard can be tried in three ways: the **web version** (no install), the **Android app** (Android Studio) and
the **iPhone app** (Xcode, Mac only). All three use the same ScamGuard server, which runs on your computer with
Docker, either with Docker Compose or on Kubernetes (minikube). Start with step 1, then choose any of the three ways.

The web version has **all three privacy levels** of the phone apps (Cloud, Hybrid, On-device), so every way calls
can be checked can be tried in a browser, without a phone or Android Studio.

| Way | You need | Time |
|---|---|---|
| [Web version](#2-web-version-no-install) | Docker Desktop and a browser | about 15 min (first build), then 1 min |
| [Android app](#3-android-app-android-studio) | Docker Desktop and Android Studio | about 10 min after step 1 |
| [iPhone app](#4-iphone-app-xcode-mac-only) | Docker Desktop and Xcode 27 on a Mac | about 10 min after step 1 |

---

## 1. Start the server (needed for all three)

1. Install **Docker Desktop** and start it. Give Docker at least 8 GB of memory (Docker Desktop → Settings → Resources).
2. Open a terminal in the project folder and run:

   ```bash
   docker compose up -d --build
   docker compose ps          # wait until every service shows "Up"
   ```

   The first build downloads and builds the images and takes about 15 minutes. Later starts take under a minute.
3. Check that it works (optional):

   ```bash
   curl http://127.0.0.1:8088/api/healthz      # {"status":"ok", ...}
   ```

To stop it later, run `docker compose down`.

### 1b. Or: start the server on Kubernetes (minikube)

This is the full cloud deployment (replicas, autoscaling, Ingress with HTTPS), the same one the report describes. It
needs Docker Desktop (8 GB of memory for Docker), `minikube` and `kubectl`.

```powershell
demo\up.ps1        # Windows (macOS/Linux: bash demo/up.sh). First run about 20 min: builds 12 images, starts minikube
demo\expose.ps1    # leave this window open: it keeps https://127.0.0.1:8443/ (and https://<laptop IP>:8443/) open
```

The web version is then at **https://127.0.0.1:8443/** (step 2). Its certificate is signed by the project's own
demo CA (`demo/tls/ca.crt`), so the browser warns the first time: choose **Advanced → Continue to 127.0.0.1**, or
import `demo/tls/ca.crt` as a trusted certificate to avoid the warning. More: [demo/README.md](demo/README.md).

---

## 2. Web version (no install)

Open **http://127.0.0.1:8088/** (Docker Compose) or **https://127.0.0.1:8443/** (Kubernetes, step 1b) in Chrome,
Edge, Safari or Firefox. It works best in a narrow window or with the browser's phone view (developer tools →
device toolbar).

The web version has the same screens and wording as the phone apps, and the same three privacy levels
(**Settings → How calls are checked**; a change applies from the next call):

| Level in Settings | Where each caller line is checked | What is sent to the server during a call |
|---|---|---|
| **Most accurate** (Cloud, the default) | by the server: 3 checks per language + BERT | every caller line |
| **Balanced** (Hybrid) | in the browser, with the apps' own on-device models | only at unsure moments: the caller's last 5 lines, with names and numbers removed, for a BERT second opinion |
| **Most private** (On-device) | in the browser only, with the apps' on-device models and MobileBERT as the second opinion | nothing |

Hybrid and On-device run the **same model files and decision rules as the Android and iOS apps**
(`web/ondevice.js`, a port of the apps' code; checked against the same parity files, see
`tools/testing/web_ondevice_parity.mjs`). Like the apps, they check English calls only. The first switch to
**Most private** loads MobileBERT into the page (about 37 MB, once); Settings shows when it is ready.

**On a phone's browser** (any phone, including an iPhone, with no app installed): with the Kubernetes server
(step 1b) and `demo\expose.ps1` running, put the phone on the same Wi-Fi as the laptop and open
`https://<laptop IP>:8443/` (expose prints the IP). Campus Wi-Fi often blocks devices from reaching each other; if
the page doesn't open, connect the laptop to the phone's hotspot instead.

**What to try:**

1. **Home → Simulate scam call** (or **Choose a call script… → Bank OTP**), then **Answer**.
   - The risk bar fills as each caller line is checked by the server.
   - After a few lines the screen turns red: **"This may be a scam"**, with **Hang up**, **Call my family**,
     **Tell (name)**, **Call 1799 ScamShield Helpline** and **Close this warning**.
2. Tap **Hang up**. The **Call summary** shows the result and the **"What to do now"** card:
   - **Report to ScamShield** (copies a short summary first, then opens the ScamShield site);
   - Call 1799;
   - Tell my family;
   - How to block this number;
   - If I already shared details.
3. **Simulate normal call**: stays green ("Safe so far"), with no warning.
4. **Try the privacy levels**: Settings → How calls are checked → **Balanced** or **Most private**, then play the
   same Bank OTP call again. The call screen says where it is checked ("Checked on this phone" / "online only when
   unsure"); a caller line that got a second opinion says **Also checked online** (Hybrid) or **Checked again on
   this phone** (MobileBERT). The summary says "Checked on this phone". To see that Most private sends nothing,
   open developer tools → Network before the call: no request is made until you leave the page. (Turning the
   server off, `docker compose stop gateway`, also shows it: Most private still warns; Hybrid keeps its own answer
   when the online check is unavailable.)
5. **Learn to spot scams**: 10 common scam types with warning signs and what to do. **Practice quiz**: 5 real caller lines, "Scam or Safe?".
6. **Dashboard → Share weekly summary**: a short note for the family. It has no phone numbers and nothing the
   caller said. **Share** opens the browser's share menu, or copies the note.
7. **Settings**:
   - **How calls are checked** (the privacy level, above).
   - **App style**: **Standard** or **Simple**. Simple has bigger text and fewer buttons, and its Home has
     "ScamGuard is protecting you", **Try a practice call**, Learn and Recent calls.
   - **Text size**.
   - **My family contact** (used by Call my family / Tell my family).
   - **Read warnings aloud**.
   - **Show 'Be careful' warnings**.
   - **Connection** status.

**Known limits of the web version:**
- A browser can't make phone calls or send text messages. **Call my family**, **Tell my family** and
  **Call 1799** open the computer's phone or messages app if it has one; otherwise nothing happens. The
  demo video shows them on a phone.
- These are in the phone apps only:
  - the weekly reminder notification;
  - the PIN lock;
  - the hidden Developer view;
  - newer on-device models downloaded from the server (federated learning): the web version uses the models built
    into the apps.
- "Balanced" and "Most private" check English calls only (as on the phones); Chinese lines show "Not checked".
- MobileBERT runs on onnxruntime-web (WebAssembly), whose int8 arithmetic is not bit-for-bit the phones': on the
  test windows its score is within 0.06 of the server's Python run and on the same side of the 0.45 / 0.6
  thresholds, and every English demo call gets the same verdicts, line by line, as native ONNX Runtime.
- History, settings and the family contact are kept in this browser only (nothing is uploaded).

If the page says it can't connect, check that step 1 (or 1b) is running. "Most private" works even then.

---

## 3. Android app (Android Studio)

**Needs:** a recent Android Studio (with AGP 9 support), its Android SDK and an emulator (Device Manager → create
any phone, e.g. "Medium Phone", API 35 or newer). The app needs Android 8.0 or newer (minSdk 26).

1. Start the server (step 1).
2. Android Studio → **File → Open** → choose the **`android-app`** folder (not the project root). Wait for the
   Gradle sync to finish (the first sync downloads dependencies and takes a few minutes).
3. Pick the emulator at the top and press **Run ▶**.
4. On first launch:
   - Android 17 and newer asks for **local network access** ("nearby devices"). Tap **Allow**. Without it,
     every call shows "Could not check".
   - The app asks **"Who will use this phone?"**: pick **Me** (Standard), **Me, but simpler** (Simple) or
     **An older family member** (Simple, set up by a helper with a family contact and an optional PIN).
5. The emulator reaches the server at `http://10.0.2.2:8080/` (the default), so no setting is needed. Settings →
   **Connection** should say it is connected.
6. Try the same steps as in the web version:
   - a scam call → red warning → summary with "What to do now";
   - Learn and the quiz;
   - Dashboard (Standard) or For family (Simple) → Share weekly summary;
   - the **Weekly reminder** switch (it asks for notification permission only when you turn it on).
   - On the emulator, **Call my family** and **Tell my family** open the emulator's Phone and Messages apps.

**A real Android phone:**
- Put the phone and the laptop on the same Wi-Fi.
- In the app, unlock the Developer view: Settings → Help → tap **Version 1.0** seven times.
- Then Settings → Developer view → Server address: enter `http://<laptop IP>:8088/api/`, tap **Save**, then **Test connection**.

**From the command line (optional):**

```bash
cd android-app
bash gradlew assembleDebug testDebugUnitTest     # builds the app and runs 183 unit tests
```

If Gradle can't find Java, use Android Studio's:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (macOS). On Windows, use
`.\gradlew.bat assembleDebug`. More: [android-app/README.md](android-app/README.md).

---

## 4. iPhone app (Xcode, Mac only)

**Needs:** a Mac with **Xcode 27** (the project uses Xcode's new text project format, so older Xcode versions
can't open it) and an iPhone simulator (iOS 17 or newer).

1. Start the server (step 1).
2. Open **`ios-app/ScamGuard.xcodeproj`** in Xcode. On first open Xcode downloads the ONNX Runtime package (used
   for checking calls on the phone). Wait until "Package resolution" finishes.
3. Choose the **ScamGuard** scheme and any iPhone simulator, then press **Run (⌘R)**. No Apple account or signing
   is needed for the simulator.
4. The simulator reaches the server at `http://localhost:8080/` (the default), so no setting is needed. The first
   screen asks "Who will use this phone?", as on Android.
5. Try the same steps as above. The iOS app has the same screens, flow and wording as the Android app.

**A real iPhone:**
- Xcode → Settings → Accounts: add your Apple ID (a free Personal Team is enough).
- Choose the ScamGuard target → Signing & Capabilities → Team.
- Connect the iPhone, turn on Developer Mode, and Run.
- Set the server address in the Developer view: Settings → Help → tap the version seven times, then use
  `http://<Mac IP>:8088/api/`.
- Free signing lasts 7 days.

**Tests from the command line (optional):**

```bash
cd ios-app
xcodebuild test -project ScamGuard.xcodeproj -scheme ScamGuard \
  -destination 'platform=iOS Simulator,name=iPhone 17' -only-testing:ScamGuardTests   # 238 unit tests
```

Use any simulator name from `xcrun simctl list devices available`. More: [ios-app/README.md](ios-app/README.md).

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Every call shows "Could not check" | The server isn't running: `docker compose ps`, then `docker compose up -d`. On Android 17+, allow local network access (Android Settings → Apps → ScamGuard → Permissions). |
| The web page is blank or old | Reload the page without the cache (Shift + Reload). After changing `web/`, rebuild it: `docker compose up -d --build edge` (Kubernetes: run `demo\up.ps1` again). |
| Browser warns about the certificate at https://127.0.0.1:8443/ | Expected: the demo's own CA. Choose Advanced → Continue, or import `demo/tls/ca.crt` as trusted. |
| Settings says "MobileBERT could not be loaded" | Most private then uses the three small checks only. Reload the page; check that `https://127.0.0.1:8443/vendor/ort/ort.wasm.min.js` opens (the web image downloads onnxruntime-web when it is built, so building needs internet). |
| Port 8088 or 8080 is already in use | Stop the other program, or run `docker compose down` and start again. |
| Android Studio: "SDK location not found" | Open the `android-app` folder (not the root), or set `sdk.dir` in `android-app/local.properties`. |
| Xcode can't open the project | Xcode 27 is needed. |
| A call never turns red | Use a scam script (Choose a call script → Bank OTP, Gov official). Normal scripts stay green on purpose. |
