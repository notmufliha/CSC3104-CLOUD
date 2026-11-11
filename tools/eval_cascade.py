"""eval_cascade.py - task 7.5 + 7.12: does the BERT second opinion help, and how much BERT work does the cascade
save? Replays labelled calls line by line through the RUNNING stack (gateway -> workers -> aggregator -> BERT), so
it measures the real system: window, streak, money rule, quorum and cascade, not just the models.

1. Run the stack twice, once per mode, and record every line's answer:

    docker compose up -d --build                                   # cascade ON
    python tools/eval_cascade.py run --label cascade
    BERT_ADDR= docker compose up -d aggregator                     # cascade OFF (fast workers only)
    python tools/eval_cascade.py run --label fast
    docker compose up -d aggregator                                # back to normal

   Or the live three-way comparison (Workstream F): AGG_MODE=fast|bert_only|cascade docker compose up -d aggregator,
   one run per mode, then: report --runs fast bert_only cascade

2. Compare (adds "bert_only" if the model files are in backend/workers/bert/model/):

    python tools/eval_cascade.py report --runs fast cascade --bert-local backend/workers/bert

Calls come from:
  * data/eval/eval_calls.jsonl  (held-out calls written by notebooks/bert_cascade.ipynb: FGRC / BothBosu test
                                 groups + quanshiyun out-of-distribution calls; {call_id, lang, label, source, lines})
  * the ZH gold set and the EN robocall set as one-line "calls" (built in)

Every call starts at t = 20 s (after the 15 s warm-up), one line every 5 s: this measures what the system
SAYS, not the warm-up policy. A call counts as flagged when any line is SCAM ("SCAM or UNSURE" also reported).
Standard library only for `run` (plus the BERT pod's requirements for --bert-local).
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "data" / "eval" / "runs"
URL = os.environ.get("GATEWAY_URL", "http://localhost:8080").rstrip("/")


def load_calls(calls_path, limit):
    calls = []
    if calls_path and Path(calls_path).exists():
        for line in open(calls_path, encoding="utf-8"):
            if line.strip():
                calls.append(json.loads(line))
    else:
        print(f"(no {calls_path}: only the built-in one-line sets are used)")
    import csv
    gold = ROOT / "backend/workers/common_zh/gold_test_zh.csv"
    for i, r in enumerate(csv.DictReader(open(gold, encoding="utf-8-sig"))):
        calls.append(dict(call_id=f"gold{i}", lang="zh", label=int(r["type"] == "scam"), source="zh_gold_sentences",
                          lines=[r["text"]]))
    robo = ROOT / "backend/workers/urgency_en/eval/robocall_gold.csv"
    for r in csv.DictReader(open(robo, encoding="utf-8")):
        calls.append(dict(call_id=f"robo{r['id']}", lang="en", label=int(r["label"]), source="en_robocall_sentences",
                          lines=[r["text"]]))
    if limit:  # at most `limit` calls per source, half scam / half legit where possible, spread over the file
        by = {}
        for c in calls:
            by.setdefault((c["source"], c["label"]), []).append(c)
        keep = []
        for (src, lab), v in by.items():
            n = min(len(v), max(1, limit // 2))
            keep += [v[int(i * len(v) / n)] for i in range(n)]
        calls = keep
    return calls


def post(body):
    req = urllib.request.Request(f"{URL}/v1/utterance", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def run(a):
    calls = load_calls(a.calls, a.limit)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / f"{a.label}.jsonl"
    stamp = time.strftime("%H%M%S")
    n_lines = 0
    with open(out, "w", encoding="utf-8") as f:
        for k, c in enumerate(calls, 1):
            steps = []
            for i, text in enumerate(c["lines"]):
                body = dict(call_id=f"eval-{a.label}-{stamp}-{c['call_id']}"[:128], seq=i + 1, speaker="caller",
                            text=text[:10000], lang=c["lang"], t_ms=20000 + 5000 * i)
                try:
                    r = post(body)
                except urllib.error.URLError as e:
                    sys.exit(f"gateway not reachable at {URL}: {e}")
                steps.append({k2: r.get(k2) for k2 in ("verdict", "combined_score", "fast_score", "bert_score",
                                                        "bert_status", "quorum_met", "latency_ms", "missing")})
                n_lines += 1
            f.write(json.dumps(dict(call_id=c["call_id"], source=c["source"], lang=c["lang"], label=c["label"],
                                    lines=c["lines"], steps=steps), ensure_ascii=False) + "\n")
            if k % 50 == 0:
                print(f"  {k}/{len(calls)} calls")
    print(f"{len(calls)} calls, {n_lines} lines -> {out.relative_to(ROOT)}")


def prf(y, yhat):
    tp = sum(1 for a, b in zip(y, yhat) if a and b)
    fp = sum(1 for a, b in zip(y, yhat) if not a and b)
    fn = sum(1 for a, b in zip(y, yhat) if a and not b)
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * p * r / (p + r) if p + r else 0.0
    f2 = 5 * p * r / (4 * p + r) if p + r else 0.0
    neg = sum(1 for a in y if not a)
    fa = fp / neg if neg else float("nan")
    return p, r, f1, f2, fa


def bert_only(rows, bert_dir, threshold):
    """BERT alone on every line (what 'always ask BERT' would say), with the pod's own predict.py."""
    sys.path.insert(0, str(Path(bert_dir).resolve()))
    import predict
    predict.load_model(Path(bert_dir) / "model")
    ms, out = [], {}
    for r in rows:
        scores = []
        for i in range(len(r["lines"])):
            t0 = time.perf_counter()
            scores.append(predict.score(r["lines"][max(0, i - 4): i + 1]))
            ms.append((time.perf_counter() - t0) * 1000)
        out[r["call_id"]] = scores
    flagged = {cid: any(s >= threshold for s in sc) for cid, sc in out.items()}
    return flagged, statistics.mean(ms) if ms else 0.0, predict.version()


