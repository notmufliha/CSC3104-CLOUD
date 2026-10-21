# impersonation_en

The English-language worker for the **impersonation** signal: it reads a call
transcript and returns a score from 0 (not impersonation) to 1 (impersonation)
for *"the caller is falsely claiming to be an authority or trusted
organisation"* — police, government, bank, telco, courier, or tech support.

It's one of six workers the gateway fans a transcript out to (see the
[top-level README](../../../README.md#architecture)): `{urgency,money,impersonation}_{en,zh}`.
The aggregator combines this worker's score with the other two signals for the
same language into the final verdict.

## TL;DR for teammates

This worker is **already trained and ready to run** — unlike some of the other
worker stubs, you don't need to run anything before `docker compose up --build`.
The trained model is checked into [`model/model.pkl`](model/model.pkl) (an
exception to how the other workers handle their model files — see
[Why the model file is committed here](#why-the-model-file-is-committed-here)
below).

```bash
docker compose up --build impersonation_en
```

Or standalone, from `backend/`:

```bash
docker build -f workers/impersonation_en/Dockerfile -t impersonation_en .
docker run --rm -p 50051:50051 impersonation_en
```

It speaks gRPC (`WorkerService.ClassifyTranscript`, see
[`backend/proto/scam_detect.proto`](../../proto/scam_detect.proto)), not plain
HTTP — use the gateway's `/submit-transcript` endpoint to try it end-to-end, or
see [Calling it directly](#calling-it-directly) below to hit it on its own.

## How it works

A scikit-learn pipeline, not a neural net or LLM call — small, fast, and easy
to run offline:

1. **Clean the text** — strip speaker tags/brackets, lowercase, collapse long
   digit runs to a placeholder (so the model can't just memorise phone/account
   numbers).
2. **Caller-only** — drop everything the *receiver* says, so the model is
   judging what the caller claims, not how the victim reacts.
3. **Brand masking** — replace specific org names ("DBS", "SPF", "IRAS"...)
   with their *type* (`orgbank`, `orgpolice`, `orggovt`...), so the model
   learns "claims to be a bank/police/government body" instead of memorising
   a short list of brand names it happened to see in training.
4. **TF-IDF features** — word 1–2-grams plus character 3–5-grams (the
   character n-grams help with typos / speech-to-text errors).
5. **Logistic Regression** — a linear classifier on those features.
6. **Calibration** — a small Platt-scaling step on top, so a score of 0.8
   behaves like "about 80% likely", not just a raw ranking number. The
   aggregator needs real probabilities, not just a ranking, to combine scores
   from three different workers.

All of this (steps 1–3, plus the calibration wrapper) lives in
[`text_utils.py`](text_utils.py) — see
[Why text_utils.py has to ship with the model](#why-text_utilspy-has-to-ship-with-the-model).

The reporting threshold used while evaluating is **0.47** (chosen on
cross-validated scores, not on the test/gold sets) — but the gateway and
aggregator consume the *raw* score, not a yes/no label.

## File layout

```
impersonation_en/
  server.py                 gRPC service — loads model/model.pkl, exposes ClassifyTranscript
  train.py                  reproduces model/model.pkl from the CSVs in data/impersonation_en/
  evaluate_gold.py          scores a saved model against the gold test set, no retraining
  text_utils.py             text cleaning + the CalibratedModel class (see below — required, not optional)
  model_card.json           what the current model/model.pkl actually is: features, threshold, metrics
  model/model.pkl           the trained model itself (checked into git for this worker only)
  requirements.txt          pinned to the exact scikit-learn/joblib/numpy the model was pickled with
  Dockerfile                builds the worker image (build context must be backend/, see docker-compose.yml)
  Impersonation_Worker_EN_Training.ipynb   the original training/analysis notebook (full diagnostics:
                                            CV tuning table, brand-masking robustness check, coefficient
                                            audit, bootstrap confidence intervals, learning curve — more
                                            than train.py prints, useful for the project report)
  training_outputs/         plots + misclassified-row CSVs from that notebook run, for the report
```

Training data lives at the repo level, alongside the other workers' datasets:
[`data/impersonation_en/impersonation_data.csv`](../../../data/impersonation_en/impersonation_data.csv)
(training rows) and
[`data/impersonation_en/gold_test.csv`](../../../data/impersonation_en/gold_test.csv)
(held-out final test rows — never used for tuning).

## Retraining

Only needed if you're adding/changing training data. From this directory:

```bash
pip install -r requirements.txt
python train.py              # writes model/model.pkl + model_card.json + reports/
python evaluate_gold.py       # re-checks the saved model against data/impersonation_en/gold_test.csv
```

**Use scikit-learn 1.6.1** (pinned in `requirements.txt`) — a joblib-pickled
model generally only loads cleanly in the scikit-learn version that wrote it.
After retraining, rebuild the Docker image (or restart the container with a
volume-mounted `model/model.pkl`) to pick up the new model.

`train.py` re-runs the same cross-validated search over `C` and
word-vs-character n-grams that produced the current model, so if the dataset
changes meaningfully, the chosen hyperparameters can change too — that's
expected, not a bug.

## Calling it directly

```python
import grpc
import scam_detect_pb2, scam_detect_pb2_grpc

ch = grpc.insecure_channel("localhost:50051")
stub = scam_detect_pb2_grpc.WorkerServiceStub(ch)
r = stub.ClassifyTranscript(scam_detect_pb2.TranscriptRequest(
    transcript="Hello this is Inspector Lim from the police. Your account is linked to a case.",
    language="en",
))
print(r.score)  # ~0.9+
```

## Why text_utils.py has to ship with the model

`model/model.pkl` isn't a plain scikit-learn `Pipeline` — it's a
`text_utils.CalibratedModel` wrapping one, and the pipeline's first step is a
`FunctionTransformer` over `text_utils.prep`. `joblib`/`pickle` doesn't bake
that code into the file; it only stores a reference to it by module + name.
That means:

- `text_utils.py` must sit right next to `server.py` (it does — the Dockerfile
  copies the whole worker directory).
- Whatever process calls `joblib.load(...)` must `import text_utils`
  **before** that load, or you'll get `AttributeError: Can't get attribute
  'prep' on <module 'text_utils'>`. `server.py`, `train.py`, and
  `evaluate_gold.py` all do this already.
- Don't rename this file, or rename/move `prep`/`CalibratedModel` inside it,
  without re-running `train.py` to re-save the model against the new names.

## Why the model file is committed here

The repo's `.gitignore` excludes `*.pkl`/`*.joblib` everywhere under
`backend/workers/**` — the convention (see `urgency_en`) is that teammates run
`train.py` locally to produce the model before building. `impersonation_en` is
a deliberate exception: `model/model.pkl` is force-added to git so the worker
runs immediately with zero setup. If you retrain with `train.py`, `git status`
will show `model/model.pkl` as modified (it's tracked, not ignored) — commit
that the same way you would any other changed file.

## Model card

See [`model_card.json`](model_card.json) for the exact settings and metrics of
the currently-committed model (TF-IDF ngram (1,2) + char n-grams, `C=30`,
threshold `0.47`, calibrated). Headline numbers on the held-out gold set:
precision 1.00, recall 0.94, F1 0.97, ROC-AUC 0.99 — all synthetic data (see
`model_card.json`'s `non_commercial_sources_in_training_data` /
`training_rows_by_source` for provenance), so treat these as "the model works
well on scripts like these", not a guarantee about real-world calls.
