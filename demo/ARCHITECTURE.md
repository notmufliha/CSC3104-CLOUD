# ScamGuard demo architecture

This describes the Kubernetes demo in `demo/`, built on top of the existing `docker-compose.yml` stack
(10 containers: redis, bert, aggregator, gateway, 6 workers — unchanged, still the known-good baseline for
local dev and for Phase-1 Android-emulator testing). Everything below is new. **Kafka is not used anywhere
in this repo** — if a diagram or slide shows Kafka, it is wrong; remove it.

## Components, ports, who calls whom

```
Android app (emulator/phone)   saved address: https://<laptop IP>:8443/api/  (demo/expose.ps1/.sh)
        |  HTTPS  POST /v1/utterance, GET /healthz
        v
   ingress-nginx (Ingress "gateway", TLS ends here; the ONLY thing reachable from outside -- Workstream E,
        |         see demo/STABLE_ADDRESS.md)
        |  HTTP, cluster network
        v
   gateway (Rust, 1 replica, ClusterIP Service "gateway:8080")
        |  gRPC, through Envoy -----------------------------+
        |                                                   |
        v                                                   v
  envoy-lb (1 replica)                              envoy-lb (same pod, different listener port)
   :50061 urgency-en      -> urgency-en-hl   (headless) :50051  x1-4 pods (HPA)
   :50062 money-en        -> money-en-hl     (headless) :50051  x1-4 pods (HPA)
   :50063 impersonation-en-> impersonation-en-hl         :50051  x2-4 pods (HPA, min 2: busiest)
   :50064 urgency-zh      -> urgency-zh-hl              :50051  x1-4 pods (HPA)
   :50065 money-zh        -> money-zh-hl                :50051  x1-4 pods (HPA)
   :50066 impersonation-zh-> impersonation-zh-hl        :50051  x1-4 pods (HPA)
   :50067 aggregator      -> aggregator-hl   (headless) :50052  x2 pods
   :50068 bert            -> bert-hl         (headless) :50051  x1-2 pods (HPA)
   :9901  admin (stats, health, /clusters)

  aggregator  --gRPC, through Envoy :50068--> bert (second opinion, cascade only)
  aggregator  --TCP-------------------------> redis:6379 (call memory: call:<id>, calllog:<id>)
```

Every address above is an environment variable the gateway/aggregator already support
(`URGENCY_EN_ADDR`, ..., `AGGREGATOR_ADDR`, `BERT_ADDR` — see `docker-compose.yml`); the demo just points
them at `envoy-lb:<port>` instead of a plain Kubernetes Service. No gateway/aggregator/worker source code
changed.

## Why Envoy (the one deliberate design decision here)

