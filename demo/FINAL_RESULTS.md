# FINAL_RESULTS.md

Measured on 2026-11-14 (times are UTC; Singapore time is UTC+8) on this laptop, from a cluster built from zero with
`demo/up.ps1` on branch `shreya-final2`. Every number below comes from the command next to it. "not tested" means it
was not run, and says why.

Host: Docker Desktop 28.3.2 on Windows 11, `docker info`: 16 CPUs, 16469127168 bytes (15.3 GiB) memory. minikube
v1.39.0 (driver docker), Kubernetes v1.37.0, containerd 2.3.4, kubectl client v1.32.2. Node allocatable: 16 CPU,
16083132Ki memory.

## 1. Git

Commits teammates added since 12 Nov (`git log --oneline dbd4d65..origin/main`, 2026-11-14 05:01 UTC):

| hash | author | subject | touches my parts |
|---|---|---|---|
| e59e280 | Goutham Saravanan | C.6-C.8: end-of-call review (BERT reads the whole call, final verdict, riskiest window) | N |
| b8b5bc8 | Goutham Saravanan | Merge pull request #24 (workstream-c-endcall) | N |
| fafff02 | notmufliha | Web app, tabbed Android home, and Workstream H review fixes | Y: demo/make_manifests.py, demo/make_monitoring.py, demo/tools/learning.py, demo/k8s/11-web.yaml, demo/k8s/monitoring/grafana.yaml, demo/compose-edge.conf, demo/LEARNING_LOOP.md |
| 6028c6b | notmufliha | Nine new calls from SPF's 2026 scam advisories; canary deadline fix | Y: demo/k8s/11-web.yaml, demo/tools/learning.py (and 9 new call scripts + tools/testing/expected.json, now 23 calls) |

Nothing under `backend/workers/impersonation_en` or `data/impersonation_en` was changed by teammates.
Mufliha's `make_manifests.py` change (the web Deployment, Ingress path `/`) did not break anything I tested.

My commits (on top of origin/main 6028c6b), oldest first. The F4 rename commit was dropped at your request:

| hash | subject | files |
|---|---|---|
| 8235c27 | Envoy: health-check the aggregator over HTTP /health, not gRPC (F1) | demo/make_envoy_config.py, demo/k8s/_generated/envoy-config.yaml, demo/envoy-config.docker.yaml, demo/k8s/02-envoy-configmap.yaml |
| ecdd7fb | k8s: add AGG_MODE and AGG_MONEY_RULE_LINES to the aggregator manifest (F3a) | demo/make_manifests.py, demo/k8s/04-aggregator.yaml |
| 3f1ad6e | docs: fix stale facts (F5) | README.md, demo/ARCHITECTURE.md, demo/README.md, demo/STABLE_ADDRESS.md, demo/EXPECTED.md |
| b366fd0 | k8s: startup probe for the six workers so pods started under load are not killed while loading (new, found in T2) | demo/make_manifests.py, demo/k8s/06a..06f-worker-*.yaml, demo/k8s/loadgen/loadgen.yaml (regenerated) |
| b4be391 | k8s: raise the Redis CPU limit from 250m to 1 (new, found in T2) | demo/make_manifests.py, demo/k8s/01-redis.yaml |
| (this file) | demo/FINAL_RESULTS.md | |

No AI signature in any commit (a local commit-msg hook refuses them). Nothing was pushed until the end; see the
chat message for the push.

## 2. Fixes

### F1 (P0) Envoy aggregator health check: done
Change: `demo/make_envoy_config.py` gives the aggregator cluster `http_health_check: { path: /health }` with
`health_check_config: { port_value: 8082 }` on its endpoint; the six workers and BERT keep `grpc_health_check`. Same
in `--docker` mode. Both files regenerated.
Verify:
- `docker run --rm -v <file>:/cfg.yaml envoyproxy/envoy:v1.31-latest --mode validate -c /cfg.yaml` (05:10 UTC):
  `configuration '/cfg.yaml' OK` for both `demo/k8s/_generated/envoy-config.yaml` and `demo/envoy-config.docker.yaml`.
- Live cluster, `kubectl port-forward deploy/envoy-lb 19901:9901` then `curl :19901/clusters | grep health_flags`
  (05:32 UTC): all 11 hosts `healthy`, including both aggregator pods (10.244.0.10 and 10.244.0.11). The panic-mode
  stat `lb_healthy_panic` was 0 for every cluster. Re-checked at 06:08 after killing an aggregator pod: both hosts
  healthy, rq_error 0.

### F2 (P0) Retrain version clash: already fixed by Mufliha, nothing to change
`backend/retrain/models.py` on origin/main already has `impersonation_en: baked 5` (with a comment). Check script
(05:12 UTC) compared `baked` with what is really in each worker image:

| model | models.py baked | really baked (file / BAKED_MODEL_VERSION) | match | next release name (empty registry) |
|---|---|---|---|---|
| urgency_en | 4 | v4 (`model/v4.json`) | yes | v5 |
| money_en | 2 | money_en_v2c (`model/money_en_v2c.json`) | yes (number) | v3 |
| impersonation_en | 5 | impersonation_en_v5 (`model_card.json`) | yes | v6 |
| urgency_zh | 1 | v1 (`server.py`) | yes | v2 |
| money_zh | 1 | v1 | yes | v2 |
| impersonation_zh | 1 | v1 | yes | v2 |

Letter version: the registry only accepts `^v\d{1,4}$` (`backend/review/app.py` VERSION_RE), so "v2c" can never be
in the registry list that `retrain_one.py` parses with `int(version.lstrip("v"))`. The retrain code treats money_en's
baked model as "v2" and names the next release v3. `learning.py status` prints "v2 (baked)" for money_en while the
worker itself reports `money_en_v2c`: cosmetic only. The live worker images confirm the baked versions (T4 below).

### F3 (P0) Kubernetes generator parity: done
a) `AGG_MODE: "cascade"` and `AGG_MONEY_RULE_LINES: "6"` added to `AGG_ENV` in `demo/make_manifests.py`; manifests
regenerated. Verify (05:32 UTC): `kubectl -n scamguard exec deploy/aggregator -- env | grep -E "AGG_MODE|AGG_MONEY_RULE_LINES"`
printed `AGG_MONEY_RULE_LINES=6` and `AGG_MODE=cascade`.
b) Every env var of gateway, aggregator and the six workers compared between `docker compose config` and the
generated manifests (script `compare_env.py`, 05:11 UTC). Differences:
- Addresses differ by design (compose service names vs `envoy-lb:<port>`): `*_ADDR`, `BERT_ADDR`. Expected.
- `MODEL_VERSION`: compose sets it to an empty string (`${X_MODEL_VERSION:-}`), the manifests do not set it. Both
  mean "the baked model" (`model_registry.resolve` treats empty as unset), so nothing to fix.
- No other difference. All `AGG_*`, `BERT_TIMEOUT_MS`, gateway timeouts, `REVIEW_URL`, `VERIFY_TIMEOUT_MS`,
  `BERT_CONTEXT_LINES`, `MODEL_REGISTRY_URL` match.
