"""server.py - Workstream G.6-G.10: the federated-learning coordinator (a Flower server).

One run = FL_ROUNDS rounds of federated averaging over FL_PHONES simulated phones:
  - every round, every phone gets the current global model, trains on its own lines and returns new weights
  - FedAvg averages them (each phone counts equally)
  - G.8 (FL_NOISE > 0): Flower's DifferentialPrivacyClientSideFixedClipping wrapper asks every phone to clip its
    change to FL_CLIP, then adds Gaussian noise (std = FL_NOISE * FL_CLIP / FL_PHONES) to the average
  - G.7: after every round the global model is scored on the fixed human-labelled test sets (never trained on)
At the end:
  - G.9: the release check (backend/retrain/release_check.py, the code H.7 uses) compares the candidate with the
    model phones run now (the registry's latest phone model, or the G.1 central model when run on a laptop)
  - G.10 (FL_PUBLISH=1): a candidate that passes is published to the registry as the next version; phones get it
    from the model-download service on their next start-up. A blocked candidate is not published.
Results (per-round scores, update norms, release check) go to FL_RESULTS/<run>.json for plot_rounds.py.

  FL_ROUNDS=10 FL_PHONES=10 FL_NOISE=0 python backend/federated/server.py

FL_INIT=zeros (default) starts from an empty model, which is what the G.7 chart shows. FL_INIT=current starts
from the phones' current model, which is what a regular weekly round would do.
"""
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import flwr as fl
import joblib
import numpy as np
import pandas as pd
from flwr.common import ndarrays_to_parameters
from flwr.server.strategy import DifferentialPrivacyClientSideFixedClipping, FedAvg
from sklearn.metrics import f1_score, roc_auc_score

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[0] / "retrain"))
import export_phone  # noqa: E402
import fedcore  # noqa: E402
import features as F  # noqa: E402
import registry  # noqa: E402
import release_check  # noqa: E402  backend/retrain/release_check.py (Workstream H.7, reused as G.9)

ROUNDS = int(os.environ.get("FL_ROUNDS", 10))
PHONES = int(os.environ.get("FL_PHONES", 10))
NOISE = float(os.environ.get("FL_NOISE", 0))          # noise multiplier; 0 = no differential privacy
CLIP = float(os.environ.get("FL_CLIP", 10.0))         # clipping norm, only used when NOISE > 0
TOLERANCE = float(os.environ.get("FL_TOLERANCE", 0.005))
INIT = os.environ.get("FL_INIT", "zeros")
PUBLISH = os.environ.get("FL_PUBLISH", "0") == "1"
ADDRESS = os.environ.get("FL_ADDRESS", "0.0.0.0:8080")
DATA = Path(os.environ.get("FL_DATA", HERE / "data"))
RESULTS = Path(os.environ.get("FL_RESULTS", HERE / "results"))
CENTRAL = Path(os.environ.get("FL_CENTRAL", HERE / "model" / "urgency_en_hash_v1.joblib"))
RUN = os.environ.get("FL_RUN", f"fedavg_r{ROUNDS}_p{PHONES}_noise{NOISE:g}" + ("_from_current" if INIT == "current" else ""))


class HashAdapter:
    """What release_check.compare needs: the fixed test sets and how to score a model on raw text."""
    gold_sets = [("gold_handwritten", F.URGENCY_DIR / "eval" / "gold_handwritten.csv", "text", "label"),
                 ("robocall_gold", F.URGENCY_DIR / "eval" / "robocall_gold.csv", "text", "label_time_ai")]

    def __init__(self, featurize):
        self.featurize = featurize

    def model_text(self, text):
        return text

    def score(self, model, texts):
        return F.score(self.featurize, model["coef"], model["intercept"], F.clean(texts))


def current_phone_model(central, featurize):
    """The model phones run now: the registry's latest phone model. With FL_PUBLISH=1 and an empty registry, the
    G.1 central model is published first as v1 (it is what the app ships with)."""
    if not PUBLISH:
        return {"coef": central["coef"], "intercept": central["intercept"], "threshold": central["threshold"],
                "version": central["version"]}
    meta = registry.latest()
    if meta is None:
        doc = export_phone.to_phone_json(central["coef"], central["intercept"], featurize, central["threshold"],
                                         source=central["version"])
        meta = registry.publish(json.dumps(doc).encode(), {"source": "G.1 central hashing model",
                                                            "gold": central.get("gold")})
        print(f"[server] registry was empty: published the G.1 central model as {meta['version']}", flush=True)
    model = export_phone.from_phone_json(json.loads(registry.file(registry.PHONE_MODEL, meta["version"])))
    return {**model, "version": f"{registry.PHONE_MODEL} {meta['version']}"}


