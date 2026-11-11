#!/usr/bin/env python3
"""make_manifests.py - generates every Kubernetes manifest for the ScamGuard demo into demo/k8s/*.yaml.

Why a generator instead of hand-written YAML: every tunable (namespace, image tag, replica counts, HPA
bounds, resource requests/limits, every AGG_* value) sits in ONE settings block below with a plain-English
comment, instead of being scattered across a dozen files. Change a number here, re-run this script, and
`kubectl apply -f demo/k8s/` picks it up.

Usage:
    python demo/make_manifests.py            # writes demo/k8s/*.yaml (overwrites previous output)

Env var names below are copied verbatim from docker-compose.yml / README.md -- see DEMO_TASK.md section 1.
The gateway's worker/aggregator addresses and the aggregator's BERT_ADDR point at the Envoy Service
(envoy-lb) and its per-upstream ports, not at the real Services directly -- see demo/make_envoy_config.py
and demo/ARCHITECTURE.md for why (gRPC connection pinning defeats plain Kubernetes load balancing).
"""
import os
import sys
from pathlib import Path

# Set by up.sh/up.ps1 when the host looks small (< 6 CPUs or < 10 GB docker memory, see demo/README.md
# "laptop size"); swaps HPA_MAX/BERT_HPA_MAX/AGGREGATOR_REPLICAS for the LITE_* values further down.
DEMO_LITE = os.environ.get("DEMO_LITE", "").strip() in ("1", "true", "yes")

# ====================================================================================================
# SETTINGS -- the only block you should need to edit.
# ====================================================================================================

NAMESPACE = "scamguard"          # every demo resource lives here; `demo/down.sh` deletes this namespace
IMAGE_TAG = "demo"               # all 8 images are built locally as scam/<service>:<this tag>

# Horizontal Pod Autoscaler bounds used by every worker + bert (laptop-friendly: a real deployment would
# raise HPA_MAX). impersonation-en gets its own higher minimum below (it is the busiest worker: every call's
# every caller line needs it, and it is the one DEMO_TASK.md calls out as "the busiest").
HPA_MIN = 1
HPA_MAX = 4
HPA_CPU_PERCENT = 50
IMPERSONATION_EN_MIN_REPLICAS = 2   # overrides HPA_MIN for this one worker

# How fast the HPA scales back down after load stops. Kept short on purpose so the demo doesn't need to
# stand around for 5 minutes (the Kubernetes default) waiting for replicas to disappear on stage.
SCALE_DOWN_SECONDS = 30

AGGREGATOR_REPLICAS = 2   # safe: aggregator state lives in Redis behind a Lua version-checked optimistic
                          # save (backend/aggregator/src/store.rs) -- two copies updating the same call
                          # retry instead of corrupting it. See ARCHITECTURE.md for the reasoning.
GATEWAY_REPLICAS = 1      # MUST stay 1: the gateway keeps "seq already seen" + each call's last answer in
                          # its own process RAM (CALL_TTL_SECS/MAX_CALLS, backend/gateway/src/calls.rs).
                          # A second replica would not share that memory and duplicate/resend handling
                          # would silently break. (DEMO_TASK.md section 2.)
INGRESS_HOST = "scamguard.test"      # must match HOSTNAME in demo/make_tls.py
INGRESS_TLS_SECRET = "gateway-tls"   # made by `python demo/make_tls.py --apply` (up.sh/up.ps1 run it)
ENVOY_REPLICAS = 1
REDIS_REPLICAS = 1
BERT_HPA_MIN = 2          # two, so losing a pod doesn't leave the cascade without BERT (C.9)
BERT_HPA_MAX = 4          # BERT is only called on the cascade path (unsure/alarmed lines and spot checks), so
                          # it needs fewer replicas than the always-called workers.

# ---- Resource requests/limits -----------------------------------------------------------------------
# EN/ZH worker numbers are the DEMO_TASK.md section 4.4 estimates, themselves measured on the OLD code
# (EN ~100-115 MiB RSS, ZH ~185-195 MiB RSS incl. jieba). NOT re-measured against the new images on a real
# cluster in this session (no Kubernetes cluster was available here -- see demo/EXPECTED.md). Re-check with
# `kubectl top pods -n scamguard` once Shreya runs this for real and tighten these numbers.
WORKER_EN_REQUESTS = {"cpu": "100m", "memory": "128Mi"}
WORKER_EN_LIMITS = {"cpu": "500m", "memory": "384Mi"}
WORKER_ZH_REQUESTS = {"cpu": "100m", "memory": "224Mi"}
WORKER_ZH_LIMITS = {"cpu": "500m", "memory": "448Mi"}

