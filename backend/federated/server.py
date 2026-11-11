"""server.py - Workstream G.6-G.10: the federated-learning coordinator (a Flower server), all three models.

One run = FL_ROUNDS rounds of federated averaging over FL_PHONES simulated phones, training the urgency, money
and impersonation models together:
  - every round, every phone gets the current global models, trains them on its own lines, returns new weights
  - FedAvg averages them (each phone counts equally)
  - G.8 (FL_NOISE > 0): Flower's DifferentialPrivacyClientSideFixedClipping wrapper asks every phone to clip its
    change to FL_CLIP, then adds Gaussian noise (std = FL_NOISE * FL_CLIP / FL_PHONES) to the average
  - G.7: after every round each model is scored on its fixed human-labelled test sets (never trained on)
At the end, for EACH model separately:
  - G.9: the release check (backend/retrain/release_check.py, the code H.7 uses) compares the candidate with the
    model phones run now (the registry's latest, or the G.1 central model when run on a laptop)
  - G.10 (FL_PUBLISH=1): a candidate that passes is published to the registry as the next <model>_en_phone version;
    phones get it from the model-download service on their next start-up. A blocked candidate is not published.
Results (per-round scores, update norms, release checks) go to FL_RESULTS/<run>.json for plot_rounds.py.

  FL_ROUNDS=10 FL_PHONES=10 FL_NOISE=0 python backend/federated/server.py

FL_INIT=zeros (default) starts from empty models, which is what the G.7 chart shows. FL_INIT=current starts from
the phones' current models, which is what a regular weekly round would do.
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
import specs  # noqa: E402
import train_hashing as G1  # noqa: E402

ROUNDS = int(os.environ.get("FL_ROUNDS", 10))
PHONES = int(os.environ.get("FL_PHONES", 10))
# Real phones: one extra Flower client, bridge_client.py, carries the updates real phones uploaded (inbox.py).
BRIDGE = os.environ.get("FL_BRIDGE", "0") == "1"
CLIENTS = PHONES + (1 if BRIDGE else 0)
NOISE = float(os.environ.get("FL_NOISE", 0))          # noise multiplier; 0 = no differential privacy
CLIP = float(os.environ.get("FL_CLIP", 10.0))         # clipping norm, only used when NOISE > 0
TOLERANCE = float(os.environ.get("FL_TOLERANCE", 0.005))
INIT = os.environ.get("FL_INIT", "zeros")
PUBLISH = os.environ.get("FL_PUBLISH", "0") == "1"
ADDRESS = os.environ.get("FL_ADDRESS", "0.0.0.0:8080")
RESULTS = Path(os.environ.get("FL_RESULTS", HERE / "results"))
WEEKLY_LR = float(os.environ.get("FL_WEEKLY_LR", 1.0))      # FL_INIT=current: gentle local steps
WEEKLY_EPOCHS = int(os.environ.get("FL_WEEKLY_EPOCHS", 1))
RUN = os.environ.get("FL_RUN", f"fedavg_r{ROUNDS}_p{PHONES}_noise{NOISE:g}" + ("_from_current" if INIT == "current" else ""))


class Adapter:
    """What release_check.compare needs for one model: its fixed test sets and how to score raw text."""

    def __init__(self, spec, featurize):
        self.spec, self.featurize = spec, featurize
        self.gold_sets = spec.gold_sets

    def model_text(self, text):
        return text

    def score(self, model, texts):
        return self.spec.proba(model, self.featurize, texts)


class Model:
    """One of the three models on the server: frozen featurizer + calibration, the phones' current version, and
    its test sets pre-hashed so scoring every round is one sparse product."""

    def __init__(self, name):
        self.name = name
        self.spec = specs.get(name)
        self.central = joblib.load(G1.model_path(name))
        self.featurize = self.central["featurizer"]
        assert len(self.central["coef"]) == fedcore.dims(name), f"{name}: feature size differs from fedcore.PARTS"
        self.adapter = Adapter(self.spec, self.featurize)
        self.current = self.current_phone_model()
        self.gold = []
        for set_name, texts, y in self.spec.load_gold():
            pieces, owner = [], []
            for i, t in enumerate(texts):
                u = self.spec.units(t) or [""]
                pieces += u
                owner += [i] * len(u)
            self.gold.append((set_name, self.featurize.transform(pieces), np.array(owner), y))

    def current_phone_model(self):
        """The model phones run now: the registry's latest <name>_en_phone. With FL_PUBLISH=1 and nothing in the
        registry yet, the G.1 central model is published first as v1 (what the app downloads first)."""
        c = self.central
        if not PUBLISH:
            return {"coef": c["coef"], "intercept": c["intercept"], "platt": c["platt"], "threshold": c["threshold"],
                    "version": f"{self.name} central {c['version']}"}
        meta = registry.latest(self.spec.registry_name)
        if meta is None:
            doc = export_phone.to_phone_json(self.name, c["coef"], c["intercept"], self.featurize, c["platt"],
                                             c["threshold"], f"{self.spec.registry_name} v1", source=f"G.1 {c['version']}")
            meta = registry.publish(json.dumps(doc).encode(), {"source": f"G.1 central {c['version']}",
                                                               "gold": c.get("gold")}, self.spec.registry_name)
            print(f"[server] registry had no {self.spec.registry_name}: published the G.1 model as {meta['version']}",
                  flush=True)
        model = export_phone.from_phone_json(json.loads(registry.file(self.spec.registry_name, meta["version"])))
        return {**model, "version": f"{self.spec.registry_name} {meta['version']}"}

    def evaluate(self, w, b):
        m = {"coef": w, "intercept": b, "platt": self.current["platt"]}
        a, c = m["platt"] or (1.0, 0.0)
        row = {}
        for set_name, X, owner, y in self.gold:
            p_piece = F.sigmoid(a * (X @ w + b) + c)
            p = np.zeros(len(y))
            np.maximum.at(p, owner, p_piece)
            row[f"{self.name}/{set_name}_roc_auc"] = round(float(roc_auc_score(y, p)), 4)
            row[f"{self.name}/{set_name}_f1"] = round(float(f1_score(y, (p >= self.current["threshold"]).astype(int),
                                                                     zero_division=0)), 4)
        return row


def main():
    models = {m: Model(m) for m in fedcore.MODELS}
    history, last, norms, real_phones = [], {}, [], []

    def evaluate(server_round, params, config):
        row = {"round": server_round}
        parts = fedcore.split(params)
        for m, model in models.items():
            w, b = parts[m]
            row.update(model.evaluate(w.astype(np.float64), float(b[0])))
        history.append(row)
        last["params"] = parts
        print(f"[server] round {server_round}: " + "  ".join(f"{k.replace('_roc_auc', '')}={v}" for k, v in row.items()
                                                            if k.endswith("roc_auc")), flush=True)
        return 0.0, row

    def fit_metrics(results):
        norms.append([round(m["update_norm"], 3) for _, m in results])
        real = sum(int(m.get("real_phones", 0)) for _, m in results)
        if real:
            real_phones.append(real)
        return {"mean_update_norm": float(np.mean(norms[-1]))}

    def fit_config(server_round):
        # Sent to every phone each round. From empty models: learn fast. From the current models (a weekly update):
        # small, careful steps, so the update only adds what the phones' new lines teach and the release check
        # has a chance to pass.
        lr, epochs = (fedcore.LEARNING_RATE, fedcore.LOCAL_EPOCHS) if INIT == "zeros" else (WEEKLY_LR, WEEKLY_EPOCHS)
        return {"round": server_round, "epochs": epochs, **{f"lr_{m}": lr[m] if isinstance(lr, dict) else lr
                                                           for m in fedcore.MODELS}}

    if INIT == "zeros":
        init = fedcore.zeros()
    else:
        init = []
        for m in fedcore.MODELS:
            cur = models[m].current
            init += [cur["coef"].astype(np.float32), np.array([cur["intercept"]], dtype=np.float32)]
    strategy = FedAvg(fraction_fit=1.0, fraction_evaluate=0.0, min_fit_clients=CLIENTS,
                      min_available_clients=CLIENTS, initial_parameters=ndarrays_to_parameters(init),
                      evaluate_fn=evaluate, on_fit_config_fn=fit_config,
                      fit_metrics_aggregation_fn=fit_metrics)
    if NOISE > 0:
        strategy = DifferentialPrivacyClientSideFixedClipping(strategy, noise_multiplier=NOISE, clipping_norm=CLIP,
                                                              num_sampled_clients=CLIENTS)
    print(f"[server] {RUN}: {ROUNDS} rounds, {PHONES} simulated phones{' + real phones (bridge)' if BRIDGE else ''}, models {', '.join(fedcore.MODELS)}, noise {NOISE}, "
          f"clip {CLIP if NOISE > 0 else '-'}, start from {INIT}", flush=True)
    started = time.time()
    fl.server.start_server(server_address=ADDRESS, config=fl.server.ServerConfig(num_rounds=ROUNDS),
                           strategy=strategy)

    out = {"run": RUN, "finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
           "seconds": round(time.time() - started, 1), "rounds": ROUNDS, "phones": PHONES, "real_phones": sum(real_phones), "noise_multiplier": NOISE,
           "clipping_norm": CLIP if NOISE > 0 else None, "init": INIT, "local_epochs": fedcore.LOCAL_EPOCHS,
           "learning_rate": fedcore.LEARNING_RATE if INIT == "zeros" else WEEKLY_LR,
           "local_epochs_used": fedcore.LOCAL_EPOCHS if INIT == "zeros" else WEEKLY_EPOCHS, "history": history, "update_norms": norms,
           "central_gold": {m: models[m].central.get("gold") for m in models}, "models": {}}
    for m, model in models.items():
        w, b = last["params"][m]
        cur = model.current
        candidate = {"coef": w.astype(np.float64), "intercept": float(b[0]), "platt": cur["platt"]}
        check = release_check.compare(model.adapter, cur, cur["threshold"], candidate, cur["threshold"], TOLERANCE)
        release_check.print_table(check, cur["version"], f"{m} {RUN}")
        entry = {"compared_with": cur["version"], "release_check": {"pass": check["pass"], "tolerance": TOLERANCE,
                                                                   "checks": check["checks"]}}
        if check["pass"] and PUBLISH:
            version = registry.next_version(model.spec.registry_name)
            doc = export_phone.to_phone_json(m, candidate["coef"], candidate["intercept"], model.featurize,
                                             cur["platt"], cur["threshold"], f"{model.spec.registry_name} {version}",
                                             source=RUN)
            meta = registry.publish(json.dumps(doc).encode(), {"source": RUN, "release_check": entry["release_check"]},
                                    model.spec.registry_name)
            entry["published"] = meta["version"]
            print(f"[server] PUBLISHED {model.spec.registry_name} {meta['version']}: phones download it on next "
                  f"start-up", flush=True)
        elif PUBLISH:
            print(f"[server] release check BLOCKED {m}: not published", flush=True)
        out["models"][m] = entry
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{RUN}.json").write_text(json.dumps(out, indent=2))
    print(f"[server] wrote {RESULTS / (RUN + '.json')}", flush=True)
    # In Kubernetes the pod's disk goes away with it: demo/tools/federated.py reads the results from this log line.
    print("RESULT_JSON " + json.dumps(out), flush=True)


if __name__ == "__main__":
    main()
