"""train_zh.py - ONE training script for the three Chinese workers (urgency_zh, money_zh, impersonation_zh).

Each worker's train.py is just `train_zh.main("<signal>")`; each *_zh Dockerfile copies this file next to it
(like text_norm.py), so all three train the same way.

TF-IDF + Logistic Regression, sigmoid-calibrated; the winning settings from zh_train.ipynb (Plan 3).

v6 changes (2026-10-25)
  * Train/serve parity: the model input is built from the RAW caller sentence (column text_raw) with
    text_norm.prepare(), the SAME function the server uses. The old text_clean column also had each row's
    known person names removed (and sometimes ordinary words with them, e.g. 安全账号 -> 安全), which the
    server can never do; 0.9% / 1.5% / 3.1% of urgency / money / impersonation rows differed.
    A parity check runs before training and fails the build if any row differs.
  * Frozen threshold: read from model_config.json next to train.py (the val-F2 threshold drifted between
    machines, 0.20 vs 0.33, because the F2 curve is flat). The aggregator uses scores, not thresholds; the
    threshold is only for the per-worker YES/NO display and the report.
  * Extra rows: data/<signal>_zh_extra.csv (if present) is added to training. impersonation uses it for
    ordinary self-introductions (clinic, school, restaurant ...) labelled 0; see
    tools/zh_make_intro_negatives.py.

Input : data/<signal>_zh.csv        columns used: text_raw, label, source, scenario_id, conversation_id, row_id
        data/<signal>_zh_extra.csv  (optional, same columns)
        data/gold_test_zh.csv       (optional; only for a printout. Scored the way the SERVER scores.)
        model_config.json           (optional; {"threshold": 0.3, ...}). Missing = compute + print.
Output: model/model.joblib          dict(model, threshold, kind, worker, C, data_version)
"""
import json
import logging
import os
import re
import sys
import warnings

import joblib
import numpy as np
import pandas as pd
from sklearn.calibration import CalibratedClassifierCV
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import fbeta_score, f1_score, precision_score, recall_score, roc_auc_score
from sklearn.model_selection import GroupKFold, GroupShuffleSplit
from sklearn.pipeline import Pipeline

import text_norm as zn

warnings.filterwarnings("ignore")
logging.getLogger("jieba").setLevel(logging.WARNING)
SEED = 42
DATA_VERSION = "v6"

# winning settings from zh_train.ipynb, Cell 5
CONFIG = {"urgency": ("char", 100), "money": ("char", 100), "impersonation": ("jieba", 100)}

# rows whose cleaned text still starts with a speaker label ("张经理:") are dropped, unless the "label" is a
# normal word before a colon (链接: / 电话: ...)
PREFIX_RE = re.compile(r"^\s*([^,，。！？!?\s:：]{1,8})\s*[:：]")
PREFIX_OK = re.compile(r"链接|拨打|电话|网址|地址|时间|账号|卡号|热线|邮箱|微信|官网|注意|提示|步骤|备注")


def make_pipe(kind, C):
    if kind == "char":
        vec = TfidfVectorizer(analyzer="char", ngram_range=(1, 3), min_df=2, sublinear_tf=True,
                              lowercase=False, max_features=200_000)
    else:  # input = space-joined jieba tokens
        vec = TfidfVectorizer(analyzer="word", token_pattern=r"(?u)\S+", lowercase=False, ngram_range=(1, 2),
                              min_df=2, sublinear_tf=True, max_features=200_000)
    return Pipeline([("vec", vec), ("clf", LogisticRegression(class_weight="balanced", max_iter=3000, C=C,
                                                              random_state=SEED))])


def report(name, y, p, thr):
    yhat = (p >= thr).astype(int)
    print(f"  {name:24s} P={precision_score(y, yhat, zero_division=0):.3f} R={recall_score(y, yhat, zero_division=0):.3f} "
          f"F1={f1_score(y, yhat, zero_division=0):.3f} F2={fbeta_score(y, yhat, beta=2, zero_division=0):.3f}")


def load_rows(here, signal):
    path = os.environ.get("CSV_PATH", os.path.join(here, "data", f"{signal}_zh.csv"))
    d = pd.read_csv(path)
    if "text_raw" not in d.columns:
        sys.exit(f"{path} has no text_raw column: v6 trains from the raw caller sentence")
    extra = os.path.join(here, "data", f"{signal}_zh_extra.csv")
    if os.path.exists(extra):
        e = pd.read_csv(extra)
        print(f"  + {len(e)} extra rows from {os.path.basename(extra)} ({int(e.label.sum())} positive)")
        d = pd.concat([d, e], ignore_index=True)
    d = d[d.text_raw.notna() & (d.text_raw.astype(str).str.strip() != "")].reset_index(drop=True)
    return d


def parity_check(raw, X, signal, kind):
    """The training input must be exactly what the server builds from the same sentence.
    Server path (predict.score -> text_norm.score_text): split_sentences(raw) -> prepare(piece) per piece.
    Rows up to 60 characters are scored whole (longer ones only as pieces), so for every row the server scores
    whole, the training string must be exactly the string the server builds."""
    whole, mismatch = 0, []
    for r, x in zip(raw, X):
        r = r.strip()
        if r in zn.split_sentences(r):                       # server scores this row whole (maybe + pieces)
            whole += 1
            if zn.prepare(r, signal, kind) != x:
                mismatch.append(r)
    print(f"  parity: {whole}/{len(raw)} rows are scored whole by the server; "
          f"{whole - len(mismatch)}/{whole} identical to training input "
          f"(the rest are >60 chars: the server scores their pieces)")
    if mismatch:
        for r in mismatch[:5]:
            print("    differs:", r)
        sys.exit("PARITY FAILED: training input differs from what the server produces")