# BERT: k8s/bert.yaml's existing numbers (int8 DistilBERT, ~400-600 MB RAM per backend/workers/bert/README.md).
# Not re-measured here either (no cluster). If a laptop is small, up.sh/up.ps1 switch to "lite" bounds
# (see LITE_* below) instead of editing this block.
BERT_REQUESTS = {"cpu": "500m", "memory": "512Mi"}
BERT_LIMITS = {"cpu": "2", "memory": "1Gi"}

# aggregator/gateway/redis/envoy: small stateless Rust/Redis/proxy processes. These are reasonable
# defaults, NOT measured on a running cluster in this session -- treat as "needs verification" until
# `kubectl top` confirms them (DEMO_TASK.md honesty rule 5).
AGGREGATOR_REQUESTS = {"cpu": "100m", "memory": "128Mi"}
AGGREGATOR_LIMITS = {"cpu": "500m", "memory": "256Mi"}
GATEWAY_REQUESTS = {"cpu": "100m", "memory": "128Mi"}
GATEWAY_LIMITS = {"cpu": "500m", "memory": "256Mi"}
REDIS_REQUESTS = {"cpu": "50m", "memory": "32Mi"}
REDIS_LIMITS = {"cpu": "250m", "memory": "128Mi"}
ENVOY_REQUESTS = {"cpu": "100m", "memory": "64Mi"}
ENVOY_LIMITS = {"cpu": "500m", "memory": "256Mi"}

# "Lite" bounds up.sh/up.ps1 switch to automatically on a small host (< 6 CPU or < 10 GB docker memory).
LITE_HPA_MAX = 2
LITE_BERT_HPA_MIN = 1
LITE_BERT_HPA_MAX = 1
LITE_AGGREGATOR_REPLICAS = 1

if DEMO_LITE:
    HPA_MAX = LITE_HPA_MAX
    BERT_HPA_MIN = LITE_BERT_HPA_MIN
    BERT_HPA_MAX = LITE_BERT_HPA_MAX
    AGGREGATOR_REPLICAS = LITE_AGGREGATOR_REPLICAS
    print("DEMO_LITE=1: using lite bounds (HPA_MAX=2, BERT_HPA_MAX=1, AGGREGATOR_REPLICAS=1)")

# ---- Aggregator tunables (AGG_*), copied verbatim from docker-compose.yml's defaults ------------------
AGG_ENV = {
    "AGG_W_URGENCY": "0.3333",
    "AGG_W_MONEY": "0.3333",
    "AGG_W_IMPERSONATION": "0.3333",
    "AGG_SCAM_THRESHOLD": "0.6",
    "AGG_UNSURE_THRESHOLD": "0.45",
    "AGG_BERT_SAFE_THRESHOLD": "0.3",
    "AGG_BERT_SCAM_THRESHOLD": "0.6",
    "AGG_FALLBACK_STRONG_SIGNAL_THRESHOLD": "0.8",
    "AGG_RED_DISAGREEMENTS_TO_CLEAR": "2",
    "AGG_WINDOW_LINES": "6",
    "AGG_WARMUP_SECS": "15",
    "AGG_WARMUP_OVERRIDE": "0.9",
    "AGG_SCAM_STREAK": "2",
    "AGG_MONEY_RULE": "0.7",
    "AGG_MONEY_RULE_OTHER": "0.5",
    "AGG_REASON_THRESHOLD": "0.5",
    "AGG_MIN_QUORUM": "2",
    "AGG_CALL_TTL_SECS": "3600",
    # Workstream B: when a call ends (see docker-compose.yml)
    "AGG_SILENCE_SECS": "30",
    "AGG_SILENCE_CHECK_SECS": "5",
    "AGG_END_REVIEW_TIMEOUT_MS": "1500",
    "AGG_END_TOMBSTONE_SECS": "3600",
    "BERT_TIMEOUT_MS": "300",
    "AGG_BERT_WEIGHT": "0.5",
    "AGG_BERT_VETO": "0",
    "AGG_BERT_SPOT_EVERY": "5",
}

