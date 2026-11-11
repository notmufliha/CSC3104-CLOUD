"""retrain_job.py - Workstream H.6: retrain the worker models when reviewed reports show a new scam.

Runs as a Kubernetes CronJob (weekly) or on demand: python demo/tools/learning.py retrain [--force]

Each reported line is labelled for all three signals, so a new scam in a language retrains all three of that
language's models, one after the other. A language only counts its own lines: Chinese reports can't teach an
English model, so they never trigger one.

  1. For each language, count the labelled lines no model has trained on yet. Fewer than MIN_NEW_LABELS
     (default 50) -> nothing to do for it. FORCE=1 runs with any new line at all (for a demo).
  2. For each model of a triggered language that has new labels: retrain_one.py in that model's environment.
     One model being blocked doesn't stop the others. Its labels stay waiting, so it alone is tried next run.
  3. Print one summary. Any model blocked or failed -> exit 1 (the Job shows as Failed).

Standard library only: this runs outside the two training environments (models.py).
Settings (environment): REVIEW_URL (http://review:8090), MIN_NEW_LABELS, FORCE, TOLERANCE (0.005).
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from models import MODELS

REVIEW = os.environ.get("REVIEW_URL", "http://review:8090").rstrip("/")
MIN_NEW = int(os.environ.get("MIN_NEW_LABELS", "50"))
FORCE = os.environ.get("FORCE", "").strip() in ("1", "true", "yes")
OUTCOME = {0: "published", 1: "BLOCKED"}


def python_for(name: str) -> str:
    """The model's training environment in the image; the current Python elsewhere (a laptop run)."""
    path = Path("/venv") / MODELS[name]["python"] / "bin" / "python"
    return str(path) if path.exists() else sys.executable


def models_to_retrain(stats: dict) -> list:
    chosen = []
    for language in sorted({m["language"] for m in MODELS.values()}):
        new = stats["new_lines"].get(language, 0)
        go = new > 0 and (FORCE or new >= MIN_NEW)
        print(f"{language}: {new} labelled line(s) not trained on yet (threshold {MIN_NEW}"
              f"{', FORCE' if FORCE else ''}) -> {'retrain' if go else 'nothing to do'}")
        if go:
            waiting = stats["unused_by_language"][language]
            chosen += [n for n, m in MODELS.items() if m["language"] == language and waiting[m["signal"]] > 0]
    return chosen


def main() -> int:
    with urllib.request.urlopen(f"{REVIEW}/api/stats", timeout=60) as r:
        stats = json.loads(r.read())
    results = []
    for name in models_to_retrain(stats):
        print(f"\n===== {name} =====", flush=True)
        t0 = time.monotonic()
        code = subprocess.run([python_for(name), "retrain_one.py", name], cwd=Path(__file__).parent).returncode
        results.append((name, OUTCOME.get(code, f"FAILED (exit {code})"), time.monotonic() - t0))
    if results:
        print("\n===== summary =====")
        for name, outcome, seconds in results:
            print(f"{name:<20}{outcome:<20}{seconds:>6.0f} s")
        if any(o == "published" for _, o, _ in results):
            print("Roll out what was published:  python demo/tools/learning.py rollout latest --during-call")
    return 0 if all(o == "published" for _, o, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main())
