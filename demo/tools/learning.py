#!/usr/bin/env python3
"""learning.py - Workstream H: run the "learn a new scam" loop on the Kubernetes demo, step by step.

    python demo/tools/learning.py status                      what's deployed: model version, queue, registry
    python demo/tools/learning.py report  [--url URL]         play a short scam call and report it (what the
                                                              app's "Report this call" button does)
    python demo/tools/learning.py review                      open the labelling page: http://127.0.0.1:8090/
    python demo/tools/learning.py retrain [--force]           run the retraining Job now and show its result
    python demo/tools/learning.py rollout v5 [--during-call]  rolling update urgency-en to v5, one pod at a time
    python demo/tools/learning.py rollout v4                  back to the model baked into the image
    python demo/tools/learning.py rollback [--during-call]    kubectl rollout undo (undo the last change)
    python demo/tools/learning.py monitoring up|down          Prometheus + Grafana (optional, ~400 MB)
    python demo/tools/learning.py ensure-key                  (used by up.sh/up.ps1) create the review-key Secret

--url defaults to the Workstream E address https://127.0.0.1:8443/api/ (demo/expose.ps1 or expose.sh running),
checked against demo/tls/ca.crt. Plain http://127.0.0.1:8080/ (port-forward) works too.
"""
import argparse
import base64
import json
import os
import shutil
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

NS = "scamguard"
DEPLOY = "urgency-en"
MODEL = "urgency_en"
BAKED = "v4"   # the version baked into the urgency_en image
REPO = Path(__file__).resolve().parents[2]
CA = REPO / "demo" / "tls" / "ca.crt"
DEFAULT_URL = "https://127.0.0.1:8443/api/"
SCAM_CALL = [
    "Hello, this is the delivery department about your parcel.",
    "Your package will be sent back tonight unless you confirm in the next ten minutes.",
    "Please stay on the line, if you hang up now the case is closed.",
    "I need you to do this right now, there is no time to ask anyone.",
]


# ------------------------------------------------------------------------------------------------ helpers
def kubectl(*args, check=True, capture=True, input_text=None):
    if not shutil.which("kubectl"):
        sys.exit("kubectl not found")
    r = subprocess.run(["kubectl", *args], capture_output=capture, text=True, input=input_text)
    if check and r.returncode != 0:
        sys.exit(f"kubectl {' '.join(args)} failed:\n{r.stderr if capture else ''}")
    return r


def in_review(code: str) -> str:
    """Runs a short Python snippet inside the review pod (the registry/queue are internal-only)."""
    return kubectl("exec", "-n", NS, "deploy/review", "--", "python", "-c", code).stdout


def review_get(path: str):
    out = in_review(f"import urllib.request,sys;sys.stdout.write(urllib.request.urlopen("
                    f"'http://127.0.0.1:8090{path}',timeout=20).read().decode())")
    return json.loads(out)


