"""train_hashing.py - Workstream G.1: hashing versions of the three on-device models, trained centrally.

For each model (urgency, money, impersonation) this trains the worker's own recipe with a hashing vectoriser
instead of TF-IDF's word list (specs.py), and compares it with the deployed TF-IDF model on the same fixed
human-labelled test sets. G.1's goal: ROC-AUC within about 0.02.

These models are (1) what the app gets as v1 from the registry, (2) the frozen feature space (hash settings + IDF)
and calibration every simulated phone uses, and (3) the reference line in the federated chart (G.7).

  python backend/federated/tfidf_baselines.py ...     (first, once: the TF-IDF side of the comparison)
  python backend/federated/train_hashing.py           all three -> model/<name>_en_hash_v1.joblib
  python backend/federated/train_hashing.py money     one model
"""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, roc_auc_score

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import features as F  # noqa: E402
import specs  # noqa: E402

MODEL_DIR = HERE / "model"
REPORTS = HERE / "reports"
VERSION = "hash_v1"


def model_path(name):
    return MODEL_DIR / f"{name}_en_{VERSION}.joblib"


def train(spec) -> dict:
    data = spec.training()
    tr = data["train"]
    texts, y, w = tr["text"].tolist(), tr["label"].to_numpy().astype(int), tr["weight"].to_numpy(dtype=float)
    print(f"\n[{spec.name}] {len(tr)} training rows, {int(y.sum())} positive, {tr['source'].nunique()} sources")

    def featurize_fn(t):
        return F.featurizer(spec.parts).fit(list(t))

    feat = featurize_fn(texts)
    lr = spec.fit_lr(feat.transform(texts), y, w)
    model = {"coef": lr.coef_[0].astype(np.float64), "intercept": float(lr.intercept_[0]), "platt": None}

    if data["calibrate"] is None:                              # urgency: no calibration, own threshold rule
        model["threshold"] = spec.threshold(tr, featurize_fn)
    elif isinstance(data["calibrate"], pd.DataFrame):          # money: Platt on the calibrate split
        cal = data["calibrate"]
        z = feat.transform(cal["text"].tolist()) @ model["coef"] + model["intercept"]
        platt = LogisticRegression(C=1e6, max_iter=1000).fit(z.reshape(-1, 1), cal["label"])
        model["platt"] = (float(platt.coef_[0][0]), float(platt.intercept_[0]))
        model["threshold"] = spec.threshold(tr, featurize_fn)
    else:                                                      # impersonation: Platt on out-of-fold scores
        z, yd = spec.oof_decision(featurize_fn)
        platt = LogisticRegression(C=1e6, max_iter=1000).fit(z.reshape(-1, 1), yd)
        model["platt"] = (float(platt.coef_[0][0]), float(platt.intercept_[0]))
        model["threshold"] = spec.threshold_from(platt.predict_proba(z.reshape(-1, 1))[:, 1], yd)
    print(f"[{spec.name}] threshold {model['threshold']:.4f}, calibration {model['platt']}")
    return {**model, "featurizer": feat, "training": tr}


def gold_table(spec, model, baselines) -> pd.DataFrame:
    rows = []
    base = baselines.get(spec.name, {})
    for set_name, texts, y in spec.load_gold():
        p = spec.proba(model, model["featurizer"], texts)
        b = base.get("sets", {}).get(set_name, {})
        rows.append({"model": spec.name, "test_set": set_name, "rows": len(y),
                     "tfidf_roc_auc": b.get("roc_auc"), "hash_roc_auc": round(float(roc_auc_score(y, p)), 4),
                     "tfidf_f1": b.get("f1"),
                     "hash_f1": round(float(f1_score(y, (p >= model["threshold"]).astype(int), zero_division=0)), 4)})
    t = pd.DataFrame(rows)
    t["roc_auc_gap"] = (t["tfidf_roc_auc"] - t["hash_roc_auc"]).round(4)
    return t


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("models", nargs="*", default=list(specs.SPECS))
    ap.add_argument("--no-save", action="store_true")
    args = ap.parse_args()

    bl_path = REPORTS / "tfidf_baselines.json"
    baselines = json.loads(bl_path.read_text()) if bl_path.exists() else {}
    tables = []
    for name in args.models:
        spec = specs.get(name)
        m = train(spec)
        t = gold_table(spec, m, baselines)
        tables.append(t)
        print(t.to_string(index=False))
        if args.no_save:
            continue
        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        meta = {"model": name, "version": VERSION, "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                "sklearn_version": sklearn.__version__, "parts": [p.describe() for p in spec.parts], "C": spec.C,
                "threshold": m["threshold"], "platt": m["platt"], "trained_rows": len(m["training"]),
                "nonzero_weights": int(np.count_nonzero(m["coef"])), "gold": t.to_dict("records")}
        joblib.dump({k: v for k, v in m.items() if k != "training"} | meta, model_path(name))
        (MODEL_DIR / f"{name}_en_{VERSION}.json").write_text(json.dumps(meta, indent=2))
        print(f"wrote {model_path(name).relative_to(HERE.parents[1])}")

    if not args.no_save and set(args.models) == set(specs.SPECS):
        all_t = pd.concat(tables)
        REPORTS.mkdir(parents=True, exist_ok=True)
        all_t.to_csv(REPORTS / "g1_hashing_vs_tfidf.csv", index=False)
        gaps = all_t.dropna(subset=["roc_auc_gap"])
        print("\nG.1 goal (ROC-AUC gap <= 0.02 on every set): " + ("MET" if (gaps["roc_auc_gap"] <= 0.02).all()
                                                                  else "NOT MET on: " + ", ".join(
            f"{r.model}/{r.test_set} ({r.roc_auc_gap:+.4f})" for r in gaps.itertuples() if r.roc_auc_gap > 0.02)))


if __name__ == "__main__":
    main()
