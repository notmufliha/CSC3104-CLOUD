# CSC3104 — Distributed Scam-Call Detection

A skeleton for a distributed scam-call detection system: an Android app
captures/simulates a call transcript, a Rust gateway fans it out via gRPC to
language-specific worker services for scoring, and an aggregator combines
the scores into a verdict.

This is a **working skeleton**, not a finished product — see the `TODO`
comments throughout for what still needs real data, trained models, or
production hardening.

## Architecture

```
Android app --HTTP/JSON--> gateway --gRPC--> urgency_{en,zh}
                              |     --gRPC--> money_{en,zh}
                              |     --gRPC--> impersonation_{en,zh}
                              `-----gRPC--> aggregator
```

- **gateway** (Rust, Actix-web + tonic): the phone's only entry point,
  `POST /v1/utterance` (see [Gateway API](#gateway-api)). For each caller
  line it picks the language, calls the three workers for that language at
  the same time over gRPC (at most 1.7 s), sends the scores that arrived to
  the aggregator and returns the call's current verdict, all within 2 s.
  Receiver lines are not scored. If a worker is slow or down, the answer
  comes back without it and lists it under `missing`.
- **aggregator** (Rust, tonic gRPC server + a small Actix-web health
  endpoint): implements `Aggregate`, a weighted sum of the worker scores
  (equal 1/3 weights to start) mapped to `SCAM` (≥0.6) / `UNSURE`
  (0.45–0.6) / `SAFE` (<0.45).
- **workers** (Python, grpcio): six independent services —
  `{urgency,money,impersonation}_{en,zh}` — each a TF-IDF +
  LogisticRegression scikit-learn pipeline behind the same
  `ClassifyTranscript` RPC. Each has its own `train.py` to (re)produce its
  pickled model from a CSV of `(text, label)` pairs.
- **android-app** (Kotlin, Jetpack Compose): an `IncomingCallScreen` with a
  "Simulate call" button that plays a scripted transcript through the
  gateway and shows the verdict as a notification-style banner.

The shared gRPC contract lives in [backend/proto/scam_detect.proto](backend/proto/scam_detect.proto).

## Running the backend locally

Requires Docker and Docker Compose.

```bash
docker compose up --build
```

This builds and starts the gateway, aggregator, and all six workers on a
shared `scamnet` network, with the gateway published at
`http://localhost:8080`.

Try it:

```bash
curl -X POST http://localhost:8080/v1/utterance \
  -H "Content-Type: application/json" \
  -d '{"call_id": "demo-1", "seq": 1, "speaker": "caller", "lang": "en", "text": "This is the police. Transfer the money to the safe account now and read me the OTP."}'
```

Run every gateway check (three scores + verdict, receiver lines, language
routing, stopping a worker, timing logs) against the running stack:

```bash
python tools/gateway_test.py
```

Replay the 14 demo calls (Workstream 6: 5 scam + 5 normal in English, 2 scam +
2 normal in Mandarin) through the gateway and compare the verdicts with what
we expect (details in [tools/testing/README.md](tools/testing/README.md)):

```bash
python tools/testing/replay.py --only scam_en_10 --verbose   # one call
python tools/testing/replay.py                               # all 14, real time, prints the results table
```

### Gateway API

`POST /v1/utterance`, one line of a call at a time:

```json
{"call_id": "abc-123", "seq": 1, "speaker": "caller", "text": "Transfer the money now", "lang": "en"}
```

| Field | Required | Meaning |
|---|---|---|
| `call_id` | yes | Same value for every line of one call (1-128 characters). |
| `seq` | no (recommended) | Line number within the call: 1, 2, 3... (caller and receiver lines share one count). Echoed back. A caller line resent with a `seq` that was already scored is not scored again (`"duplicate": true`). Lines may arrive out of order. |
| `speaker` | yes | `caller` = the person who rang (scored). Anything else (e.g. `receiver`) is not scored. |
| `text` | yes | What was said in this line (max 10,000 characters). |
| `lang` | no | `en` or `zh` (`zh-SG`, `en-US`... also work). If left out, text with more than 30% Chinese characters goes to the ZH workers, otherwise EN. |

