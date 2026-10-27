"""Send each script's caller lines to the gateway exactly as the app does and print the verdict per line.

Usage (backend running, e.g. `docker compose up -d --build` from the repo root):
    python3 -I backend_script_test.py <path to android-app/app/src/main/assets/scripts> [gateway url]
"""
import glob
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

scripts_dir = sys.argv[1]
base = (sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080").rstrip("/")


def post(body):
    req = urllib.request.Request(base + "/v1/utterance", json.dumps(body).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return {"verdict": f"HTTP {e.code}"}
    except Exception as e:
        return {"verdict": f"ERROR {type(e).__name__}"}


print("| Script | seq | Caller line | Verdict | combined | call_scores (u / m / i) |")
print("| --- | --- | --- | --- | --- | --- |")
for path in sorted(glob.glob(os.path.join(scripts_dir, "*.json"))):
    name = os.path.basename(path)
    parts = name[:-5].split("_")
    lang = parts[1] if len(parts) > 1 and parts[1] in ("en", "zh") else None
    call_id = str(uuid.uuid4())
    for i, line in enumerate(json.load(open(path, encoding="utf-8"))):
        if line.get("speaker", "").strip().lower() != "caller":
            continue  # the app never sends receiver lines
        body = {"call_id": call_id, "seq": i + 1, "speaker": "caller", "text": line["text"]}
        if lang:
            body["lang"] = lang
        r = post(body)
        cs = r.get("call_scores") or {}
        fmt = lambda k: f"{cs[k]:.2f}" if k in cs else "-"
        score = r.get("combined_score")
        print(f"| {name} | {i + 1} | {line['text'][:45]} | {r.get('verdict')} | "
              f"{'-' if score is None else f'{score:.2f}'} | {fmt('urgency')} / {fmt('money')} / {fmt('impersonation')} |")
