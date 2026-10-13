"""
train.py - train + validate the English URGENCY classifier (TF-IDF + LogisticRegression).

Reads
  data/urgency_en/final_cleaned_dataset.csv          (text_raw, label, source, conversation_id, hard_negative)
  backend/workers/urgency_en/training/lexicon_urgency_en.txt   (optional, for the keyword baseline)

Writes
  backend/workers/urgency_en/model/urgency_en.joblib  (dict: pipeline, threshold, metadata) unless --no-save
  backend/workers/urgency_en/reports/train_report.txt (everything printed below)
  backend/workers/urgency_en/reports/*.csv            (tables)

How C and the threshold are chosen: "out-of-source" selection. For every source, a model trained WITHOUT
that source's rows scores them, so every row gets a probability from a model that never saw its dataset.
C and the threshold are picked on those pooled probabilities. The 80/20 validation split is still
reported but no longer used for selection. The gold sets are NOT used anywhere here (see evaluate_gold.py).

Train with scikit-learn 1.4.2 (pinned in requirements-train.txt): the server pins the same version, and a
joblib model generally only loads in the version that wrote it.

Usage
  python backend/workers/urgency_en/train.py [--data PATH] [--out PATH] [--no-source-weight] [--no-save]
"""
import argparse
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from joblib import Parallel, delayed
from sklearn.feature_extraction.text import CountVectorizer, TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, average_precision_score, confusion_matrix, f1_score,
                             precision_recall_curve, precision_score, recall_score, roc_auc_score)
from sklearn.model_selection import GroupShuffleSplit
from sklearn.pipeline import Pipeline

from text_norm import normalize

SEED = 42
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DEFAULT_DATA = REPO / "data" / "urgency_en" / "final_cleaned_dataset.csv"
DEFAULT_OUT = HERE / "model" / "urgency_en.joblib"
LEXICON = HERE / "training" / "lexicon_urgency_en.txt"
REPORTS = HERE / "reports"

# Keeps apostrophes inside words so "don't" / "can't" stay one token. The default sklearn pattern
# would split them into "don" + "t", and negations matter for urgency ("don't hang up").
TOKEN_PATTERN = r"(?u)\b[\w']+\b"
PLACEHOLDERS = ["numtoken", "timetoken", "moneytoken", "phonetoken", "urltoken", "emailtoken"]
# Unigrams + bigrams won every earlier grid search ("act now", "within numtoken"); fixing it keeps the
# out-of-source sweep to len(C_GRID) x n_sources fits.
NGRAM = (1, 2)
C_GRID = [0.3, 1, 3, 10]
# Among Cs whose pooled F1 is within this of the best, take the smallest. Smaller C = stronger
# regularisation = simpler model, which usually transfers better to data that looks different.
F1_TOLERANCE = 0.01

_lines = []


def log(*parts):
    """print() that also remembers the line so the whole run can be saved to train_report.txt."""
    s = " ".join(str(p) for p in parts)
    print(s)
    _lines.append(s)


def save_table(df, name):
    REPORTS.mkdir(parents=True, exist_ok=True)
    df.to_csv(REPORTS / name, index=False)


def make_pipeline(C=1.0, ngram_range=NGRAM):
    # No stopword removal: words like "now", "before", "don't", "must" are stopwords in most lists,
    # but they carry exactly the urgency signal we want.
    # sublinear_tf: a word repeated 10x is not 10x more important (dampens long, repetitive turns).
    # min_df=2: a word seen in only one row is usually a typo/name and just lets the model memorise.
    return Pipeline([
        ("tfidf", TfidfVectorizer(ngram_range=ngram_range, sublinear_tf=True, min_df=2, max_features=50000,
                                  lowercase=True, token_pattern=TOKEN_PATTERN)),
        # class_weight="balanced": positives and negatives count equally even though counts differ.
        # liblinear: solid, deterministic solver for small/medium sparse binary problems.
        ("clf", LogisticRegression(C=C, class_weight="balanced", solver="liblinear", max_iter=3000,
                                   random_state=SEED)),
    ])


def source_weights(df):
    """Per-row weights so that, inside each class, every source contributes the same total weight.

    WHY: Fraud_R1 is 3000 of ~4200 positives. Unweighted, "urgent" would mostly mean "sounds like
    Fraud_R1". Equalising sources forces the model to find patterns shared by ALL positive sources.
    The class total stays equal to its row count, so class_weight="balanced" still works as before.
    Caveat: tiny sources (BETTER30, ~34 rows) get very large per-row weights.
    """
    w = np.ones(len(df))
    for _, cls in df.groupby("label"):
        per_source_total = len(cls) / cls["source"].nunique()
        for _, rows in cls.groupby("source"):
            w[df.index.get_indexer(rows.index)] = per_source_total / len(rows)
    return w