Answer for a caller line:

```json
{"call_id": "abc-123", "seq": 1, "scored": true, "duplicate": false, "verdict": "SCAM", "combined_score": 0.79,
 "language": "en", "language_source": "phone",
 "scores": {"urgency": 0.47, "money": 1.0, "impersonation": 0.90}, "missing": [],
 "call_scores": {"urgency": 0.47, "money": 1.0, "impersonation": 0.90}, "caller_lines": 1, "latency_ms": 15}
```

- `scores`: this line's score per worker; `null` = that worker didn't answer in time, and it is listed in `missing`.
- `verdict` is for the **whole call so far**: for each signal the gateway keeps the highest score
  from the call's caller lines (`call_scores`) and sends those to the aggregator.
- A receiver line returns `"scored": false` with the call's last verdict (`null` before the first caller
  line), and no worker is called.
- A resent caller line (same `call_id` + `seq`) returns `"duplicate": true, "scored": false` with the
  call's current verdict, and no worker is called. If scoring a line fails (503/502), its `seq` is
  forgotten so the phone can retry it. The verdict uses each signal's highest score, so the order lines
  arrive in doesn't change it.
- Errors are JSON `{"error": "..."}`: 400 for a bad request, 503 if no worker answered and the call has no
  earlier scores, 502 if the aggregator failed.
- `GET /healthz` returns `{"status": "ok", "calls_in_memory": N}`. The older one-shot
  `POST /submit-transcript` (`{"transcript", "language"}`, no call memory) still works.

Gateway settings (environment variables; defaults match `docker-compose.yml`):

| Variable | Default | |
|---|---|---|
| `URGENCY_EN_ADDR`, `MONEY_EN_ADDR`, `IMPERSONATION_EN_ADDR`, `URGENCY_ZH_ADDR`, `MONEY_ZH_ADDR`, `IMPERSONATION_ZH_ADDR` | `http://<signal>_<lang>:50051` | Worker addresses. `http://` is added if missing, so `urgency-en:50051` works in Kubernetes. |
| `AGGREGATOR_ADDR` | `http://aggregator:50052` | |
| `WORKER_TIMEOUT_MS` / `AGGREGATOR_TIMEOUT_MS` | `1700` / `300` | Together they keep every answer within 2 s. |
| `CALL_TTL_SECS` / `MAX_CALLS` | `1800` / `10000` | How long and how many calls are remembered. |
| `LOG_FORMAT` | plain | `json` for one JSON object per log line. |

Logs: one `timing` line per worker call (`stage="worker" ... signal="money" ms=6 outcome="ok"`), one per
aggregator call and one per request. Transcript text is never logged. Call memory lives in the gateway
process, so with several gateway replicas all lines of a call must reach the same replica (sticky routing
on `call_id`).

The gateway and aggregator build with **Rust 1.90** (pinned in their Dockerfiles) and a committed
`Cargo.lock`, so dependency updates can't break the build.

To start only the six workers (no gateway/aggregator):

```bash
docker compose up -d --build urgency_en money_en impersonation_en urgency_zh money_zh impersonation_zh
```

Every worker has the same shape (`server.py`, `predict.py`, `text_norm.py`, `model/`,
`requirements.txt`, `Dockerfile`) and follows the same rules:

- **No model, no start.** A worker without its model exits at start-up instead of
  serving a fake score.
- **gRPC health service** (`grpc.health.v1`): `SERVING` only once the model has loaded.
  The Kubernetes template probes it.
- **Errors are errors.** If scoring fails, the worker returns gRPC `INTERNAL` and the
  gateway leaves that score out; it never sends a made-up score.
- **No transcript text in logs**, only scores.

