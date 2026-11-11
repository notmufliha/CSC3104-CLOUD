# Workstream G: federated learning and on-device model updates

Phones improve a shared urgency model without sending their calls anywhere. Each phone trains on its own labelled
lines and sends back only new model weights. The server averages them (FedAvg), checks the result against fixed
human-labelled test sets, and publishes it only if it is not worse. Phones download new versions on start-up.

Scope (as the Tasks 2.0 brief set it): English urgency only; phones simulated as pods.

| Task | What | Where |
|---|---|---|
| G.1 | Hashing version of the urgency model, so phones' weights line up and can be averaged | `features.py`, `train_hashing.py` |
| G.6 | Flower server + 10 simulated phones, each with a different (non-IID) data slice | `server.py`, `client.py`, `prepare.py` |
| G.7 | 10 rounds; accuracy on the gold sets after every round, vs the central model | `server.py`, `plot_rounds.py` |
| G.8 | Clipping + noise (Flower's `DifferentialPrivacyClientSideFixedClipping`), accuracy cost | `server.py`, `fedcore.py` |
| G.9 | Release check: the code H.7 uses (`backend/retrain/release_check.py`) | `server.py` |
| G.10 | Registry (H's, in the review service) + read-only download service behind the Ingress | `registry.py`, `download_service.py`, `export_phone.py` |

Not done yet: the app side of G.10 (Kotlin hashing scorer + updater), G.11 (distillation, stretch), and money /
impersonation hashing versions (G.1 for the other two signals; the federated loop only uses urgency).

## How it works

```
phone i (pod)                         fl-server (pod)                         review (H registry)   model-download
  slice i of the training data          round r: send global weights  ───►
  3 local epochs, lr 20         ◄───    
  clip change to norm 10 (G.8)  ───►    FedAvg (each phone counts once)
                                        + noise std = σ·10/10 (G.8)
                                        score gold sets (G.7)
                                        after 10 rounds: release check (G.9)
                                        pass → publish urgency_en_phone vN  ───►  stored, versioned  ◄── reads (cache)
                                                                                                         ▲
                                                                                  phone app: GET /models/manifest
```

- **Why hashing (G.1).** TF-IDF learns its word list from the data it sees, so two phones would get two different
  lists, and weight 1234 would mean a different word on each. A hashing vectoriser maps every word to a fixed slot
  with a hash function. The IDF weights are fitted once by the cloud on the public training data and then frozen,
  so phones only ever train the logistic regression weights, and those can be averaged.
- **Non-IID phones (G.6).** For every data source, the share of its conversations each phone gets is drawn from a
  Dirichlet(0.5) distribution (the standard way FL papers simulate uneven users). Phone 8 ends up with 25 urgent
  lines out of 1,132; phone 4 with 682 out of 2,102.
- **Equal weighting.** Every phone counts once in the average (not by how many lines it has). The DP noise is
  calibrated for that, and it stops one heavy user from dominating.

## Results so far (laptop, `run_local.py`, 2026-11-11)

G.1, ROC-AUC on the fixed test sets (`reports/g1_hashing_vs_tfidf.csv`):

| Test set | TF-IDF v4 (deployed) | Hashing v1 | Gap | Goal ≤ 0.02 |
|---|---|---|---|---|
| gold_handwritten (59) | 0.8228 | 0.8228 | 0.0000 | met |
| robocall_gold (181) | 0.9229 | 0.9144 | 0.0085 | met |

G.7 / G.8, after 10 rounds from an empty model (`results/rounds.png`, `results/rounds_table.csv`):

| Run | gold_handwritten | robocall_gold |
|---|---|---|
| central hashing model (G.1, reference) | 0.8228 | 0.9144 |
| FedAvg, no noise | 0.8100 | 0.8844 |
| noise 0.1, clip 10 | 0.7716 | 0.8261 |
| noise 0.3, clip 10 | 0.6970 | 0.7999 |
| noise 1.0, clip 10 | 0.7145 | 0.5237 |

What to say in the report:
- Without noise, 10 rounds get within 0.013 / 0.030 of the centrally trained model, while no phone shared a line.
  The gap is the cost of uneven phones.
- Noise costs accuracy fast. With only 10 phones per round, the noise needed for privacy is large compared with the
  averaged update; real systems use hundreds or thousands of phones per round, so the same noise is divided by far
  more. The noisy runs also vary from run to run (the noise is random): run each setting 3 times and report the
  mean and spread.
- Every candidate so far was **blocked** by the release check, correctly: these phones hold the same data the
  central model already trained on, so averaging cannot beat it. The loop only helps when phones see something
  new. Next experiment: train the central model without one scam source, give that source to the phones, and show
  a weekly round (`FL_INIT=current`) that passes the release check and gets published.
- Honest limits: no secure aggregation (Flower's SecAgg+ needs the newer ServerApp API); the server adds the noise
  (central DP), so it must be trusted to do so; no privacy budget (epsilon) is computed for 10 rounds of 10 phones.

## How to check it

### 1. On the laptop (no Kubernetes, about 5 minutes)

Python 3.12 with the urgency worker's versions:

```bash
uv venv --python 3.12 .venv-g && uv pip install --python .venv-g flwr==1.12.0 scikit-learn==1.4.2 numpy==1.26.4 \
    scipy==1.13.1 pandas==2.2.2 joblib==1.4.2 matplotlib
# (Windows: .venv-g\Scripts\python.exe below)
.venv-g/bin/python backend/federated/train_hashing.py   # G.1: prints the table above; "G.1 goal ... MET"
.venv-g/bin/python backend/federated/prepare.py         # G.6: rows per phone (should be uneven)
.venv-g/bin/python backend/federated/run_local.py       # G.6/G.7: 10 rounds, ROC-AUC climbs from 0.5 each round
.venv-g/bin/python backend/federated/run_local.py --noise 0.1 --clip 10 --port 8082   # G.8
.venv-g/bin/python backend/federated/plot_rounds.py     # results/rounds.png
```

Each run ends with the release check table and `=> BLOCKED` or `=> PASS` (G.9).

### 2. On minikube (the main demo must be up: `demo/up.ps1`)

```bash
python demo/tools/federated.py build        # builds scam/federated (trains G.1 inside), scam/model-download,
                                            # and rebuilds scam/review (registry now accepts urgency_en_phone)
python demo/tools/federated.py up           # model-download (2 pods) + Ingress /models
python demo/tools/federated.py run          # fl-server Job + 10 phone pods in namespace scamguard-fl
kubectl get pods -n scamguard-fl            # (other terminal) 1 server + fl-phones-0..9
python demo/tools/federated.py run --noise 0.1
python demo/tools/federated.py run --init current --publish   # a "weekly" round that may publish
python demo/tools/federated.py status       # registry versions + the manifest phones see
curl -k https://<address>/models/manifest   # what the phone sees through the Ingress (address from expose.ps1)
python demo/tools/federated.py down
```

What you should see:
- `run`: 11 pods in `scamguard-fl`; the server prints one line per round; results saved under `results/`.
- With `--publish`, the first run publishes the G.1 model as `urgency_en_phone v1` (the version the app ships
  with), then compares the candidate with it. `status` lists the versions; the manifest shows the newest.
- Delete a phone pod mid-run (`kubectl delete pod -n scamguard-fl <fl-phones-3-...>`): the Indexed Job restarts it
  with the same index (same data slice), it reconnects, and the round continues.

Memory: a run uses about 2 GB (10 phones × ~160 MB + the server). On the 8 GiB Docker setup, use the lite demo.
