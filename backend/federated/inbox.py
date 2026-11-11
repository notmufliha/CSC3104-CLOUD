"""inbox.py - Workstream G, real phones: where a phone's trained model change waits for the next federated round.

A real phone (the app's PhoneTrainer) trains the downloaded models on its own labelled calls, then uploads ONLY the
change to the models' numbers, clipped and with noise added on the phone (see the app's PhoneTrainer.kt). This file
stores those uploads; bridge_client.py hands them to the Flower server in the next round. Standard library only.

Upload format "fl-update" v1 (JSON, optionally gzip):
  {"format": "fl-update", "format_version": 1,
   "phone": "<random id made at install; not linked to the user>",
   "clip": 10.0, "local_noise": 1.0,
   "models": {"urgency": {"base": "v3", "lines": 12, "delta_b64": "<float32 LE, n_features x parts>",
                          "intercept_delta": 0.01}, ...}}       (only the models the phone trained)
A newer upload from the same phone replaces its older pending one. Nothing here is ever served back out.

Settings: INBOX_DIR (unset = uploads disabled), INBOX_MAX_BYTES (default 8 MB).
"""
import base64
import gzip
import json
import math
import os
import re
import struct
import time
from pathlib import Path

DIR = Path(os.environ["INBOX_DIR"]) if os.environ.get("INBOX_DIR") else None
ENABLED = DIR is not None
MAX_BYTES = int(os.environ.get("INBOX_MAX_BYTES", 8_000_000))
N_FEATURES = 2 ** 18
DIMS = {"urgency": N_FEATURES, "money": N_FEATURES, "impersonation": 2 * N_FEATURES}   # = fedcore.PARTS
PHONE_RE = re.compile(r"^[a-f0-9-]{8,40}$")
VERSION_RE = re.compile(r"^v\d{1,4}$")


def pending_dir() -> Path:
    d = DIR / "pending"
    d.mkdir(parents=True, exist_ok=True)
    return d


def used_dir() -> Path:
    d = DIR / "used"
    d.mkdir(parents=True, exist_ok=True)
    return d


def decode_delta(b64: str, n: int) -> list:
    raw = base64.b64decode(b64, validate=True)
    if len(raw) != 4 * n:
        raise ValueError(f"delta has {len(raw) // 4} numbers, expected {n}")
    return raw


def store(body: bytes, gzipped=False) -> dict:
    """Validate one upload and keep it for the next round. Raises ValueError on anything malformed."""
    if gzipped:
        body = gzip.decompress(body)
        if len(body) > 4 * MAX_BYTES:
            raise ValueError("update too large")
    try:
        doc = json.loads(body)
    except json.JSONDecodeError:
        raise ValueError("not JSON")
    if doc.get("format") != "fl-update" or doc.get("format_version") != 1:
        raise ValueError("unknown format")
    phone = str(doc.get("phone", ""))
    if not PHONE_RE.match(phone):
        raise ValueError("bad phone id")
    models = doc.get("models") or {}
    if not models or set(models) - set(DIMS):
        raise ValueError(f"models must be some of {sorted(DIMS)}")
    for name, m in models.items():
        if not VERSION_RE.match(str(m.get("base", ""))):
            raise ValueError(f"{name}: bad base version")
        if not (isinstance(m.get("lines"), int) and 0 < m["lines"] <= 100_000):
            raise ValueError(f"{name}: bad line count")
        raw = decode_delta(m.get("delta_b64", ""), DIMS[name])
        if not all(math.isfinite(v) for v in struct.unpack(f"<{len(raw) // 4}f", raw)[::997]) or \
                not math.isfinite(float(m.get("intercept_delta", 0.0))):
            raise ValueError(f"{name}: not finite")
    # One pending update per phone: a newer one replaces the older (a phone trains on all its labels each time).
    for old in pending_dir().glob(f"*_{phone}.json.gz"):
        old.unlink()
    name = f"{int(time.time() * 1000)}_{phone}.json.gz"
    (pending_dir() / name).write_bytes(gzip.compress(json.dumps(doc).encode()))
    print(f"inbox: update from phone {phone[:8]} for {', '.join(f'{k} ({v['lines']} lines, base {v['base']})' for k, v in models.items())}",
          flush=True)
    return {"stored": True, "models": sorted(models), "next": "used in the next federated round"}


def pending() -> list:
    """[(path, doc)] of every waiting upload (bridge_client.py)."""
    out = []
    for p in sorted(pending_dir().glob("*.json.gz")):
        try:
            out.append((p, json.loads(gzip.decompress(p.read_bytes()))))
        except Exception as e:
            print(f"inbox: skipping unreadable {p.name}: {e}", flush=True)
    return out


def mark_used(paths) -> None:
    for p in paths:
        p.rename(used_dir() / p.name)