# Gateway timing (docker-compose.yml): 2 s promise to the phone = worker budget + aggregator budget.
GATEWAY_ENV = {
    "WORKER_TIMEOUT_MS": "1400",
    "AGGREGATOR_TIMEOUT_MS": "550",
    "BERT_CONTEXT_LINES": "5",
    # Workstream B: POST /v1/call/end's wait for the aggregator (must match make_envoy_config.py's END_CALL_TIMEOUT_MS).
    "END_CALL_TIMEOUT_MS": "2000",
    # Workstream H: where POST /v1/report goes (the internal review service).
    "REVIEW_URL": "http://review:8090",
    # Workstream G.3: POST /v1/verify's wait for BERT (the app's Hybrid mode; the phone gives up after 1.5 s).
    # BERT_ADDR is set in gateway_yaml: through Envoy, so its 300 ms BERT route limit applies here too.
    "VERIFY_TIMEOUT_MS": "1200",
}

# ---- Workstream H: learning loop (review queue, retraining, monitoring) -----------------------------
REVIEW_RETENTION_DAYS = "30"        # reported calls are deleted this long after the report
REVIEW_STORAGE = "1Gi"              # queue database + released model files, all six workers
REVIEW_REQUESTS = {"cpu": "50m", "memory": "96Mi"}
REVIEW_LIMITS = {"cpu": "500m", "memory": "256Mi"}
RETRAIN_SCHEDULE = "0 3 * * 0"      # Sundays 03:00; or run now: python demo/tools/learning.py retrain --force
RETRAIN_MIN_NEW_LABELS = "50"       # per language: skip it unless this many new labelled lines are waiting
RETRAIN_TOLERANCE = "0.005"         # release check: allowed drop per gold-set metric (rounding-level noise)
# Measured 2026-11-06, one after the other in this limit: urgency_en ~80 s, money_en ~10 s, impersonation_en ~310 s,
# urgency_zh ~100 s, money_zh ~90 s, impersonation_zh ~30 s.
RETRAIN_REQUESTS = {"cpu": "500m", "memory": "768Mi"}
RETRAIN_LIMITS = {"cpu": "2", "memory": "1536Mi"}
# Workers that load their model version from the registry (MODEL_VERSION, set by learning.py rollout).
# MODEL_VERSION itself is deliberately NOT written here: `kubectl apply` keeps an env var added by
# `kubectl set env`, so re-running up.sh/up.ps1 does not silently roll a released version back to v4.
REGISTRY_WORKERS = {"urgency-en", "money-en", "impersonation-en", "urgency-zh", "money-zh", "impersonation-zh"}
# Drift alert (H.2): compare the last DRIFT_SHORT with the DRIFT_BASELINE before it. The planning doc's
# "well above its usual level for a day" = 1h vs 1d. For a live demo, regenerate with e.g. 2m / 30m.
DRIFT_SHORT = os.environ.get("DRIFT_SHORT", "1h")
DRIFT_BASELINE = os.environ.get("DRIFT_BASELINE", "1d")
DRIFT_FACTOR = "1.5"                # "well above" = 1.5x the baseline ...
DRIFT_MIN_UNSURE_SHARE = "0.15"     # ... and at least 15% of lines UNSURE (ignores tiny baselines)
PROMETHEUS_IMAGE = "prom/prometheus:v3.1.0"
GRAFANA_IMAGE = "grafana/grafana:11.4.0"

# The 6 workers: (signal, language) -> matches docker-compose service names signal_language.
WORKERS = [
    ("urgency", "en"), ("money", "en"), ("impersonation", "en"),
    ("urgency", "zh"), ("money", "zh"), ("impersonation", "zh"),
]

# Envoy's per-upstream listener ports (must match make_envoy_config.py's WORKERS/AGGREGATOR_PORT/BERT_PORT).
ENVOY_WORKER_PORTS = {
    "urgency_en": 50061, "money_en": 50062, "impersonation_en": 50063,
    "urgency_zh": 50064, "money_zh": 50065, "impersonation_zh": 50066,
}
ENVOY_AGGREGATOR_PORT = 50067
ENVOY_BERT_PORT = 50068
ENVOY_ADMIN_PORT = 9901

