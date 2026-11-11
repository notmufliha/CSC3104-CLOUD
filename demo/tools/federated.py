#!/usr/bin/env python3
"""federated.py - Workstream G on minikube: build, run federated rounds, publish, download.

Needs the main demo running (demo/up.ps1 or up.sh): the registry lives in the review service.

  python demo/tools/federated.py build            build scam/federated + scam/model-download, load into minikube
  python demo/tools/federated.py up               start the model-download service + its Ingress (/models)
  python demo/tools/federated.py run [options]    one federated run: fl-server Job + 10 fl-phones pods
        --rounds 10 --phones 10 --noise 0 --clip 10 --init zeros|current --publish --name NAME
        --real-phones   also bring in the model changes real phones uploaded to the inbox (fl-bridge Job)
        --make-room     pause what a federated run doesn't need (BERT, the 3 Chinese workers, Prometheus, Grafana)
                        for the run, then put them back: on the 8 GB laptop the 11 extra pods don't fit otherwise
  python demo/tools/federated.py inbox            uploads waiting for the next round, and those already used
  python demo/tools/federated.py chart            all runs saved so far -> backend/federated/results/rounds.png
  python demo/tools/federated.py status           pods, registry versions, the manifest phones see
  python demo/tools/federated.py down             delete the run namespace and the download service

`run` waits for the server to finish, saves its results to backend/federated/results/<run>.json (the same files
run_local.py writes), and prints the release check.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "demo"))
import make_federated as M  # noqa: E402

RESULTS = REPO / "backend" / "federated" / "results"
# Not used by a federated run or by phones downloading models: the phone models, the registry (review), the
# download service and the inbox all stay up. Cloud calls (level 1) still work, without BERT's second opinion.
ROOM = ["bert", "urgency-zh", "money-zh", "impersonation-zh", "prometheus", "grafana"]


def sh(*cmd, check=True, capture=False, input_text=None):
    r = subprocess.run(list(cmd), text=True, capture_output=capture, input=input_text, cwd=REPO)
    if check and r.returncode != 0:
        sys.exit(f"failed: {' '.join(cmd)}\n{(r.stderr or '') if capture else ''}")
    return r.stdout if capture else r.returncode


def kubectl(*args, **kw):
    return sh("kubectl", *args, **kw)


def cmd_build(_):
    for tag, dockerfile, context in (("federated", "backend/federated/Dockerfile", "."),
                                     ("model-download", "backend/federated/Dockerfile.download", "backend")):
        print(f"--- building scam/{tag}:{M.TAG} ---", flush=True)
        sh("docker", "build", "-t", f"scam/{tag}:{M.TAG}", "-f", dockerfile, context)
        sh("minikube", "image", "load", f"scam/{tag}:{M.TAG}")
    # The review service gained the _phone model name (G.10): rebuild it too if the cluster runs an older image.
    print("--- rebuilding scam/review (registry accepts urgency_en_phone) ---", flush=True)
    sh("docker", "build", "-t", f"scam/review:{M.TAG}", "-f", "backend/review/Dockerfile", "backend")
    sh("minikube", "image", "load", f"scam/review:{M.TAG}")
    kubectl("rollout", "restart", "deployment/review", "-n", M.NS, check=False)


def cmd_up(_):
    M.write()
    kubectl("apply", "-f", "demo/k8s/federated/00-download.yaml")
    kubectl("apply", "-f", "demo/k8s/federated/05-inbox.yaml")
    kubectl("rollout", "status", "deployment/model-download", "-n", M.NS, "--timeout=120s")
    kubectl("rollout", "status", "deployment/fl-inbox", "-n", M.NS, "--timeout=120s")
    print("model-download + fl-inbox are up. Phones fetch https://<address>/models/manifest on start-up and "
          "upload their training to https://<address>/fl/updates.")


def make_room() -> dict:
    """Scale ROOM to 0 and return each one's replica count, to put back afterwards. HPAs leave a Deployment at 0
    alone, so they don't fight this."""
    saved = {}
    for d in ROOM:
        n = kubectl("get", "deploy", d, "-n", M.NS, "-o", "jsonpath={.spec.replicas}", capture=True, check=False).strip()
        if n.isdigit() and int(n) > 0:
            saved[d] = int(n)
    if saved:
        kubectl("scale", "deploy", *saved, "-n", M.NS, "--replicas=0")
        print(f"made room: paused {', '.join(saved)} for this run", flush=True)
        for _ in range(60):   # wait until their pods are gone (memory is only free then)
            left = kubectl("get", "pods", "-n", M.NS, "-o", "name", capture=True, check=False)
            if not any(f"pod/{d}-" in left for d in saved):
                break
            time.sleep(3)
    return saved


def give_back(saved: dict) -> None:
    for d, n in saved.items():
        kubectl("scale", "deploy", d, "-n", M.NS, f"--replicas={n}", check=False)
    if saved:
        print(f"put back: {', '.join(f'{d} x{n}' for d, n in saved.items())}", flush=True)


def cmd_run(a):
    saved = make_room() if a.make_room else {}
    try:
        run(a)
    finally:
        give_back(saved)


