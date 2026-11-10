# up.ps1 - Windows PowerShell version of up.sh. Brings up the whole ScamGuard demo on minikube.
# Safe to re-run. See demo/README.md for when to run this and demo/ARCHITECTURE.md for what it builds.
#
# Usage: .\demo\up.ps1   (run from the repo root or from demo\ -- it finds the repo root either way)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
Set-Location $RepoRoot

$Namespace = "scamguard"
$ImageTag = "demo"
$RolloutTimeout = "180s"

function Say($msg)  { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Warn($msg) { Write-Host "!! $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------------------------------------- 1. tools
Say "Checking required tools"
if (-not (Get-Command docker -ErrorAction SilentlyContinue))   { Die "Docker not found. Install Docker Desktop: https://www.docker.com/products/docker-desktop/" }
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue))  { Die "kubectl not found. Install: https://kubernetes.io/docs/tasks/tools/#kubectl" }
if (-not (Get-Command minikube -ErrorAction SilentlyContinue)) { Die "minikube not found. Install: https://minikube.sigs.k8s.io/docs/start/" }
# Not `try { docker info *> $null } catch {...}`: with $ErrorActionPreference = "Stop" (set above),
# redirecting a native command's stderr in PowerShell 5.1 turns ANY stderr line -- even a harmless
# WARNING docker info prints on some setups -- into a terminating NativeCommandError, falsely reporting
# Docker as not running even though it exited 0. Check $LASTEXITCODE instead, with ErrorActionPreference
# relaxed just for this one call. (Found for real while testing this script.)
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
docker info *> $null
$dockerOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEAP
if (-not $dockerOk) { Die "Docker is installed but not running. Start Docker Desktop and re-run this script." }
Write-Host "docker, kubectl, minikube all found."

# ---------------------------------------------------------------------------------------- 2. size the host
Say "Checking host size (CPUs / memory Docker can see)"
try { $hostCpus = [int](docker info --format '{{.NCPU}}') } catch { $hostCpus = 4 }
try { $hostMemBytes = [int64](docker info --format '{{.MemTotal}}') } catch { $hostMemBytes = 8589934592 }
$hostMemGb = [int]($hostMemBytes / 1GB)
Write-Host "Docker reports $hostCpus CPUs, $hostMemGb GiB memory."

$env:DEMO_LITE = "0"
if ($hostCpus -lt 6 -or $hostMemGb -lt 10) {
    Warn "Host looks small (< 6 CPUs or < 10 GiB). Switching to the 'lite' profile: HPA max 2, BERT max 1, 1 aggregator replica, fewer load-test users."
    $env:DEMO_LITE = "1"
    $mkCpus = 3; $mkMem = "5000mb"
} else {
    $mkCpus = 6; $mkMem = "10000mb"
}

if ($hostMemGb -lt 8) {
    Warn "Under 8 GiB total is tight for Kubernetes + 9 images. Raise the limit in %UserProfile%\.wslconfig:"
    Warn "    [wsl2]"
    Warn "    memory=8GB"
    Warn "    processors=4"
    Warn "then run 'wsl --shutdown' and restart Docker Desktop (Settings > Resources also has a memory slider)."
}

# ---------------------------------------------------------------------------------------- 3. minikube
Say "Starting minikube (docker driver -- no Hyper-V/VM needed, runs the k8s node as a container)"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "SilentlyContinue"
minikube status *> $null
$already = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = $prevEAP
if ($already) {
    Write-Host "minikube is already running. (Resource flags below only take effect on a fresh cluster --"
    Write-Host " run 'minikube delete' first if you need to change --cpus/--memory.)"
}
minikube start --driver=docker --cpus=$mkCpus --memory=$mkMem --disk-size=30g
if ($LASTEXITCODE -ne 0) { Die "minikube start failed. If it mentions memory, raise Docker Desktop's memory limit (Settings > Resources) and retry." }
minikube addons enable metrics-server | Out-Null
# Workstream E: ingress-nginx, the one stable entry point for the phone (demo/STABLE_ADDRESS.md).
minikube addons enable ingress | Out-Null
if ($LASTEXITCODE -ne 0) { Die "minikube addons enable ingress failed. Try 'minikube addons enable ingress' by hand to see why." }

