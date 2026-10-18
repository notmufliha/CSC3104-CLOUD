"""
train.py - train + validate the English IMPERSONATION classifier
(TF-IDF + character n-grams + LogisticRegression, Platt-calibrated).

Scores "is the caller falsely claiming to be an authority or trusted
organisation" (police, bank, government, telco, courier, tech support).

Reads
  data/impersonation_en/impersonation_data.csv   (text, label[, source, scenario_id,
                                                    org_category, is_hard_negative, evidence, style])
  data/impersonation_en/gold_test.csv            (same columns; final held-out test, never tuned on)

Writes
  backend/workers/impersonation_en/model/model.pkl        (text_utils.CalibratedModel, unless --no-save)
  backend/workers/impersonation_en/model_card.json        (settings + test/gold metrics)
  backend/workers/impersonation_en/reports/*.csv           (CV tuning table, misclassified rows)

How C and "use character n-grams" are chosen: 5-fold grouped cross-validation on the
development set (85% of the data; the test set is the other 15%, held out and only
used to report numbers). Rows are grouped by scenario_id plus near-duplicate detection
first, so variations of the same script never end up split across train/val/test -
otherwise the score would be fake-good. The calibration step and the reporting
threshold are both fit on out-of-fold scores, never on the test or gold set.

Train with scikit-learn 1.6.1 (pinned in requirements.txt): the server pins the
same version, and a joblib model generally only loads in the version that wrote it.

Usage
  python train.py                       # writes model/model.pkl + reports/
  python train.py --no-save             # just print the CV/test/gold numbers
  python train.py --data PATH --gold PATH --out PATH
"""
import argparse
import json
import os
import random
import sys
import warnings
from functools import partial
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from scipy.special import expit
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, brier_score_loss, precision_recall_fscore_support,
                             roc_auc_score)
from sklearn.model_selection import GroupShuffleSplit, StratifiedGroupKFold
from sklearn.pipeline import FeatureUnion, Pipeline
from sklearn.preprocessing import FunctionTransformer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # so `import text_utils` works when run from elsewhere
import text_utils

warnings.filterwarnings("ignore")

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DEFAULT_DATA = REPO / "data" / "impersonation_en" / "impersonation_data.csv"
DEFAULT_GOLD = REPO / "data" / "impersonation_en" / "gold_test.csv"
DEFAULT_OUT = HERE / "model" / "model.pkl"
REPORTS = HERE / "reports"

RANDOM_STATE = 42
TEST_SIZE = 0.15
N_FOLDS = 5
MIN_PER_CLASS = 5
NEAR_DUP_THRESHOLD = 0.8
NGRAM_RANGE = (1, 2)
MIN_DF = 2
MAX_FEATURES = 30000
C_GRID = sorted({0.1, 0.3, 1, 3, 10, 30})
TRY_CHAR_NGRAMS = True
CALLER_ONLY = True
MASK_BRANDS = True
NOISE_COPIES = 1
MODEL_VERSION = "impersonation_en_v2"
SYNTHETIC_PATTERN = "synthetic|ai_written|llm"


def tidy(frame):
    frame = frame.copy()
    for col, default in [("source", "unknown"), ("scenario_id", ""), ("org_category", ""), ("is_hard_negative", 0), ("style", "")]:
        if col not in frame.columns:
            frame[col] = default
    frame = frame.dropna(subset=["text", "label"])
    frame["label"] = pd.to_numeric(frame["label"], errors="coerce")
    frame = frame[frame["label"].isin([0, 1])].copy()
    frame["label"] = frame["label"].astype(int)
    frame["is_hard_negative"] = pd.to_numeric(frame["is_hard_negative"], errors="coerce").fillna(0).astype(int)
    frame["source"] = frame["source"].fillna("unknown").astype(str)
    frame["is_synthetic"] = frame["source"].str.contains(SYNTHETIC_PATTERN, case=False, regex=True)
    return frame


def build_groups(texts, scenario_ids, thr):
    """Union-find over scenario_id (same script) and near-duplicate TF-IDF similarity, so
    a GroupShuffleSplit/StratifiedGroupKFold never splits variations of the same script
    across train/val/test."""
    n = len(texts)
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb

    first = {}
    for i, s in enumerate(scenario_ids):
        s = "" if pd.isna(s) else str(s)
        if s:
            if s in first:
                union(i, first[s])
            else:
                first[s] = i

    X = TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True).fit_transform(texts)
    for s in range(0, n, 500):
        sims = (X[s:s + 500] @ X.T).tocsr()
        sims.data[sims.data < thr] = 0
        sims.eliminate_zeros()
        coo = sims.tocoo()
        for r, c in zip(coo.row, coo.col):
            if c > s + r:
                union(s + r, c)
    return np.array([find(i) for i in range(n)])


