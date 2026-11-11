"""registry.py - Workstream G.10: talk to the model registry Workstream H already runs (backend/review/app.py).

The phone models are stored there under their own names (PHONE_MODELS: <signal>_en_phone), next to the cloud workers
models, with the same rules: versions v1, v2, ... are never overwritten, and only a model that passed the release
check is published. Standard library only (the download service image has nothing else).
"""
import json
import os
import urllib.error
import urllib.request
import uuid

REVIEW = os.environ.get("REVIEW_URL", "http://review.scamguard.svc.cluster.local:8090")
PHONE_MODELS = ("urgency_en_phone", "money_en_phone", "impersonation_en_phone")
PHONE_MODEL = PHONE_MODELS[0]


def _get(path, raw=False, timeout=10):
    with urllib.request.urlopen(f"{REVIEW}{path}", timeout=timeout) as r:
        data = r.read()
    return data if raw else json.loads(data)


def versions(name=PHONE_MODEL) -> list:
    return _get(f"/models/{name}")


def latest(name=PHONE_MODEL):
    """Metadata of the newest version, or None if the registry has none yet."""
    try:
        return _get(f"/models/{name}/latest")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def file(name, version) -> bytes:
    return _get(f"/models/{name}/{version}/file", raw=True, timeout=30)


def next_version(name=PHONE_MODEL) -> str:
    nums = [int(m["version"].lstrip("v")) for m in versions(name)]
    return f"v{max(nums, default=0) + 1}"


def publish(data: bytes, meta: dict, name=PHONE_MODEL) -> dict:
    """Multipart POST, the format the registry's add_model endpoint takes (file + meta)."""
    version = next_version(name)
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n{json.dumps(meta)}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}_{version}.json\"\r\n"
            f"Content-Type: application/json\r\n\r\n").encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(f"{REVIEW}/models/{name}/{version}", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())
