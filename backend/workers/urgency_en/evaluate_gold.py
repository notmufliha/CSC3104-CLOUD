"""
evaluate_gold.py - score the saved urgency model on human-labelled GOLD test sets.

Reads
  backend/workers/urgency_en/model/urgency_en.joblib      (from train.py; uses its SAVED threshold)
  one or more gold CSVs with RAW text: columns text,label[,category]
      e.g. backend/workers/urgency_en/eval/gold_handwritten.csv, eval/robocall_gold.csv
  data/urgency_en/final_cleaned_dataset.csv               (only to check gold/training overlap)

Writes
  backend/workers/urgency_en/reports/gold_report_<gold filename>.txt   (one per gold CSV)

The gold set is the final exam. Nothing here feeds back into training or the threshold; if you change
the model because of these numbers, they stop being an honest estimate.

Usage
  python backend/workers/urgency_en/evaluate_gold.py --gold backend/workers/urgency_en/eval/gold_handwritten.csv
"""
import argparse
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import (average_precision_score, confusion_matrix, f1_score, precision_score,
                             recall_score, roc_auc_score)

from text_norm import normalize

SEED = 42
HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = HERE / "model" / "urgency_en.joblib"
DEFAULT_DATA = HERE.parents[2] / "data" / "urgency_en" / "final_cleaned_dataset.csv"
REPORTS = HERE / "reports"
WARNING = "!!! Gold set = final test. Do not tune anything based on this output. !!!"
N_BOOT = 1000
SIM_LIMIT = 0.9


def positive_proba(pipeline, texts):
    return pipeline.predict_proba(texts)[:, list(pipeline.classes_).index(1)]


def overlap_check(gold_clean, train_clean, train_source, tfidf, log):
    """Warn if a gold sentence is (nearly) a training sentence - then it would test memory, not skill."""
    train_set = set(train_clean)
    exact = [i for i, t in enumerate(gold_clean) if t in train_set]
    # The fitted TF-IDF rows are L2-normalised, so a dot product IS the cosine similarity.
    g, t = tfidf.transform(gold_clean), tfidf.transform(train_clean)
    sims = (g @ t.T).tocsr()
    near = []
    for i in range(sims.shape[0]):
        row = sims.getrow(i)
        if row.nnz and row.data.max() >= SIM_LIMIT and i not in exact:
            j = row.indices[row.data.argmax()]
            near.append((i, row.data.max(), j))
    if not exact and not near:
        log(f"overlap check: OK (no exact matches, no cosine >= {SIM_LIMIT} with {len(train_clean)} training rows)")
        return
    log(f"WARNING overlap: {len(exact)} exact matches, {len(near)} near-duplicates (cosine >= {SIM_LIMIT}). "
        "These rows inflate the score; consider rewriting or removing them from the gold set.")
    for i in exact:
        log(f"  exact  | gold #{i}: {gold_clean[i]}")
    for i, s, j in near:
        log(f"  {s:.3f}  | gold #{i}: {gold_clean[i]}\n         | train ({train_source[j]}): {train_clean[j]}")


def bootstrap_ci(y, prob, pred, log):
    """95% CI by resampling the gold rows with replacement 1,000 times.

    WHY: gold sets are small, so one number (e.g. F1 = 0.82) hides a lot of luck. The interval shows how
    much the score could move with a different sample of sentences of the same kind.
    """
    rng = np.random.default_rng(SEED)
    aucs, f1s = [], []
    for _ in range(N_BOOT):
        idx = rng.integers(0, len(y), len(y))
        f1s.append(f1_score(y[idx], pred[idx], zero_division=0))
        if len(np.unique(y[idx])) == 2:  # AUC is undefined when a resample has only one class
            aucs.append(roc_auc_score(y[idx], prob[idx]))
    for name, vals in (("ROC-AUC", aucs), ("F1", f1s)):
        if vals:
            lo, hi = np.percentile(vals, [2.5, 97.5])
            log(f"  {name} 95% CI: [{lo:.4f}, {hi:.4f}]  ({len(vals)} resamples)")


