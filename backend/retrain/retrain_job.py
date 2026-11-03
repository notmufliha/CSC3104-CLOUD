"""retrain_job.py - Workstream H.6: retrain urgency_en on the original data + reviewed user reports.

Runs as a Kubernetes CronJob (weekly) or on demand:
    kubectl create job -n scamguard --from=cronjob/retrain-urgency-en retrain-now

Steps
  1. Ask the review service how many labelled report lines no model has trained on yet. Fewer than
     MIN_NEW_LABELS (default 50) -> nothing to do, exit 0. FORCE=1 skips this (for the demo).
  2. Download every labelled urgency line (masked text, label) as an extra training file.
  3. Get the model in service now: the newest version in the registry, or the baked v4 if there is none.
  4. Train a candidate with the worker's own train.py (same data, same settings + the reported lines).
  5. Release check (release_check.py): the candidate must score at least as well as the current model on the
     fixed human test sets. If not -> BLOCKED: nothing is published, the Job exits 1 (shows as Failed).
  6. PASS -> publish it to the registry as the next version (v5, v6...) with the release-check scores in its
     metadata, and mark the lines it trained on as used. Rolling it out is a separate, human step
     (python demo/tools/learning.py rollout vN), so a person decides when live traffic moves to the new version.

Settings (environment): REVIEW_URL (http://review:8090), MIN_NEW_LABELS, FORCE, TOLERANCE (0.005).
"""
import hashlib
import io
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

import joblib
import pandas as pd

import release_check

REVIEW = os.environ.get("REVIEW_URL", "http://review:8090").rstrip("/")
NAME, SIGNAL, LANGUAGE = "urgency_en", "urgency", "en"
MIN_NEW = int(os.environ.get("MIN_NEW_LABELS", "50"))
FORCE = os.environ.get("FORCE", "").strip() in ("1", "true", "yes")
TOLERANCE = float(os.environ.get("TOLERANCE", "0.005"))
URGENCY = release_check.URGENCY_DIR
REPO = URGENCY.parents[2]
BAKED = URGENCY / "model" / "urgency_en_v4.joblib"
# Inside the repo tree: train.py records its training files relative to the repo root.
WORK = Path(os.environ.get("WORK_DIR", str(REPO / "work")))


def get(path: str, raw=False):
    with urllib.request.urlopen(REVIEW + path, timeout=60) as r:
        data = r.read()
    return data if raw else json.loads(data)


def publish(version: str, path: Path, meta: dict) -> dict:
    """multipart/form-data POST with the standard library (no extra dependency in the image)."""
    boundary = uuid.uuid4().hex
    body = io.BytesIO()
    body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="meta"\r\n\r\n'.encode())
    body.write(json.dumps(meta).encode() + b"\r\n")
    body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
               f"Content-Type: application/octet-stream\r\n\r\n".encode())
    body.write(path.read_bytes() + f"\r\n--{boundary}--\r\n".encode())
    req = urllib.request.Request(f"{REVIEW}/models/{NAME}/{version}", data=body.getvalue(), method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def post_json(path: str, payload: dict) -> dict:
    req = urllib.request.Request(REVIEW + path, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def current_model():
    """(version, bundle) of the newest released model, or the baked v4."""
    try:
        meta = get(f"/models/{NAME}/latest")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        return "v4", joblib.load(BAKED)
    data = get(f"/models/{NAME}/{meta['version']}/file", raw=True)
    if hashlib.sha256(data).hexdigest() != meta["sha256"]:
        raise SystemExit(f"registry file for {meta['version']} does not match its sha256")
    path = WORK / f"current_{meta['version']}.joblib"
    path.write_bytes(data)
    return meta["version"], joblib.load(path)


def main() -> int:
    WORK.mkdir(parents=True, exist_ok=True)
    stats = get("/api/stats")
    new = stats["unused"][SIGNAL]
    print(f"labelled {SIGNAL} lines not trained on yet: {new} (threshold {MIN_NEW}{', FORCE' if FORCE else ''})")
    if new == 0 or (new < MIN_NEW and not FORCE):
        print("nothing to do")
        return 0

    # All labelled lines (not just the new ones), so v6 still learns from what v5 learned.
    reported = pd.read_csv(io.BytesIO(get(f"/api/labelled.csv?signal={SIGNAL}&language={LANGUAGE}", raw=True)))
    unused_ids = pd.read_csv(io.BytesIO(get(
        f"/api/labelled.csv?signal={SIGNAL}&language={LANGUAGE}&unused=true", raw=True)))["line_id"].tolist()
    extra = WORK / "augment_user_reports.csv"
    reported.drop(columns=["line_id"]).to_csv(extra, index=False)
    print(f"training on the original data + {len(reported)} reviewed report line(s) "
          f"({int(reported['label'].sum())} urgent)")

    cur_version, cur_bundle = current_model()
    existing = [int(m["version"].lstrip("v")) for m in get(f"/models/{NAME}")]
    version = f"v{max(existing + [int(cur_version.lstrip('v'))]) + 1}"

    candidate = WORK / f"{NAME}_{version}.joblib"
    t0 = time.monotonic()
    defaults = [REPO / "data" / "urgency_en" / "augment_review_flips.csv",
                REPO / "data" / "urgency_en" / "augment_urgency_en.csv"]
    subprocess.run([sys.executable, str(URGENCY / "train.py"), "--augment", *map(str, defaults), str(extra),
                    "--out", str(candidate)], check=True, cwd=URGENCY)
    print(f"trained {version} in {time.monotonic() - t0:.0f}s")

    bundle = joblib.load(candidate)
    bundle["version"] = version
    bundle["trained_on_reports"] = int(len(reported))
    joblib.dump(bundle, candidate)

    result = release_check.compare(cur_bundle, bundle, TOLERANCE)
    release_check.print_table(result, cur_version, version)
    if not result["pass"]:
        print(f"{version} BLOCKED by the release check: not published. {cur_version} stays in service.")
        return 1

    meta = {"created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "previous": cur_version, "trained_on_reports": int(len(reported)),
            "release_check": result, "threshold": bundle["threshold"],
            "sklearn_version": bundle.get("sklearn_version")}
    out = publish(version, candidate, meta)
    marked = post_json("/api/labelled/mark-used", {"signal": SIGNAL, "line_ids": unused_ids, "version": version})
    print(f"PUBLISHED {NAME} {version} (sha256 {out['sha256'][:12]}), {marked['marked']} line(s) marked used.")
    print(f"Roll it out:  python demo/tools/learning.py rollout {version} --during-call")
    return 0


if __name__ == "__main__":
    sys.exit(main())