OUT_DIR = Path(__file__).resolve().parent / "k8s"


# ====================================================================================================
# YAML builders (plain string templates -- no YAML library needed/added as a new dependency).
# ====================================================================================================

def resources_block(requests: dict, limits: dict, indent: str) -> str:
    r = "\n".join(f"{indent}    {k}: \"{v}\"" for k, v in requests.items())
    l = "\n".join(f"{indent}    {k}: \"{v}\"" for k, v in limits.items())
    return f"{indent}resources:\n{indent}  requests:\n{r}\n{indent}  limits:\n{l}\n"


def env_block(env: dict, indent: str) -> str:
    if not env:
        return ""
    lines = []
    for k, v in env.items():
        lines.append(f"{indent}- name: {k}\n{indent}  value: \"{v}\"")
    return "\n".join(lines) + "\n"


def namespace_yaml() -> str:
    return f"""apiVersion: v1
kind: Namespace
metadata:
  name: {NAMESPACE}
"""


def redis_yaml() -> str:
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: {NAMESPACE}
  labels: {{ app: redis }}
spec:
  replicas: {REDIS_REPLICAS}
  selector:
    matchLabels: {{ app: redis }}
  template:
    metadata:
      labels: {{ app: redis }}
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports:
            - containerPort: 6379
              name: redis
{resources_block(REDIS_REQUESTS, REDIS_LIMITS, "          ")}          readinessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 2
            periodSeconds: 5
          livenessProbe:
            exec:
              command: ["redis-cli", "ping"]
            initialDelaySeconds: 5
            periodSeconds: 10
# No persistence (no volume): call memory is temporary (TTL'd, see AGG_CALL_TTL_SECS) and fine to lose if
# this pod restarts -- that is in fact one of the things demo/chaos.sh demonstrates.
---
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: {NAMESPACE}
spec:
  selector: {{ app: redis }}
  ports:
    - name: redis
      port: 6379
      targetPort: 6379
"""


def envoy_configmap_yaml() -> str:
    cfg_path = OUT_DIR / "_generated" / "envoy-config.yaml"
    cfg_text = cfg_path.read_text(encoding="utf-8")
    indented = "\n".join("    " + line if line else "" for line in cfg_text.splitlines())
    return f"""apiVersion: v1
kind: ConfigMap
metadata:
  name: envoy-config
  namespace: {NAMESPACE}
data:
  envoy.yaml: |
{indented}
"""


def envoy_yaml() -> str:
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: envoy-lb
  namespace: {NAMESPACE}
  labels: {{ app: envoy-lb }}
spec:
  replicas: {ENVOY_REPLICAS}
  selector:
    matchLabels: {{ app: envoy-lb }}
  template:
    metadata:
      labels: {{ app: envoy-lb }}
    spec:
      containers:
        - name: envoy
          image: envoyproxy/envoy:v1.31-latest
          args: ["-c", "/etc/envoy/envoy.yaml"]
          ports:
            - containerPort: {ENVOY_ADMIN_PORT}
              name: admin
            - containerPort: 50061
              name: p-urgency-en
            - containerPort: 50062
              name: p-money-en
            - containerPort: 50063
              name: p-imperson-en
            - containerPort: 50064
              name: p-urgency-zh
            - containerPort: 50065
              name: p-money-zh
            - containerPort: 50066
              name: p-imperson-zh
            - containerPort: {ENVOY_AGGREGATOR_PORT}
              name: p-aggregator
            - containerPort: {ENVOY_BERT_PORT}
              name: p-bert
{resources_block(ENVOY_REQUESTS, ENVOY_LIMITS, "          ")}          readinessProbe:
            httpGet: {{ path: /ready, port: {ENVOY_ADMIN_PORT} }}
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            httpGet: {{ path: /ready, port: {ENVOY_ADMIN_PORT} }}
            initialDelaySeconds: 5
            periodSeconds: 10
          volumeMounts:
            - name: config
              mountPath: /etc/envoy
      volumes:
        - name: config
          configMap:
            name: envoy-config
---
apiVersion: v1
kind: Service
metadata:
  name: envoy-lb
  namespace: {NAMESPACE}
spec:
  selector: {{ app: envoy-lb }}
  ports:
    - name: admin
      port: {ENVOY_ADMIN_PORT}
      targetPort: {ENVOY_ADMIN_PORT}
    - name: urgency-en
      port: 50061
      targetPort: 50061
    - name: money-en
      port: 50062
      targetPort: 50062
    - name: impersonation-en
      port: 50063
      targetPort: 50063
    - name: urgency-zh
      port: 50064
      targetPort: 50064
    - name: money-zh
      port: 50065
      targetPort: 50065
    - name: impersonation-zh
      port: 50066
      targetPort: 50066
    - name: aggregator
      port: {ENVOY_AGGREGATOR_PORT}
      targetPort: {ENVOY_AGGREGATOR_PORT}
    - name: bert
      port: {ENVOY_BERT_PORT}
      targetPort: {ENVOY_BERT_PORT}
"""


