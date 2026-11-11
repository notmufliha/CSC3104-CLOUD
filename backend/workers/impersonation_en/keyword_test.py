"""
keyword_test.py - Workstream D.2: is impersonation_en just a keyword detector?

Finds the words and character n-grams the model leans on the most (the
feature weights with the biggest positive push toward label 1), masks them
out of the gold set, and checks how much the score drops. If the model is
mostly reading a short word list, masking those words should wreck recall.
If it is reading behaviour (claim + scam cues together), masking a handful
of words should only hurt a little.

Reads
  backend/workers/impersonation_en/model/model.pkl
  backend/workers/impersonation_en/model_card.json   (reporting threshold)
  data/impersonation_en/gold_test.csv                (never used for training)

Writes
  backend/workers/impersonation_en/training_outputs/keyword_test_<model_version>.json

Usage
  python keyword_test.py
  python keyword_test.py --top 20
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import precision_recall_fscore_support, roc_auc_score

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import text_utils  # noqa: F401 -- required so joblib.load can resolve the pickled pipeline

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DEFAULT_MODEL = HERE / "model" / "model.pkl"
DEFAULT_CARD = HERE / "model_card.json"
DEFAULT_GOLD = REPO / "data" / "impersonation_en" / "gold_test.csv"
DEFAULT_OUT_DIR = HERE / "training_outputs"


def top_features(model, top_n):
    """Returns (word_features, char_features): each a list of (feature_text, weight),
    biggest positive weight first. Specific to this worker's pipeline shape:
    CalibratedModel(base=Pipeline([prep, tfidf, clf]), calibrator), where tfidf is
    either a single word TfidfVectorizer or a FeatureUnion of word + char_wb vectorizers."""
    base = model.base
    tfidf = base.named_steps["tfidf"]
    clf = base.named_steps["clf"]
    coef = clf.coef_[0]

    if hasattr(tfidf, "transformer_list"):  # FeatureUnion(word, char)
        names = tfidf.get_feature_names_out()
    else:  # word-only TfidfVectorizer
        names = [f"word__{n}" for n in tfidf.get_feature_names_out()]

    pairs = list(zip(names, coef))
    word_pairs = sorted([(n[len("word__"):], w) for n, w in pairs if n.startswith("word__")], key=lambda p: -p[1])
    char_pairs = sorted([(n[len("char__"):], w) for n, w in pairs if n.startswith("char__")], key=lambda p: -p[1])
    return word_pairs[:top_n], char_pairs[:top_n]


def mask_text(text, word_feats, char_feats):
    t = str(text)
    for w, _ in word_feats:
        if not w.strip():
            continue
        t = re.sub(r"\b" + re.escape(w) + r"\b", " MASKED ", t, flags=re.I)
    for c, _ in char_feats:
        if not c.strip():
            continue
        t = re.sub(re.escape(c), " ", t, flags=re.I)
    return t


def metrics(y, prob, threshold):
    pred = (prob >= threshold).astype(int)
    p, r, f, _ = precision_recall_fscore_support(y, pred, average="binary", zero_division=0)
    return {"precision": round(float(p), 4), "recall": round(float(r), 4), "f1": round(float(f), 4),
            "roc_auc": round(float(roc_auc_score(y, prob)), 4)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    ap.add_argument("--gold", type=Path, default=DEFAULT_GOLD)
    ap.add_argument("--card", type=Path, default=DEFAULT_CARD)
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    args = ap.parse_args()

    model = joblib.load(args.model)
    card = json.loads(args.card.read_text(encoding="utf-8")) if args.card.exists() else {}
    threshold = card.get("threshold_used_for_reporting", 0.5)
    model_version = card.get("model_version", "unversioned")

    word_feats, char_feats = top_features(model, args.top)
    print(f"Top {len(word_feats)} word features (highest positive weight toward impersonation):")
    for w, wt in word_feats:
        print(f"  {wt:+.3f}  {w!r}")
    print(f"\nTop {len(char_feats)} character n-gram features:")
    for c, wt in char_feats:
        print(f"  {wt:+.3f}  {c!r}")

    gold = pd.read_csv(args.gold).dropna(subset=["text", "label"])
    gold["label"] = gold["label"].astype(int)
    y = gold["label"].values

    prob_before = model.predict_proba(gold["text"])[:, 1]
    masked_text = gold["text"].apply(lambda t: mask_text(t, word_feats, char_feats))
    prob_after = model.predict_proba(masked_text)[:, 1]

    m_before = metrics(y, prob_before, threshold)
    m_after = metrics(y, prob_after, threshold)
    drop = {k: round(m_before[k] - m_after[k], 4) for k in m_before}
    roc_drop_frac = (m_before["roc_auc"] - m_after["roc_auc"]) / m_before["roc_auc"] if m_before["roc_auc"] else 0.0

    print(f"\n{'metric':10} {'before':>8} {'after':>8} {'drop':>8}")
    for k in m_before:
        print(f"{k:10} {m_before[k]:8.3f} {m_after[k]:8.3f} {drop[k]:8.3f}")
    print(f"\nROC-AUC relative drop: {roc_drop_frac:.1%}")
    verdict = "KEYWORD DETECTOR (ROC-AUC dropped by more than a third)" if roc_drop_frac > 1 / 3 \
        else "not primarily a keyword detector (ROC-AUC held up after masking top words)"
    print(verdict)

    args.out_dir.mkdir(exist_ok=True)
    out_path = args.out_dir / f"keyword_test_{model_version}.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({
            "model_version": model_version,
            "threshold": threshold,
            "top_word_features": word_feats,
            "top_char_features": char_feats,
            "metrics_before": m_before,
            "metrics_after_masking": m_after,
            "drop": drop,
            "roc_auc_relative_drop": round(roc_drop_frac, 4),
            "verdict": verdict,
            "gold_rows": int(len(gold)),
        }, f, indent=2)
    print(f"\nsaved {out_path}")


if __name__ == "__main__":
    main()