def canonical_check(bundle, signal, here):
    """The README's test sentences (probes_zh.csv, group 'canonical') must come out right, scored the way the
    server scores them, or the build fails. Guards against the police/clinic regression coming back."""
    path = os.path.join(here, "data", "probes_zh.csv")
    if not os.path.exists(path):
        path = os.path.join(here, "..", "common_zh", "probes_zh.csv")
    if not os.path.exists(path):
        print("  (no probes_zh.csv: canonical check skipped)")
        return
    pr = pd.read_csv(path)
    pr = pr[(pr.group == "canonical") & (pr.worker == signal)]
    bad = []
    for r in pr.itertuples():
        s = zn.score_text(bundle, r.text, signal)[0]
        ok = s >= 0.5 if r.expected == 1 else s < 0.5
        print(f"  canonical {'ok  ' if ok else 'FAIL'} expected {r.expected} got {s:.3f}  {r.text}")
        if not ok:
            bad.append(r.text)
    if bad:
        sys.exit(f"CANONICAL CHECK FAILED for {signal}_zh ({len(bad)} sentence(s)); not saving the model")


def main(signal):
    here = os.path.dirname(os.path.abspath(sys.argv[0]))
    kind, C = CONFIG[signal]
    model_path = os.path.join(here, "model", "model.joblib")
    gold_path = os.environ.get("GOLD_PATH", os.path.join(here, "data", "gold_test_zh.csv"))
    if not os.path.exists(gold_path):  # local runs: the gold set lives once in common_zh/
        gold_path = os.path.join(here, "..", "common_zh", "gold_test_zh.csv")
    cfg_path = os.path.join(here, "model_config.json")

    d = load_rows(here, signal)
    if kind == "jieba":
        zn.jieba_join("预热")
    X = np.array([zn.prepare(t, signal, kind) for t in d.text_raw.astype(str)])
    cleaned = np.array([zn.clean(t) for t in d.text_raw.astype(str)])
    bad = np.array([bool(PREFIX_RE.match(c)) and not PREFIX_OK.search(PREFIX_RE.match(c).group(1)) for c in cleaned])
    keep = ~bad & (np.char.str_len(X.astype(str)) > 0)
    d, X = d[keep].reset_index(drop=True), X[keep]
    gid = d.scenario_id.where(d.scenario_id.notna() & (d.scenario_id.astype(str) != ""), d.conversation_id)
    groups = (d.source.astype(str) + "|" + gid.where(gid.notna(), d.row_id).astype(str)).values
    y = d.label.astype(int).values
    print(f"{signal}_zh {DATA_VERSION}: {len(d)} rows ({y.sum()} pos) | model {kind}, C={C}")
    parity_check(d.text_raw.astype(str).tolist(), X, signal, kind)

    tr, va = next(GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED).split(X, y, groups))
    folds = list(GroupKFold(n_splits=5).split(X[tr], y[tr], groups[tr]))
    model = CalibratedClassifierCV(make_pipe(kind, C), method="sigmoid", cv=folds).fit(X[tr], y[tr])
    p = np.array(zn.authority_gate(X[va], model.predict_proba(X[va])[:, 1], signal))  # as served
    grid = np.round(np.arange(0.05, 0.951, 0.01), 2)
    thr_val = float(grid[int(np.argmax([fbeta_score(y[va], (p >= t).astype(int), beta=2, zero_division=0) for t in grid]))])
    if os.path.exists(cfg_path):
        thr = float(json.load(open(cfg_path))["threshold"])
        print(f"  threshold FROZEN = {thr:.2f} (model_config.json; this run's val-F2 optimum would be {thr_val:.2f})")
    else:
        thr = thr_val
        print(f"  threshold = {thr:.2f} (val-F2 optimum; no model_config.json, so NOT frozen)")
    report("validation", y[va], p, thr)
    print(f"  validation AUC={roc_auc_score(y[va], p):.3f}")

    # refit on ALL rows for the shipped model (validation above was only to measure and pick the threshold)
    model = CalibratedClassifierCV(make_pipe(kind, C), method="sigmoid",
                                   cv=list(GroupKFold(n_splits=5).split(X, y, groups))).fit(X, y)
    bundle = dict(model=model, threshold=thr, kind=kind, worker=signal, C=C, data_version=DATA_VERSION)

    if os.path.exists(gold_path):  # scored exactly like the server: split -> prepare -> max
        g = pd.read_csv(gold_path)
        g = g[g[signal].notna()]
        pg = np.array([zn.score_text(bundle, t, signal)[0] for t in g.text])
        yg = g[signal].astype(int).values
        report("gold (served path)", yg, pg, thr)
        report("gold (served) @0.5", yg, pg, 0.5)
        print(f"  gold AUC={roc_auc_score(yg, pg):.3f}  near-miss false alarms @0.5: "
              f"{(pg[(g.type == 'legit_near_miss').values] >= 0.5).mean():.1%}")

    canonical_check(bundle, signal, here)
    os.makedirs(os.path.dirname(model_path), exist_ok=True)
    joblib.dump(bundle, model_path, compress=3)
    print(f"saved {model_path} ({os.path.getsize(model_path) / 1e6:.1f} MB)")
