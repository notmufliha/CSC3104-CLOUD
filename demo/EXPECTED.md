> Measured 2026-10-26 on the older build. Newer results are in demo/FINAL_RESULTS.md.

# EXPECTED.md — measured results, not assumptions

Every number below came from an actual command run against actual code in this session, on 2026-10-26.
Where something could not be run (no Android emulator, no real phone in this environment), it says so —
it does not guess a number. "This requires verification" means exactly that: nobody has confirmed it yet.

Test environment for this session (NOT necessarily Shreya's laptop — see `demo/README.md` "laptop size"
for how `up.sh`/`up.ps1` size themselves to whatever machine they actually run on):
`docker info` reported 16 CPUs, 15 GiB memory, Docker Desktop on Windows. minikube v1.39.0, driver=docker,
Kubernetes v1.37.0, containerd 2.3.4. kubectl client v1.32.2 (older than the cluster — cosmetic warning
only, did not cause any failure).

## `up.ps1` end-to-end on real Windows PowerShell (2026-10-26, follow-up session)

Re-ran the whole sequence on real PowerShell (not Git-Bash) this time, exactly as told to Shreya:
`demo/down.ps1` → `demo/up.ps1` → `demo/port-forward.ps1` → `demo/tools/demo_flow.py` → `demo/watch.ps1`
→ `demo/tools/load_sim.py` → `demo/spread.ps1` → `demo/chaos.ps1`. Two more real, reproducible bugs were
found and fixed as a direct result (not left for Shreya to hit):

**1. `up.ps1`'s own Docker check could falsely report "Docker is not running."** The script had
`try { docker info *> $null } catch { Die "Docker is installed but not running..." } }`. On this machine,
`docker info` prints a benign `WARNING: DOCKER_INSECURE_NO_IPTABLES_RAW is set` line to stderr even on
success (exit 0). PowerShell 5.1, with `$ErrorActionPreference = "Stop"` (set at the top of the script)
and ANY redirection of a native command's stderr (here, the `*>` in the same line), turns that stderr line
into a terminating `NativeCommandError` — regardless of the command's real exit code. The script would
have told Shreya Docker wasn't running when it plainly was. Fixed (and the equivalent `minikube status`
check below it) by temporarily relaxing `$ErrorActionPreference` around just that one native call and
checking `$LASTEXITCODE` directly instead of relying on try/catch. Re-ran `up.ps1` **plainly** (as Shreya
would — no output-wrapping) afterward: clean run, `EXIT=0`, all 13 pods `Running` on the first try.

**2. `minikube.exe` was never actually on Shreya's persistent Windows PATH.** It had only been added to
one Bash session's `$PATH` earlier, which doesn't persist to PowerShell or a fresh terminal. Attempting to
persist it automatically was correctly blocked by this session's own safety policy (it's a system-wide,
outlives-the-session change) — **this needs Shreya's own one-time action**:
```powershell
[Environment]::SetEnvironmentVariable("PATH", [Environment]::GetEnvironmentVariable("PATH","User") + ";$env:USERPROFILE\bin", "User")
```
(adjust the path if `minikube.exe` lives somewhere else), then open a **new** terminal. Until then,
`minikube` commands only work in a terminal where that session's `$env:Path` was patched by hand.

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

Independently confirmed with the repo's own test tool:

```
python3 tools/testing/replay.py --only scam_en_10 --caller-only --verbose
```

```
| script               | expected | final | red at line (key) | secs after key | status    |
| scam_en_10_gov_official | SCAM   | SCAM  | 3 (11)             | -42.2           | TOO EARLY |
```

**Correction**: this command was run with no `--gateway`/`GATEWAY_URL` override, so it used its default
`http://localhost:8080`. That port was already claimed by Shreya's own long-running `docker compose up`
(see "Port 8080 conflict" below) — so this result is actually from the **docker-compose** stack, not
minikube, despite an earlier draft of this file claiming otherwise. The finding (TOO EARLY at line 3) is
still valid either way, since compose and the k8s demo run the exact same images/code/models — but the
attribution was wrong and is corrected here rather than left standing.

## `watch.sh`/`watch.ps1` used an unsupported `kubectl -w` form (found and fixed 2026-10-26)

Running the real `watch.ps1` for the first time failed outright:
```
kubectl : error: you may only specify a single resource type
```
`kubectl get pods,hpa -n scamguard -w` — watching **two** resource types at once — is rejected by this
repo's kubectl version (client v1.32.2). Rewrote both `watch.sh` and `watch.ps1` to use the
clear-and-reprint-every-2s pattern DEMO_TASK.md itself offered as an alternative, instead of `-w`. Verified
the fix for real (PowerShell): refreshed cleanly twice in a 5-second capture window, correct pod/HPA table
each time.

## A hand-typed PowerShell `curl -X POST ... -d '{"...":"..."}'` sends broken JSON (found 2026-10-26)