def aggregator_yaml() -> str:
    env = {
        "AGGREGATOR_GRPC_ADDR": "0.0.0.0:50052",
        "AGGREGATOR_HTTP_ADDR": "0.0.0.0:8082",
        "REDIS_URL": "redis://redis:6379",
        **AGG_ENV,
        "BERT_ADDR": f"envoy-lb:{ENVOY_BERT_PORT}",
    }
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: aggregator
  namespace: {NAMESPACE}
  labels: {{ app: aggregator }}
spec:
  replicas: {AGGREGATOR_REPLICAS}
  selector:
    matchLabels: {{ app: aggregator }}
  template:
    metadata:
      labels: {{ app: aggregator }}
    spec:
      containers:
        - name: aggregator
          image: scam/aggregator:{IMAGE_TAG}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 50052
              name: grpc
            - containerPort: 8082
              name: http
          env:
{env_block(env, "            ")}{resources_block(AGGREGATOR_REQUESTS, AGGREGATOR_LIMITS, "          ")}          readinessProbe:
            httpGet: {{ path: /health, port: 8082 }}
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            httpGet: {{ path: /health, port: 8082 }}
            initialDelaySeconds: 10
            periodSeconds: 10
---
# Headless (clusterIP: None) so Envoy's STRICT_DNS cluster resolves every pod IP, not one ClusterIP VIP --
# see demo/make_envoy_config.py's header comment for why this matters for a gRPC server with 2+ replicas.
apiVersion: v1
kind: Service
metadata:
  name: aggregator-hl
  namespace: {NAMESPACE}
spec:
  clusterIP: None
  selector: {{ app: aggregator }}
  ports:
    - name: grpc
      port: 50052
      targetPort: 50052
---
# Plain ClusterIP too, for the HTTP /health endpoint (kubectl port-forward svc/aggregator 8082:8082) and
# anything that wants ordinary round-the-robin-per-connection access instead of going through Envoy.
apiVersion: v1
kind: Service
metadata:
  name: aggregator
  namespace: {NAMESPACE}
spec:
  selector: {{ app: aggregator }}
  ports:
    - name: grpc
      port: 50052
      targetPort: 50052
    - name: http
      port: 8082
      targetPort: 8082
"""


def bert_yaml() -> str:
    env = {"PORT": "50051", "ORT_THREADS": "2", "GRPC_THREADS": "2"}
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: bert
  namespace: {NAMESPACE}
  labels: {{ app: bert }}
spec:
  replicas: {BERT_HPA_MIN}
  selector:
    matchLabels: {{ app: bert }}
  template:
    metadata:
      labels: {{ app: bert }}
    spec:
      containers:
        - name: bert
          image: scam/bert:{IMAGE_TAG}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 50051
              name: grpc
          env:
{env_block(env, "            ")}{resources_block(BERT_REQUESTS, BERT_LIMITS, "          ")}          # Model load takes a few seconds; startupProbe gives it room before readiness/liveness kick in.
          startupProbe:
            grpc: {{ port: 50051 }}
            failureThreshold: 30
            periodSeconds: 2
          readinessProbe:
            grpc: {{ port: 50051 }}
            periodSeconds: 5
          livenessProbe:
            grpc: {{ port: 50051 }}
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: bert-hl
  namespace: {NAMESPACE}
spec:
  clusterIP: None
  selector: {{ app: bert }}
  ports:
    - name: grpc
      port: 50051
      targetPort: 50051
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: bert
  namespace: {NAMESPACE}
spec:
  scaleTargetRef: {{ apiVersion: apps/v1, kind: Deployment, name: bert }}
  minReplicas: {BERT_HPA_MIN}
  maxReplicas: {BERT_HPA_MAX}
  behavior:
    scaleDown:
      stabilizationWindowSeconds: {SCALE_DOWN_SECONDS}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {{ type: Utilization, averageUtilization: {HPA_CPU_PERCENT} }}
"""


