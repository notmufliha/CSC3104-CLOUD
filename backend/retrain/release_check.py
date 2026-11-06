"""release_check.py - Workstream H.7 (and G.9): blocks a new model that scores worse than the current one.

Both models score the same FIXED human-labelled test sets (adapters.py lists them per worker), which are never
used for training. For each set: ROC-AUC (ranking quality, threshold-free) and F1 at each model's OWN threshold.

A fixed test set can't notice a model forgetting a NEW scam, because that scam isn't in it. So there is one more
check: the "learned" set, the report lines earlier versions already trained on, labelled as showing this signal.
The candidate must still recognise them (recall at its own threshold).

PASS = on every check, the candidate is at least the current model's score minus TOLERANCE.
TOLERANCE (default 0.005) only absorbs rounding-level noise from retraining; a real drop still fails.
"""
import pandas as pd
from sklearn.metrics import f1_score, recall_score, roc_auc_score


def evaluate(adapter, model, threshold: float) -> dict:
    out = {}
    for name, path, text_col, label_col in adapter.gold_sets:
        df = pd.read_csv(path)
        df = df[df[label_col].isin([0, 1])]
        y = df[label_col].astype(int)
        p = adapter.score(model, df[text_col].astype(str).tolist())
        pred = (p >= threshold).astype(int)
        out[name] = {"rows": int(len(df)), "roc_auc": round(float(roc_auc_score(y, p)), 4),
                     "f1": round(float(f1_score(y, pred)), 4), "recall": round(float(recall_score(y, pred)), 4)}
    return out


def learned_recall(adapter, model, threshold: float, learned: list) -> float:
    p = adapter.score(model, [adapter.model_text(t) for t in learned])
    return round(float((p >= threshold).mean()), 4)


def compare(adapter, current, current_threshold, candidate, candidate_threshold, tolerance: float, learned=()) -> dict:
    cur, cand = evaluate(adapter, current, current_threshold), evaluate(adapter, candidate, candidate_threshold)
    checks = []

    def check(set_name, metric, cur_value, cand_value):
        checks.append({"set": set_name, "metric": metric, "current": cur_value, "candidate": cand_value,
                       "pass": cand_value >= cur_value - tolerance})

    for name in cur:
        for metric in ("roc_auc", "f1"):
            check(name, metric, cur[name][metric], cand[name][metric])
    if learned:
        learned = list(learned)
        check(f"learned ({len(learned)} lines)", "recall", learned_recall(adapter, current, current_threshold, learned),
              learned_recall(adapter, candidate, candidate_threshold, learned))
    return {"pass": all(c["pass"] for c in checks), "tolerance": tolerance, "checks": checks,
            "current": cur, "candidate": cand}


def print_table(result: dict, current_version: str, candidate_version: str) -> None:
    print(f"\nRelease check: {candidate_version} vs {current_version} (tolerance {result['tolerance']})")
    print(f"{'test set':<22}{'metric':<9}{current_version:>10}{candidate_version:>12}   result")
    for c in result["checks"]:
        print(f"{c['set']:<22}{c['metric']:<9}{c['current']:>10.4f}{c['candidate']:>12.4f}   "
              f"{'ok' if c['pass'] else 'BELOW BAR'}")
    print(f"=> {'PASS' if result['pass'] else 'BLOCKED'}\n")