While re-verifying the Redis-down/up failure behavior from `demo/README.md`'s "on stage" step 7, on
PowerShell:
```powershell
curl.exe -X POST http://127.0.0.1:8080/v1/utterance -H "Content-Type: application/json" -d '{"call_id":"test1","seq":1,"speaker":"caller","lang":"en","text":"test"}'
```
returned `{"error":"Json deserialize error: key must be a string at line 1 column 2"}` -- not the expected
502. This is **not** a gateway bug: PowerShell re-quotes arguments before handing them to a native `.exe`,
and embedded double-quotes inside a single-quoted string arrive at `curl.exe` mangled. Re-tested with
`Invoke-RestMethod` + a real PowerShell object (`@{...} | ConvertTo-Json`) instead of hand-typed JSON text:
got the correct `502 {"error":"aggregator timed out"}` while Redis was scaled to 0, and a correct `SAFE`
verdict immediately after scaling it back to 1. The Redis down/up failure behavior itself is confirmed
correct (again); only the ad-hoc curl command needed a different shape on PowerShell. Added a
troubleshooting row and a copy-pasteable `Invoke-RestMethod` pattern to `demo/README.md`
(`demo/tools/phone_check.ps1` already used the correct pattern all along).

## `localhost` is slow to resolve on this Windows machine (found 2026-10-26)

While re-testing on real PowerShell, `demo/tools/demo_flow.py` (default `--url` was `http://localhost:8080`
at the time) showed a suspiciously uniform ~2000-2100ms on **every single request** — far above the
10-90ms measured earlier over Git-Bash/kubectl port-forward on the same cluster. Isolated with
`Measure-Command`:

```
Invoke-RestMethod http://localhost:8080/healthz   -> 2299 ms
Invoke-RestMethod http://127.0.0.1:8080/healthz   ->   51 ms
curl.exe http://localhost:8080/healthz            ->  504 ms
curl.exe http://127.0.0.1:8080/healthz            ->  109 ms
```

Classic Windows dual-stack resolution behaviour: `localhost` resolves to `::1` (IPv6) first, which times
out against this port-forward (IPv4-only), before falling back to `127.0.0.1`. Different HTTP clients pay
a different penalty (PowerShell/.NET: ~2.2s extra; `curl.exe`: ~0.4s extra) but all pay *something*. This
would have made every verdict in the live demo visibly sluggish, and would have badly skewed
`load_sim.py`'s throughput/latency numbers across thousands of requests. **Fixed**: changed the default
`--url` in `demo/tools/demo_flow.py` and `demo/tools/load_sim.py` from `localhost` to `127.0.0.1`, and
updated `demo/README.md`'s example commands and troubleshooting table to say the same. Not an issue on
Mac/Linux/WSL (confirmed throughout this document's earlier Git-Bash-based numbers) — Windows-specific.

## Port 8080 conflict between `docker compose` and the Kubernetes demo (found 2026-10-26)

Both `docker compose up`'s gateway and `demo/port-forward.sh`/`.ps1` (the Kubernetes demo's gateway
access) bind **host port 8080** by default. Running both at once means whichever started first wins that
port; the second one's `kubectl port-forward` either fails to bind (silently, in the background) or you
simply end up talking to the wrong stack without realizing it — which is exactly what happened in this
session (see the correction above). **Do not run `docker compose up` and the Kubernetes demo's
port-forward at the same time on the same port.** Stop one before starting the other, or change one
side's host port (e.g. `kubectl port-forward ... 18080:8080` and use `http://localhost:18080` instead).
Added to `demo/README.md`'s troubleshooting table.

## BERT's 300ms timeout is tight under real CPU contention (found 2026-10-26)

While this session had **both** docker-compose (10 containers) and minikube (a 13-pod cluster) running
simultaneously on one machine, the compose stack's BERT cascade intermittently returned
`bert_status: "timeout"` instead of `"ok"` on lines that should have gotten a real score (confirmed via
`docker stats`: the `minikube` container alone was using 42% CPU at that moment). Pausing minikube
(`minikube pause`) and re-running the identical call immediately fixed it — every line got
`bert_status: "ok"` with real scores again. This is **not a code defect**: `BERT_TIMEOUT_MS=300` is a
deliberately tight budget (to fit inside the gateway's 2s promise), and it is sensitive to real CPU
scheduling delays when the host is heavily oversubscribed, as this session's host was with two full
stacks running at once. Shreya's normal workflow (one stack at a time) should not hit this; if BERT
timeouts show up unexpectedly, check `docker stats`/`kubectl top pods` for CPU contention first.

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

Run for real (`bash demo/tools/phone_check.sh`): auto-detected a LAN IP, printed it, then got a real `200`
from `/healthz` and a real `SCAM` verdict from one sample POST. **Correction, same cause as the port 8080
conflict above**: the `kubectl port-forward ... 8080:8080` started just before this test silently failed
to bind (compose's gateway already had host port 8080), so this request also landed on docker-compose,
not minikube — confirmed by `docker compose logs aggregator` showing exactly one `"phone-check"` call_id.
The script itself worked correctly end-to-end; it simply talked to whichever stack already owned port
8080, which is the real, demo-relevant lesson here (see "Port 8080 conflict" above), not a flaw in
`phone_check.sh`. First request after the port-forward (compose's gateway, already warm) showed
`latency_ms: 1536` — likely a one-off cold TCP/TLS-handshake-free-but-still-cold-connection cost through
the auto-detected LAN IP rather than `localhost`, not representative of steady-state latency (compare the
~10-90ms seen everywhere else in this document once a connection is warm).

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
