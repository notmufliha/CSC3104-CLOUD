#!/usr/bin/env bash
# up.sh - brings up the whole ScamGuard demo on minikube, from a laptop with Docker and nothing else
# running. Safe to re-run: every step either is idempotent (minikube start, kubectl apply) or just
# rebuilds an image layer-cached by Docker. See demo/README.md for when to run this (day before / 1 hour
# before) and demo/ARCHITECTURE.md for what it builds.
#
# Usage: ./demo/up.sh   (run from the repo root or from demo/ -- it finds the repo root either way)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

NAMESPACE=scamguard
IMAGE_TAG=demo
ROLLOUT_TIMEOUT=180s

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
warn() { printf '\033[1;33m!! %s\033[0m\n' "$1"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$1"; exit 1; }

# ---------------------------------------------------------------------------------------------- 1. tools
say "Checking required tools"
command -v docker >/dev/null 2>&1 || die "Docker not found. Install Docker Desktop: https://www.docker.com/products/docker-desktop/"
command -v kubectl >/dev/null 2>&1 || die "kubectl not found. Install: https://kubernetes.io/docs/tasks/tools/#kubectl"
command -v minikube >/dev/null 2>&1 || die "minikube not found. Install: https://minikube.sigs.k8s.io/docs/start/"
docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker Desktop and re-run this script."
echo "docker, kubectl, minikube all found."

# ---------------------------------------------------------------------------------------- 2. size the host
say "Checking host size (CPUs / memory Docker can see)"
HOST_CPUS=$(docker info --format '{{.NCPU}}' 2>/dev/null || echo 4)
HOST_MEM_BYTES=$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 8589934592)
HOST_MEM_GB=$(( HOST_MEM_BYTES / 1024 / 1024 / 1024 ))
echo "Docker reports ${HOST_CPUS} CPUs, ${HOST_MEM_GB} GiB memory."

DEMO_LITE=0
if [ "$HOST_CPUS" -lt 6 ] || [ "$HOST_MEM_GB" -lt 10 ]; then
  warn "Host looks small (< 6 CPUs or < 10 GiB). Switching to the 'lite' profile: HPA max 2, BERT max 1, 1 aggregator replica, fewer load-test users."
  DEMO_LITE=1
  MK_CPUS=3
  MK_MEM=5000mb
else
  MK_CPUS=6
  MK_MEM=10000mb
fi
export DEMO_LITE

if [ "$HOST_MEM_GB" -lt 8 ]; then
  warn "Under 8 GiB total is tight for Kubernetes + 9 images. On Windows/WSL2, raise the limit in %UserProfile%\\.wslconfig:"
  warn "    [wsl2]"
  warn "    memory=8GB"
  warn "    processors=4"
  warn "then run 'wsl --shutdown' and restart Docker Desktop. On Mac, raise it in Docker Desktop > Settings > Resources."
fi

# ---------------------------------------------------------------------------------------- 3. minikube
say "Starting minikube (docker driver -- no VM/hypervisor needed, runs the k8s node as a container)"
if minikube status >/dev/null 2>&1; then
  echo "minikube is already running. (Resource flags below only take effect on a fresh cluster --"
  echo " run 'minikube delete' first if you need to change --cpus/--memory.)"
fi
if ! minikube start --driver=docker --cpus="$MK_CPUS" --memory="$MK_MEM" --disk-size=30g; then
  die "minikube start failed. If it mentions memory, raise Docker Desktop's memory limit (Settings > Resources) and retry."
fi
minikube addons enable metrics-server >/dev/null
# Workstream E: ingress-nginx, the one stable entry point for the phone (demo/STABLE_ADDRESS.md).
minikube addons enable ingress >/dev/null \
  || die "minikube addons enable ingress failed. Try 'minikube addons enable ingress' by hand to see why."

# ---------------------------------------------------------------------------------- 4. build images
# Built on the HOST's Docker, then copied into minikube with `minikube image load`. (An earlier version
# of this script used `eval $(minikube docker-env)` to build directly inside minikube's own Docker, which
# is what minikube's own docs suggest -- but it FAILED in testing on Windows with minikube's default
# containerd runtime: "SSH_AGENT_START: starting an SSH agent on Windows is not yet supported". `minikube
# image load` works regardless of driver/runtime and was verified end-to-end, so it's what this script
# uses. It is slightly slower (one extra copy per image) but far more reliable.)
say "Building all 11 images on the host Docker, then loading them into minikube"

build() {
  local name="$1" dockerfile="$2"
  echo "--- building scam/${name}:${IMAGE_TAG} ---"
  docker build -t "scam/${name}:${IMAGE_TAG}" -f "backend/${dockerfile}" backend \
    || die "build failed for ${name} -- see the error above. This is almost always a backend/ source problem, not a demo/ problem."
  echo "--- loading scam/${name}:${IMAGE_TAG} into minikube ---"
  minikube image load "scam/${name}:${IMAGE_TAG}" \
    || die "minikube image load failed for ${name}. Is 'minikube status' Running?"
}

build gateway            gateway/Dockerfile
build aggregator         aggregator/Dockerfile
build urgency_en         workers/urgency_en/Dockerfile
build money_en           workers/money_en/Dockerfile
build impersonation_en   workers/impersonation_en/Dockerfile
build urgency_zh         workers/urgency_zh/Dockerfile
build money_zh           workers/money_zh/Dockerfile
build impersonation_zh   workers/impersonation_zh/Dockerfile
# Workstream H: review queue + model registry, and the retraining Job (needs data/, so the repo root context).
build review             review/Dockerfile
echo "--- building scam/retrain:${IMAGE_TAG} ---"
docker build -t "scam/retrain:${IMAGE_TAG}" -f backend/retrain/Dockerfile . \
  || die "build failed for retrain -- see the error above."