See [Worker models](#worker-models) for each worker's version, test scores and weak spots.

### Training a worker's model

Each worker directory (`backend/workers/<signal>_<lang>/`) has its own
`train.py`:

```bash
cd backend/workers/urgency_en
pip install -r requirements.txt
# TODO: edit CSV_PATH in train.py to point at a real (text,label) dataset
python train.py        # writes model/model.pkl
```

Rebuild that worker's image (or restart it with a volume-mounted
`model/model.pkl`) to pick up the new model.

#### urgency_en

`urgency_en` has its own training setup. **Train with scikit-learn 1.4.2**,
the same version the worker's `requirements.txt` pins: a joblib model
generally only loads in the scikit-learn version that wrote it. 1.4.2 has no
wheels for Python 3.13+, so use Python 3.11 or 3.12:

```bash
pip install -r backend/workers/urgency_en/requirements-train.txt
python backend/workers/urgency_en/train.py          # -> model/urgency_en.joblib + reports/
python backend/workers/urgency_en/evaluate_gold.py --gold backend/workers/urgency_en/eval/gold_handwritten.csv
```

The deployed model is the frozen `model/urgency_en_v4.joblib` (allowed past the
`.gitignore` rule so it is committed; checksum and thresholds in `model/v4.json`). `train.py` writes `model/urgency_en.joblib`, which is
git-ignored, so a retrain never overwrites v4: freeze a new version explicitly and point
the Dockerfile at it. Training data lives in `data/urgency_en/`; older cleaning-history
files are in `data/urgency_en/archive/`, and the MiniLM experiments in
`backend/workers/urgency_en/research/`.

#### urgency_zh, money_zh, impersonation_zh

The three Chinese workers share **one** cleaning file,
`backend/workers/common_zh/text_norm.py` (plus one gold set,
`common_zh/gold_test_zh.csv`); each ZH Dockerfile copies it into the image. It
lowercases Latin words, and only `impersonation_zh` splits words with jieba
(`urgency_zh`/`money_zh` read characters), because that is how the models were
trained. Each ZH image **trains its model during `docker build`** from the CSV in the
worker's `data/` folder (~1 min), so the model always matches the image's
scikit-learn version. Local check without gRPC:

```bash
cd backend/workers/urgency_zh && python predict.py "我是公安局的警官，请马上转账"
```

#### impersonation_en

`impersonation_en` also has its own training setup, and — unlike every other
worker — its trained model is **committed to git**:
`backend/workers/impersonation_en/model/model.pkl` is force-added past the
`.gitignore` rule that excludes `*.pkl`/`*.joblib` elsewhere, so the worker
runs with `docker compose up --build` and no training step. See
[backend/workers/impersonation_en/README.md](backend/workers/impersonation_en/README.md)
for what it scores, how it works, and why its model file needs
`text_utils.py` importable to unpickle at all.

If you change `data/impersonation_en/*.csv` and need to retrain:

```bash
pip install -r backend/workers/impersonation_en/requirements.txt
python backend/workers/impersonation_en/train.py          # -> model/model.pkl + model_card.json + reports/
python backend/workers/impersonation_en/evaluate_gold.py  # re-check the saved model against the gold set
```

**Train with scikit-learn 1.6.1**, the version pinned in its
`requirements.txt` — a joblib model generally only loads in the scikit-learn
version that wrote it. After retraining, `model/model.pkl` is a tracked file
here (not git-ignored), so commit it like any other changed file.

## Worker models

One line per worker. Test scores are from the running containers (2026-10-18) for one
scam and one normal sentence. EN: *"This is the police. Transfer the money to the safe
account now and read me the OTP."* / *"Hi, this is the clinic calling to confirm your
appointment on Tuesday."* ZH: *"我是警察，你必须马上把钱转到安全账户，并告诉我验证码。"* /
*"你好，这里是诊所，打电话确认你星期二的预约。"* Each worker returns a raw 0–1
probability; "threshold" is the model's own cut-off.

