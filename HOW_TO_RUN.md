# How to run ScamGuard

ScamGuard can be tried in three ways: the **web version** (no install), the **Android app** (Android Studio) and
the **iPhone app** (Xcode, Mac only). All three use the same ScamGuard server, which runs on your computer with
Docker. Start with step 1, then choose any of the three ways.

| Way | You need | Time |
|---|---|---|
| [Web version](#2-web-version-no-install) | Docker Desktop and a browser (computer or phone) | about 15 min (first build), then 1 min |
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

---

## 2. Web version (no install)

Open **http://127.0.0.1:8088/** in Chrome, Edge, Safari or Firefox. On a computer the app appears inside a phone
frame (untick **Show phone frame** at the top right to hide it; the switch next to it picks light or dark). It is a
replica of the iPhone app: the same screens, wording and look, and the same three privacy levels.

**From a phone** (on the same Wi-Fi or hotspot as the laptop): find the laptop's address (macOS:
`ipconfig getifaddr en0`; Windows: `ipconfig`) and open `http://<that address>:8088/`. It fills the screen; **Share →
Add to Home Screen** makes it open like an app. There, **Call my family**, **Tell my family** and **Call 1799** open
the phone's own Phone and Messages apps.

**What to try:**

1. The first screen asks **"Who will use this phone?"**. **Me** gives the full app; **Me, but simpler** gives Simple
   mode; **An older family member** sets it up for someone else (text size, family contact, optional PIN).
2. **Home → Simulate scam call** (or **Choose a call script… → Bank OTP**), then **Answer**.
   - The risk bar fills as each caller line is checked; the warning signs light up.
   - After a few lines the screen turns red: **"This may be a scam"** (with a sound, and read aloud if that is on),
     with **Hang up**, **Call my family**, **Call 1799 ScamShield Helpline**, **Tell (name)** and **Close this warning**.
3. Tap **Hang up**. The **Call summary** shows the result and **"What to do now"** (Report to ScamShield, Call 1799,
   Tell my family, How to block this number, If I already shared details).
4. **Simulate normal call**: stays green, with no warning.
5. **Settings → How calls are checked**: **Balanced** (Hybrid) and **Most private** (On-device) check the call
   inside the browser with the same small models as the phones; Most private sends nothing at all.
6. **Learn to spot scams**: 10 common scam types and a 5-question practice quiz.
7. **Dashboard**: results, recent calls, Insights charts, History, each call's details, and **Share weekly summary**.
8. **Settings → Help → tap "Version 1.0" seven times** unlocks the **Developer view**: server address, models, the
   last call's scores, the request log (caller text masked), live scores during calls, playback speed, sample
   history, and **Reset app to first launch**.

**Known limits of the web version:**
- On a computer, **Call** and **Tell my family** can't call or text; the app says what a phone would do. The demo
  video shows them on a phone.
- No MobileBERT (the phone's large second-opinion model); **Most private** uses the three small models only.
- The weekly reminder works only while the page is open, and only over HTTPS or on `127.0.0.1`.
- Over plain `http://<laptop address>:8088/` browsers turn off sharing, so **Share** becomes **Copy text**.
- History, settings, the family contact and the PIN are kept in this browser only (nothing is uploaded).

If the page says it can't connect, check that step 1 is running. The web version is also served by the
Kubernetes demo at https://127.0.0.1:8443/ (see README.md, section B). More: [web/README.md](web/README.md).

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
| The web page is blank or old | Reload the page without the cache (Shift + Reload). |
| Port 8088 or 8080 is already in use | Stop the other program, or run `docker compose down` and start again. |
| Android Studio: "SDK location not found" | Open the `android-app` folder (not the root), or set `sdk.dir` in `android-app/local.properties`. |
| Xcode can't open the project | Xcode 27 is needed. |
| A call never turns red | Use a scam script (Choose a call script → Bank OTP, Gov official). Normal scripts stay green on purpose. |