def fit_params(df, use_source_weight):
    # Pipeline routes "clf__sample_weight" to the LogisticRegression step.
    return {"clf__sample_weight": source_weights(df)} if use_source_weight else {}


def positive_proba(pipeline, texts):
    classes = list(pipeline.classes_)
    return pipeline.predict_proba(texts)[:, classes.index(1)]


def metrics_at(y, prob, threshold):
    pred = (prob >= threshold).astype(int)
    return {
        "threshold": round(float(threshold), 4),
        "precision": precision_score(y, pred, zero_division=0),
        "recall": recall_score(y, pred, zero_division=0),
        "f1": f1_score(y, pred, zero_division=0),
        "accuracy": accuracy_score(y, pred),
    }


def best_f1_threshold(y, prob, sample_weight=None):
    """Threshold that maximises F1, and that F1."""
    prec, rec, thr = precision_recall_curve(y, prob, sample_weight=sample_weight)
    f1s = 2 * prec[:-1] * rec[:-1] / np.clip(prec[:-1] + rec[:-1], 1e-12, None)  # last P/R point has no threshold
    i = int(np.argmax(f1s))
    return float(thr[i]), float(f1s[i]), (prec, rec, thr)


def load_data(path):
    df = pd.read_csv(path, usecols=["text_raw", "label", "source", "conversation_id", "hard_negative"],
                     low_memory=False)
    # Fail loudly on bad data instead of silently training on it.
    assert set(df["label"].unique()) <= {0, 1}, f"labels must be 0/1, got {sorted(df['label'].unique())}"
    assert df["conversation_id"].notna().all(), "missing conversation_id"
    df["hard_negative"] = df["hard_negative"].astype(str).str.lower().eq("true")

    # Build the model input with the SAME function the server uses on live text. text_clean was made by
    # a different script and differs on ~16% of rows, so training on it would mean the deployed model
    # sees slightly different text than it learned from (train/serve skew).
    df["text"] = df["text_raw"].fillna("").map(normalize)
    n0 = len(df)
    df = df[df["text"] != ""]
    n_empty = n0 - len(df)
    # Exact duplicates add no information and, if one copy lands in train and one in validation,
    # make validation look better than it is.
    df = df.drop_duplicates(subset=["text", "label"])
    n_dup = n0 - n_empty - len(df)
    log(f"    cleaning: {n0} rows -> dropped {n_empty} empty after normalize, {n_dup} duplicate (text, label) "
        f"-> {len(df)} rows")
    conflicts = df.groupby("text")["label"].nunique().gt(1).sum()
    if conflicts:
        log(f"    note: {conflicts} texts appear with BOTH labels (kept; they cap the achievable accuracy)")

    # normalize() replaces every number with a placeholder. A leftover digit would let the model memorise
    # specific codes/amounts that happen to occur in one class (a shortcut, not urgency).
    has_digit = df["text"].str.contains(r"\d", regex=True)
    if has_digit.any():
        log(f"    rows still containing a digit after normalize: {int(has_digit.sum())}")
    assert not has_digit.any(), "digits remain after normalize - fix text_norm.py"

    # Several rows can come from the same conversation. They must stay on the same side of every split,
    # otherwise the model is tested on near-copies of sentences it trained on (leakage).
    # Prefixing the source avoids accidental ID collisions between datasets.
    df["group"] = df["source"].astype(str) + "_" + df["conversation_id"].astype(str)
    return df.reset_index(drop=True)


def split_counts(df):
    return df.groupby(["source", "label"]).size().rename("rows").reset_index()


def keyword_baseline(texts):
    """1 if any lexicon phrase appears as whole words. Returns None if the lexicon file is missing."""
    if not LEXICON.exists():
        return None
    phrases = []
    for line in LEXICON.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            # Normalise the phrase like the model input ("within 24 hours" -> "within numtoken hours").
            p = normalize(line)
            if p:
                phrases.append(p)
    if not phrases:
        return None
    # Longest first so the alternation prefers full phrases. Lookarounds = whole-word match
    # ("now" must not fire on "know"), treating apostrophes as part of a word.
    alt = "|".join(re.escape(p) for p in sorted(set(phrases), key=len, reverse=True))
    rx = re.compile(rf"(?<![\w'])(?:{alt})(?![\w'])")
    return np.array([1 if rx.search(t) else 0 for t in texts]), len(set(phrases))