kubeconform (`-strict -summary -ignore-missing-schemas demo/k8s/*.yaml`): 43 resources in 17 files, all valid;
plus `federated/ monitoring/ loadgen/`: 21 resources, all valid (05:10 UTC). The 12 Nov count of 58 grew because
Mufliha added `11-web.yaml` (6 objects).

### F4 (P1) Rename gold_human.csv: not done (rename was reverted at your request)
The rename was made, then removed from history again; `data/impersonation_en/gold_human.csv` keeps its name
and no note was added to `LABELING.md`. The two `evaluate_gold.py` runs I did while the rename was in place
(host Python 3.13, scikit-learn 1.6.1, 05:14 UTC) gave gold_test.csv 622 rows F1 0.927, ROC-AUC 0.988 and the 96-row
file F1 0.958, ROC-AUC 0.993. The file contents did not change, so those numbers are the same under the old name.

### F5 (P2) Stale statements: done
- `demo/ARCHITECTURE.md`: compose "10 containers" to 13 (+3 with the federated profile); BERT "x1-2 pods" to
  "x2-4 pods (HPA, min 2)"; "health-checks each pod over gRPC" now says gRPC for workers/BERT and HTTP /health for the
  aggregator.
- `demo/README.md`: "BERT up to 2" to "BERT 2 to 4"; "builds all 9 images" to 11 (gateway, aggregator, 6 workers, BERT,
  review, retrain).
- `demo/STABLE_ADDRESS.md`: "9 images" to 11.
- `README.md` worker table: impersonation_en row was v2 / threshold 0.47 / scores 0.941 / 0.011; now v5, reporting
  threshold 0.67, and the two README test sentences re-scored with the baked v5 `model.pkl` on 2026-11-14:
  scam 0.990, normal 0.003 (`predict.score`, 05:17 UTC).
- `demo/EXPECTED.md`: dated note at the top; measured numbers untouched.
- Not found anywhere in the three docs: "32 resources" (only in EXPECTED.md, history), `GET /v1/model/latest` (no
  doc mentions it), "checks every pod's health" for the aggregator (no such sentence).

