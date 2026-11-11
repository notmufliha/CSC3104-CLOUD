"""
benchmark_data.py - Workstream G.5: the robocall test set for the phone benchmark (BenchmarkTest).

Reads backend/workers/urgency_en/eval/robocall_gold.csv (200 sentences from real robocalls) and writes
app/src/androidTest/assets/benchmark/robocall.json: [{"id", "text", "label", "status"}].

Note what `label` is: the URGENCY worker's label (1 = urgent), partly AI-labelled and marked PROVISIONAL in
backend/workers/urgency_en/LABELING.md. It is not a scam/not-scam label, so the benchmark reports it as
"agreement with urgency labels" and uses the 14 demo calls for call-level scam detection.

  python android-app/tools/benchmark_data.py
"""
import csv
import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / "backend" / "workers" / "urgency_en" / "eval" / "robocall_gold.csv"
OUT = REPO / "android-app" / "app" / "src" / "androidTest" / "assets" / "benchmark" / "robocall.json"

rows = [{"id": r["id"], "text": r["text"], "label": int(r["label"]), "status": r["status"]}
        for r in csv.DictReader(SRC.open(newline="", encoding="utf-8"))]
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({"source": str(SRC.relative_to(REPO)), "label": "urgency (provisional, see LABELING.md)",
                           "rows": rows}, indent=1, ensure_ascii=False) + "\n")
print(f"{OUT.relative_to(REPO)}: {len(rows)} sentences, {sum(r['label'] for r in rows)} labelled 1")
