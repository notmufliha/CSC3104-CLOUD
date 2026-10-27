#!/usr/bin/env python3
"""make_loadgen.py - generates demo/k8s/loadgen/loadgen.yaml: a ConfigMap holding demo/tools/load_sim.py,
a second ConfigMap holding the app's own real call scripts, and a Job that runs load_sim.py against
http://gateway:8080 FROM INSIDE THE CLUSTER (so the laptop's own CPU/network is never the bottleneck when
judging whether the backend spreads load across pods -- see DEMO_TASK.md section 4, "loadgen").

Run this whenever demo/tools/load_sim.py or the app's scripts change; `demo/up.sh`/`up.ps1` do NOT apply
this Job automatically (it is a one-off load test, not part of the always-on stack) -- run it with:

    python demo/make_loadgen.py && kubectl apply -f demo/k8s/loadgen/loadgen.yaml
    kubectl logs -n scamguard -f job/loadgen
    kubectl delete -f demo/k8s/loadgen/loadgen.yaml   # before re-running (Jobs don't restart themselves)

Tune the run with env vars on the Job (LOAD_USERS, LOAD_DURATION, LOAD_RAMP, LOAD_PACE, LOAD_SCAM_RATIO,
LOAD_ZH_RATIO, LOAD_ERROR_THRESHOLD -- see load_sim.py --help), either by editing JOB_ENV below and
re-running this script, or with `kubectl set env job/loadgen KEY=VALUE` before applying.
"""
import sys
from pathlib import Path

NAMESPACE = "scamguard"
DEMO_DIR = Path(__file__).resolve().parent
REPO_ROOT = DEMO_DIR.parent
LOAD_SIM_PY = DEMO_DIR / "tools" / "load_sim.py"
SCRIPTS_DIR = REPO_ROOT / "android-app" / "app" / "src" / "main" / "assets" / "scripts"
OUT = DEMO_DIR / "k8s" / "loadgen" / "loadgen.yaml"

# Defaults for the in-cluster run -- shorter/lighter than load_sim.py's own interactive defaults, since a
# Job is meant to run unattended once and finish (see `kubectl logs -f job/loadgen` for the results table).
JOB_ENV = {
    "LOAD_USERS": "30",
    "LOAD_DURATION": "60",
    "LOAD_RAMP": "10",
    "LOAD_PACE": "10",
    "LOAD_SCAM_RATIO": "0.5",
    "LOAD_ZH_RATIO": "0.3",
    "LOAD_ERROR_THRESHOLD": "0.05",
}


def indent(text: str, prefix: str) -> str:
    return "\n".join((prefix + line if line else "") for line in text.splitlines())


def configmap_from_files(name: str, files: dict) -> str:
    """files: {key -> text content}. Uses a literal YAML block scalar per key, safe for arbitrary Python/
    JSON text (no quoting headaches) as long as content has no trailing lines that confuse indentation,
    which plain source/JSON files don't."""
    parts = [f"apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: {name}\n  namespace: {NAMESPACE}\ndata:"]
    for key, content in files.items():
        parts.append(f"  {key}: |")
        parts.append(indent(content, "    "))
    return "\n".join(parts) + "\n"


def job_yaml() -> str:
    env_lines = "\n".join(f"            - name: {k}\n              value: \"{v}\"" for k, v in JOB_ENV.items())
    return f"""apiVersion: batch/v1
kind: Job
metadata:
  name: loadgen
  namespace: {NAMESPACE}
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: loadgen
          image: python:3.11-slim
          command: ["python", "/app/load_sim.py", "--url", "http://gateway:8080", "--scripts-dir", "/scripts"]
          env:
{env_lines}
          volumeMounts:
            - name: script
              mountPath: /app
            - name: scripts
              mountPath: /scripts
          resources:
            requests: {{ cpu: "200m", memory: "128Mi" }}
            limits: {{ cpu: "1", memory: "256Mi" }}
      volumes:
        - name: script
          configMap:
            name: loadgen-script
        - name: scripts
          configMap:
            name: loadgen-scripts
"""


def main() -> int:
    if not LOAD_SIM_PY.exists():
        print(f"error: {LOAD_SIM_PY} not found", file=sys.stderr)
        return 1
    script_files = sorted(p for p in SCRIPTS_DIR.glob("*.json") if p.stem.split("_")[0] in ("scam", "normal"))
    if not script_files:
        print(f"error: no scam_*/normal_* *.json files under {SCRIPTS_DIR}", file=sys.stderr)
        return 1

    cm_script = configmap_from_files("loadgen-script", {"load_sim.py": LOAD_SIM_PY.read_text(encoding="utf-8")})
    cm_scripts = configmap_from_files("loadgen-scripts", {p.name: p.read_text(encoding="utf-8") for p in script_files})
    job = job_yaml()

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(cm_script + "---\n" + cm_scripts + "---\n" + job, encoding="utf-8")
    print(f"wrote {OUT} ({len(script_files)} call scripts bundled)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