def worker_yaml(signal: str, lang: str) -> str:
    name = f"{signal}-{lang}"
    img_name = f"{signal}_{lang}"
    is_zh = lang == "zh"
    requests, limits = (WORKER_ZH_REQUESTS, WORKER_ZH_LIMITS) if is_zh else (WORKER_EN_REQUESTS, WORKER_EN_LIMITS)
    min_replicas = IMPERSONATION_EN_MIN_REPLICAS if name == "impersonation-en" else HPA_MIN
    registry_env = ("""            # H.8: set MODEL_VERSION (learning.py rollout) to load a released version from the registry.
            - name: MODEL_REGISTRY_URL
              value: "http://review:8090"
""" if name in REGISTRY_WORKERS else "")
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: {name}
  namespace: {NAMESPACE}
  labels: {{ app: {name}, signal: {signal}, language: {lang} }}
spec:
  replicas: {min_replicas}
  # H.9 rolling update: start the new pod first, remove an old one only once the new one is ready, so a
  # version change never drops a line (with 1 replica Kubernetes' defaults round to the same thing).
  strategy:
    type: RollingUpdate
    rollingUpdate: {{ maxSurge: 1, maxUnavailable: 0 }}
  selector:
    matchLabels: {{ app: {name} }}
  template:
    metadata:
      labels: {{ app: {name}, signal: {signal}, language: {lang} }}
    spec:
      containers:
        - name: {name}
          image: scam/{img_name}:{IMAGE_TAG}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 50051
              name: grpc
          env:
            - name: PORT
              value: "50051"
{registry_env}          # Keep serving for a few seconds after Kubernetes decides to stop this pod: Envoy refreshes the pod
          # list every 2 s, so without this pause it still sends a line or two to a pod that has stopped
          # (measured: 2 lines without an urgency score during a rolling update; see demo/LEARNING_LOOP.md).
          lifecycle:
            preStop:
              exec: {{ command: ["sleep", "5"] }}
{resources_block(requests, limits, "          ")}          readinessProbe:
            grpc: {{ port: 50051 }}
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            grpc: {{ port: 50051 }}
            initialDelaySeconds: 10
            periodSeconds: 10
---
# Headless: Envoy's cluster for this worker needs every pod IP (see make_envoy_config.py).
apiVersion: v1
kind: Service
metadata:
  name: {name}-hl
  namespace: {NAMESPACE}
  labels: {{ app: {name} }}
spec:
  clusterIP: None
  selector: {{ app: {name} }}
  ports:
    - name: grpc
      port: 50051
      targetPort: 50051
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {name}
  namespace: {NAMESPACE}
spec:
  scaleTargetRef: {{ apiVersion: apps/v1, kind: Deployment, name: {name} }}
  minReplicas: {min_replicas}
  maxReplicas: {HPA_MAX}
  behavior:
    scaleDown:
      stabilizationWindowSeconds: {SCALE_DOWN_SECONDS}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {{ type: Utilization, averageUtilization: {HPA_CPU_PERCENT} }}
