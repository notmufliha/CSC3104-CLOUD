"""
experiments.py - compare urgency model variants with the SAME out-of-source selection as train.py.

Variants
  current      TF-IDF + LogisticRegression, silver_rule_urgency rows at weight 0.5   (what train.py builds)
  A            TF-IDF + LogisticRegression, silver rows removed
  B_silver     all-MiniLM-L6-v2 sentence vectors + LogisticRegression, silver rows at weight 0.5
  B_nosilver   all-MiniLM-L6-v2 sentence vectors + LogisticRegression, silver rows removed

Each variant picks C and the threshold exactly like train.py step 4 (every (source, label) block scored by a
model trained without it; threshold = max source-weighted pooled F1; smallest C within F1_TOLERANCE).

For the COMPARISON, every variant is measured on the same rows: all non-silver rows, out-of-source probabilities.
Silver rows are ~35% mislabelled (data/urgency_en/silver_audit.csv), so they would be an unfair yardstick and
variants without them would have nothing to compare on. Silver recall is reported separately, as a noisy extra.

Reads    same training files as train.py, eval/gold_handwritten.csv, checks/long_calls.csv, optional --probes CSV
Writes   model/experiments/<variant>.joblib, model/urgency_en.joblib (winner by out-of-source ROC-AUC),
         reports/experiments.csv, reports/experiments_report.txt

Usage
  python backend/workers/urgency_en/research/experiments.py [--probes PATH] [--no-save]
"""
# torch must be imported before scikit-learn/numpy load their OpenMP runtime, otherwise on Windows
# torch's c10.dll fails to initialise (WinError 1114).
import torch  # noqa: F401  isort:skip
import argparse
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from joblib import Parallel, delayed
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, roc_auc_score
from sklearn.pipeline import Pipeline

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # the worker folder: predict, train, text_norm
import predict  # noqa: E402
import train as T  # noqa: E402
from embed import DEFAULT_MODEL as MINILM, MiniLMEncoder  # noqa: E402
from text_norm import normalize

HERE = T.HERE
MODEL_DIR = HERE / "model"
EXP_DIR = MODEL_DIR / "experiments"
GOLD = HERE / "eval" / "gold_handwritten.csv"
LONG_CALLS = HERE / "checks" / "long_calls.csv"
NEW_AUGMENT = T.REPO / "data" / "urgency_en" / "augment_urgency_en.csv"
VARIANTS = [("current", "tfidf", True), ("A", "tfidf", False),
            ("B_silver", "minilm", True), ("B_nosilver", "minilm", False)]

_lines = []


def log(*parts):
    s = " ".join(str(p) for p in parts)
    print(s)
    _lines.append(s)


def make_clf(C):
    # Same settings as the TF-IDF pipeline's classifier step.
    return LogisticRegression(C=C, class_weight="balanced", solver="liblinear", max_iter=3000, random_state=T.SEED)


def fit(kind, C, X, y, w):
    if kind == "tfidf":
        return T.make_pipeline(C).fit(X, y, clf__sample_weight=w)
    return make_clf(C).fit(X, y, sample_weight=w)


def proba(model, X):
    return model.predict_proba(X)[:, list(model.classes_).index(1)]


def _held_out(kind, C, X, d, pos):
    rest = np.setdiff1d(np.arange(len(d)), pos)
    m = fit(kind, C, X[rest], d["label"].to_numpy()[rest], T.fit_weights(d.iloc[rest]))
    return C, pos, proba(m, X[pos])