def gateway(url: str):
    url = url if url.endswith("/") else url + "/"
    ctx = None
    if url.startswith("https"):
        if not CA.exists():
            sys.exit(f"{CA} not found: run demo/up first (it makes the certificate)")
        ctx = ssl.create_default_context(cafile=str(CA))

    def post(path, body, timeout=5.0):
        req = urllib.request.Request(url + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            return json.loads(r.read())
    return post


def deployed_version() -> str:
    env = json.loads(kubectl("get", "deploy", DEPLOY, "-n", NS, "-o",
                             "jsonpath={.spec.template.spec.containers[0].env}").stdout or "[]")
    return next((e.get("value", "") for e in env if e["name"] == "MODEL_VERSION"), "") or f"{BAKED} (baked)"


# ----------------------------------------------------------------------------------------------- commands
def cmd_ensure_key(_):
    r = kubectl("get", "secret", "review-key", "-n", NS, check=False)
    if r.returncode == 0:
        print("review-key Secret already exists (kept: changing it would make stored reports unreadable)")
        return 0
    key = base64.urlsafe_b64encode(os.urandom(32)).decode()  # a Fernet key
    manifest = kubectl("create", "secret", "generic", "review-key", "-n", NS, f"--from-literal=key={key}",
                       "--dry-run=client", "-o", "yaml").stdout
    kubectl("apply", "-f", "-", input_text=manifest)
    print("created the review-key Secret (the review queue's encryption key)")
    return 0


def cmd_status(_):
    print(f"urgency-en model version: {deployed_version()}")
    pods = kubectl("get", "pods", "-n", NS, "-l", f"app={DEPLOY}", "-o",
                   "jsonpath={range .items[*]}{.metadata.name} {.status.phase}{\"\\n\"}{end}").stdout.strip()
    print("pods:\n  " + pods.replace("\n", "\n  "))
    try:
        print("registry:", [m["version"] for m in review_get(f"/models/{MODEL}")] or "(empty: only the baked v4)")
        s = review_get("/api/stats")
        print(f"queue: {s['reports']} reports, {s['unlabelled']} lines waiting for a label, "
              f"{s['unused']['urgency']} urgency label(s) not trained on yet, disputed {s['disputed']}")
    except SystemExit as e:
        print(f"review service not reachable: {e}")
    jobs = kubectl("get", "jobs", "-n", NS, "-l", "app=retrain-urgency-en", "--sort-by=.metadata.creationTimestamp",
                   "-o", "jsonpath={range .items[*]}{.metadata.name} succeeded={.status.succeeded} "
                   "failed={.status.failed}{\"\\n\"}{end}", check=False).stdout.strip()
    print("retrain jobs:\n  " + (jobs.replace("\n", "\n  ") if jobs else "(none yet)"))
    return 0


def cmd_report(a):
    post = gateway(a.url)
    call_id = f"learn-{int(time.time())}"
    print(f"Playing a {len(SCAM_CALL)}-line scam call ({call_id}) through {a.url}")
    lines, verdict = [], None
    for i, text in enumerate(SCAM_CALL):
        seq = 2 * i + 1
        r = post("v1/utterance", {"call_id": call_id, "seq": seq, "speaker": "caller", "lang": "en",
                                  "text": text, "t_ms": 20000 + 4000 * i})
        verdict = r.get("verdict")
        print(f"  line {seq}: {verdict:<6} urgency={r['scores'].get('urgency')} "
              f"model={r.get('model_versions', {}).get('urgency', '?')}")
        lines.append({"seq": seq, "text": text})
    r = post("v1/report", {"call_id": call_id, "kind": "scam", "app_verdict": verdict or "", "language": "en",
                           "consent": True, "lines": lines})
    print(f"Reported with consent: {r}")
    print("Next: label it ->  python demo/tools/learning.py review")
    return 0


def cmd_review(_):
    print("Labelling page: http://127.0.0.1:8090/   (Ctrl+C to stop)")
    while True:
        kubectl("port-forward", "-n", NS, "svc/review", "8090:8090", check=False, capture=False)
        print("forward dropped -- restarting in 1s...")
        time.sleep(1)


def cmd_retrain(a):
    name = f"retrain-now-{int(time.time())}"
    job = json.loads(kubectl("create", "job", name, "-n", NS, "--from=cronjob/retrain-urgency-en",
                             "--dry-run=client", "-o", "json").stdout)
    env = job["spec"]["template"]["spec"]["containers"][0].setdefault("env", [])
    if a.force:
        env.append({"name": "FORCE", "value": "1"})
    if a.tolerance is not None:
        env[:] = [e for e in env if e["name"] != "TOLERANCE"] + [{"name": "TOLERANCE", "value": str(a.tolerance)}]
    kubectl("apply", "-f", "-", input_text=json.dumps(job))
    print(f"Job {name} started{' (FORCE)' if a.force else ''}; training takes about a minute...")
    deadline = time.time() + 900
    while time.time() < deadline:
        st = json.loads(kubectl("get", "job", name, "-n", NS, "-o", "json").stdout).get("status", {})
        if st.get("succeeded") or st.get("failed"):
            break
        time.sleep(3)
    print(kubectl("logs", "-n", NS, f"job/{name}", check=False).stdout)
    ok = bool(st.get("succeeded"))
    print(f"Job {name}: {'succeeded' if ok else 'FAILED (blocked by the release check, or an error above)'}")
    return 0 if ok else 1


def call_during(url, stop, log):
    """Sends a caller line every 0.5 s until stopped; records which model version answered."""
    post = gateway(url)
    call_id, seq = f"rollout-{int(time.time())}", 0
    while not stop.is_set():
        seq += 1
        t = time.monotonic()
        try:
            r = post("v1/utterance", {"call_id": call_id, "seq": seq, "speaker": "caller", "lang": "en",
                                      "text": SCAM_CALL[seq % len(SCAM_CALL)], "t_ms": 20000 + 3000 * seq}, timeout=4)
            v = r.get("model_versions", {}).get("urgency", "missing" if "urgency" in r.get("missing", []) else "?")
            log.append((seq, v, None, (time.monotonic() - t) * 1000))
        except (urllib.error.URLError, OSError, ValueError) as e:
            log.append((seq, None, str(e), (time.monotonic() - t) * 1000))
        stop.wait(0.5)


def with_call(a, change) -> int:
    """Runs change() (a kubectl change to urgency-en), waits for the rollout, optionally with a live call."""
    stop, log = threading.Event(), []
    th = None
    if a.during_call:
        th = threading.Thread(target=call_during, args=(a.url, stop, log), daemon=True)
        th.start()
        time.sleep(2)
    t0 = time.monotonic()
    change()
    r = kubectl("rollout", "status", f"deployment/{DEPLOY}", "-n", NS, "--timeout=300s", check=False, capture=False)
    print(f"rollout {'finished' if r.returncode == 0 else 'DID NOT FINISH'} in {time.monotonic() - t0:.0f}s; "
          f"{DEPLOY} now runs {deployed_version()}")
    if th:
        time.sleep(3)
        stop.set()
        th.join()
        report_call(log)
    return r.returncode


def cmd_rollout(a):
    version = a.version
    have = [m["version"] for m in review_get(f"/models/{MODEL}")]
    if version not in have + [BAKED]:
        sys.exit(f"{version} is not in the registry (released: {have or 'none'}; {BAKED} is baked in). Only "
                 f"versions that passed the release check can be rolled out.")
    before = deployed_version()

    def change():
        print(f"Rolling update {DEPLOY}: {before} -> {version} (one pod at a time; old pods serve until new ones are ready)")
        # Annotate first: the new revision copies it, so `kubectl rollout history` says what each revision was.
        kubectl("annotate", f"deployment/{DEPLOY}", "-n", NS, "--overwrite",
                f"kubernetes.io/change-cause=urgency_en model {before} -> {version}")
        # The baked version = no MODEL_VERSION at all.
        kubectl("set", "env", f"deployment/{DEPLOY}", "-n", NS,
                "MODEL_VERSION-" if version == BAKED else f"MODEL_VERSION={version}")
    return with_call(a, change)


def report_call(log):
    errors = [x for x in log if x[2]]
    versions = []
    for _, v, _, _ in log:
        if v and (not versions or versions[-1][0] != v):
            versions.append([v, 0])
        if v:
            versions[-1][1] += 1
    slow = max((x[3] for x in log), default=0)
    print(f"\nLive call during the rollout: {len(log)} lines sent, {len(errors)} failed, slowest {slow:.0f} ms")
    print("  versions that answered, in order: " + " -> ".join(f"{v} x{n}" for v, n in versions))
    for seq, _, err, _ in errors[:5]:
        print(f"  line {seq} failed: {err}")


def cmd_rollback(a):
    """kubectl rollout undo: back to the revision before the LAST change to urgency-en (normally the last rollout)."""
    before = deployed_version()

    def change():
        print(f"Rolling back {DEPLOY} (now {before}) to its previous revision")
        kubectl("rollout", "undo", f"deployment/{DEPLOY}", "-n", NS)
    rc = with_call(a, change)
    # Kubernetes copies the Deployment's note onto its current revision, so set it AFTER the undo (before it,
    # it would overwrite the note of the revision being undone); otherwise the restored revision keeps the last
    # rollout's note, which describes the wrong change.
    kubectl("annotate", f"deployment/{DEPLOY}", "-n", NS, "--overwrite",
            f"kubernetes.io/change-cause=rollback: urgency_en model {before} -> {deployed_version()}")
    print(kubectl("rollout", "history", f"deployment/{DEPLOY}", "-n", NS).stdout)
    return rc


def cmd_monitoring(a):
    folder = str(REPO / "demo" / "k8s" / "monitoring")
    if a.action == "down":
        kubectl("delete", "-f", folder, "--ignore-not-found", capture=False)
        return 0
    kubectl("apply", "-f", folder, capture=False)
    for d in ("prometheus", "grafana"):
        kubectl("rollout", "status", f"deployment/{d}", "-n", NS, "--timeout=180s", capture=False)
    print("\nGrafana    http://127.0.0.1:3000/   (dashboard 'ScamGuard: learning new scams' opens by itself)")
    print("Prometheus http://127.0.0.1:9090/alerts   (Ctrl+C to stop the forwards)")
    procs = [subprocess.Popen(["kubectl", "port-forward", "-n", NS, f"svc/{s}", f"{p}:{p}"])
             for s, p in (("grafana", 3000), ("prometheus", 9090))]
    try:
        for p in procs:
            p.wait()
    except KeyboardInterrupt:
        for p in procs:
            p.terminate()
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("ensure-key").set_defaults(fn=cmd_ensure_key)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    p = sub.add_parser("report")
    p.add_argument("--url", default=DEFAULT_URL)
    p.set_defaults(fn=cmd_report)
    sub.add_parser("review").set_defaults(fn=cmd_review)
    p = sub.add_parser("retrain")
    p.add_argument("--force", action="store_true", help="run even with fewer than MIN_NEW_LABELS new labels")
    p.add_argument("--tolerance", type=float, help="override the release check's tolerance (e.g. -0.05 to see a block)")
    p.set_defaults(fn=cmd_retrain)
    p = sub.add_parser("rollout")
    p.add_argument("version")
    p.add_argument("--during-call", action="store_true", help="keep a call running during the update and report it")
    p.add_argument("--url", default=DEFAULT_URL)
    p.set_defaults(fn=cmd_rollout)
    p = sub.add_parser("rollback")
    p.add_argument("--during-call", action="store_true")
    p.add_argument("--url", default=DEFAULT_URL)
    p.set_defaults(fn=cmd_rollback)
    p = sub.add_parser("monitoring")
    p.add_argument("action", choices=["up", "down"])
    p.set_defaults(fn=cmd_monitoring)
    a = ap.parse_args()
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
