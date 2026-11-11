"""model_registry.py - Workstream H.8: lets any worker run a released model version instead of its baked one.

Set MODEL_VERSION (e.g. "v5") and the worker downloads that version from the model registry (MODEL_REGISTRY_URL,
the review service) at start-up and checks its sha256. If that fails the worker refuses to start, so a rolling
update to a bad version stalls with the old pods still serving. No MODEL_VERSION = the model baked into the image.

Copied next to server.py in each worker image, under this exact name.
"""
import hashlib
import json
import os
import tempfile
import urllib.request


def resolve(model_name: str, baked_path: str):
    """(path of the model to load, its registry version). The version is None for the baked model."""
    version = os.environ.get("MODEL_VERSION", "").strip()
    if not version:
        return baked_path, None
    base_url = os.environ.get("MODEL_REGISTRY_URL", "http://review:8090").rstrip("/")
    base = f"{base_url}/models/{model_name}/{version}"
    with urllib.request.urlopen(base, timeout=30) as r:
        meta = json.load(r)
    path = os.path.join(tempfile.gettempdir(), f"{model_name}_{version}.joblib")
    digest = hashlib.sha256()
    with urllib.request.urlopen(base + "/file", timeout=120) as r, open(path, "wb") as f:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            digest.update(chunk)
            f.write(chunk)
    if digest.hexdigest() != meta["sha256"]:
        raise ValueError(f"{model_name} {version}: sha256 mismatch, refusing to load")
    return path, version
