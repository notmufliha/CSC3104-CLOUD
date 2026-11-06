#!/usr/bin/env python3
"""learning.py - Workstream H: run the "learn a new scam" loop on the Kubernetes demo, step by step.

    python demo/tools/learning.py status                      what's deployed: model versions, queue, registry
    python demo/tools/learning.py report  [--url URL]         play a short scam call and report it (what the
                                                              app's "Report this call" button does)
    python demo/tools/learning.py review                      open the labelling page: http://127.0.0.1:8090/
    python demo/tools/learning.py retrain [--force]           run the retraining Job now and show its result
    python demo/tools/learning.py rollout latest [--during-call]   every model with a newer release, one pod at a time
    python demo/tools/learning.py rollout v5 --model urgency_en    one model to one version (its baked version = back
                                                                   to the model baked into the image)
    python demo/tools/learning.py rollback [--model urgency_en] [--during-call]   kubectl rollout undo (the last
                                                                   change; no --model = every model on a release)
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
REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "backend" / "retrain"))
from models import MODELS  # noqa: E402  the six workers, their signal, language and baked version
CA = REPO / "demo" / "tls" / "ca.crt"
DEFAULT_URL = "https://127.0.0.1:8443/api/"
SCAM_CALL = [
    "Hello, this is the delivery department about your parcel.",
    "Your package will be sent back tonight unless you confirm in the next ten minutes.",
    "Please stay on the line, if you hang up now the case is closed.",
    "I need you to do this right now, there is no time to ask anyone.",
]
SCAM_CALL_ZH = ["您好，这里是快递公司，您有一个包裹出了问题。", "请在十分钟内确认，否则包裹今晚就会被退回。",
                "请不要挂电话，挂断的话案件就会关闭。", "您现在马上处理，没有时间问别人了。"]


def deploy_of(model: str) -> str:
    return model.replace("_", "-")


def baked(model: str) -> str:
    return f"v{MODELS[model]['baked']}"


# ------------------------------------------------------------------------------------------------ helpers
def kubectl(*args, check=True, capture=True, input_text=None):
    if not shutil.which("kubectl"):
        sys.exit("kubectl not found")
    # UTF-8 explicitly: Chinese job logs can't be read with Windows' default encoding.
    r = subprocess.run(["kubectl", *args], capture_output=capture, text=True, input=input_text,
                       encoding="utf-8", errors="replace")
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


def released_version(model: str) -> str:
    """The registry version this worker's Deployment is set to run; empty = the model baked into its image."""
    env = json.loads(kubectl("get", "deploy", deploy_of(model), "-n", NS, "-o",
                             "jsonpath={.spec.template.spec.containers[0].env}").stdout or "[]")
    return next((e.get("value", "") for e in env if e["name"] == "MODEL_VERSION"), "")