| Worker | Version | Test score (scam / normal) | Known weak spots |
|---|---|---|---|
| urgency_en | v4, TF-IDF + LR (sklearn 1.4.2), frozen 2026-10-13, threshold 0.368 | 0.470 / 0.365 | Passes 2/8 urgency goals. Many false alarms: "no rush", banks' anti-scam advice, normal time talk ("bill due on the 15th"). Misses pressure with no keywords. The normal test sentence sits just under the threshold. |
| money_en | money_en_v1, word + char TF-IDF + LR, Platt-calibrated (sklearn 1.6.1), created 2026-10-15, threshold 0.5 | 1.000 / 0.000 | Scores **any** mention of money or OTPs, so a genuine "your bill is due" also scores high (by design; the aggregator separates scams). Validation F1 0.988 is on its own data split, so expect lower on real calls. Training notebook isn't in the repo yet (`train.py` is the old template), so the model can't be reproduced. |
| impersonation_en | impersonation_en_v2, TF-IDF + char n-grams + LR, calibrated (sklearn 1.6.1), threshold 0.47 | 0.941 / 0.011 | Trained and tested only on synthetic, advisory-based rows (~510 dev rows); performance on real calls is unknown. |
| urgency_zh | trained at image build (char TF-IDF + LR, calibrated, C=100), threshold 0.20 | 0.481 / 0.019 | Low threshold gives many false alarms: gold precision 0.46 at 0.20 (0.63 at 0.5). Retrained on every build, so not version-pinned. |
| money_zh | trained at image build (char, C=100), threshold 0.20 | 0.993 / 0.011 | Gold precision 0.61 at 0.20 (0.94 at 0.5). Retrained on every build. |
| impersonation_zh | trained at image build (jieba words, C=100), threshold 0.38 | **0.132 / 0.496** | **Fails this test**: missed the police scam and flagged the clinic call (same result with the original, unmodified code, so it's the model). Gold precision 0.70 at 0.38. Retrained on every build. |

## Pointing the Android app at the backend

The app's base URL is set in
[android-app/app/src/main/java/com/csc3104/scamdetect/network/RetrofitClient.kt](android-app/app/src/main/java/com/csc3104/scamdetect/network/RetrofitClient.kt):

- **Android emulator** against a `docker compose up` running on the same
  machine: the default, `http://10.0.2.2:8080/`, already works —
  `10.0.2.2` is the emulator's alias for the host's `localhost`.
- **Physical device**: change `BASE_URL` to your host machine's LAN IP,
  e.g. `http://192.168.1.23:8080/`, and make sure the device is on the same
  network and nothing (firewall, VPN) blocks port 8080.

Open `android-app/` in Android Studio, let Gradle sync, and run the `app`
configuration. The `IncomingCallScreen` composable previews (with fake
verdict data) are visible in the Compose Preview pane without needing to
run the app or the backend.

## Kubernetes

[k8s/](k8s/) has `.tmpl` templates (Deployment, Service, HPA) for a single
worker, parameterised by `${SIGNAL}` and `${LANGUAGE}` — copy/substitute
per worker (see the comments at the top of each file for an `envsubst`
example). The gateway and aggregator aren't templated here yet — TODO.

## Repo layout

```
backend/
  proto/scam_detect.proto       shared gRPC contract
  gateway/                      Rust + Actix-web HTTP front door, gRPC fan-out
  aggregator/                   Rust gRPC Aggregate endpoint
  workers/
    urgency_en/ urgency_zh/
    money_en/ money_zh/
    impersonation_en/ impersonation_zh/
    common_zh/                  ONE shared ZH text_norm.py + gold set, copied into each ZH image
data/                            training data per worker (data/urgency_en/archive/ = cleaning history)
tools/                           datagen, eval helpers, zh_check (try the 3 ZH workers interactively)
tools/testing/                       demo-call replay + expected verdicts + window experiment 
android-app/                    Kotlin + Jetpack Compose client
k8s/                             Deployment/Service/HPA templates
docker-compose.yml               local dev stack
```