A gRPC client (the gateway calling a worker, the aggregator calling BERT) opens **one long-lived HTTP/2
connection** and sends every request down it. A plain Kubernetes `ClusterIP` Service load-balances per
**connection** (it's just iptables/IPVS NAT), not per **request** — so once that one connection is
established, every request from that client lands on the **same backend pod**, forever. Extra replicas
added by the HPA sit completely idle, and the demo would visibly show 100% of traffic hitting one pod. This
was measured as a real problem on an earlier version of this code (1533 requests to one pod, 0 to the
others it had scaled up).

The fix needs no change to gateway/aggregator/worker code: put **Envoy** in front of every scalable gRPC
service as a request-level load balancer, and give each such service a **headless Service**
(`clusterIP: None`) so Envoy's own DNS resolution (`STRICT_DNS`, refreshed every 2s) sees every pod's IP
individually, not one virtual IP. Envoy then round-robins **per request**, actively health-checks each
pod over gRPC every 2s, and ejects a pod that starts erroring (`outlier_detection`). Measured effect on an
earlier version of this code with the same fix: 491/491/492 requests spread evenly across 3 pods; killing
one pod mid-load produced 0 errors and 0 missing scores. **Re-measured on this version of the code in this
session** — see `demo/EXPECTED.md` for the real numbers (an aggregator 458/459 split, an impersonation-en
290/290 split, both measured from a live run, not assumed).

`demo/make_envoy_config.py` generates the Envoy bootstrap config (one listener + cluster per scalable
service, retries, timeouts derived from the gateway/aggregator's own `*_TIMEOUT_MS`, health checks,
outlier detection — every number has a comment explaining it). It has a `--docker` flag that swaps
Kubernetes FQDNs (`<svc>-hl.scamguard.svc.cluster.local`) for plain docker-compose service names, for
testing the same config outside Kubernetes.

## Why 1 gateway replica

The gateway keeps two things in its own process RAM, not in Redis: which `(call_id, seq)` pairs it has
already scored (so a resend isn't scored twice) and each call's last answer (for receiver lines, which
aren't scored, and for resends). This lives in `backend/gateway/src/calls.rs`, governed by `CALL_TTL_SECS`
/ `MAX_CALLS`. A second gateway replica would have its own, different copy of this memory: a resend that
happened to land on the other replica would be scored again, and a receiver line could get a stale or
missing answer. There is no sticky-session routing configured for the gateway's Service, so until that is
added, **the gateway must stay at exactly 1 replica**. `demo/make_manifests.py` hard-codes this
(`GATEWAY_REPLICAS = 1`) with a comment explaining why, rather than leaving it as an easy-to-change number.

## Why 2 aggregator replicas is safe (unlike the gateway)

The aggregator keeps **no** per-call state of its own — all of it lives in Redis
(`backend/aggregator/src/store.rs`). Two aggregator pods updating the same call at the same moment don't
corrupt it: each save is a Lua script that only writes if the call's `version` field is still what was
read; the loser of a race re-reads the (now newer) state and retries, up to 20 times with a small random
backoff. So the aggregator is safely horizontally scalable, and 2 replicas were chosen to also demonstrate
HPA-free, always-on failover (one can be killed with zero coordination with the other). Verified in this
session: restarting (deleting) **both** aggregator pods simultaneously mid-call, the call's verdict and
`caller_lines` count were both correctly recovered from Redis afterwards ("red stays red" held) — see
`demo/EXPECTED.md` for the one caveat this test turned up (a ~1-2s window of `UNAVAILABLE` immediately
after *both* replicas die at once, before Envoy's health check/DNS refresh catches up — this does not
happen when only one of the two dies, which is what `chaos.sh`'s default single-pod kill actually tests).

## Where state lives

| What | Where | Survives what |
|---|---|---|
| Call verdict memory (window, streak, red, verdict) | Redis (`call:<id>`) | aggregator pod restart, any aggregator replica count |
| Verdict log (one entry per line, no text) | Redis (`calllog:<id>`) | same |
| "seq already scored" + last answer | gateway process RAM | nothing — this is exactly why gateway must stay at 1 replica |
| Worker models | baked into each worker's image at build time | everything (stateless) |
| BERT model | baked into the bert image at build time (downloaded from Hugging Face during `docker build`, hash-checked) | everything (stateless) |

Redis itself has **no persistent volume** in this demo (deliberate): call memory is meant to be temporary
(`AGG_CALL_TTL_SECS`, default 1 hour) and losing it if the Redis pod restarts is an acceptable, honest
trade-off for a free laptop demo — in fact `demo/chaos.sh redis` (or scaling `redis` to 0 and back) is a
good way to *show* that trade-off live: calls in flight get a 502 while Redis is down, then recover,
with no in-flight call's memory preserved across that gap unless Redis had a volume (it doesn't, here).

## What is real vs simulated in the demo

- **Real**: the gateway, aggregator, all 6 workers, BERT (the actual fine-tuned ONNX model fetched from
  the team's Hugging Face repo at build time, SHA256-checked — not the dummy), Redis, Envoy, the HPAs, and
  every verdict/score reported. Nothing in the request/response path is mocked.
- **Simulated**: the "caller" in every test call is a script (`android-app/.../assets/scripts/*.json`)
  played back by `demo/tools/demo_flow.py` / `demo/tools/load_sim.py`, or the Android app's own "Simulate
  call" button — there is no real phone network, SIM, or telephony integration anywhere in this repo. The
  Android app's role is to send the same JSON lines a real call-screening integration would need to send;
  how those lines would be captured from an actual phone call is out of scope (see the main README's
  "working skeleton" framing).
- **Fake-model warning**: `tools/bert_dummy_model.py` exists only to test the BERT pod's wiring before the
  real model existed. It was **not used** to produce any number in `demo/EXPECTED.md` — every BERT score
  reported there came from a build that ran `fetch_model.py` and downloaded the real, hash-verified model.

## Kafka-free

This repo has no message broker. The gateway calls workers and the aggregator synchronously over gRPC,
within a ~2 second budget (`WORKER_TIMEOUT_MS` + `AGGREGATOR_TIMEOUT_MS`, both in `docker-compose.yml`).
If an architecture diagram elsewhere shows Kafka, Dapr, or any async broker between these services, it
does not describe this codebase — this repo's actual data flow is request/response gRPC + HTTP, full stop.