def make_test_split(df, groups, test_size, seed0):
    y = df["label"].values
    for attempt in range(50):
        gss = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed0 + attempt)
        dev_i, test_i = next(gss.split(df, y, groups))
        if all((y[idx] == 1).sum() >= MIN_PER_CLASS and (y[idx] == 0).sum() >= MIN_PER_CLASS for idx in (dev_i, test_i)):
            return dev_i, test_i
    raise RuntimeError("Could not make a split with enough examples of both classes. Add more data.")


def make_pipeline(c, mask, char=False):
    prep = partial(text_utils.prep, mask=mask, caller=CALLER_ONLY)  # a partial of a module-level function can be saved with joblib
    word = TfidfVectorizer(ngram_range=NGRAM_RANGE, min_df=MIN_DF, max_features=MAX_FEATURES, sublinear_tf=True)
    if char:
        feats = FeatureUnion([("word", word),
                              ("char", TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), min_df=MIN_DF, max_features=MAX_FEATURES, sublinear_tf=True))])
    else:
        feats = word
    return Pipeline([("prep", FunctionTransformer(prep, validate=False)), ("tfidf", feats),
                     ("clf", LogisticRegression(C=c, max_iter=2000, random_state=RANDOM_STATE))])


def add_noise(text, rng):
    """Mimic speech-to-text: drop a few words, add fillers, swap letters in a few words."""
    out = []
    for w in str(text).split():
        r = rng.random()
        if r < 0.04:
            continue
        if r < 0.07:
            out.append(rng.choice(["um", "uh", "like", "you know"]))
        if r < 0.10 and len(w) > 4:
            i = rng.randrange(1, len(w) - 2)
            w = w[:i] + w[i + 1] + w[i] + w[i + 2:]
        out.append(w)
    return " ".join(out) if out else str(text)


def noisy_copy(frame, seed):
    rng = random.Random(seed)
    cleaned = text_utils.prep(frame["text"], mask=False, caller=CALLER_ONLY)
    copy = frame.copy()
    copy["text"] = [add_noise(t, rng) for t in cleaned]
    return copy


def with_noise(frame, seed0):
    return pd.concat([frame] + [noisy_copy(frame, seed0 + k) for k in range(NOISE_COPIES)])


def fit_config(frame, c, char, seed0=RANDOM_STATE):
    aug = with_noise(frame, seed0)
    return make_pipeline(c, MASK_BRANDS, char).fit(aug["text"], aug["label"])


