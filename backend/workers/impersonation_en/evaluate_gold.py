"""
evaluate_gold.py - score the saved model/model.pkl on the held-out GOLD test set,
without retraining. Useful to sanity-check the committed model, or a model someone
else retrained with train.py, against the final exam.

Reads
  backend/workers/impersonation_en/model/model.pkl   (from train.py)
  backend/workers/impersonation_en/model_card.json   (for the reporting threshold)
  data/impersonation_en/gold_test.csv                (text,label[,source,org_category,...])

The gold set is the final exam: nothing here feeds back into training or the
threshold. If you change the model because of these numbers, they stop being an
honest estimate - go add more training data instead.

Usage
  python evaluate_gold.py
  python evaluate_gold.py --gold path/to/other_gold.csv --threshold 0.5
"""
import argparse
import json
import os
import sys
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import (average_precision_score, brier_score_loss, confusion_matrix,
                             precision_recall_fscore_support, roc_auc_score)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import text_utils  # noqa: F401 -- required so joblib.load can resolve the pickled pipeline

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DEFAULT_MODEL = HERE / "model" / "model.pkl"
DEFAULT_CARD = HERE / "model_card.json"
DEFAULT_GOLD = REPO / "data" / "impersonation_en" / "gold_test.csv"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    ap.add_argument("--gold", type=Path, default=DEFAULT_GOLD)
    ap.add_argument("--threshold", type=float, default=None, help="default: threshold_used_for_reporting from model_card.json")
    args = ap.parse_args()

    if not args.model.exists():
        raise SystemExit(f"{args.model} not found. Run train.py first (or it should already be checked in).")
    model = joblib.load(args.model)

    threshold = args.threshold
    if threshold is None and DEFAULT_CARD.exists():
        threshold = json.loads(DEFAULT_CARD.read_text())["threshold_used_for_reporting"]
    threshold = threshold if threshold is not None else 0.5

    gold = pd.read_csv(args.gold)
    gold = gold.dropna(subset=["text", "label"])
    gold["label"] = gold["label"].astype(int)

    prob = model.predict_proba(gold["text"])[:, 1]
    pred = (prob >= threshold).astype(int)
    y = gold["label"].values

    p, r, f, _ = precision_recall_fscore_support(y, pred, average="binary", zero_division=0)
    print(f"gold rows: {len(gold)} ({int(y.sum())} impersonation)  threshold: {threshold}")
    print(f"precision={p:.3f} recall={r:.3f} f1={f:.3f} roc_auc={roc_auc_score(y, prob):.3f} "
          f"pr_auc={average_precision_score(y, prob):.3f} brier={brier_score_loss(y, prob):.3f}")
    print("confusion matrix [[tn, fp], [fn, tp]]:")
    print(confusion_matrix(y, pred, labels=[0, 1]))

    if "source" in gold.columns:
        errs = gold.assign(prob=prob.round(4), pred=pred)
        errs = errs[errs.pred != errs.label][["text", "label", "prob", "source"]]
        if len(errs):
            print(f"\n{len(errs)} misclassified rows:")
            print(errs.to_string(index=False, max_colwidth=90))


if __name__ == "__main__":
    main()
