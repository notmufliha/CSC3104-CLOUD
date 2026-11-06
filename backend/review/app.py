"""review service - Workstream H: the review queue, labelling page and model registry. INTERNAL ONLY.

The phone never talks to this service directly. It sends a report to the gateway (POST /v1/report), and the
gateway forwards just that one call here. Everything else (the queue, labelling, the registry) is reachable
only inside the cluster, or from the laptop with `kubectl port-forward` (demo/learning.ps1 / .sh).

  POST /reports                            from the gateway: one consented report (lines masked again here)
  GET  /                                   labelling page (H.5)
  GET  /api/queue                          unlabelled lines
  POST /api/labels                         label one line for the three signals
  POST /api/skip                           skip a line that can't be labelled
  GET  /api/spotcheck?checker=NAME         someone else's labels to double-check
  POST /api/spotcheck                      agree / disagree
  GET  /api/labelled.csv?signal=urgency    training rows for the retraining Job (H.6)
  POST /api/labelled/mark-used             the Job marks the rows it trained on
  GET  /api/stats                          queue counts
  GET  /models/{name}                      released versions (H.8)
  GET  /models/{name}/{version|latest}     one version's metadata (sha256, release-check scores...)
  GET  /models/{name}/{version}/file       the model file (workers download it at start-up)
  POST /models/{name}/{version}            the retraining Job publishes a version that PASSED the release check
  GET  /metrics                            Prometheus counts (no text)
  GET  /health

Settings (environment): REVIEW_KEY (Fernet key, required; from a Kubernetes Secret), REVIEW_DATA_DIR
(/data), REVIEW_RETENTION_DAYS (30; store.purge_expired says what happens then). Transcript text is never logged.
"""
import csv
import io
import logging
import os
import re
import threading
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, PlainTextResponse, Response
from pydantic import BaseModel, Field

from mask import mask
from store import LANGUAGES, SIGNALS, Store

