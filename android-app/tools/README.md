# android-app/tools

## On-device models (Workstream G.2)

The phone runs the same English urgency, money and impersonation models as the server workers. They are bundled
in `app/src/main/assets/models/` (`*_en.json` + `manifest.json` with versions and the sha256 of the source model).
There is no model download service (G.10) yet, so the bundled versions are what the app uses.

### Format: JSON, not ONNX

ONNX was tried first (`try_onnx.py`) and doesn't work for these models:

| Model | skl2onnx result |
|---|---|
| urgency_en | converts, but ONNX Runtime can't load it: `invalid perl operator: (?u` (the vectoriser's token pattern) |
| money_en | `NotImplementedError`: character n-grams (`analyzer="char_wb"`) are not supported |
| impersonation_en | same as money_en |

Also, each worker cleans text with its own custom Python regex before the model sees it. ONNX can't hold that, so
it has to be ported to Kotlin in any case. So each model is exported as plain numbers (vectoriser settings,
vocabulary, idf, logistic regression weights, Platt calibration), and the app scores with a dot product and a
sigmoid (`app/src/main/java/.../ondevice/`). No extra library is needed, and the APK grows by about 3.4 MB of JSON
(compressed in the APK).

### Re-export after a worker model changes

The pickles only load in the scikit-learn version that saved them, so there are two virtualenvs:

```bash
python3.12 -m venv /tmp/venv14 && /tmp/venv14/bin/pip install scikit-learn==1.4.2 numpy==1.26.4 joblib==1.4.2 scipy
python3.12 -m venv /tmp/venv16 && /tmp/venv16/bin/pip install scikit-learn==1.6.1 numpy==2.1.3 joblib==1.6.0 pandas==2.2.2 scipy

/tmp/venv14/bin/python android-app/tools/export_models.py urgency
/tmp/venv16/bin/python android-app/tools/export_models.py money
/tmp/venv16/bin/python android-app/tools/export_models.py impersonation
```

### Parity test (scores within 0.01)

`parity_cases.py` scores 300 sentences with each worker's own `predict.score()` and saves the cleaned text and
score to `app/src/androidTest/assets/parity/parity_cases.json`. The sentences are every caller line of the English
demo scripts, the 200-sentence robocall set and 30 edge cases.

```bash
/tmp/venv14/bin/python android-app/tools/parity_cases.py urgency
/tmp/venv16/bin/python android-app/tools/parity_cases.py money
/tmp/venv16/bin/python android-app/tools/parity_cases.py impersonation

cd android-app
bash gradlew testDebugUnitTest --tests '*OnDeviceParityTest*'      # JVM
bash gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.scamdetect.OnDeviceParityDeviceTest   # phone
```

Both tests fail if any score differs by more than 0.01 or any cleaned text differs. Last run (2026-11-05, Pixel 8
emulator, API 37): 300 sentences × 3 models, largest difference < 0.000001 on both the JVM and the phone.

If a worker's cleaning file changes (`urgency_en/text_norm.py`, `money_en/money_norm.py`,
`impersonation_en/text_utils.py`), change the matching port in `ondevice/TextCleaning.kt`, then re-run the export
and the parity cases.

## MobileBERT on the phone (Workstream G.4)

Level 3 can ask MobileBERT for a second opinion on the phone, under the same rule as Hybrid asks the cloud: only
when the phone's own score is ≥ 0.45 or on every 5th caller line, never once the call is red. It reads the caller's
last 5 lines (not masked, because they never leave the phone) and its score is blended in exactly as the aggregator
blends the cloud BERT (`ondevice/LocalRules.kt`). **No model is bundled yet**, so Settings shows "MobileBERT not
installed" and level 3 uses the small models only. Training and export are Goutham's.

### What the exported model must look like

Put these files in `app/src/main/assets/models/mobilebert/`:

| File | Required | Contents |
|---|---|---|
| `model.onnx` | yes | Fine-tuned MobileBERT, ONNX (INT8 is fine), opset ≤ 21 (ONNX Runtime Android 1.20.0) |
| `vocab.txt` | yes | The WordPiece vocabulary it was trained with: one token per line, line number = id, containing `[CLS]`, `[SEP]`, `[UNK]`. If you only have `tokenizer.json`, write `model.vocab` out in id order. |
| `bert_config.json` | recommended | `{"version": "...", "max_len": 128, "temperature": 1.0, "scam_index": 1, "max_turns": 5, "lower_case": true, "model_file": "model.onnx"}`. Every key is optional; the values shown are the defaults. |

