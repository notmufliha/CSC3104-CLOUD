"""download_service.py - Workstream G.10: where phones get new on-device models. Read-only and stateless.

The phone calls this through the Ingress (https://<address>/models/...) when the app starts. It never reaches the
registry itself: the registry lives in the review service, which also holds the review queue and must stay
internal. This service only reads the registry's released phone models and caches the files in memory (a few MB,
identified by sha256), so it can run as several copies and be restarted at any time.

  GET /models/manifest                 {"models": {"urgency_en_phone": {"version", "sha256", "path"}, ...}}
  GET /models/<name>/<v>               the model JSON (export_phone.py format 2), name = urgency|money|impersonation_en_phone
  GET /health

Standard library only.  REVIEW_URL (default http://review.scamguard.svc.cluster.local:8090), PORT (8000).
"""
import hashlib
import json
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import registry  # noqa: E402

MODELS = list(registry.PHONE_MODELS)
FILE_RE = re.compile(r"^/models/([a-z_]+)/(v\d{1,4})$")
_cache, _lock = {}, threading.Lock()


def manifest() -> dict:
    out = {}
    for name in MODELS:
        meta = registry.latest(name)
        if meta:
            out[name] = {"version": meta["version"], "sha256": meta["sha256"],
                         "path": f"/models/{name}/{meta['version']}"}
    return {"models": out}


def model_file(name: str, version: str) -> bytes:
    key = (name, version)
    with _lock:
        if key in _cache:
            return _cache[key]
    data = registry.file(name, version)   # versions never change once published, so caching is safe
    with _lock:
        _cache[key] = data
    return data


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body: bytes, ctype="application/json", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0].rstrip("/")
        try:
            if path == "/health":
                return self._send(200, b'{"status":"ok"}')
            if path == "/models/manifest":
                return self._send(200, json.dumps(manifest()).encode(), extra={"Cache-Control": "no-cache"})
            m = FILE_RE.match(path)
            if m and m.group(1) in MODELS:
                data = model_file(m.group(1), m.group(2))
                return self._send(200, data, extra={"ETag": hashlib.sha256(data).hexdigest(),
                                                    "Cache-Control": "public, max-age=31536000, immutable"})
            return self._send(404, b'{"error":"not found"}')
        except Exception as e:  # registry down or unknown version: the app keeps the model it has
            code = getattr(e, "code", 503)
            return self._send(code if code in (404,) else 503, json.dumps({"error": type(e).__name__}).encode())

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.address_string()} {fmt % args}\n")


def main():
    port = int(os.environ.get("PORT", 8000))
    print(f"model download service on :{port}, registry {registry.REVIEW}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
