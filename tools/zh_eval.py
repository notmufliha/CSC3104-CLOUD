"""zh_eval.py - one command to check the three Chinese workers the way the SERVER scores text.

Scores every sentence through text_norm.score_text (split -> clean -> score each piece -> max), i.e. exactly
what server.py / predict.py return, using the model files train.py wrote. Reports:

  1. gold set (common_zh/gold_test_zh.csv, 72 hand-written SG-style sentences, never trained on):
     precision / recall / F1 / F2 at the worker's frozen threshold and at 0.5, AUC, and the false-alarm rate
     on the 32 legit near-miss sentences
  2. probes (common_zh/probes_zh.csv): pass/fail per group. canonical = the README test sentences;
     ordinary_intro = clinics, florists, schools ... introducing themselves (none appear in training)

Run from the repo root after training the three models (python train.py in each *_zh folder):

    python tools/zh_eval.py              # report
    python tools/zh_eval.py --strict     # exit 1 if a canonical probe fails (use before pushing)
"""
import argparse
import logging
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import f1_score, fbeta_score, precision_score, recall_score, roc_auc_score

ROOT = Path(__file__).resolve().parents[1]
WORKERS = ROOT / "backend" / "workers"
sys.path.insert(0, str(WORKERS / "common_zh"))
import text_norm as zn  # noqa: E402

logging.getLogger("jieba").setLevel(logging.ERROR)
SIGNALS = ["urgency", "money", "impersonation"]


def load(models_dir):
    out = {}
    for w in SIGNALS:
        p = Path(models_dir or WORKERS / f"{w}_zh" / "model") / ("model.joblib" if not models_dir else f"{w}_zh.joblib")
        out[w] = joblib.load(p)
    return out


def served(bundle, worker, texts):
    return np.array([zn.score_text(bundle, t, worker)[0] for t in texts])


def metrics(y, p, thr):
    yhat = (p >= thr).astype(int)
    return dict(P=precision_score(y, yhat, zero_division=0), R=recall_score(y, yhat, zero_division=0),
                F1=f1_score(y, yhat, zero_division=0), F2=fbeta_score(y, yhat, beta=2, zero_division=0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", help="folder with urgency_zh.joblib etc. (default: each worker's model/model.joblib)")
    ap.add_argument("--strict", action="store_true", help="exit 1 if a canonical probe fails")
    a = ap.parse_args()
    B = load(a.models)

    gold = pd.read_csv(WORKERS / "common_zh" / "gold_test_zh.csv")
    print("GOLD (served path: split + max)   thr = frozen threshold of each model")
    print(f"{'worker':14s} {'thr':>5s} {'P':>6s} {'R':>6s} {'F1':>6s} {'F2':>6s} | {'F1@.5':>6s} {'AUC':>6s} {'nearmissFP@.5':>14s}")
    for w in SIGNALS:
        g = gold[gold[w].notna()]
        y, p = g[w].astype(int).values, served(B[w], w, g.text)
        thr = float(B[w]["threshold"])
        m, m5 = metrics(y, p, thr), metrics(y, p, 0.5)
        nm = (p[(g.type == "legit_near_miss").values] >= 0.5).mean()
        print(f"{w:14s} {thr:5.2f} {m['P']:6.3f} {m['R']:6.3f} {m['F1']:6.3f} {m['F2']:6.3f} | {m5['F1']:6.3f} "
              f"{roc_auc_score(y, p):6.3f} {nm:14.1%}")

    probes = pd.read_csv(WORKERS / "common_zh" / "probes_zh.csv")
    probes["score"] = [zn.score_text(B[r.worker], r.text, r.worker)[0] for r in probes.itertuples()]
    probes["ok"] = np.where(probes.expected == 1, probes.score >= 0.5, probes.score < 0.5)
    print("\nPROBES (pass = score >= 0.5 when expected 1, < 0.5 when expected 0)")
    for grp, d in probes.groupby("group", sort=False):
        print(f"  {grp:22s} {d.ok.sum():2d}/{len(d):2d}")
    fails = probes[~probes.ok]
    if len(fails):
        print("\n  failures:")
        for r in fails.itertuples():
            print(f"    [{r.group}] {r.worker:13s} expected {r.expected} got {r.score:.3f}  {r.text}")
    canon_fail = fails[fails.group == "canonical"]
    if a.strict and len(canon_fail):
        print(f"\nSTRICT: {len(canon_fail)} canonical probe(s) failed")
        sys.exit(1)


if __name__ == "__main__":
    main()