# ---------------------------------------------------------------------------------- 4. build images
# Built on the HOST's Docker, then copied into minikube with `minikube image load`. (An earlier version
# of this script used `minikube docker-env`, which is what minikube's own docs suggest -- but it FAILED
# in testing on Windows with minikube's default containerd runtime: "SSH_AGENT_START: starting an SSH
# agent on Windows is not yet supported". `minikube image load` works regardless of driver/runtime and
# was verified end-to-end, so it's what this script uses -- slightly slower, far more reliable.)
Say "Building all 11 images on the host Docker, then loading them into minikube"

function Build-Image($name, $dockerfile) {
    Write-Host "--- building scam/${name}:${ImageTag} ---"
    docker build -t "scam/${name}:${ImageTag}" -f "backend/$dockerfile" backend
    if ($LASTEXITCODE -ne 0) { Die "build failed for $name -- see the error above. This is almost always a backend/ source problem, not a demo/ problem." }
    Write-Host "--- loading scam/${name}:${ImageTag} into minikube ---"
    minikube image load "scam/${name}:${ImageTag}"
    if ($LASTEXITCODE -ne 0) { Die "minikube image load failed for $name. Is 'minikube status' Running?" }
}

Build-Image gateway            "gateway/Dockerfile"
Build-Image aggregator         "aggregator/Dockerfile"
Build-Image urgency_en         "workers/urgency_en/Dockerfile"
Build-Image money_en           "workers/money_en/Dockerfile"
Build-Image impersonation_en   "workers/impersonation_en/Dockerfile"
Build-Image urgency_zh         "workers/urgency_zh/Dockerfile"
Build-Image money_zh           "workers/money_zh/Dockerfile"
Build-Image impersonation_zh   "workers/impersonation_zh/Dockerfile"
# Workstream H: review queue + model registry, and the retraining Job (needs data/, so the repo root context).
Build-Image review             "review/Dockerfile"
Write-Host "--- building scam/retrain:${ImageTag} ---"
docker build -t "scam/retrain:${ImageTag}" -f backend/retrain/Dockerfile .
if ($LASTEXITCODE -ne 0) { Die "build failed for retrain -- see the error above." }
minikube image load "scam/retrain:${ImageTag}"
if ($LASTEXITCODE -ne 0) { Die "minikube image load failed for retrain. Is 'minikube status' Running?" }

Say "Building bert (downloads the real model from Hugging Face at build time -- needs internet)"
docker build -t "scam/bert:${ImageTag}" -f backend/workers/bert/Dockerfile backend
if ($LASTEXITCODE -ne 0) {
    Warn "BERT build failed -- likely the model download (network blocked, or Hugging Face unreachable)."
    Warn "Options: (1) check your internet connection and retry; (2) copy model.onnx, tokenizer.json,"
    Warn "bert_config.json from the team's Colab notebook into backend\workers\bert\model\ and retry;"
    Warn "(3) run 'python tools\bert_dummy_model.py' for a FAKE wiring-only model -- never demo its scores as real."
    Die "bert image not built. The rest of the stack will come up, but AGG_BERT cascade will be off until this is fixed."
}
Write-Host "--- loading scam/bert:${ImageTag} into minikube ---"
minikube image load "scam/bert:${ImageTag}"
if ($LASTEXITCODE -ne 0) { Die "minikube image load failed for bert. Is 'minikube status' Running?" }

# -------------------------------------------------------------------------------- 5. generate manifests
Say "Generating Envoy config + Kubernetes manifests"
python demo/make_envoy_config.py
python demo/make_envoy_config.py --docker
python demo/make_loadgen.py
python demo/make_manifests.py