def evaluate(path, bundle, train_df, log):
    gold = pd.read_csv(path)
    missing = {"text", "label"} - set(gold.columns)
    if missing:
        raise SystemExit(f"{path}: missing column(s) {sorted(missing)}")
    gold = gold.dropna(subset=["text", "label"]).reset_index(drop=True)
    gold["label"] = gold["label"].astype(int)
    # Gold text is RAW, like a live transcript, so it gets exactly the cleaning used to build text_clean.
    gold["clean"] = gold["text"].map(normalize)
    empty = gold["clean"].eq("")
    if empty.any():
        log(f"note: {int(empty.sum())} rows are empty after normalisation and were dropped")
        gold = gold[~empty].reset_index(drop=True)

    pipeline, threshold = bundle["pipeline"], bundle["threshold"]
    log(f"gold file: {path}  rows={len(gold)}  positives={int(gold['label'].sum())}")
    log(f"model: created_at={bundle.get('created_at')} trained_rows={bundle.get('trained_rows')} "
        f"best_params={bundle.get('best_params')}  saved threshold={threshold:.4f}")

    overlap_check(gold["clean"].tolist(), train_df["text"].tolist(), train_df["source"].tolist(),
                  pipeline.named_steps["tfidf"], log)

    y = gold["label"].to_numpy()
    prob = positive_proba(pipeline, gold["clean"])
    pred = (prob >= threshold).astype(int)
    log(f"\nprecision={precision_score(y, pred, zero_division=0):.4f}  "
        f"recall={recall_score(y, pred, zero_division=0):.4f}  f1={f1_score(y, pred, zero_division=0):.4f}  "
        f"accuracy={(pred == y).mean():.4f}")
    if len(np.unique(y)) == 2:
        log(f"ROC-AUC={roc_auc_score(y, prob):.4f}  PR-AUC={average_precision_score(y, prob):.4f}")
    else:
        log("ROC-AUC/PR-AUC: undefined (gold set contains only one class)")
    tn, fp, fn, tp = confusion_matrix(y, pred, labels=[0, 1]).ravel()
    log(f"confusion matrix: TN={tn} FP={fp} FN={fn} TP={tp}")
    bootstrap_ci(y, prob, pred, log)

    gold = gold.assign(prob=prob, pred=pred, correct=pred == y)
    if "category" in gold.columns:
        # Categories show WHICH kinds of sentences fail (e.g. polite urgency, hard negatives).
        cat = (gold.groupby("category").agg(rows=("correct", "size"), positives=("label", "sum"),
                                            accuracy=("correct", "mean"), mean_prob=("prob", "mean"))
               .reset_index())
        log("\naccuracy per category:\n" + cat.round(4).to_string(index=False))

    mistakes = gold[~gold["correct"]].sort_values("prob", ascending=False)
    log(f"\nmistakes ({len(mistakes)}):")
    for r in mistakes.itertuples():
        kind = "FP" if r.pred == 1 else "FN"
        extra = f" [{r.category}]" if "category" in gold.columns else ""
        log(f"  {kind} p={r.prob:.4f}{extra} {r.text}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gold", type=Path, nargs="+", required=True, help="one or more gold CSVs")
    ap.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA, help="training CSV, for the overlap check")
    args = ap.parse_args()

    print(WARNING)
    bundle = joblib.load(args.model)
    # Rebuild the training input exactly as train.py does, so "overlap" means overlap with what the
    # model actually learned from.
    train_df = pd.read_csv(args.data, usecols=["text_raw", "label", "source"], low_memory=False)
    train_df["text"] = train_df["text_raw"].fillna("").map(normalize)
    train_df = train_df[train_df["text"] != ""].drop_duplicates(subset=["text", "label"]).reset_index(drop=True)
    REPORTS.mkdir(parents=True, exist_ok=True)

    for path in args.gold:
        lines = [WARNING]

        def log(s=""):
            print(s)
            lines.append(s)

        evaluate(path, bundle, train_df, log)
        out = REPORTS / f"gold_report_{path.stem}.txt"
        out.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"report -> {out}\n")


if __name__ == "__main__":
    main()
