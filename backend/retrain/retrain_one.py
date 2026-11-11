"""retrain_one.py - retrain ONE worker's model on its original data + the reviewed user reports. Started by
retrain_job.py, in the environment that model is served with.

  1. Download every labelled line for this signal and language (masked text, label) as extra training rows.
  2. Get the current model: the newest version in the registry, or the one baked into the worker image.
  3. Train a candidate with the worker's own train.py.
  4. Release check (release_check.py). If it fails: BLOCKED, nothing is published, exit 1.
  5. PASS -> publish to the registry as the next version with the scores in its metadata, and mark the lines it
     trained on as used. Rolling out is a separate, human step (python demo/tools/learning.py rollout latest).

Usage: python retrain_one.py urgency_en     (REVIEW_URL, TOLERANCE from the environment)
"""
import hashlib
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

import adapters
import release_check
from models import MODELS

REVIEW = os.environ.get("REVIEW_URL", "http://review:8090").rstrip("/")
TOLERANCE = float(os.environ.get("TOLERANCE", "0.005"))


def get(path: str, raw=False):
    with urllib.request.urlopen(REVIEW + path, timeout=60) as r:
        data = r.read()
    return data if raw else json.loads(data)


def post_json(path: str, payload: dict) -> dict:
    req = urllib.request.Request(REVIEW + path, data=json.dumps(payload).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


def publish(name: str, version: str, path: Path, meta: dict) -> dict:
    """multipart/form-data POST with the standard library (no extra dependency in the image)."""
    boundary = uuid.uuid4().hex
    body = io.BytesIO()
    body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="meta"\r\n\r\n'.encode())
    body.write(json.dumps(meta).encode() + b"\r\n")
    body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
               f"Content-Type: application/octet-stream\r\n\r\n".encode())
    body.write(path.read_bytes() + f"\r\n--{boundary}--\r\n".encode())
    req = urllib.request.Request(f"{REVIEW}/models/{name}/{version}", data=body.getvalue(), method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def current_model(name: str, adapter, baked: int, work: Path):
    """(version, model, threshold) of the newest released model, or the one baked into the worker image."""
    try:
        meta = get(f"/models/{name}/latest")
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        return f"v{baked}", adapter.load(adapter.baked_path), adapter.baked_threshold()
    data = get(f"/models/{name}/{meta['version']}/file", raw=True)
    if hashlib.sha256(data).hexdigest() != meta["sha256"]:
        raise SystemExit(f"registry file for {name} {meta['version']} does not match its sha256")
    path = work / f"current_{meta['version']}.joblib"
    path.write_bytes(data)
    return meta["version"], adapter.load(path), float(meta["threshold"])


def main(name: str) -> int:
    cfg = MODELS[name]
    signal, language = cfg["signal"], cfg["language"]
    work = Path(os.environ.get("WORK_DIR", str(adapters.REPO / "work"))) / name
    work.mkdir(parents=True, exist_ok=True)
    adapter = adapters.get(name, signal, work)

    # All labelled lines, not just the new ones, so the next version still learns what the last one learned.
    labelled = pd.read_csv(io.BytesIO(get(f"/api/labelled.csv?signal={signal}&language={language}", raw=True)),
                           keep_default_na=False)
    if labelled.empty:
        print(f"{name}: no labelled lines, nothing to train on")
        return 0
    new_ids = labelled.loc[labelled["used_in"] == "", "line_id"].tolist()
    learned = labelled.loc[(labelled["used_in"] != "") & (labelled["label"] == 1), "text_raw"].tolist()
    reports = labelled.drop(columns=["used_in"])
    print(f"{name}: training on the original data + {len(reports)} reviewed report line(s) "
          f"({int(reports['label'].sum())} positive, {len(new_ids)} new)")

    cur_version, current, cur_threshold = current_model(name, adapter, cfg["baked"], work)
    existing = [int(m["version"].lstrip("v")) for m in get(f"/models/{name}")]
    version = f"v{max(existing + [cfg['baked']]) + 1}"

    candidate = work / f"{version}.joblib"
    t0 = time.monotonic()
    threshold = adapter.train(reports, candidate, version)
    print(f"{name}: trained {version} in {time.monotonic() - t0:.0f}s")

    result = release_check.compare(adapter, current, cur_threshold, adapter.load(candidate), threshold,
                                   TOLERANCE, learned)
    release_check.print_table(result, cur_version, version)
    if not result["pass"]:
        print(f"{name} {version} BLOCKED by the release check: not published. {cur_version} stays in service.")
        return 1

    meta = {"created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "previous": cur_version,
            "trained_on_reports": int(len(reports)), "release_check": result, "threshold": threshold}
    out = publish(name, version, candidate, meta)
    marked = post_json("/api/labelled/mark-used", {"signal": signal, "line_ids": new_ids, "version": version})
    print(f"PUBLISHED {name} {version} (sha256 {out['sha256'][:12]}), {marked['marked']} line(s) marked used.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