logging.basicConfig(level=os.environ.get("LOGLEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("review")

KEY = os.environ.get("REVIEW_KEY", "").strip()
if not KEY:
    raise SystemExit("REVIEW_KEY is not set: the review queue refuses to store text unencrypted")
store = Store(os.environ.get("REVIEW_DATA_DIR", "/data"), KEY.encode(),
              int(os.environ.get("REVIEW_RETENTION_DAYS", "30")))
NAME_RE = re.compile(r"^[a-z]+_(en|zh)$")
VERSION_RE = re.compile(r"^v\d{1,4}$")
reports_received = {"scam": 0, "fine": 0}

app = FastAPI(title="ScamGuard review service", docs_url=None, redoc_url=None)


def purge_loop():
    while True:
        try:
            n = store.purge_expired()
            if n:
                log.info("purged %d expired report(s)", n)
        except Exception as e:  # keep purging even if one round fails
            log.error("purge failed: %s", e)
        time.sleep(600)


threading.Thread(target=purge_loop, daemon=True).start()


# ------------------------------------------------------------------------------------------------ reports
class ReportLine(BaseModel):
    seq: int = Field(ge=0)
    text: str = Field(min_length=1, max_length=2000)


class Report(BaseModel):
    call_id: str = Field(min_length=1, max_length=128)   # only used for the log line, never stored
    kind: str = Field(pattern="^(scam|fine)$")
    app_verdict: str = Field(default="", max_length=16)
    language: str = Field(default="en", pattern="^(en|zh)$")
    consent: bool
    # Which consent wording the user saw. 2 = approved lines may be kept after 30 days; an older app sends nothing.
    consent_version: int = Field(default=1, ge=1)
    lines: list[ReportLine] = Field(min_length=1, max_length=200)


@app.post("/reports")
def add_report(r: Report):
    if r.consent is not True:
        raise HTTPException(400, "consent is required: nothing was stored")
    lines = [(l.seq, mask(l.text)) for l in r.lines]
    out = store.add_report(r.kind, r.app_verdict, r.language, lines, r.consent_version)
    reports_received[r.kind] += 1
    log.info("report %s stored: kind=%s lines=%d language=%s", out["report_id"], r.kind, len(lines), r.language)
    return out


# ---------------------------------------------------------------------------------------------- labelling
class Labels(BaseModel):
    line_id: int
    labels: dict[str, int]
    labeller: str = Field(min_length=1, max_length=40)


class Skip(BaseModel):
    line_id: int


class SpotCheck(BaseModel):
    line_id: int
    signal: str
    agrees: bool
    checker: str = Field(min_length=1, max_length=40)


class MarkUsed(BaseModel):
    signal: str
    line_ids: list[int]
    version: str = Field(pattern=VERSION_RE.pattern)


@app.get("/", response_class=HTMLResponse)
def labelling_page():
    return (Path(__file__).parent / "labelling.html").read_text(encoding="utf-8")


@app.get("/api/queue")
def queue(limit: int = Query(20, ge=1, le=200)):
    return store.queue(limit)


@app.post("/api/labels")
def label(b: Labels):
    bad = [s for s, v in b.labels.items() if s not in SIGNALS or v not in (0, 1)]
    if bad or not b.labels:
        raise HTTPException(400, f"labels must be 0/1 for signals {SIGNALS}")
    try:
        return {"labelled": store.label(b.line_id, b.labels, b.labeller.strip())}
    except KeyError:
        raise HTTPException(404, "no such line (expired?)")


@app.post("/api/skip")
def skip(b: Skip):
    store.skip(b.line_id)
    return {"skipped": b.line_id}


@app.get("/api/spotcheck")
def spot_check_sample(checker: str = Query(min_length=1, max_length=40), limit: int = Query(10, ge=1, le=100)):
    return store.spot_check_sample(checker.strip(), limit)


@app.post("/api/spotcheck")
def spot_check(b: SpotCheck):
    try:
        store.spot_check(b.line_id, b.signal, b.agrees, b.checker.strip())
    except KeyError:
        raise HTTPException(404, "no such label, or you labelled it yourself")
    return {"ok": True}


@app.get("/api/labelled.csv", response_class=PlainTextResponse)
def labelled_csv(signal: str = Query(pattern="^(urgency|money|impersonation)$"),
                 language: str = Query("en", pattern="^(en|zh)$"), unused: bool = False):
    """Same columns as the workers' augment CSVs (text_raw, label, source, conversation_id, label_method)."""
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["text_raw", "label", "source", "conversation_id", "label_method", "line_id", "used_in"])
    for r in store.labelled(signal, language, unused):
        w.writerow([r["text"], r["label"], "user_report", f"report_{r['report_id']}", "human_review",
                    r["line_id"], r["used_in"] or ""])
    return buf.getvalue()


@app.post("/api/labelled/mark-used")
def mark_used(b: MarkUsed):
    return {"marked": store.mark_used(b.signal, b.line_ids, b.version)}


@app.get("/api/stats")
def stats():
    return store.stats()


# ----------------------------------------------------------------------------------------- model registry
def check_name(name: str, version: str = "v1"):
    if not NAME_RE.match(name) or not (version == "latest" or VERSION_RE.match(version)):
        raise HTTPException(400, "bad model name or version (e.g. urgency_en / v5)")


@app.get("/models/{name}")
def list_models(name: str):
    check_name(name)
    return store.models(name)


@app.get("/models/{name}/{version}")
def get_model(name: str, version: str):
    check_name(name, version)
    meta = store.model(name, version)
    if not meta:
        raise HTTPException(404, f"{name} {version} not in the registry")
    return meta


@app.get("/models/{name}/{version}/file")
def get_model_file(name: str, version: str):
    check_name(name, version)
    meta = store.model(name, version)
    if not meta:
        raise HTTPException(404, f"{name} {version} not in the registry")
    return FileResponse(store.model_path(name, meta["version"]), media_type="application/octet-stream")


@app.post("/models/{name}/{version}")
async def add_model(name: str, version: str, file: UploadFile = File(...), meta: str = Form("{}")):
    import json
    check_name(name, version)
    if version == "latest":
        raise HTTPException(400, "publish an explicit version")
    try:
        out = store.add_model(name, version, await file.read(), json.loads(meta))
    except FileExistsError as e:
        raise HTTPException(409, str(e))
    log.info("model %s %s published (sha256 %s)", name, version, out["sha256"][:12])
    return out


# ------------------------------------------------------------------------------------------------ metrics
@app.get("/metrics")
def metrics():
    s = store.stats()
    out = ["# HELP review_reports_received_total Reports received since this pod started.",
           "# TYPE review_reports_received_total counter"]
    out += [f'review_reports_received_total{{kind="{k}"}} {v}' for k, v in reports_received.items()]
    out += ["# HELP review_reports_stored Reports currently in the queue (before the 30-day deletion).",
            "# TYPE review_reports_stored gauge"]
    out += [f'review_reports_stored{{kind="{k}"}} {v}' for k, v in s["reports"].items()]
    out += ["# HELP review_lines_kept Reviewer-approved lines kept as training examples after their report expired.",
            "# TYPE review_lines_kept gauge", f"review_lines_kept {s['kept_lines']}"]
    out += ["# TYPE review_lines_unlabelled gauge", f"review_lines_unlabelled {s['unlabelled']}",
            "# HELP review_labels_unused Labelled lines no model has trained on yet.",
            "# TYPE review_labels_unused gauge"]
    out += [f'review_labels_unused{{signal="{k}"}} {v}' for k, v in s["unused"].items()]
    out += ["# TYPE review_labels_disputed gauge"]
    out += [f'review_labels_disputed{{signal="{k}"}} {v}' for k, v in s["disputed"].items()]
    out += ["# HELP review_model_latest Number of the newest released version, e.g. 5 for v5.",
            "# TYPE review_model_latest gauge"]
    for name in (f"{signal}_{lang}" for lang in LANGUAGES for signal in SIGNALS):
        latest = store.model(name, "latest")
        if latest:
            out.append(f'review_model_latest{{name="{name}"}} {latest["version"].lstrip("v")}')
    return Response("\n".join(out) + "\n", media_type="text/plain; version=0.0.4")


@app.get("/health")
def health():
    store.stats()  # touches the database
    return {"status": "ok"}