def _score_held_out_block(df, C, block_index):
    """Train on everything except one (source, label) block, return probabilities for that block."""
    rest = df.drop(index=block_index)
    m = make_pipeline(C).fit(rest["text"], rest["label"], clf__sample_weight=source_weights(rest))
    return C, block_index, positive_proba(m, df.loc[block_index, "text"])


def out_of_source_selection(df):
    """For each C, give every row a probability from a model that never saw that row's (source, label).

    WHY: a random or grouped split keeps every dataset on both sides, so the model is tested on the same
    styles it trained on - that is why validation looked like F1 0.98 while unseen data looked much worse.
    Holding out a whole source asks the real question: does "urgency" transfer to a new kind of call?
    Positive and negative halves are held out separately, so e.g. BothBosu's urgent rows are scored by a
    model that still saw BothBosu's non-urgent rows (the harder, more honest case).
    """
    blocks = list(df.groupby(["source", "label"]).groups.items())
    # 4 Cs x ~10 blocks independent fits -> run them in parallel.
    results = Parallel(n_jobs=-1)(delayed(_score_held_out_block)(df, C, idx)
                                  for C in C_GRID for _, idx in blocks)
    probs = {C: np.full(len(df), np.nan) for C in C_GRID}
    for C, idx, p in results:
        probs[C][df.index.get_indexer(idx)] = p
    assert all(not np.isnan(p).any() for p in probs.values()), "some rows got no out-of-source probability"

    y = df["label"].to_numpy()
    # Source-balanced weights so the pooled threshold isn't decided by Fraud_R1/CallCenterEN alone.
    w = source_weights(df)
    pos_sources = sorted(df.loc[df["label"] == 1, "source"].unique())
    neg_sources = sorted(df.loc[df["label"] == 0, "source"].unique())
    rows = []
    for C in C_GRID:
        threshold, f1, _ = best_f1_threshold(y, probs[C], sample_weight=w)
        pred = probs[C] >= threshold
        row = {"C": C, "threshold": threshold}
        for s in pos_sources:
            m = ((df["source"] == s) & (df["label"] == 1)).to_numpy()
            row[f"recall {s}"] = pred[m].mean()
        for s in neg_sources:
            m = ((df["source"] == s) & (df["label"] == 0)).to_numpy()
            row[f"acc {s}"] = (~pred[m]).mean()
        row["pooled_f1"] = f1
        rows.append(row)
    return pd.DataFrame(rows), probs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    # On by default: leave-one-source-out showed the unweighted model leans on Fraud_R1's style.
    # (The out-of-source selection always uses source weights; this flag affects validation + final fit.)
    ap.add_argument("--source-weight", action=argparse.BooleanOptionalAction, default=True,
                    help="equalise total weight per source within each class (validation + final fit); "
                         "--no-source-weight turns it off")
    ap.add_argument("--no-save", action="store_true", help="do not write the model file")
    args = ap.parse_args()

    log(f"urgency_en training run {datetime.now(timezone.utc).isoformat(timespec='seconds')}")
    log(f"data={args.data} source_weight={args.source_weight} sklearn={sklearn.__version__} seed={SEED}")
    if not args.source_weight:
        log("WARNING: --no-source-weight: the saved threshold comes from a source-weighted selection, "
            "so it may not suit the unweighted final model")

    # ---- 1. load + clean ----------------------------------------------------------------------------
    log("\n[1] load + normalize text_raw")
    df = load_data(args.data)
    log(f"    {len(df)} rows, {df['group'].nunique()} groups, "
        f"positives={int(df['label'].sum())} negatives={int((df['label'] == 0).sum())}")

    # ---- 2. group-aware train/validation split --------------------------------------------------------
    # GroupShuffleSplit puts whole conversations on one side. The validation set is only REPORTED now
    # (in-distribution performance); selection happens out-of-source in step 4.
    gss = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED)
    tr_idx, va_idx = next(gss.split(df, df["label"], groups=df["group"]))
    train, val = df.iloc[tr_idx].reset_index(drop=True), df.iloc[va_idx].reset_index(drop=True)
    overlap = set(train["group"]) & set(val["group"])
    assert not overlap, f"{len(overlap)} groups appear in both train and validation"
    log(f"\n[2] split: train={len(train)} rows, validation={len(val)} rows, shared groups=0")
    counts = split_counts(train).merge(split_counts(val), on=["source", "label"], how="outer",
                                       suffixes=("_train", "_val")).fillna(0)
    counts[["rows_train", "rows_val"]] = counts[["rows_train", "rows_val"]].astype(int)
    log(counts.sort_values(["label", "source"], ascending=[False, True]).to_string(index=False))
    save_table(counts, "split_counts.csv")
    y_val = val["label"].to_numpy()

    # ---- 3. baselines -------------------------------------------------------------------------------
    # A model is only interesting if it beats trivial rules. "Always 0" shows what accuracy alone hides;
    # the keyword rule shows how much a hand-written list already gets.
    log("\n[3] baselines on validation")
    base_rows = [{"baseline": "always_0", **metrics_at(y_val, np.zeros(len(val)), 0.5)}]
    kw = keyword_baseline(val["text"])
    if kw is None:
        log(f"  keyword rule: SKIPPED - lexicon not found/empty at {LEXICON}")
    else:
        kw_pred, n_phrases = kw
        base_rows.append({"baseline": f"keyword_rule ({n_phrases} phrases)", **metrics_at(y_val, kw_pred, 0.5)})
    baselines = pd.DataFrame(base_rows)
    log(baselines.round(4).to_string(index=False))
    save_table(baselines, "baselines.csv")

    # ---- 4. out-of-source selection of C and threshold ------------------------------------------------
    log(f"\n[4] out-of-source selection (all data, source-weighted, ngram={NGRAM}, C in {C_GRID})")
    log("    each row scored by a model trained without its (source, label) block; "
        "threshold = max source-weighted pooled F1")
    oos, oos_probs = out_of_source_selection(df)
    log(oos.round(4).to_string(index=False))
    save_table(oos, "out_of_source_selection.csv")
    best_f1 = oos["pooled_f1"].max()
    chosen = oos[oos["pooled_f1"] >= best_f1 - F1_TOLERANCE].sort_values("C").iloc[0]
    C, threshold = float(chosen["C"]), float(chosen["threshold"])
    log(f"    best pooled F1 = {best_f1:.4f}; smallest C within {F1_TOLERANCE} -> "
        f"C={C:g}, threshold={threshold:.4f}, pooled F1={chosen['pooled_f1']:.4f}")
    best_params = {"clf__C": C, "tfidf__ngram_range": list(NGRAM)}

    # ---- 5. validation report (reported only, not used for selection) --------------------------------
    # Same C and threshold as the deployed model, trained on the 80% split only.
    log("\n[5] validation report (in-distribution; C and threshold from step 4)")
    model = make_pipeline(C).fit(train["text"], train["label"], **fit_params(train, args.source_weight))
    p_val = positive_proba(model, val["text"])
    val_thr, val_f1, (prec, rec, thr) = best_f1_threshold(y_val, p_val)
    log(f"    for reference only: validation's own max-F1 threshold would be {val_thr:.4f} (F1={val_f1:.4f})")
    ok = np.where(rec[:-1] >= 0.80)[0]
    if len(ok):
        i = ok[-1]  # highest threshold that still keeps recall >= 0.80 (best precision at that recall)
        log(f"    for reference only: recall >= 0.80 at threshold {thr[i]:.4f} -> precision={prec[i]:.4f}")
    val_metrics = pd.DataFrame([{"at": "out_of_source", **metrics_at(y_val, p_val, threshold)},
                                {"at": "0.5", **metrics_at(y_val, p_val, 0.5)}])
    log(val_metrics.round(4).to_string(index=False))
    roc, pr = roc_auc_score(y_val, p_val), average_precision_score(y_val, p_val)
    log(f"  ROC-AUC={roc:.4f}  PR-AUC={pr:.4f}  (PR-AUC of random guessing = {y_val.mean():.4f})")
    pred_val = (p_val >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_val, pred_val, labels=[0, 1]).ravel()
    log(f"  confusion matrix @out_of_source threshold: TN={tn} FP={fp} FN={fn} TP={tp}")
    save_table(val_metrics.assign(roc_auc=roc, pr_auc=pr, tn=tn, fp=fp, fn=fn, tp=tp), "validation_metrics.csv")

    # Per-source accuracy shows whether the model only works for some datasets. Each source here is
    # (almost) single-class, so accuracy = recall for positive sources and specificity for negative ones.
    val = val.assign(prob=p_val, pred=pred_val, correct=(pred_val == y_val))
    per_source = (val.groupby(["source", "label"])
                  .agg(rows=("correct", "size"), accuracy=("correct", "mean"), mean_prob=("prob", "mean"))
                  .reset_index())
    log(per_source.round(4).to_string(index=False))
    save_table(per_source, "validation_per_source.csv")
    # Hard negatives are non-urgent sentences that look urgent ("please hold, I'll transfer you now").
    # They are the most realistic false-alarm risk, so report them separately.
    hn = val[val["hard_negative"]]
    if len(hn):
        log(f"  hard negatives: {len(hn)} rows, accuracy={hn['correct'].mean():.4f} "
            f"(flagged as urgent: {int(hn['pred'].sum())})")
    else:
        log("  hard negatives: none in validation")

    # ---- 6. shortcut diagnostics --------------------------------------------------------------------
    log("\n[6] shortcut diagnostics")
    # 6a. The biggest weights tell you WHAT the model learned. Look for words that are dataset artefacts
    # (names, greetings typical of one corpus) rather than urgency language.
    names = model.named_steps["tfidf"].get_feature_names_out()
    coefs = model.named_steps["clf"].coef_[0]
    order = np.argsort(coefs)
    pos_i, neg_i = order[::-1][:30], order[:30]
    log("  6a. top 30 urgent features:     " + ", ".join(f"{names[i]}({coefs[i]:.2f})" for i in pos_i))
    log("      top 30 not-urgent features: " + ", ".join(f"{names[i]}({coefs[i]:.2f})" for i in neg_i))
    save_table(pd.concat([
        pd.DataFrame({"direction": "urgent", "feature": names[pos_i], "weight": coefs[pos_i]}),
        pd.DataFrame({"direction": "not_urgent", "feature": names[neg_i], "weight": coefs[neg_i]}),
    ]), "top_features.csv")

    # 6b. If the same features can easily tell WHICH dataset a row came from, the urgency model may be
    # learning "Fraud_R1 style vs DailyDialog style" instead of urgency. Compare with the majority baseline.
    src_model = Pipeline([
        ("tfidf", TfidfVectorizer(ngram_range=NGRAM, sublinear_tf=True, min_df=2, max_features=50000,
                                  lowercase=True, token_pattern=TOKEN_PATTERN)),
        # lbfgs instead of liblinear: this is a 10-class problem and lbfgs handles multiclass natively.
        ("clf", LogisticRegression(C=C, solver="lbfgs", max_iter=3000)),
    ]).fit(train["text"], train["source"])
    src_acc = accuracy_score(val["source"], src_model.predict(val["text"]))
    majority = val["source"].value_counts(normalize=True).iloc[0]
    log(f"  6b. source-guess accuracy on validation = {src_acc:.4f} (majority-class baseline {majority:.4f}). "
        f"High = sources are stylistically easy to tell apart, so per-source results matter.")

    # 6c. Placeholder-only model: if just knowing "this row has a phone number / amount / time" separates
    # the classes well, the main model can lean on that instead of urgency wording.
    tok_model = Pipeline([
        ("presence", CountVectorizer(vocabulary=PLACEHOLDERS, binary=True, token_pattern=TOKEN_PATTERN)),
        ("clf", LogisticRegression(class_weight="balanced", solver="liblinear", random_state=SEED)),
    ]).fit(train["text"], train["label"])
    tok_auc = roc_auc_score(y_val, positive_proba(tok_model, val["text"]))
    log(f"  6c. placeholder-only model ROC-AUC on validation = {tok_auc:.4f} (0.5 = no signal)")
    # (Leftover digits are enforced as an assertion in load_data, right after cleaning.)
    save_table(pd.DataFrame([{"check": "source_guess_accuracy", "value": src_acc},
                             {"check": "source_guess_majority_baseline", "value": majority},
                             {"check": "placeholder_only_roc_auc", "value": tok_auc}]),
               "shortcut_checks.csv")

    # ---- 7. final fit on all data ---------------------------------------------------------------------
    # Selection is done, so every row goes into the deployed model. C and threshold stay as chosen in 4.
    final = make_pipeline(C).fit(df["text"], df["label"], **fit_params(df, args.source_weight))
    bundle = {
        "pipeline": final,
        "threshold": threshold,
        "signal": "urgency",
        "language": "en",
        "trained_rows": len(df),
        "best_params": best_params,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "threshold_method": "out_of_source_pooled_f1",
        # joblib files only load reliably with the same scikit-learn version that wrote them.
        "sklearn_version": sklearn.__version__,
    }
    if args.no_save:
        log(f"\n[7] --no-save: final model fitted on {len(df)} rows but not written")
    else:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        joblib.dump(bundle, args.out)
        log(f"\n[7] saved {args.out} (trained_rows={len(df)}, C={C:g}, threshold={threshold:.4f})")

    # ---- 8. report ----------------------------------------------------------------------------------
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "train_report.txt").write_text("\n".join(_lines) + "\n", encoding="utf-8")
    print(f"report -> {REPORTS / 'train_report.txt'}")


if __name__ == "__main__":
    sys.exit(main())
