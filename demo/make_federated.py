#!/usr/bin/env python3
"""make_federated.py - Workstream G: Kubernetes manifests for federated learning and model downloads (OPTIONAL).

Writes demo/k8s/federated/. Like monitoring/ it is a sub-folder on purpose: `kubectl apply -f demo/k8s/` does not
recurse, so the extra pods only run when you ask (python demo/tools/federated.py ...).

  00-download.yaml   ns scamguard:    model-download Deployment (2 copies, stateless) + Service + Ingress /models
                                      (G.10: the phone's only way to the registry, read-only)
  05-inbox.yaml      ns scamguard:    fl-inbox Deployment (1 copy) + volume + Service + Ingress /fl: where REAL
                                      phones upload their trained model change (inbox.py), POST /fl/updates
  10-namespace.yaml  ns scamguard-fl: the federated-learning experiment's own namespace
  20-run.yaml        ns scamguard-fl: one federated run = fl-server Job + Service, and fl-phones Indexed Job
                                      (10 pods, pod i = phone i, holds data slice i)  (G.6-G.9)
                     + with real phones: fl-bridge Job in ns scamguard (it mounts the inbox volume), one more Flower
                                      client that carries the real phones' uploads into round 1 (bridge_client.py)

The run is two Jobs, not Deployments: a round is a batch task that finishes (a weekly CronJob in a real system),
so nothing sits idle between runs. federated.py renders 20-run.yaml with the run's settings (noise, rounds...).

  python demo/make_federated.py
"""
import sys
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent / "k8s" / "federated"
NS, FL_NS, TAG = "scamguard", "scamguard-fl", "demo"
REVIEW_URL = f"http://review.{NS}.svc.cluster.local:8090"


def download_yaml() -> str:
    return f"""apiVersion: apps/v1
kind: Deployment
metadata:
  name: model-download
  namespace: {NS}
  labels: {{ app: model-download }}
spec:
  replicas: 2          # stateless (reads the registry, caches files by version), so copies are free
  selector:
    matchLabels: {{ app: model-download }}
  template:
    metadata:
      labels: {{ app: model-download }}
    spec:
      containers:
        - name: model-download
          image: scam/model-download:{TAG}
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8000
              name: http
          env:
            - name: REVIEW_URL
              value: "{REVIEW_URL}"
          resources:
            requests: {{ cpu: "20m", memory: "32Mi" }}
            limits: {{ cpu: "200m", memory: "128Mi" }}
          readinessProbe:
            httpGet: {{ path: /health, port: 8000 }}
            initialDelaySeconds: 2
            periodSeconds: 5
          livenessProbe:
            httpGet: {{ path: /health, port: 8000 }}
            initialDelaySeconds: 5
            periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: model-download
  namespace: {NS}
spec:
  type: ClusterIP
  selector: {{ app: model-download }}
  ports:
    - name: http
      port: 8000
      targetPort: 8000
---
# A second Ingress on the same host as the gateway's (nginx merges them). No rewrite: /models/... is passed as is.
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: model-download
  namespace: {NS}
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "1k"     # GET only
spec:
  ingressClassName: nginx
  tls:
    - hosts: [scamguard.test]
      secretName: gateway-tls
  rules:
    - host: scamguard.test
      http:
        paths:
          - path: /models
            pathType: Prefix
            backend:
              service: {{ name: model-download, port: {{ number: 8000 }} }}
    - http:
        paths:
          - path: /models
            pathType: Prefix
            backend:
              service: {{ name: model-download, port: {{ number: 8000 }} }}
"""


def inbox_yaml() -> str:
    return f"""apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: fl-inbox
  namespace: {NS}
spec:
  accessModes: [ReadWriteOnce]      # one node (minikube): the inbox pod and the bridge Job both mount it
  resources:
    requests:
      storage: 1Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: fl-inbox
  namespace: {NS}
  labels: {{ app: fl-inbox }}
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels: {{ app: fl-inbox }}
  template:
    metadata:
      labels: {{ app: fl-inbox }}
    spec:
      securityContext: {{ fsGroup: 65534 }}
      containers:
        - name: fl-inbox
          image: scam/model-download:{TAG}       # same small image; INBOX_DIR switches uploads on
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8000
              name: http
          env:
            - {{ name: INBOX_DIR, value: /inbox }}
            - {{ name: REVIEW_URL, value: "{REVIEW_URL}" }}
          resources:
            requests: {{ cpu: "20m", memory: "48Mi" }}
            limits: {{ cpu: "300m", memory: "192Mi" }}
          volumeMounts:
            - {{ name: inbox, mountPath: /inbox }}
          readinessProbe:
            httpGet: {{ path: /health, port: 8000 }}
            initialDelaySeconds: 2
            periodSeconds: 5
      volumes:
        - name: inbox
          persistentVolumeClaim:
            claimName: fl-inbox
---
apiVersion: v1
kind: Service
metadata:
  name: fl-inbox
  namespace: {NS}
spec:
  type: ClusterIP
  selector: {{ app: fl-inbox }}
  ports:
    - name: http
      port: 8000
      targetPort: 8000
---
# Real phones upload here: https://<address>/fl/updates. Its own Ingress: an upload is ~4 MB (gzip).
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: fl-inbox
  namespace: {NS}
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
spec:
  ingressClassName: nginx
  tls:
    - hosts: [scamguard.test]
      secretName: gateway-tls
  rules:
    - host: scamguard.test
      http:
        paths:
          - path: /fl
            pathType: Prefix
            backend:
              service: {{ name: fl-inbox, port: {{ number: 8000 }} }}
    - http:
        paths:
          - path: /fl
            pathType: Prefix
            backend:
              service: {{ name: fl-inbox, port: {{ number: 8000 }} }}
"""


