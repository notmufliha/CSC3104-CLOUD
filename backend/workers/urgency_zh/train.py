"""
Trains the "urgency" (zh) worker: TF-IDF + Logistic Regression, sigmoid-calibrated.

This reproduces the winning configuration from zh_train.ipynb (Plan 3), so the model is
built with the SAME scikit-learn version that server.py runs with (no pickle-version problems).

Input : data/urgency_zh.csv   (copy from Drive CSC3104/data/zh/processed/; columns used:
        text_clean, label, source, scenario_id, conversation_id, row_id)
        data/gold_test_zh.csv     (optional; copy from Drive CSC3104/data/zh/, only used for a printout)
Output: model/model.joblib        dict(model, threshold, kind, worker, C, val_metrics)

Run:    python train.py           (~1 min; also run automatically by the Dockerfile)
"""
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
from sklearn.metrics import fbeta_score, f1_score, precision_score, recall_score
from sklearn.model_selection import GroupKFold, GroupShuffleSplit
from sklearn.pipeline import Pipeline

# text_norm.py: the shared ZH cleaning (backend/workers/common_zh/, copied next to this file in the image)
sys.path.insert(1, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common_zh"))
import text_norm as zn  # noqa: E402

warnings.filterwarnings("ignore")
SIGNAL = "urgency"
HERE = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.environ.get("CSV_PATH", os.path.join(HERE, "data", f"{SIGNAL}_zh.csv"))
GOLD_PATH = os.environ.get("GOLD_PATH", os.path.join(HERE, "data", "gold_test_zh.csv"))
if not os.path.exists(GOLD_PATH):  # local runs: the gold set lives once in common_zh/
    GOLD_PATH = os.path.join(HERE, "..", "common_zh", "gold_test_zh.csv")
MODEL_PATH = os.path.join(HERE, "model", "model.joblib")
SEED = 42

# winning settings from zh_train.ipynb, Cell 5 (v5 data)
CONFIG = {"urgency": ("char", 100), "money": ("char", 100), "impersonation": ("jieba", 100)}
KIND, C = CONFIG[SIGNAL]

PREFIX_RE = re.compile(r"^\s*([^,，。！？!?\s:：]{1,8})\s*[:：]")
PREFIX_OK = re.compile(r"链接|拨打|电话|网址|地址|时间|账号|卡号|热线|邮箱|微信|官网|注意|提示|步骤|备注")


def make_pipe():
    if KIND == "char":
        vec = TfidfVectorizer(analyzer="char", ngram_range=(1, 3), min_df=2, sublinear_tf=True,
                              lowercase=False, max_features=200_000)
    else:  # input = space-joined jieba tokens
        vec = TfidfVectorizer(analyzer="word", token_pattern=r"(?u)\S+", lowercase=False, ngram_range=(1, 2),
                              min_df=2, sublinear_tf=True, max_features=200_000)
    return Pipeline([("vec", vec), ("clf", LogisticRegression(class_weight="balanced", max_iter=3000, C=C))])


def to_input(text_clean):
    t = zn.tokens_to_chars(text_clean)          # text_clean is already cleaned (+ ORG_* for impersonation)
    return zn.jieba_join(t) if KIND == "jieba" else t


def report(name, y, p, thr):
    yhat = (p >= thr).astype(int)
    print(f"  {name:22s} P={precision_score(y, yhat, zero_division=0):.3f} R={recall_score(y, yhat, zero_division=0):.3f} "
          f"F1={f1_score(y, yhat, zero_division=0):.3f} F2={fbeta_score(y, yhat, beta=2, zero_division=0):.3f}")


def main():
    d = pd.read_csv(CSV_PATH)
    d = d[d.text_clean.notna() & (d.text_clean.astype(str).str.strip() != "")]
    bad = d.text_clean.map(lambda s: bool(PREFIX_RE.match(str(s))) and not PREFIX_OK.search(PREFIX_RE.match(str(s)).group(1)))
    d = d[~bad].reset_index(drop=True)
    gid = d.scenario_id.where(d.scenario_id.notna() & (d.scenario_id.astype(str) != ""), d.conversation_id)
    groups = (d.source.astype(str) + "|" + gid.where(gid.notna(), d.row_id).astype(str)).values
    y = d.label.astype(int).values
    X = np.array([to_input(t) for t in d.text_clean])
    print(f"{SIGNAL}_zh: {len(d)} rows ({y.sum()} pos) | model {KIND}, C={C}")

    tr, va = next(GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=SEED).split(X, y, groups))
    folds = list(GroupKFold(n_splits=5).split(X[tr], y[tr], groups[tr]))
    model = CalibratedClassifierCV(make_pipe(), method="sigmoid", cv=folds).fit(X[tr], y[tr])
    p = model.predict_proba(X[va])[:, 1]
    grid = np.round(np.arange(0.05, 0.951, 0.01), 2)
    thr = float(grid[int(np.argmax([fbeta_score(y[va], (p >= t).astype(int), beta=2, zero_division=0) for t in grid]))])
    print(f"  threshold (max F2 on validation) = {thr:.2f}")
    report("validation", y[va], p, thr)

    if os.path.exists(GOLD_PATH):
        g = pd.read_csv(GOLD_PATH)
        g = g[g[SIGNAL].notna()]
        col = "text_clean_imp" if SIGNAL == "impersonation" else "text_clean"
        pg = model.predict_proba([to_input(t) for t in g[col]])[:, 1]
        report("gold", g[SIGNAL].astype(int).values, pg, thr)
        report("gold @0.5", g[SIGNAL].astype(int).values, pg, 0.5)

    os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
    joblib.dump(dict(model=model, threshold=thr, kind=KIND, worker=SIGNAL, C=C), MODEL_PATH, compress=3)
    print(f"saved {MODEL_PATH} ({os.path.getsize(MODEL_PATH) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
