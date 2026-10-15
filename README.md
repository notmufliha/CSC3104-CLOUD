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

- **gateway** (Rust, Actix-web + tonic): exposes `POST /submit-transcript`,
  validates/picks the language, fans the transcript out concurrently to the
  three signal workers for that language over gRPC (each call bounded by a
  2000ms timeout), then forwards whatever scores came back to the
  aggregator and returns its verdict as JSON. If a worker is slow or down,
  the gateway proceeds with the partial set of scores rather than blocking
  the whole request.
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
curl -X POST http://localhost:8080/submit-transcript \
  -H "Content-Type: application/json" \
  -d '{"transcript": "Your account will be suspended, transfer money now", "language": "en"}'
```

**Note:** until you run each worker's `train.py` against a real dataset
(see below), `model.pkl` won't exist and each worker falls back to a dummy
classifier that always returns a neutral 0.5 score — the pipeline is
end-to-end functional, just not meaningfully trained yet.

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

`*.joblib` and `reports/` are git-ignored, so train before building the image.

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
android-app/                    Kotlin + Jetpack Compose client
k8s/                             Deployment/Service/HPA templates
docker-compose.yml               local dev stack
```