Model interface:

| | Name | Type | Shape |
|---|---|---|---|
| input | `input_ids` | int64 | `[1, n]` (dynamic `n`, at most `max_len`) |
| input | `attention_mask` | int64 | `[1, n]`, all 1 (no padding) |
| input | `token_type_ids` | int64 | `[1, n]`, all 0 (optional: only fed if the model declares it) |
| output | the first output (`logits`) | float32 | `[1, 2]` |

Scoring matches the server's BERT (`backend/workers/bert/predict.py`):
- **Text:** the caller's last `max_turns` lines, each NFKC-normalised with whitespace collapsed, joined with `" | "` (`bert_text.make_window`). Train the model on windows built the same way.
- **Tokens:** `[CLS]` + WordPiece + `[SEP]`, lowercased and accent-stripped when `lower_case` is true (use `google/mobilebert-uncased`'s vocab for an uncased model). When the window is longer than `max_len`, the **oldest** tokens are dropped (left truncation, `direction="left"`), so the newest line is always read.
- **Score:** `softmax(logits / temperature)[scam_index]`. Fit the temperature on the validation split, as the server model does: the score is averaged with the phone's own vote, so it should be a fair probability.

### Checking a new model

```bash
# the loader, tokenizer and ONNX plumbing, with a tiny dummy model (no real model needed)
python android-app/tools/mobilebert_fixtures.py          # pip install tokenizers onnx onnxruntime numpy
cd android-app
bash gradlew testDebugUnitTest --tests '*WordPieceTokenizerTest*'
bash gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.scamdetect.MobileBertDeviceTest
```

With a real model in `assets/models/mobilebert/`, `MobileBertDeviceTest.theAppItselfSaysNotInstalled` will
(correctly) fail: delete that test, then run the benchmark below for its speed and accuracy.
`WordPieceTokenizerTest` checks the phone's tokenizer against Hugging Face `tokenizers`, id by id.

**APK size:** `onnxruntime-android` bundles native libraries for 4 CPU types (about 17.6 MB for arm64 alone), so
the debug APK grew from 16.5 MB to about 90 MB. A phone only installs one of them; add
`ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` (phone + emulator) or ship an app bundle to shrink it.

## Benchmark (Workstream G.5)

`app/src/androidTest/.../BenchmarkTest.kt` measures every privacy level on the device it runs on and prints a
Markdown report (tag `G5` in logcat):
- **Model size:** the three small models (and MobileBERT, once bundled).
- **Time per sentence:** median and p95 over the 200 robocall sentences (and per check).
- **App memory:** PSS before and after loading, and the peak while scoring.
- **Accuracy per level**, on two sets:
  - **Robocall set** (`backend/workers/urgency_en/eval/robocall_gold.csv`, copied to `androidTest/assets/benchmark/robocall.json` by `benchmark_data.py`). Its `label` is the urgency worker's label (1 = urgent), partly AI-labelled and marked provisional in `urgency_en/LABELING.md`. So this F1 means "warns on urgent robocall sentences", not scam detection.
  - **The 14 demo calls** (`app/src/main/assets/scripts`, Workstream 6), played with their own timing. Correct = a scam call ends UNSURE/SCAM and a normal call ends SAFE. Chinese calls are "not checked" at levels 2 and 3 (English-only models).
- Levels 1 and 2 run only if the server at `serverUrl` answers `/healthz`.

Run it (emulator or a real phone over USB, with the backend running):

```bash
adb logcat -c
cd android-app
bash gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.scamdetect.BenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.serverUrl=http://10.0.2.2:8080/
adb logcat -d -s G5:I -v raw > benchmark.md
```

On the Galaxy S25, use the laptop's address instead of `10.0.2.2`, e.g. `serverUrl=http://192.168.1.23:8080/`
(docker compose, same Wi-Fi), or leave the server out for level 3 only. Gradle uninstalls the app after the run,
which is why the report is read from logcat. Reinstall it with `bash gradlew installDebug`.

The bundled small models are the pre-merge ones (money_en v1, impersonation_en "v2" = the v3 file, urgency v4).
After merging `origin/main`, re-export them (see above) and re-run the benchmark: the numbers will change.
