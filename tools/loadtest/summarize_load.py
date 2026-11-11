"""Turn run_load.sh's k6 and docker stats output into markdown tables. Usage: summarize_load.py <dir> <rate>"""
import csv
import json
import re
import statistics
import sys
from pathlib import Path

d, rate = Path(sys.argv[1]), sys.argv[2]
MODES = ["fast", "bert_only", "cascade"]
UNITS = {"B": 1 / 1024**2, "KiB": 1 / 1024, "MiB": 1.0, "GiB": 1024.0}


def service(name):
    return re.sub(r"^csc3104-cloud-|-\d+$", "", name)


def mib(usage):
    m = re.match(r"([\d.]+)(B|KiB|MiB|GiB)", usage.strip())
    return float(m[1]) * UNITS[m[2]] if m else 0.0


def is_worker(s):
    return s.split("_")[0] in ("urgency", "money", "impersonation")


# What each mode would really need deployed: fast has no BERT pod, bert_only needs no workers. The stack still runs
# them (the gateway calls the workers in every mode), so those are left out of "needed", and shown in "all".
def needed(mode, s):
    if s == "review":
        return False
    if mode == "fast":
        return s != "bert"
    if mode == "bert_only":
        return not is_worker(s)
    return True


stats = {}
for mode in MODES:
    samples = {}
    for row in csv.reader(open(d / f"stats_{mode}_{rate}.csv")):
        if len(row) == 3:
            samples.setdefault(service(row[0]), []).append((float(row[1].rstrip("%")), mib(row[2])))
    stats[mode] = {
        s: (statistics.mean(c for c, _ in v), statistics.mean(m for _, m in v), len(v)) for s, v in samples.items()
    }

print(f"## Load test: {rate} lines/s\n")
print("| mode | requests | dropped | bad replies | p50 ms | p95 ms | p99 ms | max ms | BERT ran | timeout | error |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for mode in MODES:
    k = json.load(open(d / f"k6_{mode}_{rate}.json"))
    b = k["bert"]
    print(f"| {mode} | {k['requests']} | {k['dropped']} | {k['bad_replies']} | {k['p50_ms']:.0f} | {k['p95_ms']:.0f} | "
          f"{k['p99_ms']:.0f} | {k['max_ms']:.0f} | {b['ok']} | {b['timeout']} | {b['error']} |")

print("\n## CPU and memory (mean over the run; 100% = one core)\n")
print("| mode | CPU, needed services % | memory, needed services MiB | CPU, all % | memory, all MiB | BERT CPU % | BERT memory MiB |")
print("|---|---|---|---|---|---|---|")
for mode in MODES:
    st = stats[mode]
    nd = [v for s, v in st.items() if needed(mode, s)]
    al = [v for s, v in st.items() if s != "review"]
    b = st.get("bert", (0, 0, 0))
    print(f"| {mode} | {sum(v[0] for v in nd):.0f} | {sum(v[1] for v in nd):.0f} | {sum(v[0] for v in al):.0f} | "
          f"{sum(v[1] for v in al):.0f} | {b[0]:.0f} | {b[1]:.0f} |")

print("\n## Per service (CPU % / memory MiB)\n")
names = sorted({s for m in MODES for s in stats[m]})
print("| service | " + " | ".join(MODES) + " |")
print("|---|" + "---|" * len(MODES))
for s in names:
    print(f"| {s} | " + " | ".join(
        f"{stats[m][s][0]:.1f} / {stats[m][s][1]:.0f}" if s in stats[m] else "-" for m in MODES) + " |")
print(f"\ndocker stats samples per service: {stats[MODES[0]].get('gateway', (0, 0, 0))[2]}")
