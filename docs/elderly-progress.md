# Elderly-friendly work: progress note (not for commit)

Branch `elderly-friendly`, 2026-11-18.

## Build status

| App | Build | Unit tests |
|---|---|---|
| Android | OK (`assembleDebug`) | 137 run, 5 skipped, 0 failed |
| iOS | OK (`xcodebuild test`) | 187 run, 1 skipped, 0 failed |

Baseline before the work: Android 119 (5 skipped), iOS 163 (1 skipped).

## Phases

| Phase | Status | Notes |
|---|---|---|
| 1 The warning | Done | Full-screen red warning (sound on alarm stream / `.playback`, vibration, read aloud, gentle flash), Hang up / Call my family / Call 1799 / Tell [name] / Close; quiet yellow banner; icon + word for each risk colour |
| 2 Seeing and touching | Done | Body 20 / buttons 22 / titles 28+, "Extra large" option, 56 dp targets, contrast, scroll at the largest font sizes |
| 3 Simple mode | Done | Simple Home, For family tab, short Settings + Advanced, 3-screen setup, 3-step tour, existing users offered once |
| 4 Family and caregivers | Done | Helper setup path, trusted contact (dial / prefilled SMS), optional PIN (Android DataStore hash, iOS Keychain), Forgot PIN |
| 5 Language | Removed (2026-11-18) | English only. The language picker, saved app language, per-language voices, the "check calls in other languages online" consent and the Home language note were removed from both apps, with the zh/ms/ta strings and `docs/translation-review.md`. All wording is still kept in one English table: `tools/i18n/strings/*.json` → `python3 tools/i18n/gen_strings.py` writes `values/strings.xml`, `Localizable.xcstrings` and `L10nKeys.swift` |
| 6 Trust and privacy wording | Done | Privacy promise on Welcome and Help, plain yes/no consents with equal buttons, About section |
| 7 Check and document | Done (2026-11-18) | Tests (Android 137, iOS 187, all pass). `docs/usability-test-plan.md`. Screenshots re-shot English only: 9 screens × light/dark × default/largest text = 36 per app in `android-app/build/elderly-shots/` and `ios-app/build/elderly-shots/` (`{theme}_{size}_{screen}.png`). Review pass 2 fixes: iOS custom tab bar (labels grow with text size), red warning flashes an edge frame instead of washing out the text (both apps), iOS red warning pins Hang up at very large text, iOS call header capped so the yellow message fits, Android Text size icon |

Tests added: Android `WarningsTest`, `PinTest`, rewritten `OnboardingTest` (+ `TestStrings` helper);
iOS `WarningsTests` (+ call-flow warning tests), `PinTests`, rewritten `OnboardingTests`. The language tests
(`TranslationTest` / `TranslationTests`) were removed with part 5; their English checks (a label for every reason code,
plain warning wording) moved into `WarningsTest(s)`.

## Status

Parts 1-4, 6 and 7 done; part 5 (languages) removed. Parts 1-6 committed as c179eb4; the part 7 fixes are not
committed yet. Screenshot scripts were in the session scratchpad (shots_android.sh / shots_ios.sh): Android uses
`--es demo*` extras (see `DebugLaunchOptions` in MainActivity.kt), iOS `-demo*` launch arguments (ScamGuardApp.swift).

Known limitations: English only; on-device checking (Hybrid / On-device) understands English only; calls are
simulated (scripted practice calls, no real phone calls); iOS 17 or later. At the very largest text sizes the setup
pages scroll (text continues under the buttons). The iOS tab bar labels stop growing at accessibility2 (a long press
shows them bigger).
