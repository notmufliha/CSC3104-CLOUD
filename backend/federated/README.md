# Workstream G: federated learning and on-device model updates

## In simple words

The phone can check a call for scam signs by itself, with three small models: **urgency** ("act now or else"),
**money** ("read me the OTP") and **impersonation** ("this is the police"). G makes those models get better over
time **without anyone's calls leaving their phone**:

1. Each phone learns a little from its own calls (for example, the user hung up right after a red warning).
2. It sends the cloud only the *changes to the model's numbers*, never the words of the call.
3. The cloud averages the changes from many phones into one improved model.
4. It tests that model on fixed, human-checked examples. If it's worse than the current one, it is thrown away.
5. If it's at least as good, it's published as a new version, and every phone downloads it the next time the app
   starts.

In this project the "many phones" are 10 simulated phones (pods in Kubernetes, or processes on a laptop), each
holding a different slice of the training data. The real app does the download step (5) for real.

## What is in this folder

| Task | What | Where |
|---|---|---|
| G.1 | Hashing versions of all three phone models, so phones' numbers line up and can be averaged | `features.py`, `specs.py`, `train_hashing.py` |
| G.6 | Flower server + 10 simulated phones, each with a different (non-IID) slice of each model's data | `server.py`, `client.py`, `prepare.py` |
| G.7 | 10 rounds; every model scored on its fixed test sets after every round, vs the central model | `server.py`, `plot_rounds.py` |
| G.8 | Clipping + noise (Flower's `DifferentialPrivacyClientSideFixedClipping`), accuracy cost | `server.py`, `fedcore.py` |
| G.9 | Release check per model: the code H.7 uses (`backend/retrain/release_check.py`) | `server.py` |
| G.10 | Publish to H's registry as `<signal>_en_phone` vN; read-only download service; the app downloads, verifies and uses them | `registry.py`, `download_service.py`, `export_phone.py`, app `data/ModelUpdater.kt`, `ondevice/Murmur3.kt`, `ondevice/LinearTextModel.kt` |

Not done: G.11 (distillation, stretch). The Chinese models are not on the phone (the brief scopes G to English),
so they are not part of the federated loop.

## How it works

```
phone i (pod)                         fl-server (pod)                         review (H registry)   model-download
  slices of urgency, money,             round r: send the 3 global models ──►
  impersonation data             ◄───
  train each locally                    FedAvg (each phone counts once)
  clip whole change to norm 10  ───►    + noise std = σ·10/10 (G.8)
                                        score every test set (G.7)
                                        per model: release check (G.9)
                                        pass → publish <signal>_en_phone vN ───►  stored, versioned ◄── reads (cache)
                                                                                                        ▲
                                                           phone app on start-up: GET /models/manifest ─┘
                                                           download newer files, check SHA-256, use them
```

- **Why hashing (G.1).** TF-IDF learns its word list from the data it sees, so two phones would have two different
  lists, and number 1234 would mean a different word on each. Hashing maps every word (or character group) to a fixed
  slot with a hash function (MurmurHash3), so slot 1234 is the same on every phone. The IDF weights and each model's
  calibration are fitted once by the cloud on public training data, then frozen; phones only train the weights.
- **Each model keeps its worker's recipe** (`specs.py`): urgency's cleaning, relabelling and source weights; money's
  brand masking, train/calibrate split and 10x hand-written rows; impersonation's caller-only cleaning, noisy copies,
  word + character n-grams and out-of-fold calibration. Only the vectoriser changes.
- **Uneven phones (G.6).** For every data source, the share each phone gets is drawn from a Dirichlet(0.5)
  distribution, the standard way FL papers simulate real users. Example: urgency phone 8 has 25 urgent lines out of
  1,132; phone 4 has 682 out of 2,102.
- **Two kinds of run.** From empty models (`--init zeros`, the G.7 chart): learn fast (lr 20, 3 local passes).
  A weekly update from the phones' current models (`--init current`): small steps (lr 1, 1 pass). The server sends
  these settings to the phones each round.
- **The phone (G.10).** `ModelUpdater` asks `<server>/models/manifest` when the app starts (and from Settings),
  downloads any newer version, checks its SHA-256 and that it loads, then swaps it in. `OnDeviceScorer` prefers a
  downloaded model and falls back to the built-in one. `LinearTextModel` scores the hashing format with the same
  hash as scikit-learn (`Murmur3.kt`). Only model files come down; nothing about calls goes up.

## Results (laptop, 2026-11-11)

G.1, ROC-AUC on each worker's fixed test sets (`reports/g1_hashing_vs_tfidf.csv`), goal: gap ≤ 0.02. **Met.**

| Model | Test set | TF-IDF (deployed) | Hashing | Gap |
|---|---|---|---|---|
| urgency | gold_handwritten (59) | 0.8228 | 0.8228 | 0.000 |
| urgency | robocall_gold (181) | 0.9229 | 0.9144 | 0.009 |
| money | sg_gold (60) | 0.8522 | 0.8411 | 0.011 |
| money | test_set (200) | 0.7808 | 0.7990 | hashing better |
| money | ftc (1,544) | 0.6969 | 0.7225 | hashing better |
| impersonation | gold_test (622) | 0.9878 | 0.9867 | 0.001 |

G.7 / G.8, after 10 rounds from empty models, all three models trained together (`results/rounds.png`):

| Run | urg. hand. | urg. robo. | money sg | money test | money ftc | imp. gold |
|---|---|---|---|---|---|---|
| central model (G.1) | 0.823 | 0.914 | 0.841 | 0.799 | 0.723 | 0.987 |
| FedAvg, no noise | 0.817 | 0.919 | 0.850 | 0.799 | 0.718 | 0.983 |
| noise 0.1, clip 10 | 0.795 | 0.904 | 0.809 | 0.769 | 0.682 | 0.899 |
| noise 0.3, clip 10 | 0.802 | 0.796 | 0.731 | 0.759 | 0.644 | 0.750 |
| noise 1.0, clip 10 | 0.643 | 0.587 | 0.602 | 0.550 | 0.573 | 0.591 |

G.9 / G.10, a weekly round from the published models (`results/weekly_check.json`): money and impersonation passed
and were published as new versions; urgency was blocked (slightly below the bar on one set) and stays at v1.

Phone parity (`HashingParityTest`): the app's Kotlin scorer gives the same score as Python on 317 sentences for
all three downloaded models (largest difference < 0.0000001).

What to say in the report:
- Without noise, 10 rounds match the centrally trained models (within 0.006 on every test set, sometimes above),
  while no phone shared a line.
- Noise costs accuracy quickly with only 10 phones, because the noise is divided by the number of phones; real
  systems use hundreds or thousands per round. Noisy runs vary from run to run (random noise): run each setting 3
  times and report the mean and spread.
- The release check is doing its job: it blocks candidates that are worse on the fixed test sets (urgency in the
  weekly round), so a bad round never reaches phones.
- Honest limits: no secure aggregation (Flower's SecAgg+ needs the newer ServerApp API); the server adds the noise,
  so it must be trusted to; no privacy budget (epsilon) computed; phones are simulated, and the real phone does not
  train yet (its implicit labels, G.12, are what it would train on).

## How to check it

### 1. On the laptop (Python 3.12, ~10 minutes)

```bash
uv venv --python 3.12 .venv-g && uv pip install --python .venv-g flwr==1.12.0 scikit-learn==1.4.2 numpy==1.26.4 \
    scipy==1.13.1 pandas==2.2.2 joblib==1.4.2 matplotlib -r backend/review/requirements.txt
# Windows: use .venv-g\Scripts\python.exe below
python backend/federated/train_hashing.py      # G.1 table for all three; ends "G.1 goal ... MET"
python backend/federated/prepare.py            # G.6: rows per phone for each model (uneven)
python backend/federated/run_local.py          # G.6/G.7: 10 rounds; six scores per round, climbing from 0.5
python backend/federated/run_local.py --noise 0.1 --port 8082      # G.8
python backend/federated/plot_rounds.py        # results/rounds.png
python backend/federated/phone_parity.py       # then, in android-app/: gradlew testDebugUnitTest
```

### 2. On your phone, quick way (no Kubernetes)

The laptop runs the registry and the download service; the phone downloads the models over Wi-Fi.

1. Install the new app on the phone: plug it in with USB debugging on, then Android Studio > Run (or
   `cd android-app && gradlew installDebug`).
2. Laptop, terminal 1: `python backend/federated/local_stack.py`. It prints an address like
   `http://192.168.1.23:8000/`. If Windows asks, allow Python on private networks.
3. Laptop, terminal 2: `python backend/federated/run_local.py --publish --init current --rounds 3`.
   The first run publishes v1 of all three models, then runs a weekly round: watch for `PUBLISHED` / `BLOCKED`.
4. Phone (same Wi-Fi): Settings > server address = the printed address > Save > **Check for model updates**.
   Expect "Updated: urgency v1, money v2, impersonation v2" (or similar), and under "On-device models" each line
   says "federated vN (downloaded ...)".
5. Settings > Privacy level > **On-device**. Turn on **airplane mode**. Home > Simulate scam call: the warning still
   appears; it comes from the downloaded models, with nothing sent anywhere.
6. Run step 3 again, tap Check again: models that passed the release check move to the next version; a blocked
   one keeps its version.

With this address the cloud levels don't work (there is no gateway on port 8000); set the gateway address back
afterwards.

### 3. On your phone, full demo (minikube)

```bash
demo/up.ps1 ; demo/expose.ps1                  # main demo + the phone's HTTPS address (Workstream E)
python demo/tools/federated.py build           # scam/federated, scam/model-download, scam/review images
python demo/tools/federated.py up              # model-download (2 pods) + Ingress /models
python demo/tools/federated.py run --init current --publish --rounds 3   # 1 server + 10 phone pods
python demo/tools/federated.py status          # registry versions + the manifest phones see
```
Phone: keep the saved address `https://<laptop IP>:8443/api/` (the app derives `/models/` from it), tap **Check
for model updates**, then do steps 5-6 above. Delete a phone pod mid-run
(`kubectl delete pod -n scamguard-fl <fl-phones-3-...>`): the Indexed Job restarts it with the same data slice
and the round continues. A run uses about 2 GB; use the lite demo on the 8 GiB Docker setup.
