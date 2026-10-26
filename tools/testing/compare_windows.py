"""
compare_windows.py - Workstream 6.6: pick the best context window size.

Run the replay once per window size (set the aggregator's window, restart it, then):
    python replay.py --tag w3 --out results_w3.csv
    python replay.py --tag w4 --out results_w4.csv
    python replay.py --tag w6 --out results_w6.csv
    python compare_windows.py results_w3.csv results_w4.csv results_w6.csv

For each file: correct verdicts (status OK) and mean seconds from the key sentence to the alert (scams that went red).
Best = most correct verdicts, ties broken by the smaller mean delay.
"""
import csv, sys

def load(path):
    with open(path, encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))

if len(sys.argv) < 2:
    sys.exit(__doc__)
res = []
for p in sys.argv[1:]:
    rows = load(p)
    scams = [r for r in rows if r["type"] == "scam"]
    delays = [float(r["secs_after_key"]) for r in scams if r["secs_after_key"] != ""]
    res.append({"file": p, "tag": rows[0].get("tag") or p, "n": len(rows),
                "correct": sum(r["status"] == "OK" for r in rows),
                "scams_red": sum(r["first_red_line"] != "" for r in scams), "scams": len(scams),
                "false_alarms": sum(r["status"].startswith("FALSE") for r in rows),
                "mean_delay": round(sum(delays) / len(delays), 1) if delays else None})
print("| window (tag) | correct verdicts | scams red | false alarms | mean secs key->alert |")
print("|---|---|---|---|---|")
for r in res:
    print(f"| {r['tag']} | {r['correct']}/{r['n']} | {r['scams_red']}/{r['scams']} | {r['false_alarms']} | {r['mean_delay']} |")
best = sorted(res, key=lambda r: (-r["correct"], r["mean_delay"] if r["mean_delay"] is not None else 1e9))[0]
print(f"\nBest: {best['tag']} ({best['correct']}/{best['n']} correct, mean delay {best['mean_delay']} s)")
