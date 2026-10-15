"""
check_goals.py - print the 8 urgency goals as PASS/FAIL for the saved model.

Reads
  model/urgency_en.joblib                    (threshold, call_threshold, C)
  eval/gold_handwritten.csv                  (goals 2-5)
  eval/robocall_gold.csv  column label_time_ai  (goal 6 - PROVISIONAL, AI-labelled; see LABELING.md)
  checks/probes.csv                          (goals 1, 2, 5 - hand-written test sentences, never trained on)
  checks/long_calls.csv  column text_tagged  (goal 7)
  reports/out_of_source_selection.csv        (goal 8, written by train.py)
Writes
  reports/goals_<UTC timestamp>.txt

Usage
  python backend/workers/urgency_en/check_goals.py
"""
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

import predict

HERE = Path(__file__).resolve().parent
MODEL = HERE / "model" / "urgency_en.joblib"
GOLD = HERE / "eval" / "gold_handwritten.csv"
ROBOCALL = HERE / "eval" / "robocall_gold.csv"
PROBES = HERE / "checks" / "probes.csv"
LONG_CALLS = HERE / "checks" / "long_calls.csv"
OOS = HERE / "reports" / "out_of_source_selection.csv"


def rate(ok):
    return f"{int(ok.sum())}/{len(ok)}", ok.mean() if len(ok) else float("nan")


def main():
    bundle = predict.load_model(MODEL)
    thr = bundle["threshold"]
    call_thr = bundle.get("call_threshold", thr)
    lines = [f"urgency_en goals {datetime.now(timezone.utc).isoformat(timespec='seconds')}",
             f"model created_at={bundle.get('created_at')} C={bundle.get('best_params', {}).get('clf__C')} "
             f"threshold={thr:.4f} call_threshold={call_thr:.4f}", ""]

    def sent_ok(df):
        p = np.array([predict.score_sentence(t) for t in df["text"]])
        return pd.Series((p >= thr) == df["label"].astype(bool).to_numpy(), index=df.index), p

    probes = pd.read_csv(PROBES)
    probes["ok"], _ = sent_ok(probes)
    gold = pd.read_csv(GOLD).dropna(subset=["text", "label"])
    gold["ok"], gold["p"] = sent_ok(gold)

    rows = []

    def goal(n, name, shown, value, target, passed, note=""):
        rows.append({"#": n, "goal": name, "now": shown, "target": target, "result": "PASS" if passed else "FAIL",
                     "note": note})

    s, r = rate(probes.loc[probes["category"] == "negation", "ok"])
    goal(1, "understands 'not urgent' / 'no rush'", s, r, ">= 90%", r >= 0.9, "checks/probes.csv negation")

    adv = pd.concat([probes.loc[probes["category"] == "anti_scam", "ok"],
                     gold.loc[gold["category"] == "legit_warning", "ok"]])
    s, r = rate(adv)
    goal(2, "no alarm on anti-scam advice", s, r, ">= 80%", r >= 0.8, "probes anti_scam + gold legit_warning")

    neg = gold[gold["label"] == 0]
    fp = int((~neg["ok"]).sum())
    goal(3, "false alarms on normal sentences (gold)", f"{fp}/{len(neg)}", fp, "<= 6", fp <= 6)

    pos = gold[gold["label"] == 1]
    s, r = rate(pos["ok"])
    goal(4, "still catches clear urgency (gold recall)", s, r, ">= 0.85", r >= 0.85)

    sub = pd.concat([gold.loc[gold["category"] == "subtle_positive", "ok"],
                     probes.loc[probes["category"] == "subtle", "ok"]])
    s, r = rate(sub)
    goal(5, "catches subtle pressure", s, r, ">= 90%", r >= 0.9, "gold subtle_positive + probes subtle")

    rc = pd.read_csv(ROBOCALL)
    if "label_time_ai" in rc.columns:
        rc = rc.dropna(subset=["label_time_ai"])
        p = np.array([predict.score_sentence(t) for t in rc["text"]])
        auc = roc_auc_score(rc["label_time_ai"].astype(int), p)
        goal(6, "works on real robocalls (ROC-AUC)", f"{auc:.3f}", auc, ">= 0.75", auc >= 0.75,
             f"PROVISIONAL: AI labels, {int(rc['label_time_ai'].sum())} urgent of {len(rc)}")
    else:
        goal(6, "works on real robocalls (ROC-AUC)", "n/a", float("nan"), ">= 0.75", False, "no label_time column")

    lc = pd.read_csv(LONG_CALLS)
    lc["p"] = [predict.score(t)[0] for t in lc["text_tagged"]]
    lc["flag"] = lc["p"] >= call_thr
    u, n = lc[lc["label"] == 1], lc[lc["label"] == 0]
    hit, fa = int(u["flag"].sum()), int(n["flag"].sum())
    goal(7, "whole calls: urgent flagged, normal not", f"{hit}/{len(u)} urgent, {fa}/{len(n)} normal flagged",
         None, ">= 9/10 and <= 1/10", hit >= 9 and fa <= 1, "caller side only, call_threshold")

    if OOS.exists():
        oos = pd.read_csv(OOS)
        C = bundle.get("best_params", {}).get("clf__C")
        f1 = float(oos.loc[oos["C"] == C, "pooled_f1"].iloc[0]) if C in set(oos["C"]) else float(oos["pooled_f1"].max())
        goal(8, "works on new kinds of data (out-of-source F1)", f"{f1:.3f}", f1, ">= 0.75", f1 >= 0.75)

    table = pd.DataFrame(rows)
    lines.append(table[["#", "goal", "now", "target", "result", "note"]].to_string(index=False))
    lines.append(f"\n{int((table['result'] == 'PASS').sum())}/{len(table)} goals pass")

    # Details that explain failures
    lines.append("\nlong calls (caller side):")
    for r in lc.itertuples():
        lines.append(f"  {r.call_id:10s} p={r.p:.3f} {'FLAG' if r.flag else '    '}")
    wrong = pd.concat([probes.assign(src="probe"), gold.assign(src="gold")])
    wrong = wrong[~wrong["ok"]]
    lines.append(f"\nwrong sentences ({len(wrong)}):")
    for r in wrong.itertuples():
        lines.append(f"  [{r.src} {r.category}] label={int(r.label)}  {r.text}")

    out = HERE / "reports" / f"goals_{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.txt"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\nreport -> {out}")


if __name__ == "__main__":
    main()
