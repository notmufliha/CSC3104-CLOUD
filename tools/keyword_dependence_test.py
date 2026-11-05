"""
keyword_dependence_test.py - generic version of the Workstream D.2 keyword-dependence
test (see backend/workers/impersonation_en/keyword_test.py for the worker-specific one).

Finds the top positive-weight word features in a scikit-learn linear classifier,
masks them out of a gold/test CSV, and reports how much precision/recall/F1/ROC-AUC
drop. A model whose ROC-AUC drops by more than about a third when its top words are
masked is leaning hard on those words rather than on behaviour.

This script makes a best effort to find a fitted TfidfVectorizer-like step
(anything with get_feature_names_out()) and a fitted linear classifier-like step
(anything with coef_) inside your saved model, trying a few common attribute names
used by the workers in this repo (CalibratedModel.base, MoneyModel.pipe, or the
model itself if it is already a Pipeline). If it cannot find both, it still scores
before/after on an empty mask (no-op) and tells you so - look at your own model's
class and adapt the `find_vectorizer_and_classifier` function if needed.

Reads
  --model   joblib-pickled model (must expose .predict_proba(list_of_str))
  --data    CSV with text,label columns (your held-out gold/test set)

Writes
  <model filename>_keyword_test.json next to --model, unless --out is given

Usage
  python tools/keyword_dependence_test.py --model backend/workers/money_en/model/model.pkl \
      --data data/money_en/gold_test.csv
"""
import argparse
import json
import re
import sys
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import precision_recall_fscore_support, roc_auc_score


def add_model_dirs_to_path(model_path):
    """The pickled model usually references a helper module (text_utils, money_norm, ...) that
    sits next to the worker's predict.py/server.py, one or two directories up from model.pkl.
    Put both on sys.path so joblib.load can resolve it, same as each worker's own predict.py does."""
    for p in (model_path.parent, model_path.parent.parent):
        sp = str(p.resolve())
        if sp not in sys.path:
            sys.path.insert(0, sp)


def find_vectorizer_and_classifier(model):
    """Best-effort introspection. Returns (vectorizer_or_None, classifier_or_None)."""
    candidates = [model]
    for attr in ("base", "pipe", "pipeline", "model"):
        if hasattr(model, attr):
            candidates.append(getattr(model, attr))

    vec, clf = None, None
    for obj in candidates:
        steps = getattr(obj, "named_steps", None) or getattr(obj, "steps", None)
        if steps is None:
            continue
        items = steps.items() if hasattr(steps, "items") else steps
        for _, step in items:
            if vec is None and hasattr(step, "get_feature_names_out"):
                vec = step
            if clf is None and hasattr(step, "coef_"):
                clf = step
    return vec, clf


def top_word_features(vec, clf, top_n):
    if vec is None or clf is None:
        return []
    try:
        names = vec.get_feature_names_out()
    except Exception:
        return []
    coef = clf.coef_[0]
    if len(coef) != len(names):
        return []  # FeatureUnion or similar with a shape we don't understand here; skip
    pairs = sorted(zip(names, coef), key=lambda p: -p[1])
    return [(n, float(w)) for n, w in pairs[:top_n]]


def mask_text(text, feats):
    """feats are raw feature names, which may carry a FeatureUnion prefix like
    'word__police' or 'char__ban'. Strip the prefix before matching the real text.
    A feature whose prefix contains 'char' is a character n-gram: match it as a plain
    substring (it is often part of a word, not a whole word). Anything else is matched
    as a whole word, same as the worker-specific keyword_test.py does."""
    t = str(text)
    for name, _ in feats:
        prefix, _, bare = name.partition("__")
        word = bare if bare else name
        if not word.strip():
            continue
        if bare and "char" in prefix.lower():
            t = re.sub(re.escape(word), " ", t, flags=re.I)
        else:
            t = re.sub(r"\b" + re.escape(word) + r"\b", " MASKED ", t, flags=re.I)
    return t


def metrics(y, prob, threshold):
    pred = (prob >= threshold).astype(int)
    p, r, f, _ = precision_recall_fscore_support(y, pred, average="binary", zero_division=0)
    return {"precision": round(float(p), 4), "recall": round(float(r), 4), "f1": round(float(f), 4),
            "roc_auc": round(float(roc_auc_score(y, prob)), 4)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, required=True)
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--threshold", type=float, default=0.5)
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--out", type=Path, default=None)
    args = ap.parse_args()

    add_model_dirs_to_path(args.model)
    model = joblib.load(args.model)
    vec, clf = find_vectorizer_and_classifier(model)
    feats = top_word_features(vec, clf, args.top)
    if feats:
        print(f"Top {len(feats)} word features (highest positive weight):")
        for w, wt in feats:
            print(f"  {wt:+.3f}  {w!r}")
    else:
        print("Could not auto-detect a vectorizer + linear classifier pair inside this model.")
        print("Masking will be a no-op (before == after). Adapt find_vectorizer_and_classifier() for your model's shape.")

    df = pd.read_csv(args.data).dropna(subset=["text", "label"])
    df["label"] = df["label"].astype(int)
    y = df["label"].values

    prob_before = model.predict_proba(df["text"])[:, 1]
    masked = df["text"].apply(lambda t: mask_text(t, feats))
    prob_after = model.predict_proba(masked)[:, 1]

    m_before = metrics(y, prob_before, args.threshold)
    m_after = metrics(y, prob_after, args.threshold)
    drop = {k: round(m_before[k] - m_after[k], 4) for k in m_before}
    roc_drop_frac = (m_before["roc_auc"] - m_after["roc_auc"]) / m_before["roc_auc"] if m_before["roc_auc"] else 0.0

    print(f"\n{'metric':10} {'before':>8} {'after':>8} {'drop':>8}")
    for k in m_before:
        print(f"{k:10} {m_before[k]:8.3f} {m_after[k]:8.3f} {drop[k]:8.3f}")
    print(f"\nROC-AUC relative drop: {roc_drop_frac:.1%}")
    verdict = "KEYWORD DETECTOR (ROC-AUC dropped by more than a third)" if roc_drop_frac > 1 / 3 \
        else "not primarily a keyword detector (ROC-AUC held up after masking top words)"
    print(verdict)

    out_path = args.out or args.model.with_name(args.model.stem + "_keyword_test.json")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "model": str(args.model), "data": str(args.data), "threshold": args.threshold,
            "top_word_features": feats, "metrics_before": m_before, "metrics_after_masking": m_after,
            "drop": drop, "roc_auc_relative_drop": round(roc_drop_frac, 4), "verdict": verdict,
            "rows": int(len(df)),
        }, f, indent=2)
    print(f"\nsaved {out_path}")


if __name__ == "__main__":
    main()