def run(a):
    M.write()
    kubectl("apply", "-f", "demo/k8s/federated/10-namespace.yaml")
    kubectl("delete", "job", "fl-server", "fl-phones", "-n", M.FL_NS, "--ignore-not-found", "--wait=true")
    kubectl("delete", "job", "fl-bridge", "-n", M.NS, "--ignore-not-found", "--wait=true")
    kubectl("apply", "-f", "-", input_text=M.run_yaml(a.rounds, a.phones, a.noise, a.clip, a.init, a.publish, a.name,
                                                       a.real_phones))
    print(f"started: {a.rounds} rounds, {a.phones} phones, noise {a.noise}, clip {a.clip}, init {a.init}, "
          f"publish {a.publish}. Watch: kubectl get pods -n {M.FL_NS} -w", flush=True)
    deadline = time.time() + a.timeout
    while time.time() < deadline:
        # check=False: a busy API server can time out for a moment; just ask again
        state = kubectl("get", "job", "fl-server", "-n", M.FL_NS, "-o",
                        "jsonpath={.status.succeeded}/{.status.failed}", capture=True, check=False) or ""
        if state.startswith("1/"):
            break
        if state.endswith("/1"):
            print(kubectl("logs", "job/fl-server", "-n", M.FL_NS, "--tail=40", capture=True, check=False))
            sys.exit("fl-server failed (logs above)")
        time.sleep(5)
    else:
        sys.exit(f"timed out after {a.timeout}s; see kubectl logs job/fl-server -n {M.FL_NS}")
    logs = kubectl("logs", "job/fl-server", "-n", M.FL_NS, capture=True)
    if a.real_phones:
        logs = kubectl("logs", "job/fl-bridge", "-n", M.NS, capture=True, check=False) + "\n" + logs
    for line in logs.splitlines():
        if line.startswith("[server]") or line.startswith("[bridge]") or "BELOW BAR" in line or line.startswith("=>") or line.rstrip().endswith(" ok"):
            print(line)
    result = next(json.loads(l[len("RESULT_JSON "):]) for l in logs.splitlines() if l.startswith("RESULT_JSON "))
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"{result['run']}.json"
    out.write_text(json.dumps(result, indent=2))
    print(f"saved {out.relative_to(REPO)}")


def cmd_chart(_):
    sh(sys.executable, "backend/federated/plot_rounds.py")


def cmd_status(_):
    print(kubectl("get", "pods", "-n", M.FL_NS, "-o", "wide", capture=True, check=False))
    print(kubectl("get", "pods", "-n", M.NS, "-l", "app=model-download", capture=True, check=False))
    code = ("import json,urllib.request as u;"
            "print('registry versions:', [m['version'] for m in json.load(u.urlopen("
            "'http://localhost:8090/models/urgency_en_phone'))])")
    print(kubectl("exec", "-n", M.NS, "deploy/review", "--", "python", "-c", code, capture=True, check=False))
    code = "import urllib.request as u;print('manifest:', u.urlopen('http://localhost:8000/models/manifest').read().decode())"
    print(kubectl("exec", "-n", M.NS, "deploy/model-download", "--", "python", "-c", code, capture=True, check=False))


def cmd_inbox(_):
    code = ("import os;[print(d, sorted(os.listdir('/inbox/'+d)) if os.path.isdir('/inbox/'+d) else [])"
            " for d in ('pending','used')]")
    print(kubectl("exec", "-n", M.NS, "deploy/fl-inbox", "--", "python", "-c", code, capture=True, check=False))


def cmd_down(_):
    kubectl("delete", "namespace", M.FL_NS, "--ignore-not-found")
    kubectl("delete", "job", "fl-bridge", "-n", M.NS, "--ignore-not-found")
    kubectl("delete", "-f", "demo/k8s/federated/00-download.yaml", "--ignore-not-found")
    kubectl("delete", "-f", "demo/k8s/federated/05-inbox.yaml", "--ignore-not-found")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("build").set_defaults(fn=cmd_build)
    sub.add_parser("up").set_defaults(fn=cmd_up)
    p = sub.add_parser("run")
    p.add_argument("--rounds", type=int, default=10)
    p.add_argument("--phones", type=int, default=10)
    p.add_argument("--noise", type=float, default=0.0)
    p.add_argument("--clip", type=float, default=10.0)
    p.add_argument("--init", choices=["zeros", "current"], default="zeros")
    p.add_argument("--publish", action="store_true", help="publish to the registry if the release check passes")
    p.add_argument("--name", default="")
    p.add_argument("--real-phones", action="store_true", help="include real phones' uploads (fl-bridge)")
    p.add_argument("--make-room", action="store_true", help="pause BERT, ZH workers and monitoring during the run")
    p.add_argument("--timeout", type=int, default=900)
    p.set_defaults(fn=cmd_run)
    sub.add_parser("chart").set_defaults(fn=cmd_chart)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    sub.add_parser("inbox").set_defaults(fn=cmd_inbox)
    sub.add_parser("down").set_defaults(fn=cmd_down)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