def report(a):
    runs = {}
    for label in a.runs:
        p = OUT_DIR / f"{label}.jsonl"
        if not p.exists():
            sys.exit(f"no run '{label}': python tools/eval_cascade.py run --label {label}")
        runs[label] = [json.loads(x) for x in open(p, encoding="utf-8") if x.strip()]
    base = next(iter(runs.values()))
    sources = sorted({r["source"] for r in base})
    bo, bert_ms, bert_version = None, 0.0, ""
    if a.bert_local and (Path(a.bert_local) / "model" / "model.onnx").exists():
        bo, bert_ms, bert_version = bert_only(base, a.bert_local, 0.6)
        if "DUMMY" in bert_version:
            print("!! BERT model is the DUMMY: bert_only numbers are meaningless\n")

    print("## 7.5 Call-level results (flagged = any line SCAM; FA = false-alarm rate on legit calls)\n")
    print("One-line sets (*_sentences) can rarely reach SCAM (it needs 2 high updates in a row or the money rule):")
    print("read their 'SCAM or UNSURE' column.\n")
    print("| set | mode | calls (scam) | P | R | F1 | F2 | FA | F2 (SCAM or UNSURE) | median lines to SCAM |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for src in sources + ["ALL"]:
        for label, rows in runs.items():
            rs = [r for r in rows if src in ("ALL", r["source"])]
            y = [r["label"] == 1 for r in rs]
            scam = [any(s["verdict"] == "SCAM" for s in r["steps"]) for r in rs]
            loose = [any(s["verdict"] in ("SCAM", "UNSURE") for s in r["steps"]) for r in rs]
            p, rec, f1, f2, fa = prf(y, scam)
            f2l = prf(y, loose)[3]
            delays = [next(i for i, s in enumerate(r["steps"]) if s["verdict"] == "SCAM") + 1
                      for r, hit in zip(rs, scam) if hit and r["label"] == 1]
            d = f"{statistics.median(delays):.0f}" if delays else "-"
            print(f"| {src} | {label} | {len(rs)} ({sum(y)}) | {p:.3f} | {rec:.3f} | {f1:.3f} | {f2:.3f} | {fa:.3f} | {f2l:.3f} | {d} |")
        if bo is not None:
            rs = [r for r in base if src in ("ALL", r["source"])]
            p, rec, f1, f2, fa = prf([r["label"] == 1 for r in rs], [bo[r["call_id"]] for r in rs])
            print(f"| {src} | bert_only (>=0.6) | {len(rs)} | {p:.3f} | {rec:.3f} | {f1:.3f} | {f2:.3f} | {fa:.3f} | | |")

    print("\n## 7.12 How often the cascade needed BERT (lines where BERT was asked)\n")
    for label, rows in runs.items():
        steps = [(r["label"], s) for r in rows for s in r["steps"]]
        if not steps:
            continue
        asked = [s for _, s in steps if s.get("bert_status") in ("ok", "timeout", "error", "no_context")]
        lat = sorted(s["latency_ms"] for _, s in steps if s.get("latency_ms") is not None)
        by = {lab: [s for l2, s in steps if l2 == lab] for lab in (0, 1)}
        rate = lambda ss: sum(1 for s in ss if s.get("bert_status") in ("ok", "timeout", "error")) / len(ss) if ss else 0
        statuses = {}
        for _, s in steps:
            statuses[s.get("bert_status")] = statuses.get(s.get("bert_status"), 0) + 1
        print(f"- **{label}**: {len(steps)} lines, BERT asked on {len(asked)} ({len(asked) / len(steps):.1%}); "
              f"legit-call lines {rate(by[0]):.1%}, scam-call lines {rate(by[1]):.1%}; statuses {statuses}; "
              f"gateway latency p50 {lat[len(lat) // 2]} ms, p95 {lat[int(len(lat) * 0.95) - 1]} ms")
        if bert_ms and len(asked):
            always, cascade = len(steps) * bert_ms, len(asked) * bert_ms
            print(f"  - BERT CPU time: always-BERT {always / 1000:.1f} s vs cascade {cascade / 1000:.1f} s "
                  f"-> **{1 - cascade / always:.0%} saved** ({bert_ms:.0f} ms per BERT call, model {bert_version})")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--label", required=True, help="name of this run, e.g. cascade / fast")
    r.add_argument("--calls", default=str(ROOT / "data/eval/eval_calls.jsonl"))
    r.add_argument("--limit", type=int, default=0, help="max calls per source (0 = all)")
    p = sub.add_parser("report")
    p.add_argument("--runs", nargs="+", required=True)
    p.add_argument("--bert-local", help="backend/workers/bert (with model/ filled) to add a BERT-only row + CPU time")
    a = ap.parse_args()
    run(a) if a.cmd == "run" else report(a)


if __name__ == "__main__":
    main()