"""


def gateway_yaml() -> str:
    env = dict(GATEWAY_ENV)
    env["GATEWAY_BIND_ADDR"] = "0.0.0.0:8080"
    env["AGGREGATOR_ADDR"] = f"envoy-lb:{ENVOY_AGGREGATOR_PORT}"
    env["BERT_ADDR"] = f"envoy-lb:{ENVOY_BERT_PORT}"   # Workstream G.3: POST /v1/verify
    for signal, lang in WORKERS:
        key = f"{signal.upper()}_{lang.upper()}_ADDR"
        env[key] = f"envoy-lb:{ENVOY_WORKER_PORTS[f'{signal}_{lang}']}"
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: gateway
  namespace: {NAMESPACE}
  labels: {{ app: gateway }}
spec:
  replicas: {GATEWAY_REPLICAS}   # MUST be 1 -- see the comment on GATEWAY_REPLICAS at the top of this file.
  selector:
    matchLabels: {{ app: gateway }}
  template:
    metadata:
      labels: {{ app: gateway }}
    spec:
      containers:
        - name: gateway
          image: scam/gateway:{IMAGE_TAG}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
              name: http
          env:
{env_block(env, "            ")}{resources_block(GATEWAY_REQUESTS, GATEWAY_LIMITS, "          ")}          readinessProbe:
            httpGet: {{ path: /healthz, port: 8080 }}
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            httpGet: {{ path: /healthz, port: 8080 }}
            initialDelaySeconds: 10
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: gateway
  namespace: {NAMESPACE}
spec:
  type: ClusterIP
  selector: {{ app: gateway }}
  ports:
    - name: http
      port: 8080
      targetPort: 8080
"""


def ingress_yaml() -> str:
    # Workstream E: the ONE way in from outside the cluster. Only the gateway is listed; the workers, BERT,
    # aggregator, Envoy and Redis Services stay ClusterIP (internal names only, no route from outside).
    # The phone's saved address points at the Ingress controller, which follows the gateway Service's
    # endpoints, so deleting/restarting the gateway pod (new pod IP) never changes the phone's address.
    #
    # TLS (E.4): the certificate is the gateway-tls Secret made by demo/make_tls.py. The phone usually
    # connects by IP address, which sends no hostname (SNI), so nginx would show its own fake certificate;
    # up.sh/up.ps1 therefore also make gateway-tls the controller's --default-ssl-certificate.
    # The hostless rule accepts any address (laptop IP, 10.0.2.2, 127.0.0.1); the named rule is for
    # https://scamguard.test if you map that name in a hosts file. Same manifest works on k3s with
    # ingress-nginx installed (the cloud-VM option in demo/STABLE_ADDRESS.md).
    #
    # Why the /api/ prefix: a request by IP lands on nginx's catch-all server, which has its OWN hard-coded
    # `location /healthz { return 200; }` (nginx's load-balancer health check). Without a prefix, the
    # app's "Test connection" got that empty 200 from nginx instead of the gateway's answer, even with the
    # gateway down (found by testing). /api/healthz -> /healthz on the gateway can't be shadowed.
    return f"""apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: gateway
  namespace: {NAMESPACE}
  annotations:
    # /api/<anything> -> /<anything> on the gateway (e.g. /api/v1/utterance -> /v1/utterance).
    nginx.ingress.kubernetes.io/use-regex: "true"
    nginx.ingress.kubernetes.io/rewrite-target: /$2
    # Every answer must arrive within 2 s; fail fast instead of nginx's 60 s default if the gateway hangs.
    nginx.ingress.kubernetes.io/proxy-connect-timeout: "3"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "5"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "5"
    nginx.ingress.kubernetes.io/proxy-body-size: "64k"   # one transcript line is at most 10,000 characters
spec:
  ingressClassName: nginx
  tls:
    - hosts: [{INGRESS_HOST}]
      secretName: {INGRESS_TLS_SECRET}
  rules:
    - host: {INGRESS_HOST}
      http:
        paths:
          - path: /api(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service: {{ name: gateway, port: {{ number: 8080 }} }}
    - http:
        paths:
          - path: /api(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service: {{ name: gateway, port: {{ number: 8080 }} }}
"""


