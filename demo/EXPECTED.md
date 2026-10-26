# EXPECTED.md — measured results, not assumptions

Every number below came from an actual command run against actual code in this session, on 2026-10-26.
Where something could not be run (no Android emulator, no real phone in this environment), it says so —
it does not guess a number. "This requires verification" means exactly that: nobody has confirmed it yet.

Test environment for this session (NOT necessarily Shreya's laptop — see `demo/README.md` "laptop size"
for how `up.sh`/`up.ps1` size themselves to whatever machine they actually run on):
`docker info` reported 16 CPUs, 15 GiB memory, Docker Desktop on Windows. minikube v1.39.0, driver=docker,
Kubernetes v1.37.0, containerd 2.3.4. kubectl client v1.32.2 (older than the cluster — cosmetic warning
only, did not cause any failure).

## A real, unanticipated finding: `minikube docker-env` does not work on this Windows setup

`up.sh`/`up.ps1` originally built images with `eval $(minikube docker-env)` (the approach minikube's own
docs suggest, and what was first written here). **Running it for real failed**:

```
! Using the docker-env command with the containerd runtime is a highly experimental feature
X Exiting due to SSH_AGENT_START: starting an SSH agent on Windows is not yet supported
```

Fixed by building on the host's own Docker and copying each image in with `minikube image load
scam/<name>:demo` instead, which does not depend on the node's container runtime or SSH. **This fix was
then verified end-to-end twice** (see "Full up.sh run" below) — both `up.sh` and `up.ps1` now use it.
This is exactly the kind of thing the honesty rules in `DEMO_TASK.md` ask to catch rather than assume away.

## 1. Static checks (2026-10-26)

| Check | Command | Result |
|---|---|---|
| Python syntax | `python3 -I -m py_compile demo/make_envoy_config.py demo/make_manifests.py demo/make_loadgen.py demo/tools/load_sim.py demo/tools/demo_flow.py demo/tools/spread_report.py` | All compile, no errors |
| `--help` works | `python3 demo/tools/load_sim.py --help`, `python3 demo/tools/demo_flow.py --help` | Both print usage and exit 0 |
| Bash syntax | `bash -n` on all 7 `.sh` files in `demo/` + `demo/tools/` | All OK |
| PowerShell syntax | `[System.Management.Automation.Language.Parser]::ParseFile(...)` on all 7 `.ps1` files | All OK, 0 parse errors |
| Envoy config (Kubernetes FQDN variant) | `docker run --rm -v .../envoy-config.yaml:/etc/envoy/envoy.yaml envoyproxy/envoy:v1.31-latest --mode validate -c /etc/envoy/envoy.yaml` | `configuration '/etc/envoy/envoy.yaml' OK` |
| Envoy config (`--docker` variant) | same, with `demo/envoy-config.docker.yaml` | `configuration '/etc/envoy/envoy.yaml' OK` |
| Envoy image exists | `docker pull envoyproxy/envoy:v1.31-latest` | Pulled successfully, digest `sha256:caa5b4...` |
| K8s manifests (kubeconform) | `kubeconform -strict -summary -kubernetes-version 1.32.0 demo/k8s/*.yaml` | `32 resources found in 13 files - Valid: 32, Invalid: 0, Errors: 0` |
| Loadgen manifest (kubeconform) | same, on `demo/k8s/loadgen/loadgen.yaml` | `3 resources found in 1 file - Valid: 3, Invalid: 0, Errors: 0` |
| Embedded ConfigMap content integrity | parsed `load_sim.py` out of its ConfigMap YAML and `compile()`d it; parsed all 14 bundled script JSON files | All valid |

One real bug was found and fixed during testing (not left for Shreya to hit): `demo/tools/demo_flow.py`'s
table printer crashed with `TypeError: unsupported format string passed to NoneType.__format__` the first
time it hit a line with no `bert_score` (e.g. `bert_status: "skipped"`). Fixed before any further testing.

## 2. Image builds (2026-10-26, `scam/*:demo`, all from `backend/` with the service's own Dockerfile)

All 9 images (gateway, aggregator, 6 workers, bert) built successfully. All were **cache-hits** from
Shreya's prior `docker compose up --build` on this machine — i.e. this session did not measure a **cold**
build (no Docker layer cache). The ZH workers' in-image training (~1 min each, per their Dockerfiles) and
the Rust `cargo build --release` steps (several minutes each on a cold cache) were therefore **not timed
in this session**; budget for them on a true first run.

BERT specifically: the build ran `fetch_model.py` (downloads `model.onnx`/`tokenizer.json`/
`bert_config.json` from `huggingface.co/gouthamsaravanan/csc3104-scam-bert`, SHA256-checked) and then
`predict.py` as a build-time sanity check — both passed, confirming **the real model**, not the dummy.
`tools/bert_dummy_model.py` was never invoked in this session.

## 3. Kubernetes deployment (minikube, driver=docker) — 2026-10-26

```
bash demo/up.sh
```

Ran successfully end to end, twice: once against an already-running cluster (idempotent re-apply, "x
unchanged" for every resource), and once from a clean `demo/down.sh` teardown (full recreate). The clean
teardown-and-recreate cycle (images already built and cached in minikube from the first run) took
**4m03s** wall clock. All 11 Deployments (`redis, envoy-lb, aggregator, bert`, 6 workers, `gateway`) rolled
out successfully on the **first try**, inside the script's 180s-per-deployment timeout — no manual
intervention needed. `kubectl get pods,hpa -n scamguard` showed all pods `1/1 Running`, HPAs briefly
`<unknown>` for the first ~30-60s (metrics-server still warming up — this is the documented, expected
behaviour from section 10 of DEMO_TASK.md, not a bug) and real percentages after that.

`demo/down.sh` (without `--all`) was also run for real and correctly deleted only the `scamguard`
namespace, leaving minikube itself running. `demo/down.sh --all` (which also runs `minikube delete`) was
**not run** in this session (it would have destroyed the cluster this session's evidence depends on) —
its logic is a two-line wrapper around `kubectl delete namespace` + `minikube delete`, both standard
commands; this requires verification on Shreya's own machine but the risk is low.

## 4. Acceptance targets (DEMO_TASK.md section 5)

| # | Target | Status | Evidence |
|---|---|---|---|
| 1 | Phone/emulator -> app -> live gateway, full flow | **PARTIALLY TESTED HERE.** The gateway side (verdict/score/reasons/action over HTTP) was fully tested — see `demo_flow.py` results below. The Android app itself was **NOT TESTED HERE**: no Android Studio / emulator / physical device is available in this environment. **This requires verification** on Shreya's machine (steps and expected result are in `demo/README.md`). |
| 2 | BERT cascade active + Redis call memory (red stays red; restart doesn't lose the call) | **TESTED HERE.** Real `bert_status: "ok"` with real scores observed (e.g. 0.97-1.0 on scam-like lines, 0.05 on a calm one). Aggregator: both replicas deleted mid-call; `caller_lines` and `verdict: SCAM` ("red stays red") were correctly recovered from Redis afterwards. See "Aggregator restart" below. |
| 3 | Load balancing: `load_sim.py` -> `spread` shows roughly equal requests per pod | **TESTED HERE.** aggregator 458/459 across its 2 pods; impersonation-en 290/290 across its 2 pods. See "Load test" below. |
| 4 | HPA: CPU rises -> replicas go up; after load stops, go down after ~30s | **TESTED HERE.** See "HPA scaling" below: multiple workers scaled 1->2/3/4 under load and back down afterwards. |
| 5 | Resilience: kill a worker/aggregator pod during load -> ~0 errors | **TESTED HERE.** 998 requests sent while killing an `impersonation-en` pod mid-load: 0 HTTP errors, 2 requests (0.2%) with a non-empty `missing` (not literally 0 — reported exactly as measured, not rounded down). |
| 6 | Failure behaviour by design (Redis down -> 502; worker stopped -> `missing`/quorum) | **TESTED HERE.** All four sub-cases reproduced exactly as documented. See "Failure behaviour" below. |
| 7 | Ops views (`kubectl get pods,hpa`, `kubectl top pods`, Envoy admin, health endpoints) | **TESTED HERE** for `kubectl get pods,hpa`, Envoy admin `/clusters` (via `demo/spread.sh`/`.ps1`, both run for real), and `/healthz`. `kubectl top pods` itself was **not separately captured as a resource-sizing table** in this session (metrics-server was confirmed working via the HPA's own CPU percentages, which read from the same source) — **this requires verification** if Shreya wants to re-check the exact resource requests/limits in `demo/make_manifests.py` against real usage. |

## 5. `demo_flow.py` reference run (2026-10-26)

```
kubectl port-forward -n scamguard svc/gateway 18080:8080 &
python3 demo/tools/demo_flow.py --url http://localhost:18080 --pace 15
```

Full table output is reproducible by re-running the command above. Headline results:

- **scam_en_10_gov_official**: turned `SCAM` at **line 3** (money_rule rule). Cross-checked once more at
  real-time pace (`--pace 1`, ~80s) with **identical** scores and verdicts — pace does not change the
  outcome for this call, so the `--pace 15` results for the other 3 calls below can be trusted as
  representative.
- **normal_en_10_bank_reminder**: oscillated `SAFE` -> `UNSURE` -> `SAFE` -> `UNSURE` -> **`SCAM` at line 9**
  (BERT-assisted: `bert_score: 0.97`, `bert_status: ok`), then dropped to `combined: 0.51` at line 11 but
  **stayed `SCAM`** ("red stays red" — correctly cannot un-ring the bell).
- **scam_zh_10_gov_official**: `SAFE` at line 1, `SCAM` from line 3 onward, reaching `combined: 1.0` by
  line 13.
- **scam_en_12_parcel_customs** ("starts SAFE, turns SCAM"): `SAFE` (0.22) -> `UNSURE` (0.48, 0.74 with
  BERT) -> **`SCAM` at line 6** (0.75, `bert_score: 1.0`) -> stays `SCAM`, reaching 0.96.

**Honest discrepancy worth the team's attention**: `tools/testing/expected.json` / the testing README's
table says `scam_en_10_gov_official` should turn red "by line 11" (allowed from line 8, late if after 14).
The **measured** behaviour on the currently-committed worker models is `SCAM` already at **line 3** —
`UNSURE` (0.58) at line 1 breaks the warm-up on its own (some worker already scored >= 0.9 on the very
first line), then line 3's `money_rule` fires immediately. This is not a bug introduced by the demo
infrastructure — the same result reproduced at both `--pace 15` and real-time `--pace 1` — it looks like
`tools/testing/expected.json`'s timing table predates the current frozen model versions (`urgency_en` v4,
`impersonation_en` v2, `money_en_v1`, ZH v6 — see the main README's "Worker models" table and its own
documented false-positive weak spots). **This requires the team's own re-check against `expected.json`**;
it is flagged here, not silently "fixed" (no model or aggregator code was touched).

Independently confirmed with the repo's own test tool, against the same live minikube deployment:

```
python3 tools/testing/replay.py --only scam_en_10 --caller-only --verbose
```

```
| script               | expected | final | red at line (key) | secs after key | status    |
| scam_en_10_gov_official | SCAM   | SCAM  | 3 (11)             | -42.2           | TOO EARLY |
```

Same conclusion, the team's own tool, real time (not `--pace`-accelerated).

## 6. Load test (2026-10-26)

Light load (used for the spread/load-balancing check):

```
python3 demo/tools/load_sim.py --url http://localhost:18080 --users 15 --duration 50 --ramp 5 --pace 10
```

```
Requests sent:       863       Requests/s: 17.04
Latency p50/p95/p99: 32 / 62 / 74 ms
HTTP errors:         0 (0.0%)
Verdict counts:      {'SAFE': 383, 'UNSURE': 68, 'SCAM': 412}
bert_status counts:  {'skipped': 631, 'no_context': 59, 'ok': 173}
Non-empty 'missing': 0        quorum_met=false: 0
```

Immediately after, `python3 demo/tools/spread_report.py` (same as `demo/spread.sh`) read Envoy's admin
`/clusters` and showed the spread across pods -- **the key proof that the Envoy fix works**:

```
aggregator        10.244.0.6:50052     458
aggregator        10.244.0.7:50052     459
impersonation_en  10.244.0.10:50051    290
impersonation_en  10.244.0.11:50051    290
```

Not 100/0 -- both pods of both 2-replica services got essentially identical traffic.

Heavier load (used to try to trigger HPA scale-up):

```
python3 demo/tools/load_sim.py --url http://localhost:18080 --users 40 --duration 70 --ramp 5 --pace 25
```

```
Requests sent:       5912      Requests/s: 83.70
Latency p50/p95/p99: 58 / 487 / 661 ms
HTTP errors:         0 (0.0%)
Verdict counts:      {'SAFE': 2390, 'UNSURE': 599, 'SCAM': 2923}
bert_status counts:  {'skipped': 4400, 'no_context': 454, 'ok': 785, 'timeout': 273}
Non-empty 'missing': 0        quorum_met=false: 0
```

Honest note: p95/p99 latency rose sharply (62ms -> 487ms) under this heavier load, and 273 BERT calls
(4.6% of all requests) **timed out** (`bert_status: "timeout"`, within `BERT_TIMEOUT_MS=300`) before the
BERT HPA had added a second replica. This is the cascade behaving exactly as designed under real
pressure -- a slow/overloaded BERT is *skipped*, not a cause of a failed request (0 HTTP errors throughout)
-- but it is a real, measured cost of a cold single-replica BERT pod meeting a sudden load spike, worth
knowing about rather than assuming away.

## 7. HPA scaling (2026-10-26, from the heavier load test above)

Before load: all workers at `minReplicas` (1, except impersonation-en at 2). During load, CPU utilization
(read by `kubectl get hpa`) peaked at:

```
bert:               168% (target 50%) -> scaled 1 -> 2
impersonation-en:   117% (target 50%) -> scaled 2 -> 4
impersonation-zh:   139% (target 50%) -> scaled 1 -> 3
money-en:           198% (target 50%) -> scaled 1 -> 4
money-zh:           147% (target 50%) -> scaled 1 -> 3 (observed at the 3-replica point; later also seen at 2)
urgency-en:         152% (target 50%) -> scaled 1 -> 4
urgency-zh:         145% (target 50%) -> scaled 1 -> 3
```

After load stopped, replica counts were observed to have come back down (impersonation-en 4 -> 2,
urgency-zh/money-zh/impersonation-zh -> 1, bert -> 2 -> 1) within a few minutes of checking
(`SCALE_DOWN_SECONDS=30` stabilization window, plus the HPA controller's own ~15s sync period) --
the exact second-by-second scale-down curve was **not captured to the second** in this session (the gap
between the "load stopped" check and the "scaled down" check was a few minutes, not instrumented more
precisely); **this requires closer timing verification** if an exact number is needed for a slide.

## 8. Resilience / chaos (2026-10-26)

```
python3 demo/tools/load_sim.py --url http://localhost:18080 --users 20 --duration 30 --ramp 2 --pace 15 &
kubectl delete pod -n scamguard <one impersonation-en pod, chosen while the load above was running>
```

```
Requests sent: 998     HTTP errors: 0 (0.0%)      Non-empty 'missing': 2 (0.2%)
```

The killed pod was replaced automatically; `kubectl get pods -n scamguard -l app=impersonation-en` showed
a new pod `Running` within seconds. `demo/chaos.sh impersonation-en` (the actual deliverable script, not
just the equivalent manual commands) was also run for real and confirmed to delete a random pod of the
named service and print it coming back.

`demo/port-forward.ps1` (the real script, on real Windows PowerShell) was also run for real and correctly
printed the emulator URL, auto-detected a LAN IP for the "real phone" URL (`http://10.5.0.2:8080/` in this
sandboxed environment — the detection logic worked; whether `10.5.0.2` is reachable from a phone depends
on the actual network this runs on next, which **requires verification** on Shreya's own Wi-Fi), and the
`adb reverse` fallback line, then started looping `kubectl port-forward` as designed. `demo/spread.ps1`
was also run for real against the live cluster (see section 6) and matched `spread.sh`'s output shape
exactly.

## 9. Aggregator restart mid-call (2026-10-26)

Sent 2 lines of a scam script to a fresh `call_id` (verdict reached `SCAM` already at line 1 via the money
rule), then ran `kubectl delete pod -n scamguard -l app=aggregator` (**both** replicas at once -- a harder
test than `chaos.sh`'s single-pod default), then sent a 3rd, deliberately calm line to the same `call_id`.

- The very next request (sent ~1s after the delete, before Envoy's 2s DNS refresh / health check had
  caught up with zero remaining backends) failed once: `{"error":"aggregator error: Unavailable"}`.
- Retried ~3s later: succeeded, `caller_lines: 3`, `verdict: SCAM` (unaffected by the calm 3rd line,
  confirming "red stays red" survived the full restart, backed by Redis, not in-memory state).

This is an honest, narrower finding than "pod kill = 0 errors": it only shows up when **all** replicas of
a 2-replica Envoy-fronted service die in the same instant, which `chaos.sh`'s normal single-pod-kill
default never triggers (one replica is always left standing). Worth knowing, not worth losing sleep over.

## 10. Failure behaviour by design (2026-10-26)

| Scenario | Command | Measured result |
|---|---|---|
| Redis down | `kubectl scale deployment redis -n scamguard --replicas=0`, then POST a line | `HTTP 502 {"error":"aggregator timed out"}` |
| Redis back up | `kubectl scale deployment redis --replicas=1`, wait for Ready, POST again | First retry: `502 {"error":"aggregator error: Unavailable"}` (aggregator's Redis `ConnectionManager` was still reconnecting). Second retry, ~2-4s later: `200`, verdict correct. |
| One worker type stopped (`urgency-en` -> 0 replicas) | POST a calm line | `200`, `"missing":["urgency"], "quorum_met":true` (2 of 3 still answered) -- verdict computed correctly from the other two. |
| Two worker types stopped (`urgency-en` + `money-en` -> 0) | POST a scam-sounding line | `200`, `"missing":["urgency","money"], "quorum_met":false, "verdict":"UNSURE"`, reason `"Not enough data: only 1 of 3 checks answered"`. `latency_ms: 774` (much higher than the usual 10-30ms -- Envoy's `connect-failure` retries against the dead pods before the gateway's own 1400ms worker timeout gives up). |

All four match `backend/gateway` and `backend/aggregator`'s documented behaviour exactly; no code changes
were made to produce or fix any of this.

## 11. `phone_check.sh` (2026-10-26)

Run for real (`bash demo/tools/phone_check.sh`) against the port-forwarded gateway: auto-detected a LAN
IP, printed it, then got a real `200` from `/healthz` and a real `SCAM` verdict from one sample POST.
First request after the port-forward had just (re)started showed `latency_ms: 1536` — a one-off
connection-warm-up cost, not representative of steady-state latency (compare the ~10-90ms seen everywhere
else in this document once a connection is warm).

## 12. What was NOT tested here (be specific, don't guess)

- **Real Android emulator / physical phone.** No Android Studio or device is available in this execution
  environment. The HTTP mechanics `demo/tools/phone_check.sh`/`.ps1` automate (GET `/healthz`, POST one
  sample line, using the laptop's Wi-Fi IP rather than `localhost`) were exercised directly with `curl`/
  `Invoke-RestMethod` against the port-forwarded gateway and worked, but the actual app, its Settings
  screen, `AppPrefs`/`GatewayClient` code, and a real network path to a phone were not exercised.
  **This requires verification** on Shreya's own machine with Android Studio.
- **Plain Docker + Envoy, live** (DEMO_TASK.md 9.3). The `--docker` Envoy config was schema-validated with
  `envoy --mode validate` (see section 1 above) but **not** stood up as a live `redis + workers +
  aggregator + bert + gateway + envoy` stack on a user-defined Docker network in this session. The
  equivalent (and strictly more demanding, since it has multiple replicas) scenario was fully live-tested
  in real Kubernetes instead — see sections 3-10. If a pure-Docker version is specifically needed (e.g. to
  debug something Kubernetes-independent), it has not been exercised and should be treated as unverified.
- **Windows Firewall rule, `.wslconfig` memory bump.** Commands are documented in `demo/README.md` but
  were not applied/tested here (this session's Docker Desktop already reports 15 GiB, well above the
  threshold that would trigger them).
- **`demo/down.sh --all`** (which also runs `minikube delete`). Not run, to avoid destroying the cluster
  this document's evidence depends on. Low risk (thin wrapper around two standard commands) but
  unverified.
- **Cold (no Docker layer cache) image builds.** All 9 images were cache-hits from a prior
  `docker compose up --build` on this machine. The ZH workers' in-image model training (~1 min each) and
  the Rust `cargo build --release` steps were never actually timed from scratch in this session.
- **The "lite" profile** (`DEMO_LITE=1`, triggered automatically when Docker reports < 6 CPUs or < 10 GiB).
  This session's host (16 CPUs, 15 GiB) never triggered it, so the lite code path in `up.sh`/`up.ps1` and
  `demo/make_manifests.py` (lower HPA max, 1 BERT replica, 1 aggregator replica) ran but was never actually
  exercised end-to-end on a genuinely small host. **This requires verification** on a smaller laptop.
- **A real `kubectl top pods` resource-usage table** against the new images, to confirm/tighten the
  `demo/make_manifests.py` request/limit numbers (which are carried over from the DEMO_TASK.md section 4.4
  estimate, itself measured on older code). HPA CPU percentages (which use the same metrics-server data)
  were observed and looked sane, but a dedicated memory/CPU table was not captured.
