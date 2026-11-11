"""
train.py - train the English Money/OTP worker (TF-IDF + LogisticRegression, Platt-calibrated).

Reads
  --pool        C_train_pool.csv from the notebook (columns: text_clean, label, source, script_id, conversation_id)
                Labels follow LABELING.md (money_norm.request_label + hand-written rows).
Writes
  <out-dir>/<version>.joblib   money_model.MoneyModel (needs money_model.py + money_norm.py next to it to load)
  <out-dir>/<version>.json     version, data file + MD5, label rule, settings, grid, choose-split scores
  <report-dir>/E_choose_split.csv, E_grid.csv
Never overwrites an existing <version> (D.8): pick a new --version to retrain.

Usage (from backend/workers/money_en/):
  python train.py --pool ../../../data/money_en/C_train_pool.csv --out-dir model --version money_en_v3
  (the training pool lives in data/money_en/ at the repo root; the test sets in eval/, labelled per LABELING.md)
"""
import argparse
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.feature_extraction.text import ENGLISH_STOP_WORDS, CountVectorizer, TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, brier_score_loss, f1_score, precision_score, recall_score,
                             roc_auc_score)
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from money_model import MoneyModel                      # noqa: E402
from money_norm import LABEL_RULE_VERSION               # noqa: E402

PLACEHOLDERS = ["moneytoken", "codetoken", "cardtoken", "numtoken", "phonetoken", "linktoken", "datetoken",
                "timetoken", "nametoken", "orgtoken", "loctoken"]
DEFAULT_CONFIGS = [{"name": "word12_C0.3", "char": False, "C": 0.3},
                   {"name": "word12_C1", "char": False, "C": 1.0},
                   {"name": "word12_C3", "char": False, "C": 3.0}]


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_pipe(cfg, stop_words=None):
    if cfg.get("char"):
        raise ValueError("v2 uses word n-grams only (character n-grams made 'priceless' match 'price')")
    word = TfidfVectorizer(ngram_range=(1, 2), min_df=2, max_df=0.9, sublinear_tf=True, stop_words=stop_words)
    clf = LogisticRegression(C=cfg["C"], class_weight="balanced", max_iter=3000, solver="liblinear")
    return Pipeline([("features", word), ("clf", clf)])


def group_split(df, test_size, seed):
    gss = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
    a, b = next(gss.split(df, df["label"], df["group"]))
    return df.iloc[a], df.iloc[b]


def metrics_at(y, p, thr):
    pred = (np.asarray(p) >= thr).astype(int)
    return {"precision": round(precision_score(y, pred, zero_division=0), 3),
            "recall": round(recall_score(y, pred, zero_division=0), 3),
            "f1": round(f1_score(y, pred, zero_division=0), 3)}


def top_words(pipe, n):
    names = pipe[:-1].get_feature_names_out()
    coef = pipe[-1].coef_[0]
    # content words only: hiding "the" or "me" says nothing about keyword reliance
    single = [i for i in np.argsort(-coef) if " " not in names[i] and not names[i].endswith("token")
              and names[i] not in ENGLISH_STOP_WORDS]
    return [str(names[i]) for i in single[:n]]