def deployed_version(model: str) -> str:
    return released_version(model) or f"{baked(model)} (baked)"


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
    try:
        print(f"{'model':<20}{'running':<14}{'pods':<7}released")
        for model in MODELS:
            pods = kubectl("get", "pods", "-n", NS, "-l", f"app={deploy_of(model)}", "--field-selector=status.phase=Running",
                           "-o", "name").stdout.split()
            have = [m["version"] for m in review_get(f"/models/{model}")]
            print(f"{model:<20}{deployed_version(model):<14}{len(pods):<7}{', '.join(have) or '-'}")
        s = review_get("/api/stats")
        print(f"queue: {s['reports']} reports, {s['unlabelled']} lines waiting for a label, {s['kept_lines']} kept "
              f"after expiry, not trained on yet: {s['new_lines']}, disputed {s['disputed']}")
    except SystemExit as e:
        print(f"review service not reachable: {e}")
    jobs = kubectl("get", "jobs", "-n", NS, "-l", "app=retrain-models", "--sort-by=.metadata.creationTimestamp",
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
        print(f"  line {seq}: {verdict:<6} scores={r['scores']} models={r.get('model_versions', {})}")
        lines.append({"seq": seq, "text": text})
    r = post("v1/report", {"call_id": call_id, "kind": "scam", "app_verdict": verdict or "", "language": "en",
                           "consent": True, "consent_version": 2, "lines": lines})
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
    job = json.loads(kubectl("create", "job", name, "-n", NS, "--from=cronjob/retrain-models",
                             "--dry-run=client", "-o", "json").stdout)
    env = job["spec"]["template"]["spec"]["containers"][0].setdefault("env", [])
    if a.force:
        env.append({"name": "FORCE", "value": "1"})
    if a.tolerance is not None:
        env[:] = [e for e in env if e["name"] != "TOLERANCE"] + [{"name": "TOLERANCE", "value": str(a.tolerance)}]
    kubectl("apply", "-f", "-", input_text=json.dumps(job))
    print(f"Job {name} started{' (FORCE)' if a.force else ''}; each model takes a few minutes...")
    deadline = time.time() + 2400
    while time.time() < deadline:
        st = json.loads(kubectl("get", "job", name, "-n", NS, "-o", "json").stdout).get("status", {})
        if st.get("succeeded") or st.get("failed"):
            break
        time.sleep(3)
    print(kubectl("logs", "-n", NS, f"job/{name}", check=False).stdout)
    ok = bool(st.get("succeeded"))
    print(f"Job {name}: {'succeeded' if ok else 'FAILED (blocked by the release check, or an error above)'}")
    return 0 if ok else 1


def call_during(url, model, stop, log):
    """Sends a caller line every 0.5 s until stopped; records which version of this model answered."""
    post = gateway(url)
    signal, lang = MODELS[model]["signal"], MODELS[model]["language"]
    script = SCAM_CALL_ZH if lang == "zh" else SCAM_CALL
    call_id, seq = f"rollout-{model}-{int(time.time())}", 0
    while not stop.is_set():
        seq += 1
        t = time.monotonic()
        try:
            r = post("v1/utterance", {"call_id": call_id, "seq": seq, "speaker": "caller", "lang": lang,
                                      "text": script[seq % len(script)], "t_ms": 20000 + 3000 * seq}, timeout=4)
            v = r.get("model_versions", {}).get(signal, "missing" if signal in r.get("missing", []) else "?")
            log.append((seq, v, None, (time.monotonic() - t) * 1000))
        except (urllib.error.URLError, OSError, ValueError) as e:
            log.append((seq, None, str(e), (time.monotonic() - t) * 1000))
        stop.wait(0.5)


def with_call(a, model, change) -> int:
    """Runs change() (a kubectl change to this model's worker), waits for the rollout, optionally with a live call."""
    stop, log = threading.Event(), []
    th = None
    if a.during_call:
        th = threading.Thread(target=call_during, args=(a.url, model, stop, log), daemon=True)
        th.start()
        time.sleep(2)
    t0 = time.monotonic()
    change()
    deploy = deploy_of(model)
    r = kubectl("rollout", "status", f"deployment/{deploy}", "-n", NS, "--timeout=300s", check=False, capture=False)
    print(f"rollout {'finished' if r.returncode == 0 else 'DID NOT FINISH'} in {time.monotonic() - t0:.0f}s; "
          f"{deploy} now runs {deployed_version(model)}")
    if th:
        time.sleep(3)
        stop.set()
        th.join()
        report_call(log)
    return r.returncode


def roll_out(a, model: str, version: str) -> int:
    deploy, before = deploy_of(model), deployed_version(model)

    def change():
        print(f"Rolling update {deploy}: {before} -> {version} (one pod at a time; old pods serve until new ones are ready)")
        # Annotate first: the new revision copies it, so `kubectl rollout history` says what each revision was.
        kubectl("annotate", f"deployment/{deploy}", "-n", NS, "--overwrite",
                f"kubernetes.io/change-cause={model} model {before} -> {version}")
        # The baked version = no MODEL_VERSION at all.
        kubectl("set", "env", f"deployment/{deploy}", "-n", NS,
                "MODEL_VERSION-" if version == baked(model) else f"MODEL_VERSION={version}")
    return with_call(a, model, change)


def cmd_rollout(a):
    if a.version == "latest":
        rc = 0
        for model in ([a.model] if a.model else MODELS):
            have = [m["version"] for m in review_get(f"/models/{model}")]
            if not have or have[-1] == released_version(model):
                print(f"{model}: nothing newer (running {deployed_version(model)})")
                continue
            rc = roll_out(a, model, have[-1]) or rc
        return rc
    if not a.model:
        sys.exit(f"Which model should run {a.version}? Add --model, e.g. --model urgency_en. "
                 f"('rollout latest' moves every model to its newest release.)")
    have = [m["version"] for m in review_get(f"/models/{a.model}")]
    if a.version not in have + [baked(a.model)]:
        sys.exit(f"{a.version} is not in the registry for {a.model} (released: {have or 'none'}; {baked(a.model)} is "
                 f"baked in). Only versions that passed the release check can be rolled out.")
    return roll_out(a, a.model, a.version)


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
    """kubectl rollout undo: back to the revision before the LAST change to a worker (normally the last rollout)."""
    rc = 0
    models = [a.model] if a.model else [m for m in MODELS if released_version(m)]
    if not models:
        print("Every model already runs its baked version: nothing to roll back.")
    for model in models:
        deploy, before = deploy_of(model), deployed_version(model)

        def change():
            print(f"Rolling back {deploy} (now {before}) to its previous revision")
            kubectl("rollout", "undo", f"deployment/{deploy}", "-n", NS)
        rc = with_call(a, model, change) or rc
        # Kubernetes copies the Deployment's note onto its current revision, so set it AFTER the undo (before it,
        # it would overwrite the note of the revision being undone); otherwise the restored revision keeps the last
        # rollout's note, which describes the wrong change.
        kubectl("annotate", f"deployment/{deploy}", "-n", NS, "--overwrite",
                f"kubernetes.io/change-cause=rollback: {model} model {before} -> {deployed_version(model)}")
        print(kubectl("rollout", "history", f"deployment/{deploy}", "-n", NS).stdout)
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
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # job logs can contain Chinese
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
    p.add_argument("version", help="'latest', or a version such as v5 (with --model)")
    p.add_argument("--model", choices=list(MODELS), help="one model; 'rollout latest' without it means all of them")
    p.add_argument("--during-call", action="store_true", help="keep a call running during the update and report it")
    p.add_argument("--url", default=DEFAULT_URL)
    p.set_defaults(fn=cmd_rollout)
    p = sub.add_parser("rollback")
    p.add_argument("--model", choices=list(MODELS), help="one model; without it, every model on a released version")
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