def review_yaml() -> str:
    # Workstream H.4/H.5/H.8: review queue + labelling page + model registry. ClusterIP only: the phone reaches
    # it solely through the gateway's POST /v1/report; the labelling page via `learning.py review`.
    # The encryption key is the review-key Secret, made once by up.sh/up.ps1 (never written to this repo).
    return f"""apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: review-data
  namespace: {NAMESPACE}
spec:
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: {REVIEW_STORAGE}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: review
  namespace: {NAMESPACE}
  labels: {{ app: review }}
spec:
  replicas: 1          # SQLite = one writer
  strategy:
    type: Recreate     # the volume is ReadWriteOnce: the old pod must let go of it first
  selector:
    matchLabels: {{ app: review }}
  template:
    metadata:
      labels: {{ app: review }}
    spec:
      containers:
        - name: review
          image: scam/review:{IMAGE_TAG}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8090
              name: http
          env:
            - name: REVIEW_KEY
              valueFrom:
                secretKeyRef: {{ name: review-key, key: key }}
            - name: REVIEW_RETENTION_DAYS
              value: "{REVIEW_RETENTION_DAYS}"
{resources_block(REVIEW_REQUESTS, REVIEW_LIMITS, "          ")}          volumeMounts:
            - name: data
              mountPath: /data
          readinessProbe:
            httpGet: {{ path: /health, port: 8090 }}
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            httpGet: {{ path: /health, port: 8090 }}
            initialDelaySeconds: 10
            periodSeconds: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: review-data
---
apiVersion: v1
kind: Service
metadata:
  name: review
  namespace: {NAMESPACE}
spec:
  type: ClusterIP
  selector: {{ app: review }}
  ports:
    - name: http
      port: 8090
      targetPort: 8090
"""


def retrain_yaml() -> str:
    # Workstream H.6/H.7: weekly retraining of the six fast workers with a release check. A blocked release exits
    # 1 and is NOT retried (backoffLimit 0): the same data would just be blocked again.
    return f"""apiVersion: batch/v1
kind: CronJob
metadata:
  name: retrain-models
  namespace: {NAMESPACE}
spec:
  schedule: "{RETRAIN_SCHEDULE}"
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 0
      ttlSecondsAfterFinished: 86400
      template:
        metadata:
          labels: {{ app: retrain-models }}
        spec:
          restartPolicy: Never
          containers:
            - name: retrain
              image: scam/retrain:{IMAGE_TAG}
              imagePullPolicy: IfNotPresent
              env:
                - name: REVIEW_URL
                  value: "http://review:8090"
                - name: MIN_NEW_LABELS
                  value: "{RETRAIN_MIN_NEW_LABELS}"
                - name: TOLERANCE
                  value: "{RETRAIN_TOLERANCE}"
                # train.py runs parallel jobs per CPU it can see; keep it to the CPU limit.
                - name: LOKY_MAX_CPU_COUNT
                  value: "{RETRAIN_LIMITS['cpu']}"
{resources_block(RETRAIN_REQUESTS, RETRAIN_LIMITS, "              ")}"""


def write(name: str, content: str) -> None:
    path = OUT_DIR / name
    path.write_text(content, encoding="utf-8")
    print(f"wrote {path}")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    cfg_path = OUT_DIR / "_generated" / "envoy-config.yaml"
    if not cfg_path.exists():
        print(f"error: {cfg_path} is missing -- run demo/make_envoy_config.py first", file=sys.stderr)
        return 1

    write("00-namespace.yaml", namespace_yaml())
    write("01-redis.yaml", redis_yaml())
    write("02-envoy-configmap.yaml", envoy_configmap_yaml())
    write("03-envoy.yaml", envoy_yaml())
    write("04-aggregator.yaml", aggregator_yaml())
    write("05-bert.yaml", bert_yaml())
    for i, (signal, lang) in enumerate(WORKERS):
        write(f"06{chr(ord('a') + i)}-worker-{signal}-{lang}.yaml", worker_yaml(signal, lang))
    write("07-gateway.yaml", gateway_yaml())
    write("08-ingress.yaml", ingress_yaml())
    write("09-review.yaml", review_yaml())
    write("10-retrain.yaml", retrain_yaml())
    print(f"\n{2 + 1 + 1 + 1 + 1 + len(WORKERS) + 1 + 1 + 2} manifest files written to {OUT_DIR}")
    import make_monitoring  # optional Prometheus + Grafana (H.1/H.2), in demo/k8s/monitoring/
    make_monitoring.main(NAMESPACE, DRIFT_SHORT, DRIFT_BASELINE, DRIFT_FACTOR, DRIFT_MIN_UNSURE_SHARE,
                         PROMETHEUS_IMAGE, GRAFANA_IMAGE)
    print("NOTE: loadgen (demo/k8s/loadgen/loadgen.yaml) is written separately -- see demo/tools/load_sim.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
