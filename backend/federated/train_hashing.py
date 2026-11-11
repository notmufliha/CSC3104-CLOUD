"""train_hashing.py - Workstream G.1: the hashing version of the English urgency model, trained centrally.

It is the baseline the federated run (G.7) is compared with, and its frozen IDF is the feature space every
simulated phone uses. Everything except the vectoriser is the urgency worker's own recipe:
  - the same training rows, relabelling, cleaning, de-duplication and gold-set decontamination
    (backend/workers/urgency_en/train.py, load_data)
  - the same per-source weights, C (0.3, the value v4 was chosen with) and class balancing.

G.1's goal: ROC-AUC within about 0.02 of the TF-IDF model on the fixed human-labelled test sets. This script
prints both models side by side and writes the table to backend/federated/reports/g1_hashing_vs_tfidf.csv.

Run with the urgency worker's scikit-learn (1.4.2):
  python backend/federated/train_hashing.py              # writes model/urgency_en_hash_v1.joblib
  python backend/federated/train_hashing.py --no-save    # comparison only
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
from sklearn.metrics import f1_score, recall_score, precision_score, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import features as F  # noqa: E402  (also puts the urgency worker on sys.path)
import train as urgency_train  # noqa: E402  backend/workers/urgency_en/train.py

C = 0.3
SEED = 42
MODEL_DIR = HERE / "model"
REPORTS = HERE / "reports"
TFIDF_MODEL = F.URGENCY_DIR / "model" / "urgency_en_v4.joblib"
# Fixed human-labelled test sets (never used for training): (name, file, label column). Same as the release check.
GOLD = [("gold_handwritten", F.URGENCY_DIR / "eval" / "gold_handwritten.csv", "label"),
        ("robocall_gold", F.URGENCY_DIR / "eval" / "robocall_gold.csv", "label_time_ai")]


def load_training():
    df, used, _ = urgency_train.load_data(urgency_train.DEFAULT_DATA, urgency_train.DEFAULT_AUGMENT)
    return df, used


def load_gold():
    out = []
    for name, path, col in GOLD:
        g = pd.read_csv(path)
        g = g[g[col].isin([0, 1])]
        out.append((name, F.clean(g["text"].astype(str)), g[col].astype(int).to_numpy()))
    return out


def best_f1_threshold(y, p, w=None):
    from sklearn.metrics import precision_recall_curve
    prec, rec, thr = precision_recall_curve(y, p, sample_weight=w)
    f1 = 2 * prec[:-1] * rec[:-1] / np.clip(prec[:-1] + rec[:-1], 1e-12, None)
    return float(thr[int(np.argmax(f1))])


def fit_lr(X, y, w):
    return LogisticRegression(C=C, class_weight="balanced", solver="liblinear", max_iter=3000,
                              random_state=SEED).fit(X, y, sample_weight=w)


def metrics(y, p, threshold):
    pred = (p >= threshold).astype(int)
    return {"roc_auc": roc_auc_score(y, p), "f1": f1_score(y, pred, zero_division=0),
            "precision": precision_score(y, pred, zero_division=0), "recall": recall_score(y, pred, zero_division=0)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-save", action="store_true")
    ap.add_argument("--version", default="v1")
    args = ap.parse_args()

    df, used = load_training()
    texts, y = df["text"].tolist(), df["label"].to_numpy()
    w = urgency_train.fit_weights(df)
    print(f"\n{len(df)} training rows, {int(y.sum())} urgent, {df['source'].nunique()} sources")

    # Threshold: best source-weighted F1 on a 20% group split (whole conversations on one side).
    tr, va = next(GroupShuffleSplit(1, test_size=0.2, random_state=SEED).split(df, y, df["group"]))
    feat_tr = F.featurizer().fit([texts[i] for i in tr])
    lr_tr = fit_lr(feat_tr.transform([texts[i] for i in tr]), y[tr], urgency_train.fit_weights(df.iloc[tr]))
    p_va = lr_tr.predict_proba(feat_tr.transform([texts[i] for i in va]))[:, 1]
    threshold = best_f1_threshold(y[va], p_va, urgency_train.fit_weights(df.iloc[va]))
    print(f"threshold (best weighted F1 on the 20% validation split): {threshold:.4f}")

    # Final model: IDF and weights fitted on all training rows.
    feat = F.featurizer().fit(texts)
    lr = fit_lr(feat.transform(texts), y, w)
    coef, intercept = lr.coef_[0].astype(np.float64), float(lr.intercept_[0])

    tfidf = joblib.load(TFIDF_MODEL)
    rows = []
    for name, gtexts, gy in load_gold():
        p_hash = F.score(feat, coef, intercept, gtexts)
        p_tfidf = tfidf["pipeline"].predict_proba(gtexts)[:, list(tfidf["pipeline"].classes_).index(1)]
        for model, p, thr in (("tfidf_v4", p_tfidf, tfidf["threshold"]), (f"hash_{args.version}", p_hash, threshold)):
            rows.append({"test_set": name, "rows": len(gy), "model": model, **metrics(gy, p, thr)})
    table = pd.DataFrame(rows)
    REPORTS.mkdir(parents=True, exist_ok=True)
    table.round(4).to_csv(REPORTS / "g1_hashing_vs_tfidf.csv", index=False)
    print("\n" + table.round(4).to_string(index=False))
    wide = table.pivot(index="test_set", columns="model", values="roc_auc")
    gap = (wide["tfidf_v4"] - wide[f"hash_{args.version}"])
    print("\nROC-AUC gap (TF-IDF minus hashing): " + ", ".join(f"{k} {v:+.4f}" for k, v in gap.items()))
    print("G.1 goal (gap <= 0.02 on every set): " + ("MET" if (gap <= 0.02).all() else "NOT MET"))

    nonzero = int(np.count_nonzero(coef))
    print(f"non-zero weights: {nonzero} of {F.N_FEATURES}")
    if args.no_save:
        return
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    out = MODEL_DIR / f"urgency_en_hash_{args.version}.joblib"
    meta = {"version": f"hash_{args.version}", "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "sklearn_version": sklearn.__version__, "n_features": F.N_FEATURES, "ngram": list(F.NGRAM),
            "C": C, "threshold": threshold, "trained_rows": len(df), "nonzero_weights": nonzero,
            "training_files": [str(Path(p).relative_to(F.REPO)).replace("\\", "/") for p in used],
            "gold": table.round(4).to_dict("records")}
    joblib.dump({"featurizer": feat, "coef": coef, "intercept": intercept, "threshold": threshold, **meta}, out)
    (MODEL_DIR / f"urgency_en_hash_{args.version}.json").write_text(json.dumps(meta, indent=2))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
