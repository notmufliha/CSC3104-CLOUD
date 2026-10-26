"""fetch_model.py - make sure model/ has the three model files; run by the Dockerfile at build time.

If model/ already holds them (copied in from your machine, e.g. straight out of the Colab notebook), they are
used as they are. Otherwise they are downloaded from MODEL_BASE_URL, a folder URL such as a Hugging Face model
repo pinned to a commit:

    https://huggingface.co/<user>/csc3104-scam-bert/resolve/<commit-hash>

(a public repo needs no token; set HF_TOKEN for a private one). A GitHub release works too:
    https://github.com/<org>/<repo>/releases/download/bert-v1

If MODEL_SHA256 is set, model.onnx must match it (bert_config.json from the notebook also records it).

With no MODEL_BASE_URL / MODEL_SHA256 set, the team's published model (DEFAULT_URL / DEFAULT_SHA256 below) is
used, so `python fetch_model.py` on a laptop works with no setup. After retraining, update both here, in the
Dockerfile ARGs and in docker-compose.yml.
Standard library only.
"""
import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

DIR = Path(__file__).resolve().parent / "model"
FILES = ["bert_config.json", "tokenizer.json", "model.onnx"]
DEFAULT_URL = "https://huggingface.co/gouthamsaravanan/csc3104-scam-bert/resolve/a0ebfbc63c1591a889798d7828cd5c65e063c57a"
DEFAULT_SHA256 = "de0f771d861a5029666d97736f9715bef097b52eead991c973a67855d82f0c1c"


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    DIR.mkdir(exist_ok=True)
    base = (os.environ.get("MODEL_BASE_URL") or DEFAULT_URL).rstrip("/")
    for name in FILES:
        dest = DIR / name
        if dest.exists() and dest.stat().st_size > 0:
            print(f"model/{name}: present ({dest.stat().st_size / 1e6:.1f} MB)")
            continue
        if not base or "REPLACE_ME" in base:
            sys.exit(f"model/{name} is missing and MODEL_BASE_URL is not set: put the notebook's files in "
                     f"backend/workers/bert/model/ or build with --build-arg MODEL_BASE_URL=<folder url>")
        url = f"{base}/{name}"
        req = urllib.request.Request(url)
        if os.environ.get("HF_TOKEN"):
            req.add_header("Authorization", f"Bearer {os.environ['HF_TOKEN']}")
        print(f"downloading {url}")
        with urllib.request.urlopen(req, timeout=600) as r, open(dest, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        print(f"model/{name}: {dest.stat().st_size / 1e6:.1f} MB")
    want = os.environ.get("MODEL_SHA256") or (DEFAULT_SHA256 if base == DEFAULT_URL else None) \
        or json.loads((DIR / "bert_config.json").read_text()).get("model_sha256")
    if want:
        got = sha256(DIR / "model.onnx")
        if got != want:
            sys.exit(f"model.onnx sha256 {got} != expected {want}")
        print("model.onnx sha256 ok")


if __name__ == "__main__":
    main()