def main():
    featurize = joblib.load(DATA / "featurizer.joblib")
    central = joblib.load(CENTRAL)
    current = current_phone_model(central, featurize)
    threshold = current["threshold"]
    adapter = HashAdapter(featurize)
    gold = []
    for name, path, text_col, label_col in adapter.gold_sets:
        g = pd.read_csv(path)
        g = g[g[label_col].isin([0, 1])]
        gold.append((name, featurize.transform(F.clean(g[text_col].astype(str))), g[label_col].astype(int).to_numpy()))
    history, last, norms = [], {}, []

    def evaluate(server_round, params, config):
        w, b = params[0].astype(np.float64), float(params[1][0])
        row = {"round": server_round}
        for name, X, y in gold:
            p = fedcore.sigmoid(X @ w + b)
            row[f"{name}_roc_auc"] = round(float(roc_auc_score(y, p)), 4)
            row[f"{name}_f1"] = round(float(f1_score(y, (p >= threshold).astype(int), zero_division=0)), 4)
        history.append(row)
        last["params"] = (w, b)
        print(f"[server] round {server_round}: " + "  ".join(f"{k}={v}" for k, v in row.items() if k != "round"),
              flush=True)
        return 0.0, row

    def fit_metrics(results):
        norms.append([round(m["update_norm"], 3) for _, m in results])
        return {"mean_update_norm": float(np.mean(norms[-1]))}

    init = fedcore.zeros() if INIT == "zeros" else [current["coef"].astype(np.float32),
                                                    np.array([current["intercept"]], dtype=np.float32)]
    strategy = FedAvg(fraction_fit=1.0, fraction_evaluate=0.0, min_fit_clients=PHONES,
                      min_available_clients=PHONES, initial_parameters=ndarrays_to_parameters(init),
                      evaluate_fn=evaluate, on_fit_config_fn=lambda r: {"round": r},
                      fit_metrics_aggregation_fn=fit_metrics)
    if NOISE > 0:
        strategy = DifferentialPrivacyClientSideFixedClipping(strategy, noise_multiplier=NOISE, clipping_norm=CLIP,
                                                              num_sampled_clients=PHONES)
    print(f"[server] {RUN}: {ROUNDS} rounds, {PHONES} phones, noise {NOISE}, clip {CLIP if NOISE > 0 else '-'}, "
          f"start from {INIT}, compare with {current['version']}", flush=True)
    started = time.time()
    fl.server.start_server(server_address=ADDRESS, config=fl.server.ServerConfig(num_rounds=ROUNDS),
                           strategy=strategy)

    w, b = last["params"]
    candidate = {"coef": w, "intercept": b}
    check = release_check.compare(adapter, current, threshold, candidate, threshold, TOLERANCE)
    release_check.print_table(check, current["version"], RUN)

    RESULTS.mkdir(parents=True, exist_ok=True)
    out = {"run": RUN, "finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
           "seconds": round(time.time() - started, 1), "rounds": ROUNDS, "phones": PHONES, "noise_multiplier": NOISE,
           "clipping_norm": CLIP if NOISE > 0 else None, "init": INIT, "local_epochs": fedcore.LOCAL_EPOCHS,
           "learning_rate": fedcore.LEARNING_RATE, "compared_with": current["version"],
           "central_gold": central.get("gold"), "history": history, "update_norms": norms,
           "release_check": {"pass": check["pass"], "tolerance": TOLERANCE, "checks": check["checks"]}}
    if check["pass"] and PUBLISH:
        doc = export_phone.to_phone_json(w, b, featurize, threshold, source=RUN)
        meta = registry.publish(json.dumps(doc).encode(), {"source": RUN, "release_check": out["release_check"],
                                                            "final_round": history[-1]})
        out["published"] = meta["version"]
        print(f"[server] PUBLISHED {registry.PHONE_MODEL} {meta['version']}: phones download it on next start-up",
              flush=True)
    elif PUBLISH:
        print("[server] release check BLOCKED the candidate: not published", flush=True)
    (RESULTS / f"{RUN}.json").write_text(json.dumps(out, indent=2))
    print(f"[server] wrote {RESULTS / (RUN + '.json')}", flush=True)
    # In Kubernetes the pod's disk goes away with it: demo/tools/federated.py reads the results from this log line.
    print("RESULT_JSON " + json.dumps(out), flush=True)


if __name__ == "__main__":
    main()