def bridge_yaml() -> str:
    return f"""---
# Real phones: one more Flower client. In ns {NS} because it reads the inbox volume (a volume belongs to one
# namespace); it reaches the server in {FL_NS} by its DNS name.
apiVersion: batch/v1
kind: Job
metadata:
  name: fl-bridge
  namespace: {NS}
spec:
  backoffLimit: 2
  ttlSecondsAfterFinished: 3600
  template:
    metadata:
      labels: {{ app: fl-bridge }}
    spec:
      restartPolicy: OnFailure
      securityContext: {{ fsGroup: 65534 }}
      containers:
        - name: bridge
          image: scam/federated:{TAG}
          imagePullPolicy: IfNotPresent
          command: ["python", "bridge_client.py"]
          env:
            - {{ name: FL_SERVER, value: "fl-server.{FL_NS}.svc.cluster.local:8080" }}
            - {{ name: INBOX_DIR, value: /inbox }}
            - {{ name: REVIEW_URL, value: "{REVIEW_URL}" }}
          resources:
            requests: {{ cpu: "50m", memory: "200Mi" }}
            limits: {{ cpu: "500m", memory: "600Mi" }}
          volumeMounts:
            - {{ name: inbox, mountPath: /inbox }}
      volumes:
        - name: inbox
          persistentVolumeClaim:
            claimName: fl-inbox
"""


def namespace_yaml() -> str:
    return f"""apiVersion: v1
kind: Namespace
metadata:
  name: {FL_NS}
  labels: {{ app.kubernetes.io/part-of: scamguard, workstream: g }}
"""


def run_yaml(rounds=10, phones=10, noise=0.0, clip=10.0, init="zeros", publish=False, run_name="",
             real_phones=False) -> str:
    env = {"FL_ROUNDS": rounds, "FL_PHONES": phones, "FL_NOISE": noise, "FL_CLIP": clip, "FL_INIT": init,
           "FL_PUBLISH": "1" if publish else "0", "REVIEW_URL": REVIEW_URL, "FL_RESULTS": "/results",
           "FL_BRIDGE": "1" if real_phones else "0"}
    if run_name:
        env["FL_RUN"] = run_name
    server_env = "\n".join(f"            - {{ name: {k}, value: \"{v}\" }}" for k, v in env.items())
    return f"""apiVersion: v1
kind: Service
metadata:
  name: fl-server
  namespace: {FL_NS}
spec:
  type: ClusterIP
  selector: {{ app: fl-server }}
  ports:
    - name: grpc
      port: 8080
      targetPort: 8080
---
# G.6: the Flower server. Runs the rounds, scores the gold sets each round (G.7), runs the release check (G.9),
# publishes a passing model (G.10), prints the results as one RESULT_JSON line, and exits.
apiVersion: batch/v1
kind: Job
metadata:
  name: fl-server
  namespace: {FL_NS}
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    metadata:
      labels: {{ app: fl-server }}
    spec:
      restartPolicy: Never
      containers:
        - name: fl-server
          image: scam/federated:{TAG}
          imagePullPolicy: IfNotPresent
          command: ["python", "server.py"]
          ports:
            - containerPort: 8080
              name: grpc
          env:
{server_env}
          resources:
            requests: {{ cpu: "200m", memory: "384Mi" }}
            limits: {{ cpu: "1", memory: "1Gi" }}
          volumeMounts:
            - {{ name: results, mountPath: /results }}
      volumes:
        - {{ name: results, emptyDir: {{}} }}
---
# G.6: the simulated phones. Indexed Job: pod i gets JOB_COMPLETION_INDEX=i and trains on data slice i only.
apiVersion: batch/v1
kind: Job
metadata:
  name: fl-phones
  namespace: {FL_NS}
spec:
  completionMode: Indexed
  completions: {phones}
  parallelism: {phones}
  backoffLimit: {phones}
  ttlSecondsAfterFinished: 3600
  template:
    metadata:
      labels: {{ app: fl-phone }}
    spec:
      restartPolicy: OnFailure
      containers:
        - name: phone
          image: scam/federated:{TAG}
          imagePullPolicy: IfNotPresent
          command: ["python", "client.py"]
          env:
            - {{ name: FL_SERVER, value: "fl-server.{FL_NS}.svc.cluster.local:8080" }}
          resources:
            requests: {{ cpu: "50m", memory: "160Mi" }}
            limits: {{ cpu: "500m", memory: "400Mi" }}
""" + (bridge_yaml() if real_phones else "")


def write():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    files = {"00-download.yaml": download_yaml(), "05-inbox.yaml": inbox_yaml(), "10-namespace.yaml": namespace_yaml(),
             "20-run.yaml": run_yaml()}
    for name, text in files.items():
        (OUT_DIR / name).write_text(text, encoding="utf-8")
        print(f"wrote demo/k8s/federated/{name}")


if __name__ == "__main__":
    write()
    sys.exit(0)