def hide_words(texts, words):
    if not words:
        return list(texts)
    rx = re.compile(r"\b(?:" + "|".join(map(re.escape, words)) + r")\b")
    return [re.sub(r"\s+", " ", rx.sub(" ", t)).strip() for t in texts]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pool", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--report-dir", default=None)
    ap.add_argument("--configs", default=json.dumps(DEFAULT_CONFIGS), help="JSON list of {name, char, C}")
    ap.add_argument("--val-share", type=float, default=0.15)
    ap.add_argument("--cal-share", type=float, default=0.15)
    ap.add_argument("--handwritten-weight", type=float, default=10.0)
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--hide-top-n", type=int, default=20)
    ap.add_argument("--hidden-auc-min", type=float, default=0.75)
    ap.add_argument("--placeholder-max-auc", type=float, default=0.70)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--pr-auc-tolerance", type=float, default=0.01,
                    help="among settings within this of the best PR-AUC, take the smallest C (simpler model, like urgency)")
    a = ap.parse_args()

    model_path = os.path.join(a.out_dir, f"{a.version}.joblib")
    card_path = os.path.join(a.out_dir, f"{a.version}.json")
    if os.path.exists(model_path) or os.path.exists(card_path):
        sys.exit(f"REFUSED: {a.version} already exists in {a.out_dir}. Frozen versions are never overwritten (D.8): "
                 f"choose a new --version (e.g. bump MODEL_VERSION in the notebook).")
    report_dir = a.report_dir or a.out_dir
    os.makedirs(a.out_dir, exist_ok=True)
    os.makedirs(report_dir, exist_ok=True)
    configs = json.loads(a.configs)

    data = pd.read_csv(a.pool, keep_default_na=False, na_values=[""])
    data = data[data["text_clean"].fillna("").str.len() > 0].copy()
    data["label"] = data["label"].astype(int)
    data["group"] = data["script_id"].fillna(data["conversation_id"]).astype(str)
    # hand-written rows are the cases the rule cannot label (D.3 / D.4): they always go into training. Split
    # by call, each one was its own group, so ~30% of them used to land in the choose/calibrate splits unused.
    is_hw = data["source"] == "Handwritten"
    tr, hold = group_split(data[~is_hw], a.val_share + a.cal_share, a.seed)
    tr = pd.concat([tr, data[is_hw]])
    val, cal = group_split(hold, a.cal_share / (a.val_share + a.cal_share), a.seed + 1)
    shared = set(tr["group"]) & (set(val["group"]) | set(cal["group"]))
    assert not shared, f"{len(shared)} call groups on two sides of the split"
    print(f"rows: train={len(tr)}  choose={len(val)}  calibrate={len(cal)}  "
          f"(1s in train: {int(tr['label'].sum())})")
    w_tr = np.where(tr["source"] == "Handwritten", a.handwritten_weight, 1.0)
    print(f"hand-written sentences in training: {int((tr['source'] == 'Handwritten').sum())} "
          f"(each counted {a.handwritten_weight:g}x)")

    # ---- placeholder shortcut check (urgency_en train.py step 6c)
    ph = Pipeline([("presence", CountVectorizer(vocabulary=PLACEHOLDERS, binary=True)),
                   ("clf", LogisticRegression(class_weight="balanced", solver="liblinear"))])
    ph.fit(tr["text_clean"], tr["label"])
    ph_auc = float(roc_auc_score(val["label"], ph.predict_proba(val["text_clean"])[:, 1]))
    stop_words = PLACEHOLDERS if ph_auc >= a.placeholder_max_auc else None
    print(f"placeholder-only model ROC-AUC on the choose-split = {ph_auc:.3f} "
          + (f"(>= {a.placeholder_max_auc}: placeholders REMOVED from the features)" if stop_words
             else f"(< {a.placeholder_max_auc}: placeholders kept)"))

    # ---- grid with the keyword-dependence gate
    rows = []
    for cfg in configs:
        pipe = build_pipe(cfg, stop_words).fit(tr["text_clean"], tr["label"], clf__sample_weight=w_tr)
        d = pipe.decision_function(val["text_clean"])
        hidden = top_words(pipe, a.hide_top_n)
        d_hidden = pipe.decision_function(hide_words(val["text_clean"], hidden))
        rows.append({"name": cfg["name"], "pr_auc": round(float(average_precision_score(val["label"], d)), 4),
                     "roc_auc": round(float(roc_auc_score(val["label"], d)), 4),
                     "roc_auc_top_words_hidden": round(float(roc_auc_score(val["label"], d_hidden)), 4),
                     **metrics_at(val["label"], d, 0.0), "top_words": ", ".join(hidden)})
    grid = pd.DataFrame(rows)
    grid["passes_keyword_gate"] = grid["roc_auc_top_words_hidden"] >= a.hidden_auc_min
    print(grid.drop(columns="top_words").to_string(index=False))
    ok = grid[grid["passes_keyword_gate"]]
    if len(ok) == 0:
        print(f"WARNING: no setting keeps ROC-AUC >= {a.hidden_auc_min} with its top {a.hide_top_n} words hidden. "
              "Keeping the best PR-AUC anyway; add more mention-0 / ask-without-keyword rows.")
        ok = grid
    # smallest C within the tolerance: stronger regularisation, so no single word (e.g. "purchase") gets an extreme
    # weight; this usually transfers better to new data (same choice as urgency_en's train.py)
    ok = ok.assign(C=[next(c["C"] for c in configs if c["name"] == n) for n in ok["name"]])
    near = ok[ok["pr_auc"] >= ok["pr_auc"].max() - a.pr_auc_tolerance]
    best_name = near.sort_values("C").iloc[0]["name"]
    best = next(c for c in configs if c["name"] == best_name)
    print(f"Chosen: {best_name} (smallest C within {a.pr_auc_tolerance} PR-AUC of the best)")

    pipe = build_pipe(best, stop_words).fit(tr["text_clean"], tr["label"], clf__sample_weight=w_tr)
    calibrator = LogisticRegression(C=1e6, max_iter=1000).fit(
        pipe.decision_function(cal["text_clean"]).reshape(-1, 1), cal["label"])
    info = {"mask_brands": True}
    p_val = MoneyModel(pipe, calibrator, a.version, a.threshold, info=info).predict_proba(val["text_clean"], clean=False)
    val_metrics = {"pr_auc": round(float(average_precision_score(val["label"], p_val)), 4),
                   "roc_auc": round(float(roc_auc_score(val["label"], p_val)), 4),
                   "brier": round(float(brier_score_loss(val["label"], p_val)), 4),
                   **metrics_at(val["label"], p_val, a.threshold)}
    print("Calibrated, on the choose-split:", val_metrics)

    card = {"version": a.version,
            "created": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "definition": "P(the speaker ASKS for money, card/bank details or a code) - see LABELING.md",
            "label_rule": LABEL_RULE_VERSION,
            "mask_brands": True,
            "threshold": a.threshold,
            "config": best,
            "placeholders_removed": bool(stop_words), "placeholder_only_roc_auc": round(ph_auc, 4),
            "keyword_gate": {"hide_top_n": a.hide_top_n, "hidden_auc_min": a.hidden_auc_min},
            "train_rows": len(tr), "choose_rows": len(val), "calibrate_rows": len(cal),
            "handwritten_weight": a.handwritten_weight, "seed": a.seed,
            "training_data": {"file": os.path.basename(a.pool), "md5": md5(a.pool), "rows": len(data),
                              "label_1": int(data["label"].sum()), "label_0": int((data["label"] == 0).sum()),
                              "by_label_method": data.groupby("label_method").size().to_dict()
                              if "label_method" in data else {}},
            "grid": grid.to_dict("records"), "val_metrics": val_metrics,
            "sklearn_version": sklearn.__version__, "numpy_version": np.__version__,
            "note": "Frozen. Do not overwrite; retrain into a new version."}
    model = MoneyModel(pipe, calibrator, a.version, a.threshold, info=card)
    joblib.dump(model, model_path)
    with open(card_path, "w") as f:
        json.dump(card, f, indent=2, default=str)
    val.assign(score=p_val)[["text_clean", "label", "call_type", "source", "score"]].to_csv(
        os.path.join(report_dir, "E_choose_split.csv"), index=False)
    grid.to_csv(os.path.join(report_dir, "E_grid.csv"), index=False)
    print(f"saved {model_path} and {card_path}")


if __name__ == "__main__":
    main()