# ------------------------------------------------------------------------- 6. ingress + HTTPS (Workstream E)
# Before `kubectl apply`: the ingress addon has an admission webhook that rejects every Ingress until the
# controller is up, so 08-ingress.yaml would fail if applied too early.
Say "Ingress controller + HTTPS certificate (demo/tls/, reused if it already exists)"
kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout=$RolloutTimeout
if ($LASTEXITCODE -ne 0) { Die "ingress-nginx controller did not start. Check: kubectl get pods -n ingress-nginx" }
python demo/make_tls.py --apply
if ($LASTEXITCODE -ne 0) { Die "make_tls.py failed -- see the error above." }
# Workstream H: the review queue's encryption key (made once, kept across re-runs; never in git).
python demo/tools/learning.py ensure-key
if ($LASTEXITCODE -ne 0) { Die "could not create the review-key Secret -- see the error above." }
# A phone that connects by IP address sends no hostname (SNI), so nginx would show its own fake certificate.
# Make gateway-tls nginx's default certificate. Re-checked on every run: `minikube start` re-applies the
# addon and drops the extra argument.
$ctlArgs = kubectl get deployment ingress-nginx-controller -n ingress-nginx -o jsonpath='{.spec.template.spec.containers[0].args}'
if ($ctlArgs -notmatch 'default-ssl-certificate') {
    $patch = '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--default-ssl-certificate=' + $Namespace + '/gateway-tls"}]'
    $patchFile = Join-Path $env:TEMP "scamguard-ingress-patch.json"
    [IO.File]::WriteAllText($patchFile, $patch)   # a file, not an argument: PowerShell 5.1 mangles quotes passed to kubectl
    kubectl patch deployment ingress-nginx-controller -n ingress-nginx --type=json --patch-file $patchFile
    Remove-Item $patchFile
    kubectl rollout status deployment/ingress-nginx-controller -n ingress-nginx --timeout=$RolloutTimeout
}

# ------------------------------------------------------------------------------------------ 7. apply
Say "Applying manifests to the '$Namespace' namespace"
# Workstream H: this CronJob was renamed retrain-models when it grew to cover all six workers.
kubectl delete cronjob retrain-urgency-en -n $Namespace --ignore-not-found
kubectl apply -f demo/k8s/

Say "Waiting for every Deployment to roll out (timeout $RolloutTimeout each)"
$deployments = (kubectl get deployments -n $Namespace -o jsonpath='{.items[*].metadata.name}') -split ' '
$failed = $false
foreach ($d in $deployments) {
    if ([string]::IsNullOrWhiteSpace($d)) { continue }
    Write-Host "--- $d ---"
    kubectl rollout status "deployment/$d" -n $Namespace --timeout=$RolloutTimeout
    if ($LASTEXITCODE -ne 0) {
        $failed = $true
        Warn "$d did not roll out in time. Check: kubectl describe pod -n $Namespace -l app=$d"
        Warn "Common causes: Pending (not enough CPU/memory), ImagePullBackOff (image wasn't built inside minikube's docker-env), CrashLoopBackOff (check logs)."
    }
}

if ($failed) { Warn "One or more deployments did not come up cleanly. The commands above will tell you why." }

Say "Done"
kubectl get pods,hpa -n $Namespace
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Expose the Ingress (HTTPS):  .\demo\expose.ps1     (prints the address to save in the app)"
Write-Host "     Plain-HTTP fallback:        .\demo\port-forward.ps1"
Write-Host "  2. In Android Studio, run the app; Settings -> 'Emulator (HTTPS)' preset -> Test connection."
Write-Host "     First time only: install demo\tls\ca.crt on the emulator/phone (demo\STABLE_ADDRESS.md)."
Write-Host "  3. Reference run:              python demo\tools\demo_flow.py"
Write-Host "  4. Load test + spread:         python demo\tools\load_sim.py   then   .\demo\spread.ps1"
Write-Host "  5. Live view for the stage:    .\demo\watch.ps1"
if ($failed) { exit 1 }