def scores_for(y, pred, prob=None):
    p, r, f, _ = precision_recall_fscore_support(y, pred, average="binary", zero_division=0)
    out = {"precision": p, "recall": r, "f1": f}
    if prob is not None:
        out.update({"roc_auc": roc_auc_score(y, prob), "pr_auc": average_precision_score(y, prob), "brier": brier_score_loss(y, prob)})
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA)
    ap.add_argument("--gold", type=Path, default=DEFAULT_GOLD)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--no-save", action="store_true", help="print the CV/test/gold numbers but don't write model.pkl")
    args = ap.parse_args()

    df = tidy(pd.read_csv(args.data))
    df["clean"] = text_utils.prep(df["text"], mask=False, caller=CALLER_ONLY)
    df = df.drop_duplicates(subset="clean").reset_index(drop=True)
    print(f"{len(df)} training rows ({int(df['label'].sum())} impersonation, {len(df) - int(df['label'].sum())} not)")

    groups = build_groups(df["clean"].tolist(), df["scenario_id"].tolist(), NEAR_DUP_THRESHOLD)
    dev_idx, te_idx = make_test_split(df, groups, TEST_SIZE, RANDOM_STATE)
    dev = df.iloc[dev_idx].reset_index(drop=True)
    test = df.iloc[te_idx].reset_index(drop=True)
    dev_groups = groups[dev_idx]

    folds = None
    for attempt in range(50):
        sgkf = StratifiedGroupKFold(n_splits=N_FOLDS, shuffle=True, random_state=RANDOM_STATE + attempt)
        cand = list(sgkf.split(dev, dev["label"], dev_groups))
        if all(dev["label"].values[a].min() != dev["label"].values[a].max() and dev["label"].values[b].min() != dev["label"].values[b].max() for a, b in cand):
            folds = cand
            break
    if folds is None:
        raise RuntimeError("Could not make cross-validation folds with both classes in every fold. Add more data or lower N_FOLDS.")
    print(f"development {len(dev)} rows ({N_FOLDS} CV folds), test {len(test)} rows (held out)")

    # Grid search C x {word-only, word+char n-grams} by cross-validated PR-AUC. Ties go to the simplest option.
    configs = [(char, c) for char in ([False, True] if TRY_CHAR_NGRAMS else [False]) for c in C_GRID]
    y_dev = dev["label"].values
    oof, rows = {}, []
    for char, c in configs:
        z = np.zeros(len(dev))
        fold_ap = []
        for k, (tr, va) in enumerate(folds):
            m = fit_config(dev.iloc[tr], c, char, RANDOM_STATE + 100 * k)
            z[va] = m.decision_function(dev.iloc[va]["text"])
            fold_ap.append(average_precision_score(y_dev[va], z[va]))
        oof[(char, c)] = z
        rows.append((char, c, np.mean(fold_ap)))
    tune = pd.DataFrame(rows, columns=["char_ngrams", "C", "mean_fold_PR_AUC"])
    print(tune.round(4).to_string(index=False))
    tune["rounded"] = tune["mean_fold_PR_AUC"].round(3)
    best = tune.sort_values("rounded", ascending=False, kind="stable").iloc[0]
    best_c, best_char = float(best["C"]), bool(best["char_ngrams"])
    print(f"Chosen: C={best_c}, character n-grams={best_char}")
    z_oof = oof[(best_char, best_c)]

    base = fit_config(dev, best_c, best_char)
    platt = LogisticRegression(C=1e6, max_iter=1000).fit(z_oof.reshape(-1, 1), y_dev)
    model = text_utils.CalibratedModel(base, platt)
    oof_prob = platt.predict_proba(z_oof.reshape(-1, 1))[:, 1]

    def f1_at(t):
        return precision_recall_fscore_support(y_dev, (oof_prob >= t).astype(int), average="binary", zero_division=0)[2]
    threshold = round(float(max(np.linspace(0.05, 0.95, 91), key=lambda t: (round(f1_at(t), 6), -abs(t - 0.5)))), 2)
    print(f"Threshold chosen on out-of-fold scores: {threshold:.2f} (F1 {f1_at(threshold):.3f})")

    REPORTS.mkdir(exist_ok=True)
    cv_errors = dev.assign(prob=oof_prob.round(4), pred=(oof_prob >= threshold).astype(int))
    cv_errors = cv_errors[cv_errors.pred != cv_errors.label][["text", "label", "prob", "source", "org_category", "is_hard_negative"]]
    cv_errors.to_csv(REPORTS / "errors_cross_validation.csv", index=False)
    tune.to_csv(REPORTS / "cv_tuning.csv", index=False)

    test_prob = model.predict_proba(test["text"])[:, 1]
    test_pred = (test_prob >= threshold).astype(int)
    test_metrics = scores_for(test["label"].values, test_pred, test_prob)
    print(f"test set (threshold {threshold}): " + ", ".join(f"{k}={v:.3f}" for k, v in test_metrics.items()))

    gold_metrics = None
    if args.gold.exists():
        gold = tidy(pd.read_csv(args.gold))
        gold_prob = model.predict_proba(gold["text"])[:, 1]
        gold_pred = (gold_prob >= threshold).astype(int)
        gold_metrics = scores_for(gold["label"].values, gold_pred, gold_prob)
        print(f"gold set  (threshold {threshold}): " + ", ".join(f"{k}={v:.3f}" for k, v in gold_metrics.items()))

    card = {
        "model_version": MODEL_VERSION,
        "task": "English institutional impersonation (probability 0 to 1)",
        "features": f"TF-IDF ngram {NGRAM_RANGE}, min_df {MIN_DF}, max_features {MAX_FEATURES}",
        "brand_masking": MASK_BRANDS,
        "caller_only": CALLER_ONLY,
        "threshold_used_for_reporting": threshold,
        "C": best_c,
        "char_ngrams": best_char,
        "n_development_rows": int(len(dev)),
        "n_test_rows": int(len(test)),
        "cv_folds": N_FOLDS,
        "noisy_copies": NOISE_COPIES,
        "calibrated": True,
        "sklearn_version": sklearn.__version__,
        "test_metrics": test_metrics,
    }
    if gold_metrics is not None:
        card["gold_metrics"] = gold_metrics

    if args.no_save:
        print("--no-save: not writing model.pkl / model_card.json")
        return

    args.out.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, args.out)
    with open(HERE / "model_card.json", "w") as f:
        json.dump(card, f, indent=2, default=float)
    print(f"saved {args.out}")


if __name__ == "__main__":
    main()