minikube image load "scam/retrain:${IMAGE_TAG}" \
  || die "minikube image load failed for retrain. Is 'minikube status' Running?"
say "Building bert (downloads the real model from Hugging Face at build time -- needs internet)"
if ! docker build -t "scam/bert:${IMAGE_TAG}" -f backend/workers/bert/Dockerfile backend; then
  warn "BERT build failed -- likely the model download (network blocked, or Hugging Face unreachable)."
  warn "Options: (1) check your internet connection and retry; (2) copy model.onnx, tokenizer.json,"
  warn "bert_config.json from the team's Colab notebook into backend/workers/bert/model/ and retry;"
  warn "(3) run 'python tools/bert_dummy_model.py' for a FAKE wiring-only model -- never demo its scores as real."
  die "bert image not built. The rest of the stack will come up, but AGG_BERT cascade will be off until this is fixed."
fi
echo "--- loading scam/bert:${IMAGE_TAG} into minikube ---"
minikube image load "scam/bert:${IMAGE_TAG}" || die "minikube image load failed for bert. Is 'minikube status' Running?"

# -------------------------------------------------------------------------------- 5. generate manifests
say "Generating Envoy config + Kubernetes manifests"
python3 demo/make_envoy_config.py
python3 demo/make_envoy_config.py --docker
python3 demo/make_loadgen.py
python3 demo/make_manifests.py

# ------------------------------------------------------------------------- 6. ingress + HTTPS (Workstream E)
# Before `kubectl apply`: the ingress addon has an admission webhook that rejects every Ingress until the
# controller is up, so 08-ingress.yaml would fail if applied too early.
say "Ingress controller + HTTPS certificate (demo/tls/, reused if it already exists)"
kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout="$ROLLOUT_TIMEOUT" \
  || die "ingress-nginx controller did not start. Check: kubectl get pods -n ingress-nginx"
python3 demo/make_tls.py --apply || die "make_tls.py failed -- see the error above."
# Workstream H: the review queue's encryption key (made once, kept across re-runs; never in git).
python3 demo/tools/learning.py ensure-key || die "could not create the review-key Secret -- see the error above."
# A phone that connects by IP address sends no hostname (SNI), so nginx would show its own fake certificate.
# Make gateway-tls nginx's default certificate. Re-checked on every run: `minikube start` re-applies the
# addon and drops the extra argument.
CTL_ARGS=$(kubectl get deployment ingress-nginx-controller -n ingress-nginx -o jsonpath='{.spec.template.spec.containers[0].args}')
if [[ "$CTL_ARGS" != *default-ssl-certificate* ]]; then
  kubectl patch deployment ingress-nginx-controller -n ingress-nginx --type=json \
    -p "[{\"op\":\"add\",\"path\":\"/spec/template/spec/containers/0/args/-\",\"value\":\"--default-ssl-certificate=${NAMESPACE}/gateway-tls\"}]"
  kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout="$ROLLOUT_TIMEOUT"
fi

# ------------------------------------------------------------------------------------------ 7. apply
say "Applying manifests to the '${NAMESPACE}' namespace"
# Workstream H: this CronJob was renamed retrain-models when it grew to cover all six workers.
kubectl delete cronjob retrain-urgency-en -n "$NAMESPACE" --ignore-not-found
kubectl apply -f demo/k8s/

say "Waiting for every Deployment to roll out (timeout ${ROLLOUT_TIMEOUT} each)"
DEPLOYMENTS=$(kubectl get deployments -n "$NAMESPACE" -o jsonpath='{.items[*].metadata.name}')
FAILED=0
for d in $DEPLOYMENTS; do
  echo "--- $d ---"
  if ! kubectl rollout status "deployment/$d" -n "$NAMESPACE" --timeout="$ROLLOUT_TIMEOUT"; then
    FAILED=1
    warn "$d did not roll out in time. Check: kubectl describe pod -n $NAMESPACE -l app=$d"
    warn "Common causes: Pending (not enough CPU/memory -- see demo/README.md troubleshooting table),"
    warn "ImagePullBackOff (image wasn't built inside minikube's docker-env), CrashLoopBackOff (check logs)."
  fi
done

if [ "$FAILED" -ne 0 ]; then
  warn "One or more deployments did not come up cleanly. The commands above will tell you why."
fi

say "Done"
kubectl get pods,hpa -n "$NAMESPACE"
echo
echo "Next steps:"
echo "  1. Expose the Ingress (HTTPS):  ./demo/expose.sh     (prints the address to save in the app)"
echo "     Plain-HTTP fallback:        ./demo/port-forward.sh"
echo "  2. In Android Studio, run the app; Settings -> 'Emulator (HTTPS)' preset -> Test connection."
echo "     First time only: install demo/tls/ca.crt on the emulator/phone (demo/STABLE_ADDRESS.md)."
echo "  3. Reference run:              python demo/tools/demo_flow.py"
echo "  4. Load test + spread:         python demo/tools/load_sim.py   then   ./demo/spread.sh"
echo "  5. Live view for the stage:    ./demo/watch.sh"
exit $FAILED