def out_of_source(kind, X, d):
    """train.py step 4 for any feature type. X is indexable by row position (text array or embedding matrix)."""
    blocks = [d.index.get_indexer(idx) for _, idx in d.groupby(["source", "label"]).groups.items()]
    res = Parallel(n_jobs=-1)(delayed(_held_out)(kind, C, X, d, pos) for C in T.C_GRID for pos in blocks)
    probs = {C: np.full(len(d), np.nan) for C in T.C_GRID}
    for C, pos, p in res:
        probs[C][pos] = p
    y, w = d["label"].to_numpy(), T.source_weights(d)
    rows = []
    for C in T.C_GRID:
        thr, f1, _ = T.best_f1_threshold(y, probs[C], sample_weight=w)
        rows.append({"C": C, "threshold": thr, "pooled_f1": f1})
    sel = pd.DataFrame(rows)
    best = sel["pooled_f1"].max()
    chosen = sel[sel["pooled_f1"] >= best - T.F1_TOLERANCE].sort_values("C").iloc[0]
    return float(chosen["C"]), float(chosen["threshold"]), probs[float(chosen["C"])], sel


def compare_metrics(d, p, thr, no_rush):
    """Metrics on non-silver rows only (identical row set for every variant)."""
    m = ~d["label_method"].eq(T.SILVER_METHOD).to_numpy()
    c, pc = d[m], p[m]
    y, w, pred = c["label"].to_numpy(), T.source_weights(c), pc >= thr
    out = {"oos_roc_auc": roc_auc_score(y, pc, sample_weight=w),
           "oos_pooled_f1": f1_score(y, pred, sample_weight=w)}
    for s in sorted(c.loc[c["label"] == 1, "source"].unique()):
        k = ((c["source"] == s) & (c["label"] == 1)).to_numpy()
        out[f"recall {s}"] = pred[k].mean()
    for s in sorted(c.loc[c["label"] == 0, "source"].unique()):
        k = ((c["source"] == s) & (c["label"] == 0)).to_numpy()
        out[f"acc {s}{' (robocall non-urgent)' if s == 'Robocall' else ''}"] = (~pred[k]).mean()
    k = c["text"].isin(no_rush).to_numpy() & (y == 0)
    out[f"acc no-rush rows (n={int(k.sum())})"] = (~pred[k]).mean()
    return out


def gold_metrics(pipeline, thr):
    g = pd.read_csv(GOLD).dropna(subset=["text", "label"])
    y = g["label"].astype(int).to_numpy()
    p = proba(pipeline, g["text"].map(normalize))
    return {"gold_roc_auc": roc_auc_score(y, p), "gold_f1": f1_score(y, p >= thr)}


def probe_metrics(pipeline, thr, path):
    if path is None or not Path(path).exists():
        return {}
    pr = pd.read_csv(path)
    ok = (proba(pipeline, pr["text"].map(normalize)) >= thr) == pr["label"].astype(bool)
    out = {"probes": f"{int(ok.sum())}/{len(pr)}"}
    for cat, v in pd.Series(ok.to_numpy()).groupby(pr["category"].to_numpy()):
        out[f"probe {cat}"] = f"{int(v.sum())}/{len(v)}"
    return out