### New fixes found during T2 (not in FIX_AND_TEST.md, both in my files)
- Worker startup probe (b366fd0). During the first 40-user run the HPA started new worker pods, which failed the 1 s
  gRPC liveness check while still loading their model (busy node, 500m CPU limit) and were killed with exit 137,
  restarting 3 to 4 times each (`kubectl get pods`: RESTARTS 3-4; `describe pod`: "Liveness probe failed: timeout:
  failed to connect service ... within 1s", "Container money-zh failed liveness probe, will be restarted"). Their
  restart CPU kept the HPA scaled up for minutes with no load. Fix: startupProbe (gRPC, every 5 s, up to 24 tries = 2
  minutes) before liveness starts, and `timeoutSeconds: 3` on all three probes. After the fix: 0 restarts in the next
  two heavy runs and two kill tests.
- Redis CPU limit 250m to 1 (b4be391). In heavy runs 1 and 2 the aggregator logged `call memory update failed
  error=redis: timed out` (137 and 84 lines) and the gateway returned 502 for those lines, all in the last 10 s of
  the run. Redis's cgroup `cpu.stat` showed 899 of 7008 periods throttled (86 s). After the fix heavy run 3 had 0
  errors and no throttling.

### T1 (P0) Stable HTTPS address and TLS (Workstream E), all run 2026-11-14
1. Certificate creation: `python demo/make_tls.py` (file mode, 05:06 UTC) worked first time: "made a new CA" and
   "made a new server certificate for: DNS:scamguard.test,DNS:localhost,IP:10.0.2.2,IP:127.0.0.1,IP:172.20.192.1,
   IP:192.168.0.3,IP:192.168.56.1". It used Git for Windows' openssl.exe (`C:\Program Files\Git\mingw64\bin\openssl.exe`),
   which was NOT blocked on this PC. `demo/tls/` only had `ca.ext` before, so the CA is new today. `up.ps1` and
   `expose.ps1` then ran `make_tls.py --apply`: "server certificate already covers ..." and the Secret was applied.
   Inspection below used Python `cryptography`, not openssl.
2. Certificate (`demo/tls/server.crt`, also the one the Ingress served on :8443, same SHA-256 a200fbbc1818e709...):
   issuer `O=CSC3104, CN=ScamGuard demo CA`; subject `CN=scamguard.test`; not before 2026-11-14 05:06:49 UTC; not
   after 2027-12-16 05:06:49 UTC (397 days). SANs: DNS scamguard.test, DNS localhost, IP 10.0.2.2, IP 127.0.0.1,
   IP 172.20.192.1 (WSL/Hyper-V adapter), IP 192.168.0.3 (laptop Wi-Fi, from ipconfig), IP 192.168.56.1
   (VirtualBox host adapter). Nothing missing. CA: same issuer/subject, valid 2026-11-14 to 2031-11-13, SHA-256
   c0d51be7a326642c3e1440a197a39e69e763179b3d3b5244a67d77cf973d4357. The `gateway-tls` Secret's tls.crt hash equals
   the file's (125e375e93a5757d...). TLS 1.3 negotiated.
3. `curl --ssl-no-revoke --cacert demo/tls/ca.crt https://127.0.0.1:8443/api/healthz`: HTTP 200,
   `{"calls_in_memory":4,"status":"ok"}`, ssl_verify_result 0 (05:36 UTC). Without `--cacert`: curl exit 60,
   "SEC_E_UNTRUSTED_ROOT ... issued by an authority that is not trusted", HTTP 000: verification FAILS as it must.
   Plain `http://127.0.0.1:8443/api/healthz`: HTTP 400 from nginx (no unencrypted path). By Wi-Fi IP
   `https://192.168.0.3:8443/api/healthz` with the CA: HTTP 200.
4. Full call over HTTPS (05:38 UTC): `POST /api/v1/utterance` with the police/OTP line: HTTP 200, verdict UNSURE
   (warming_up true, combined 0.82, money 0.999, impersonation 0.990, urgency 0.470, bert_status no_context,
   model_versions impersonation_en_v5 / money_en_v2c / v4). `POST /api/v1/call/end` with
   `{"call_id":..., "ended_by":"script_finished","last_seq":1}`: HTTP 200, final_verdict UNSURE, review "fallback".
   (A body without `ended_by` gets HTTP 400 "missing field ended_by"; `ended_by: user` gets 400 with the allowed list.)
5. `GET https://127.0.0.1:8443/models/manifest`: HTTP 404 from nginx before `federated.py up`; HTTP 200
   `{"models": {}}` after it (05:57 UTC): no phone model versions are in the registry until a federated run publishes
   them (T8). The sha256 list is therefore empty at this point; see T8 for the values after the run.
6. `POST https://127.0.0.1:8443/fl` with an empty body: HTTP 404 `{"error":"not found"}` from the inbox service
   (the route is `/fl/updates`); `POST /fl/updates` with an empty body: HTTP 413 `{"error":"update too large"}`.
   So the `/fl` Ingress path reaches the inbox (not 502), but the inbox's own message for an empty body is wrong.
7. Speed, 50 sequential utterances each, one call, run twice (05:34 UTC, Python urllib, CA verified):
   HTTPS Ingress :8443 p50/p95 62/95 ms and 55/80 ms; `kubectl port-forward` :8080 p50/p95 49/76 ms and 47/71 ms.
   The Ingress hop costs about 8 to 13 ms at p50 and 9 to 19 ms at p95. 0 errors.
8. Android trust (static): `res/xml/network_security_config.xml` trusts system CAs, plus user-installed CAs in
   debug builds. Since commit abe40f6 (2026-11-12) `app/build.gradle.kts` (task demoCaConfig) copies
   `demo/tls/ca.crt` into the debug build as `res/raw/scamguard_demo_ca.pem` and adds it as a trust anchor. The
   Gradle run in T7 regenerated that file: its SHA-256 equals today's CA (c0d51be7...). EQUAL for any debug build made
   from now on. The APK already on disk (`app/build/intermediates/apk/debug/app-debug.apk`, built 2026-10-27) predates
   both the CA bundling and today's CA, so that old APK would reject HTTPS unless `ca.crt` is installed on the device.
   Rebuild the app once before the demo.
9. Phone on the same Wi-Fi: not tested (see section 6).

## 3. Values that differ from the report

(T3, T5 and T6 have their own blocks above; their one-line summaries are also in this table.)

| report place | old value (report / EXPECTED.md) | new value (this build) | command | time (UTC) |
|---|---|---|---|---|
| Rebuild time (section on deployment) | not in EXPECTED.md | `demo/up.ps1` from zero (no minikube profile) to every Deployment Ready: 18 min 19 s (05:12:58 to 05:31:17), all 13 Deployments rolled out first time | `powershell -File demo/up.ps1` with date stamps | 05:12-05:31 |
| Resources / Deployments count | 32 resources (EXPECTED.md, 13 files); "9 images" | demo/k8s: 43 objects in 17 files (+21 in federated/monitoring/loadgen); 13 Deployments after `up` (18 with monitoring + federated download/inbox), 7 HPAs, 2 to 4 Ingresses, 11 locally built images + 5 public ones | kubeconform; `kubectl get deploy -A` | 05:10, 06:11 |
| Table V light (15 users) | 863 req, 17.04 req/s, p50/p95/p99 32/62/74 ms, 0 errors, BERT asked 173 (20%), timeouts 0 | 894 req, 17.65 req/s, p50/p95/p99 41/83/108 ms, 0 failed (0.0%), sentences sent to BERT 454 (50.8%: ok 454, timeout 0, error 0), BERT time-outs 0 (0.0%) | `python demo/tools/load_sim.py --url http://127.0.0.1:8080 --users 15 --duration 50 --ramp 5 --pace 10` | 05:36:35-05:37:26 |
| Table V heavy (40 users), run 1 (before the two new fixes) | 5912 req, 83.70 req/s, 58/487/661 ms, 0 errors, timeouts 273 (4.6%) | 3467 req, 48.76 req/s, p50/p95/p99 462/995/1292 ms, 137 failed (4.0%, all HTTP 502 "redis: timed out" in the last 10 s), BERT asked 1595 (46.0%), time-outs 600 (17.3% of all requests, 37.6% of BERT asks), errors 6 | same tool, `--users 40 --duration 70 --ramp 5 --pace 25` | 05:38:09-05:39:21 |
| Table V heavy, run 2 (startup probe) | | 3439 req, 47.94 req/s, 403/1525/2096 ms, 84 failed (2.4%, same cause), BERT asked 1707 (49.6%), time-outs 992 (28.8%), errors 14, missing 10 | same | 05:52:00-05:53:13 |
| Table V heavy, run 3 (startup probe + Redis 1 CPU) | | 3553 req, 50.33 req/s, p50/p95/p99 419/1149/1541 ms, 0 failed (0.0%), sentences sent to BERT 1742 (49.0%: ok 853, timeout 881, error 8), BERT time-outs 881 (24.8% of all requests, 50.6% of BERT asks), 30 lines with a missing worker score | same | 05:59:09-06:00:21 |
| Fig. 2 / IV-D autoscaling, run 3 (clean) | EXPECTED.md section 7 | before: bert 2, impersonation-en 2, others 1. Peak replicas: bert 4, impersonation-en 4, impersonation-zh 4, money-en 3, money-zh 4, urgency-en 3, urgency-zh 4 (reached 06:00:57-06:01:02, 35-40 s after the load stopped). Peak CPU % of request: bert 238, impersonation-en 117, impersonation-zh 255, money-en 123, money-zh 322, urgency-en 141, urgency-zh 255. Every HPA back at its minimum by 06:02:49 = 148 s after the load stopped (stabilizationWindowSeconds 30 + metrics lag) | `hpa_sample.py` (kubectl get hpa -o json every 5 s) | 05:59-06:08 |
| Fig. 2 run 1 (before the fixes) | | peaks: bert 4 (189%), impersonation-en 4 (94%), impersonation-zh 4 (184%), money-en 4 (124%), money-zh 4 (226%), urgency-en 3 (127%), urgency-zh 4 (212%). Back to minimum 286 to 439 s after the load, because restarting pods kept CPU high (see the startup-probe fix) | same | 05:36-05:51 |
| IV-D request split | aggregator 458/459; impersonation-en 290/290 | after light load: aggregator 548/548, impersonation-en 417/417, bert 324/322 (cumulative Envoy rq_total since start). After heavy run 3: aggregator 1783/1781, impersonation-en 2352/2352, bert 2608/2608 (+225 on the new third pod) | `python demo/tools/spread_report.py` | 05:37, 06:00 |
| Table VI: kill one impersonation-en pod under load | 998 req, 0 errors, 2 missing (0.2%) | 1577 requests in 90 s (15 users), 0 failed, 2 lines (0.13%) missing the impersonation score; pod deleted at t=40 s, replacement Ready in about 1 s | `load_rec.py --users 15 --duration 90 --pace 10` + `kubectl delete pod` | 06:04:31-06:06:04 |
| Table VI NEW: kill ONE aggregator pod under load (the F1 test) | not in report | 1628 requests in 90 s (15 users), 6 failed (0.37%, all HTTP 502: "aggregator error: Unavailable" / "aggregator timed out"), all in seconds 41 to 43 after the start, i.e. the first 3 s after the delete at t=41 s; 4 lines missing the urgency score; the replacement pod and the surviving pod both `healthy` in Envoy afterwards, rq_error 0 | same, victim aggregator-5c9bc94886-5x55k | 06:06:23-06:07:55 |
| Table VI: delete both aggregator pods during a call, run 1 | ~1-2 s window of UNAVAILABLE | contaminated: run started 50 s after heavy run 1 while Redis was still throttled. Lines 1-4 took 1.9-4.0 s each before the delete. After the delete: 5 of 7 following lines got HTTP 502 ("aggregator timed out" / "Unavailable"), recovery per failure 1.7 to 23.9 s with retries; verdict SCAM reached and kept; `caller_lines` jumped (retries were counted), final verdict SCAM | `one_call.py --at 4 --retry 20 --action "kubectl delete pod -l app=aggregator"` | 05:40:17-05:42:25 |
| Table VI: delete both aggregator pods during a call, run 2 | | 90 s after heavy run 2 (Redis still recovering): lines 1-2 failed BEFORE the delete (502 "aggregator timed out", recovered after 2.6 s). After the delete: line 4 and 5 answered (1.1 s, 1.7 s), line 6 failed once (502), recovered after 9.5 s; lines 7-10 normal (49-72 ms); verdict SCAM kept; `caller_lines` continued (7, 8, 9...) so the call's history in Redis was kept; call/end final SCAM | same | 05:54:48-05:55:42 |
| Table VI: stop one detector type (urgency-en scaled to 0) | | every line answered 200 with `missing=['urgency']`, quorum met (2 of 3); verdict SAFE, UNSURE, then SCAM from caller line 3 on (combined 0.73, BERT 0.94); 29 to 348 ms per line (median about 85 ms); final SCAM | `one_call.py` after `kubectl scale deploy/urgency-en --replicas=0` | 06:08:26-06:09:01 |
| Table VI: stop two detector types (urgency-en + money-en scaled to 0) | report: UNSURE "not enough data" | `quorum_met=false` and `missing=['urgency','money']` on every line, but the verdict was UNSURE on lines 1-2 and then **SCAM** from caller line 3 (combined = BERT score 0.94 to 0.99, 71 to 115 ms). In cascade mode the aggregator asks BERT when too few workers answered and BERT alone decided. Not the UNSURE the report describes | same | 06:09:17-06:09:52 |
| Table VI: stop Redis | 502 | lines while Redis down: HTTP 502 `{"error":"aggregator timed out"}` (3.4 s) then `{"error":"aggregator error: Unavailable"}` (17 ms to 1.2 s). After `kubectl scale deploy/redis --replicas=1`: first 200 after 17.7 s (new Redis pod + aggregator reconnect). Note: the aggregator pods were restarted twice by their liveness probe while Redis was down (their /health gives 503 and :8082 refused connections) | `kubectl scale deploy/redis --replicas=0`, `one_call.py --retry 40` | 05:42:27-05:44:20 |
| Table VI: rolling update during a call | 21-23 lines, 0 failed, 0 missing (first try 2 missing) | three rollouts (T5): 31 + 35 + 44 = 110 lines, 0 failed, 0 missing; 11 / 14 / 19 s per model | `learning.py rollout latest --during-call` | 08:53-08:54 |
| Table VI: roll back all six models during calls | 165 lines, 0 failed | only the 3 English models had a release; 24 + 26 + 44 = 94 lines, 0 failed, 0 missing; 9 / 11 / 21 s | `learning.py rollback --during-call` | 08:56-08:58 |
| Section IV-G (learning loop) | urgency F1 0.396 to 0.386 blocked; both SCAM after | nothing blocked (urgency F1 0.3717 to 0.3784 PASS); A and B UNSURE after; versions v5 / v3 / v6; see T5 | `learning.py retrain --force` etc. | 08:30-09:00 |
| T8: one upload accepted by fl-inbox | | 0 accepted: valid uploads get HTTP 502 (PermissionError in the inbox pod, see Open problems 1) | `POST /fl/updates` | 08:49-08:50 |
| Section IV-F (replay) | bank reminder gave a false alarm | normal_en_10_bank_reminder: UNSURE at most, no false alarm; the only red normal call is the new normal_en_21_bank_fraud_check; 7 of 13 scam calls TOO EARLY; call-level F1 0.923 over 23 calls (1.000 over the original 14); see T3 | `replay_bert.py --speed 1` | 06:10-06:30 |
| Table VII | TBC | fast F1 0.818, p50/p95 40/64 ms, 0% to BERT; bert_only F1 0.923, 93/155 ms, 100% to BERT, no verdict without BERT; cascade F1 0.923, 77/125 ms, 56% to BERT, still decides without BERT; see T6 | `kubectl set env ... AGG_MODE=` + replay | 06:10-08:05 |
| Table VI: overload BERT | 273 time-outs (4.6%), 0 failures | from heavy run 3: 881 BERT time-outs (24.8% of all requests, 50.6% of the 1742 BERT asks), 8 BERT errors, 0 failed requests: a slow BERT is skipped, never a failed line | heavy run 3 | 05:59-06:00 |
| T1 item 7: Ingress hop cost | not in report | 50 sequential utterances, run twice each: HTTPS Ingress :8443 p50/p95 62/95 ms then 55/80 ms; port-forward :8080 p50/p95 49/76 ms then 47/71 ms. Ingress hop about +8 to +13 ms at p50 | Python urllib, CA-verified | 05:34 |
| Test counts: Android unit tests | 86 | 94 tests, 0 failures, 0 errors, 5 skipped (HashingParityTest x3 and PhoneTrainerTest x2 skip with "run backend/federated/phone_parity.py first"); 14 test classes; `BUILD SUCCESSFUL in 3m 7s` | `./gradlew.bat --no-daemon testDebugUnitTest` with JAVA_HOME = Android Studio JBR; JUnit XML counted | 05:19-05:22 |
| Test counts: Python | review "7 passed" (LEARNING_LOOP.md) | backend/review: 8 passed (python:3.11-slim, requirements.txt + pytest). backend/retrain: no test files, pytest collected 0. backend/federated: no test files, pytest collected 0. backend/workers/tests/test_error_path.py: 6/6 checks passed inside each of the six freshly built worker images | docker run python:3.11-slim / python:3.12-slim; `docker run -i scam/<worker>:demo python - < test_error_path.py` | 05:15-05:17, 05:32 |
| Memory with everything running (main stack + monitoring + model-download + fl-inbox, 33 pods) | | `kubectl top nodes`: minikube 689m CPU (4%), 4015Mi (25%). `kubectl top pods -A`: scamguard 1814 Mi in 21 pods, kube-system 531 Mi, ingress-nginx 192 Mi. Biggest: bert 256 + 245 Mi, money-zh 235, urgency-zh 220, impersonation-zh 195, ingress 192, impersonation-en 122 + 117, money-en 103, urgency-en 92, grafana 53, review 39. Docker Desktop: minikube container 3.93 GiB of 9.77 GiB; Windows `vmmemWSL` process 6229 MB | `kubectl top`, `docker stats --no-stream`, `Get-Process vmmem*` | 06:11 |

### T3 (P1) Replay of the scripted calls, final code, real time, caller lines only, cascade mode
Command: `replay_bert.py --gateway http://127.0.0.1:8080 --speed 1` (a copy of `tools/testing/replay.py`'s logic that
also records `bert_score` / `bert_status` per line and reuses its `judge()`; `expected.json` untouched). 23 calls
(the 14 original plus Mufliha's 9 new ones), 06:10:49 to 06:30:52 UTC, 153 caller lines, 0 HTTP errors,
p50/p95 latency 77/125 ms, BERT asked on 86 lines (56.2%), all 86 answered in time (`bert_status`: ok 86, skipped 55,
no_context 12). "c3 (line 5)" = 3rd caller sentence, which is line 5 of the script file.

Scam calls (first UNSURE, first SCAM, BERT score at that point, status from expected.json):

| call | set | first UNSURE | first SCAM | BERT at first SCAM | final / end-of-call | status |
|---|---|---|---|---|---|---|
| scam_en_10_gov_official | 14 | c2 (line 3), BERT not asked (no_context) | c3 (line 5) | ok 0.94 | SCAM / SCAM | TOO EARLY (allowed from line 8) |
| scam_en_11_bank_otp | 14 | c1 (line 1) | c3 (line 5) | ok 0.99 | SCAM / SCAM | TOO EARLY (from 7) |
| scam_en_12_parcel_customs | 14 | c2 (line 3) | c3 (line 5) | ok 0.99 | SCAM / SCAM | TOO EARLY (from 8) |
| scam_en_13_tech_support | 14 | never | c5 (line 9) | ok 0.93 | SCAM / SCAM | TOO EARLY (from 11) |
| scam_en_14_friend_in_trouble | 14 | never | c4 (line 7) | ok 0.92 | SCAM / SCAM | OK |
| scam_zh_10_gov_official | 14 | c2 (line 3) | c3 (line 5) | ok 0.98 | SCAM / SCAM | TOO EARLY (from 8) |
| scam_zh_12_parcel_customs | 14 | never | c3 (line 5) | ok 0.97 | SCAM / SCAM | TOO EARLY (from 8) |
| scam_en_20_ica_to_china_police | new | c1 (line 1) | c3 (line 5) | ok 0.97 | SCAM / SCAM | TOO EARLY (from 7) |
| scam_en_21_telco_to_mas_officer | new | never | c3 (line 5) | ok 0.92 | SCAM / SCAM | OK |
| scam_en_22_apple_support_crypto | new | never | c4 (line 7) | ok 0.99 | SCAM / SCAM | OK |
| scam_en_23_bank_card_handover | new | c1 (line 1) | c3 (line 5) | ok 0.97 | SCAM / SCAM | OK |
| scam_en_24_job_task_deposit | new | c6 (line 11), BERT ok 0.04 | never | - | UNSURE / UNSURE | MISSED (no one is impersonated; known) |
| scam_zh_20_ica_to_china_police | new | never | c4 (line 7) | ok 0.98 | SCAM / SCAM | OK |

Normal calls (final verdict, highest combined score):

| call | set | final / end-of-call | highest combined | false alarm? |
|---|---|---|---|---|
| normal_en_10_bank_reminder | 14 | UNSURE / UNSURE | 0.28 | no (UNSURE at c5, never SCAM) |
| normal_en_11_clinic_appointment | 14 | SAFE / SAFE | 0.31 | no |
| normal_en_12_delivery_driver | 14 | SAFE / SAFE | 0.28 | no |
| normal_en_13_insurance_sales | 14 | SAFE / SAFE | 0.21 | no |
| normal_en_14_family_call | 14 | SAFE / SAFE | 0.29 | no |
| normal_zh_10_bank_reminder | 14 | SAFE / SAFE | 0.47 | no (UNSURE at c2) |
| normal_zh_14_family_call | 14 | SAFE / SAFE | 0.26 | no |
| normal_en_20_ica_passport_collection | new | SAFE / SAFE | 0.18 | no |
| normal_en_21_bank_fraud_check | new | SCAM / SCAM | 0.82 | **YES**: UNSURE at c1, SCAM at c3 (line 5) |
| normal_en_22_telco_contract_renewal | new | SAFE / SAFE | 0.24 | no |

- Flagged TOO EARLY: 7 of 13 scam calls (6 of the 7 original scam calls). Every TOO EARLY red is at the 3rd caller
  sentence, 20 s into the call, i.e. the first scored line after the 15 s warm-up: the bank/courier/police claims in
  lines 1-3 plus BERT 0.9+ already give SCAM before the "key" sentence (line 8-14 in expected.json).
- Report section IV-F: the bank reminder (normal_en_10) does NOT give a false alarm on this build (UNSURE at most).
  The one normal call that still goes red is the new `normal_en_21_bank_fraud_check` (real bank fraud team, same
  words as the scam): SCAM from caller sentence 3, combined 0.82. Same result as Mufliha's 2026-11-13 note in
  tools/testing/README.md.
- Call-level: 12 TP, 1 FP, 1 FN, 9 TN over 23 calls, F1 0.923 (both by live verdict and by end-of-call final verdict).
- Over the original 14 only: 7 of 7 scam calls flagged, 0 of 7 normal calls red, call-level F1 1.000.

scam_en_12_parcel_customs sentence by sentence (caller lines; scores urgency/money/impersonation; combined = decision score, fast = workers only):

| caller # (line) | t | verdict | combined | fast | BERT | urg/mon/imp | text |
|---|---|---|---|---|---|---|---|
| c1 (1) | 1.5 s | SAFE | 0.40 | 0.40 | skipped | 0.41/0.00/0.78 | Hello, this is the courier service. We have a parcel under your name ... |
| c2 (3) | 11.2 s | UNSURE | 0.49 | 0.49 | no_context | 0.16/0.08/1.00 | It was sent from overseas and the declared contents do not match ... |
| c3 (5) | 20.3 s | SCAM | 0.74 | 0.49 | ok 0.99 | 0.39/0.04/0.99 | I will connect you to the customs enforcement officer. Please hold. |
| c4 (6) | 25.1 s | SCAM | 0.74 | 0.49 | ok 0.99 | 0.23/0.01/0.93 | Good afternoon, I am Officer Lim from Singapore Customs ... |
| c5 (8) | 35.1 s | SCAM | 0.83 | 0.67 | ok 0.99 | 0.88/0.13/0.99 | If you do not resolve this today, we will refer your case to the police ... |
| c6 (10) | 45.5 s | SCAM | 0.97 | 0.96 | ok 0.99 | 0.37/1.00/0.02 | You need to pay a release fee of four hundred and eighty dollars through PayNow ... |
| c7 (12) | 53.2 s | SCAM | 0.97 | 0.96 | ok 0.99 | 0.46/0.91/1.00 | I will send you the number now. Pay within one hour ... |
| c8 (13) | 59.6 s | SCAM | 0.97 | 0.96 | ok 0.99 | 0.27/0.90/0.24 | After you pay, take a screenshot of the receipt ... |

### T5 (P1) Learning loop end to end (report section IV-G), cascade mode, 08:30 to 09:00 UTC
Steps as in demo/LEARNING_LOOP.md "Run it", scripted (`t5_calls.py`: the app's calls with the scripts' own timing,
the report with consent via `POST /v1/report`, the labels via the review API `POST /api/labels` with exactly the
table in LEARNING_LOOP.md step 3); then `learning.py retrain --force` (default tolerance 0.005), `learning.py rollout
latest --during-call`, the calls again, `learning.py rollback --during-call`, the calls again.

| Check | Result (this build) | Report / LEARNING_LOOP.md says |
|---|---|---|
| Call A (scam_en_90) before | SAFE live, SAFE final; highest money 0.84, urgency 0.44, impersonation 0.18 | SAFE, money 0.84 (same) |
| Call B (scam_en_91) before | SAFE, SAFE; money 0.72, urgency 0.45 | SAFE, money 0.72 (same) |
| Normal clinic call before | SAFE, SAFE; money 0.44, urgency 0.47 | SAFE |
| Report A with consent | HTTP 200 `{'report_id': 1, 'lines_stored': 6}`; 6 lines labelled through the API (`labelled: 6`, `new_lines en 6`) | |
| Retrain, default tolerance, `--force` | Job retrain-now-1794645210 succeeded (08:33:31 to 08:52:32, 19 min). urgency_en v5 trained in 178 s, **PASS** (gold_handwritten ROC-AUC 0.8228 to 0.8263, F1 0.7419 to 0.7619; robocall ROC-AUC 0.9229 to 0.9224, F1 0.3717 to 0.3784), published. money_en v3 trained in 3 s, PASS (sg_gold ROC-AUC 0.8522 to 0.8578, F1 0.6222 = ; test_set 0.7808 to 0.7804, F1 0.5655 = ; ftc 0.6969 to 0.6993, F1 0.0680 to 0.0635), published. impersonation_en v6 trained in 771 s, PASS, published. Chinese models not touched (no Chinese report). **Nothing was blocked.** | "urgency_en v6 BLOCKED (robocall F1 0.3962 to 0.3860)"; money_en v3 and impersonation_en v5 published |
| Versions published | urgency_en **v5** (sha256 92f259f8e4be...), money_en **v3** (42696ad2149a...), impersonation_en **v6** (6b681aa04002...). The impersonation name is v6, as F2 requires (baked v5). | money_en v3, impersonation_en v5, urgency_en v6 |
| Rolling update during a live call (`rollout latest --during-call`) | urgency-en v4 to v5: 11 s, 31 lines, 0 failed, slowest 120 ms. money-en v2c to v3: 14 s, 35 lines, 0 failed, slowest 87 ms. impersonation-en v5 to v6 (2 pods): 19 s, 44 lines, 0 failed, slowest 180 ms; both versions answered during the switch, as expected. Total 0 failed, 0 missing in 110 lines | 30 and 47 lines, 0 failed |
| Calls A and B after all three rolled out | **UNSURE, UNSURE** (live and final). A: money 0.99, urgency 0.75 (the money rule fires: alarmed), but BERT scores 0.005 so the cascade caps it at UNSURE (combined 0.29). B: money 0.96, urgency 0.62, BERT 0.006, UNSURE. Normal clinic call: SAFE | "A and B after all models rolled out: SCAM, SCAM" (measured 2026-11-06 with `--tolerance 0.015` and all six models) |
| Rollback, all released models, during live calls (`rollback --during-call`) | urgency-en v5 to v4 (baked): 9 s, 24 lines, 0 failed; money-en v3 to v2c: 11 s, 26 lines, 0 failed; impersonation-en v6 to v5: 21 s, 44 lines, 0 failed. `kubectl rollout history` shows "model v4 (baked) -> v5" then "rollback: ... v5 -> v4 (baked)" for each. `learning.py status`: every model back on its baked version, registry keeps v5 / v3 / v6 | 165 lines, 0 failed |
| Calls after rollback | A SAFE (money 0.84), B SAFE (money 0.72), normal SAFE: the same as before the loop | "B and A are missed again" (same) |

So the IV-G numbers differ: no model was blocked at the default tolerance on this build (urgency_en F1 0.3717 to
0.3784, not 0.396 to 0.386), the published names are v5 / v3 / v6, and A and B end **UNSURE**, not SCAM, after the
retrain, because BERT (not retrained, score about 0.005 on these job-scam lines) now gates every move to red
(Goutham's rule 6 "alarmed always asks BERT"). The learned signal is clearly there: A's money score 0.84 to 0.99 and
urgency 0.44 to 0.75; B's money 0.72 to 0.96. The `--tolerance 0.015` second retrain from LEARNING_LOOP.md was not
run (nothing was blocked, so there was nothing for it to unblock).

### T6 (P1) Table VII: fast, bert_only, cascade (currently TBC in the report)
Goutham had no results anywhere (`git log --all --oneline -i --grep="mode\|bert_only"` finds only commit a2c32ab
"Aggregator: add AGG_MODE switch"; no .md or .csv with mode results; no branch with them), so it was measured here.
Method per mode: `kubectl -n scamguard set env deploy/aggregator AGG_MODE=<mode>`, wait for the rollout, replay the same
23 scripted calls (13 scam + 10 normal, the 14 original plus the 9 new ones) in real time with caller lines only through
`kubectl port-forward` :8080 (`replay_bert.py`), then scale BERT to 0 and play scam_en_10's caller lines once.
**23 calls is a small sample: one call changes F1 by about 0.04.**

| mode | call-level F1 (scam = correct if any line SCAM; normal = correct if never SCAM) | TP / FP / FN / TN | p50 / p95 ms per request | caller lines sent to BERT | BERT stopped (scaled to 0) | replay wall time (UTC) |
|---|---|---|---|---|---|---|
| fast | 0.818 | 9 / 0 / 4 / 10 | 40 / 64 (max 2274) | 0 of 153 (0%) | still decides: SAFE, UNSURE, then SCAM from caller line 9 (combined 0.91), `bert_status: off` | 06:31:52 to 06:56:39 |
| bert_only | 0.923 | 12 / 1 / 1 / 9 | 93 / 155 (max 4194) | 153 of 153 (100%), all answered | **no verdict**: every line UNSURE with combined 0.0, `bert_status` timeout then error; final UNSURE | 06:57:53 to 08:03:20 |
| cascade | 0.923 | 12 / 1 / 1 / 9 | 77 / 125 (max 471) | 86 of 153 (56.2%), all answered | still decides from the workers: UNSURE then SCAM from caller line 9 (combined 0.91), `bert_status: error` | 06:10:49 to 06:30:52 |

Same calls, same order, 0 HTTP errors in every mode; the end-of-call final verdict gave the same F1 as the live verdict
in every mode. Mode set back to `cascade` at 08:04:37 UTC and confirmed: `kubectl exec deploy/aggregator -- env`
prints `AGG_MODE=cascade`; the next replays (T5) ran in cascade.
Per mode, which calls were wrong:
- fast: missed scam_en_14_friend_in_trouble, scam_en_20_ica_to_china_police, scam_en_21_telco_to_mas_officer,
  scam_en_24_job_task_deposit (all UNSURE at the end); no false alarm; 4 normal calls end UNSURE. 1 TOO EARLY (en_13).
- bert_only: missed scam_en_24 (SAFE all the way, BERT 0.04); false alarm normal_en_21_bank_fraud_check (SCAM at caller
  line 4, BERT 0.99); 5 TOO EARLY. Normal calls score 0.01 to 0.05: BERT alone is very quiet on genuine calls.
- cascade: missed scam_en_24; false alarm normal_en_21 (SCAM at caller line 3, combined 0.82); 7 TOO EARLY.
The bert_only replay's wall time (65 min) was far longer than the others (20 to 25 min) although only one request took
over 1 s (4.2 s) and `call/end` took at most 1.7 s; I could not find the cause in the recorded latencies.

### T8 (P2) Federated and monitoring smoke
`python demo/tools/federated.py build` (05:47 to 05:51 UTC) built scam/federated:demo and scam/model-download:demo;
`federated.py up` (05:56) started model-download (2 pods) and fl-inbox (1 pod) with their Ingress paths.
`python demo/tools/federated.py run --init current --publish --rounds 3 --real-phones` (08:33:28 to 08:34:09 UTC):
- Jobs in namespace scamguard-fl: `fl-server` completed 1/1 (08:33:31 to 08:34:08), `fl-phones` completed 10/10
  (08:33:31 to 08:34:07), `fl-bridge` ran ("no real-phone updates waiting for this version").
- 3 rounds, 10 simulated phones, noise 0, start from the current models. ROC-AUC after round 3 (round 0 in brackets):
  urgency gold_handwritten 0.8228 (0.8228), urgency robocall_gold 0.9150 (0.9144), money sg_gold 0.8411 (0.8411),
  money test_set 0.7990 (0.7990), money ftc 0.7225 (0.7225), impersonation gold_test 0.9867 (0.9867). Mean of the six
  ROC-AUC values at the end: 0.8478.
- Release check: urgency BLOCKED (robocall F1 0.5316 to 0.5185, "BELOW BAR"), money PASS (published money_en_phone v2),
  impersonation PASS (published impersonation_en_phone v2). The G.1 models were first published as v1 of all three.
- `GET https://127.0.0.1:8443/models/manifest` after the run (08:44 UTC): urgency_en_phone v1 sha256
  70b81aade66ea6a38171a9477f8719527b78009a29abe65b31a11da7d442bc00; money_en_phone v2
  f95ab1fbb0cfa28bf36316f90688490ec6ad36690182fc279fff5129334b8648; impersonation_en_phone v2
  1f47d06cf5a46111ba8a9f254c6de14a3c22e8dccd5fb27d7f149ae92eff6753.
- Results file written by the run: `backend/federated/results/fedavg_r3_p10_noise0_from_current.json` (left
  untracked, not committed).
- One upload accepted by fl-inbox: see the line below the table in section 3 ("fl upload").
- Prometheus (`learning.py monitoring up`, 05:57 UTC): `/api/v1/targets` 3 of 3 up (aggregator x2 on :8082/metrics,
  review :8090/metrics). Grafana: login page HTTP 200, datasource Prometheus, dashboard "ScamGuard: learning new scams";
  a query through Grafana's datasource for `sum(scamguard_lines_total)` returned 3563, the same as Prometheus directly, so
  the dashboard has data.

## 4. Values that match the report

- Impersonation v5 gold numbers: F1 0.927 (622 rows, ROC-AUC 0.988) and 0.958 (96 rows, ROC-AUC 0.993), unchanged
  after the rename.
- Both Envoy configs valid; 8 listeners (6 workers + aggregator + BERT) unchanged.
- Even request split through Envoy (within 1 request of equal on every 2-pod service).
- Kill one impersonation-en pod under load: 0 failed requests, 2 missing scores (same as EXPECTED.md).
- Redis down gives HTTP 502 and recovers when Redis is back.
- Live model versions in every answer: `impersonation_en_v5`, `money_en_v2c`, `v4` (urgency); Chinese workers `v1`.
- Prometheus: 3 of 3 targets up (2 aggregator pods, review). Grafana loads (HTTP 200), has the Prometheus datasource
  and the dashboard "ScamGuard: learning new scams", and its query `sum(scamguard_lines_total)` returned 3563, equal
  to Prometheus directly.

## 5. Tell teammates

- Mufliha: F2 needed no change (models.py already says impersonation_en baked 5; all six match the images). F3: AGG_MODE and
  AGG_MONEY_RULE_LINES are now in the Kubernetes manifest; the only other env difference is MODEL_VERSION="" (compose) vs
  unset (k8s), same meaning. Your `fl-inbox` answers HTTP 413 "update too large" to an EMPTY POST /fl/updates
  (a 400 would be clearer). `POST /fl` itself is 404 (the route is /fl/updates). Your `11-web.yaml` and web
  ConfigMaps deploy fine. The learning loop results are in T5 above: nothing blocked, A and B end UNSURE (BERT gate).
- Goutham (Workstream G): real-phone uploads to `fl-inbox` fail with 502: the inbox runs as `nobody` but
  `/inbox/pending` is created root-owned by the `fl-bridge` Job (Open problems 1). The federated run itself worked
  (3 rounds, money and impersonation phone models v2 published, urgency blocked by the release check).
- Goutham: Table VII (fast / bert_only / cascade) had no results anywhere (`git log --all -i --grep="mode\|bert_only"`
  shows only the AGG_MODE commit; no .md/.csv with results), so I measured it, see T6 below. Also: with two detector
  types down the aggregator now says SCAM via BERT (quorum not met), not UNSURE; please confirm that is intended.
  The aggregator's /health returns 503 (and :8082 refuses connections) while Redis is down, so kubelet restarts the
  aggregator pods during a Redis outage (2 restarts each in my test).
- Everyone: the HPA-added worker pods were being killed by the liveness probe under load (fixed with a startupProbe)
  and Redis was CPU-throttled at 40 users (limit raised to 1 CPU). Both are in demo/make_manifests.py.

## 6. Not tested, and why

- Phone on the same Wi-Fi reaching :8443 (T1 item 9): not tested, no phone in this session. Static facts: the
  forward listens on 0.0.0.0:8443 (netstat), two enabled inbound firewall rules allow 8443 and 8080-8082 for all
  profiles (`Get-NetFirewallRule`, display name "ms-resource:AppTitle"), and the Wi-Fi network is on the Public
  profile. `curl --cacert` to https://192.168.0.3:8443/api/healthz (the Wi-Fi IP) from the laptop itself: HTTP 200.
- 10.0.2.2 (emulator alias) cannot be reached from the laptop itself; it is in the certificate SANs.
- `curl` on this PC is the Git for Windows Schannel build: with `--cacert` it fails with
  CERT_TRUST_REVOCATION_STATUS_UNKNOWN because our demo CA has no CRL. All passing results below use
  `--ssl-no-revoke` (skips only the CRL lookup; the chain is still verified against ca.crt) and the Python `ssl`
  module (OpenSSL) which verified the chain without any flag. `-k` was never used.
- Android app on a device/emulator: not run (static check of the trusted CA only).
- The second retrain with `--tolerance 0.015` from LEARNING_LOOP.md: not run, nothing was blocked at the default.
- Rollback of the three Chinese models: nothing to roll back (no Chinese report, no release).
- One real-phone upload accepted by fl-inbox: tried, failed with 502 (Open problems 1), so not shown.
- Table V and Fig. 2 were measured through `kubectl port-forward` :8080 (as EXPECTED.md did), not through the Ingress.

## 7. Architecture facts for the diagram

Namespaces: `scamguard` (everything of ours), `ingress-nginx` (minikube addon), `kube-system`; `scamguard-fl` is
created only during a federated run (`demo/tools/federated.py run`).

`kubectl get deploy,sts,job,cronjob,svc,hpa,ingress,pvc,cm,secret -A` (names only, 06:11 UTC, with monitoring and
federated download/inbox applied; no StatefulSets anywhere):

```
ingress-nginx  Deployment  ingress-nginx-controller
scamguard      Deployment  aggregator bert envoy-lb fl-inbox gateway grafana impersonation-en impersonation-zh
                           model-download money-en money-zh prometheus redis review urgency-en urgency-zh web
ingress-nginx  Job         ingress-nginx-admission-create ingress-nginx-admission-patch
scamguard      CronJob     retrain-models   (schedule "0 3 * * 0", not suspended)
ingress-nginx  Service     ingress-nginx-controller ingress-nginx-controller-admission
scamguard      Service     aggregator aggregator-hl bert-hl envoy-lb fl-inbox gateway grafana impersonation-en-hl
                           impersonation-zh-hl model-download money-en-hl money-zh-hl prometheus redis review
                           urgency-en-hl urgency-zh-hl web
scamguard      HPA         bert impersonation-en impersonation-zh money-en money-zh urgency-en urgency-zh
scamguard      Ingress     fl-inbox gateway model-download web
scamguard      PVC         fl-inbox review-data
scamguard      ConfigMap   envoy-config grafana-dashboards grafana-provisioning prometheus-config web-files web-nginx web-scripts
ingress-nginx  ConfigMap   ingress-nginx-controller tcp-services udp-services
scamguard      Secret      gateway-tls review-key          ingress-nginx Secret ingress-nginx-admission
(federated run only, namespace scamguard-fl: Service fl-server, Job fl-server, Job fl-phones; loadgen Job + 2 ConfigMaps in scamguard when demo/k8s/loadgen is applied)
```

Ingress paths (host `scamguard.test` and no-host rule, both the same; TLS secret `gateway-tls`):

| Ingress | path | backend |
|---|---|---|
| gateway | `/api(/|$)(.*)` rewritten to `/$2` | gateway:8080 |
| web | `/` | web:8080 (nginx, the browser app) |
| model-download | `/models` | model-download:8000 |
| fl-inbox | `/fl` | fl-inbox:8000 (uploads go to `/fl/updates`) |

Images and tags (running pods): scam/aggregator:demo (2), scam/bert:demo (2), scam/gateway:demo (1),
scam/impersonation_en:demo (2), scam/impersonation_zh:demo (1), scam/money_en:demo (1), scam/money_zh:demo (1),
scam/urgency_en:demo (1), scam/urgency_zh:demo (1), scam/review:demo (1), scam/model-download:demo (3: 2 download +
1 inbox), scam/retrain:demo (CronJob), scam/federated:demo (run only); public: envoyproxy/envoy:v1.31-latest,
redis:7-alpine, nginx:1.27-alpine, prom/prometheus:v3.1.0, grafana/grafana:11.4.0.

Replicas / HPA range: aggregator 2 (fixed), gateway 1 (fixed), envoy-lb 1, redis 1, review 1, web 1, model-download 2,
fl-inbox 1, prometheus 1, grafana 1; HPAs at 50% CPU: bert 2-4, impersonation-en 2-4, impersonation-zh 1-4,
money-en 1-4, money-zh 1-4, urgency-en 1-4, urgency-zh 1-4.

Live model versions: every utterance answer carries `model_versions`: impersonation `impersonation_en_v5`, money
`money_en_v2c`, urgency `v4` (English); Chinese workers `v1`. `GET /models/manifest` over HTTPS returned
`{"models": {}}` (HTTP 200) before the federated run: the phone models are published only by a federated run; see T8.

Gateway routes (`backend/gateway/src/main.rs`): `POST /v1/utterance`, `POST /v1/call/end` (body needs `ended_by` in
script_finished | user_hung_up | user_discarded), `POST /v1/report`, `POST /v1/verify`, `POST /submit-transcript`,
`GET /healthz`. Through the Ingress they are under `/api/`.

Compose-only components: `edge` (nginx on :8088, the compose stand-in for the Ingress), `model-service` (download +
inbox in ONE container), `fl-bridge`, and the `federated` profile services fl-server / fl-phones.
Cluster-only components: ingress-nginx + TLS Secret `gateway-tls`, `envoy-lb` (compose services talk gRPC directly),
headless `*-hl` Services, the 7 HPAs, `web` Deployment with its 3 ConfigMaps, `model-download` and `fl-inbox` as
separate Deployments with the `fl-inbox` PVC, `review-data` PVC, `review-key` Secret, `retrain-models` CronJob,
Prometheus + Grafana, the `loadgen` Job, the `scamguard-fl` namespace (Flower server Job + 10-phone Indexed Job).

## 8. Open problems

1. **Real-phone uploads to `fl-inbox` fail with HTTP 502 (Workstream G, not fixed by me).** A valid `fl-update`
   document (262144 zero floats for urgency, base v1, 3 lines; 1.4 MB plain and 1.5 KB gzipped) sent to
   `POST https://127.0.0.1:8443/fl/updates` at 08:49:58 and 08:50:31 UTC got 502 from nginx ("upstream prematurely
   closed connection"). The inbox pod log shows `PermissionError: [Errno 13] Permission denied:
   '/inbox/pending/<ts>_deadbeef0001.json.gz'`. Cause: the inbox container runs as uid 65534 (nobody), but
   `/inbox/pending` on the PVC is `drwxr-xr-x root root`, created at 08:34 by the `fl-bridge` Job (which runs as
   root) before the inbox ever wrote there. Fix options for the owner of `demo/make_federated.py` / `inbox.py`: an
   `fsGroup`/`runAsUser` securityContext shared by fl-inbox and fl-bridge, or create the folders with mode 0777.
   Until then "one upload accepted by fl-inbox" (T8) is NOT achieved: 0 uploads accepted, `federated.py inbox` shows
   pending [] used []. The federated run itself worked (bridge found no uploads, which is correct).
2. **An empty `POST /fl/updates` answers 413 "update too large"** (download_service.py treats Content-Length 0 as too
   large). A 400 "empty body" would be clearer. Teammate's file.
3. **Aggregator restarts when Redis is down.** `/health` returns 503 and :8082 refuses connections while Redis is
   unreachable, so the liveness probe restarts the aggregator pods (2 restarts each during the Redis-down test). They
   recover when Redis is back, but the restarts add seconds to the recovery (17.7 s measured). Design question for
   the aggregator owner: liveness should probably stay 200 while readiness goes 503.
4. **Quorum rule vs BERT.** With two detector types down (`quorum_met=false`) the cascade still returns SCAM because
   BERT alone decided (combined = BERT 0.94-0.99). The report says UNSURE "not enough data". Confirm which is intended.
5. **HPA scales up after the load stops.** In every heavy run the peak replica count was reached 35-40 s after the
   load ended (metrics lag), and new pods' model-loading CPU counted as utilization. Run 1 also showed the
   liveness-kill loop (fixed). Not wrong, but Fig. 2 should say the peak follows the load by about half a minute.
6. **BERT time-outs at 40 users.** 25-29% of all requests (about half of the lines sent to BERT) hit the 300 ms
   BERT budget in the heavy runs even with BERT at 4 pods; the request still succeeds without BERT. Fine for the
   demo, but the report should not claim BERT answers every line under heavy load.
7. **TOO EARLY on 7 of 13 scam calls (cascade).** Red at the first scored line after the 15 s warm-up, well before
   the key sentences in expected.json. Known since 2026-10-26 (EXPECTED.md section 5); still true. Not a demo bug.
8. **Old debug APK on disk (2026-10-27) has no bundled demo CA** and would reject HTTPS; rebuild the app before the
   demo (T1 item 8).
9. **The `bert_only` replay took 65 minutes** of wall time for the same 23 calls that took 20-25 minutes in the
   other modes, with no slow requests recorded; cause unknown (possibly the laptop, not the cluster).
10. `tools/testing/README.md` still says "Not yet run against the real stack: replay.py was only tested against a
   mock gateway" and lists 14 calls; it now has 23 and has been run against the cluster (this file). Teammate's file.
