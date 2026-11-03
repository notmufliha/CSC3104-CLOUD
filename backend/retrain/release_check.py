"""release_check.py - Workstream H.7 (and G.9): blocks a new model that scores worse than the one in service.

Both models score the same FIXED human-labelled test sets, which are never used for training:
  backend/workers/urgency_en/eval/gold_handwritten.csv   column label          (59 hand-written lines)
  backend/workers/urgency_en/eval/robocall_gold.csv      column label_time_ai  (200 real robocall lines;
                                                                                 the label v4's goals use)
For each set: ROC-AUC (ranking quality, threshold-free) and F1 at each model's OWN saved threshold.

PASS = on every set, the candidate's ROC-AUC and F1 are each at least the current model's minus TOLERANCE.
TOLERANCE (default 0.005) only absorbs rounding-level noise from retraining; a real drop still fails.

Usage (also imported by retrain_job.py):
    python release_check.py --current model/urgency_en_v4.joblib --candidate /work/candidate.joblib
"""
import argparse
import json
import sys
from pathlib import Path

import joblib
import pandas as pd
from sklearn.metrics import f1_score, recall_score, roc_auc_score

URGENCY_DIR = Path(__file__).resolve().parents[1] / "workers" / "urgency_en"
sys.path.insert(0, str(URGENCY_DIR))
from text_norm import normalize  # noqa: E402  (same cleaning as training and serving)

GOLD_SETS = [("gold_handwritten", URGENCY_DIR / "eval" / "gold_handwritten.csv", "label"),
             ("robocall_gold", URGENCY_DIR / "eval" / "robocall_gold.csv", "label_time_ai")]


def score(bundle, texts):
    pipe = bundle["pipeline"]
    proba = pipe.predict_proba([normalize(t) for t in texts])
    return proba[:, list(pipe.classes_).index(1)]


def evaluate(bundle) -> dict:
    out = {}
    for name, path, col in GOLD_SETS:
        df = pd.read_csv(path)
        df = df[df[col].isin([0, 1])]
        p = score(bundle, df["text"].astype(str).tolist())
        pred = (p >= bundle["threshold"]).astype(int)
        out[name] = {"rows": int(len(df)), "roc_auc": round(float(roc_auc_score(df[col], p)), 4),
                     "f1": round(float(f1_score(df[col], pred)), 4),
                     "recall": round(float(recall_score(df[col], pred)), 4)}
    return out


def compare(current, candidate, tolerance: float) -> dict:
    cur, cand = evaluate(current), evaluate(candidate)
    checks = []
    for name in cur:
        for metric in ("roc_auc", "f1"):
            ok = cand[name][metric] >= cur[name][metric] - tolerance
            checks.append({"set": name, "metric": metric, "current": cur[name][metric],
                           "candidate": cand[name][metric], "pass": ok})
    return {"pass": all(c["pass"] for c in checks), "tolerance": tolerance, "checks": checks,
            "current": cur, "candidate": cand}


def print_table(result: dict, current_version: str, candidate_version: str) -> None:
    print(f"\nRelease check: {candidate_version} vs {current_version} (tolerance {result['tolerance']})")
    print(f"{'test set':<18}{'metric':<9}{current_version:>10}{candidate_version:>12}   result")
    for c in result["checks"]:
        print(f"{c['set']:<18}{c['metric']:<9}{c['current']:>10.4f}{c['candidate']:>12.4f}   "
              f"{'ok' if c['pass'] else 'BELOW BAR'}")
    print(f"=> {'PASS' if result['pass'] else 'BLOCKED'}\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--current", type=Path, required=True)
    ap.add_argument("--candidate", type=Path, required=True)
    ap.add_argument("--tolerance", type=float, default=0.005)
    args = ap.parse_args()
    result = compare(joblib.load(args.current), joblib.load(args.candidate), args.tolerance)
    print_table(result, "current", "candidate")
    print(json.dumps(result, indent=2))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