def long_call_metrics(bundle):
    if not LONG_CALLS.exists():
        return {}
    calls = pd.read_csv(LONG_CALLS)
    predict._bundle = bundle  # score() reads the module-level bundle
    s = np.array([predict.score(t)[0] for t in calls["text"]])
    y = calls["label"].to_numpy()
    thr = bundle["threshold"]
    return {"long urgent mean": s[y == 1].mean(), "long urgent min": s[y == 1].min(),
            "long normal mean": s[y == 0].mean(), "long normal max": s[y == 0].max(),
            "long urgent >= thr": f"{int((s[y == 1] >= thr).sum())}/{int((y == 1).sum())}",
            "long normal >= thr": f"{int((s[y == 0] >= thr).sum())}/{int((y == 0).sum())}",
            "long call ROC-AUC": roc_auc_score(y, s)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--probes", type=Path, default=None, help="CSV text,label,category of extra probe sentences")
    ap.add_argument("--no-save", action="store_true")
    args = ap.parse_args()

    log(f"urgency_en experiments {datetime.now(timezone.utc).isoformat(timespec='seconds')} sklearn={sklearn.__version__}")
    df, training_files, decontam_files = T.load_data(T.DEFAULT_DATA, T.DEFAULT_AUGMENT)
    log(f"{len(df)} rows ({int(df['label_method'].eq(T.SILVER_METHOD).sum())} silver)")
    aug = pd.read_csv(NEW_AUGMENT)
    no_rush = set(aug.loc[aug["group"] == "negation", "text_raw"].map(normalize))

    log(f"encoding {len(df)} rows with {MINILM} ...")
    encoder = MiniLMEncoder()
    emb = encoder.transform(df["text"])
    texts = df["text"].to_numpy()

    results, bundles = [], {}
    for name, kind, keep_silver in VARIANTS:
        keep = np.ones(len(df), bool) if keep_silver else ~df["label_method"].eq(T.SILVER_METHOD).to_numpy()
        d = df[keep].reset_index(drop=True)
        X = texts[keep] if kind == "tfidf" else emb[keep]
        log(f"\n=== {name}: {kind}, silver {'weight ' + str(T.SILVER_WEIGHT) if keep_silver else 'removed'}, {len(d)} rows")
        C, thr, p, sel = out_of_source(kind, X, d)
        log(sel.round(4).to_string(index=False))
        log(f"chosen C={C:g} threshold={thr:.4f}")

        final = fit(kind, C, X, d["label"].to_numpy(), T.fit_weights(d))
        pipeline = final if kind == "tfidf" else Pipeline([("embed", MiniLMEncoder()), ("clf", final)])

        row = {"variant": name, "features": kind, "silver": "0.5" if keep_silver else "removed", "C": C,
               "threshold": thr, **compare_metrics(d, p, thr, no_rush)}
        # Silver recall: noisy labels (~35% wrong), so only an extra. Out-of-source if silver was trained on,
        # otherwise scored by the final model, which never saw those rows.
        silver = df[df["label_method"].eq(T.SILVER_METHOD)]
        ps = p[d["label_method"].eq(T.SILVER_METHOD).to_numpy()] if keep_silver else proba(
            pipeline, silver["text"] if kind == "tfidf" else silver["text"].to_numpy())
        for s in ["Fraud_R1", "BothBosu"]:
            row[f"silver recall {s}*"] = (ps[silver["source"].eq(s).to_numpy()] >= thr).mean()
        row.update(gold_metrics(pipeline, thr))
        row.update(probe_metrics(pipeline, thr, args.probes))
        bundle = {
            "pipeline": pipeline, "threshold": thr, "signal": "urgency", "language": "en",
            "trained_rows": len(d), "best_params": {"clf__C": C}, "features": kind,
            "encoder": MINILM if kind == "minilm" else None, "silver": row["silver"], "variant": name,
            "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "threshold_method": "out_of_source_pooled_f1",
            "training_files": [Path(f).resolve().relative_to(T.REPO).as_posix() for f in training_files],
            "decontamination": {"limit": T.DECONTAM_LIMIT, "eval_files": decontam_files},
            "sklearn_version": sklearn.__version__,
        }
        row.update(long_call_metrics(bundle))
        results.append(row)
        bundles[name] = bundle

    table = pd.DataFrame(results)
    T.REPORTS.mkdir(parents=True, exist_ok=True)
    table.to_csv(T.REPORTS / "experiments.csv", index=False)
    log("\n" + table.set_index("variant").T.to_string())
    winner = table.sort_values("oos_roc_auc", ascending=False).iloc[0]["variant"]
    log(f"\nwinner by out-of-source ROC-AUC: {winner}")

    if not args.no_save:
        EXP_DIR.mkdir(parents=True, exist_ok=True)
        for name, b in bundles.items():
            joblib.dump(b, EXP_DIR / f"{name}.joblib")
        shutil.copyfile(EXP_DIR / f"{winner}.joblib", MODEL_DIR / "urgency_en.joblib")
        log(f"saved model/experiments/*.joblib; model/urgency_en.joblib = {winner}")
    (T.REPORTS / "experiments_report.txt").write_text("\n".join(T._lines + _lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
