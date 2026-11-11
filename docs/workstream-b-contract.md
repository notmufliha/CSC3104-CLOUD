# Workstream B contract: knowing when a call ends

Status: **done** (Steps 0-5, 2026-11-05). C3 and C9: the recommended option. Room 2.8.5 + KSP 2.3.12 work with AGP 9.4.1 built-in Kotlin. Results: section 8.
Owner: Rhaylene. Brief: "Rhaylene's workstreams: B and G", Section 1.
Branch: `workstream-b` (same commit as `main` at `aaa455d`).

This is the brief's contract adapted to the code as it is today. Section 7 lists where the code and the
brief disagree, and what this contract does about it.

---

## 1. What exists today (the parts B touches)

| Piece | File | What it does now |
|---|---|---|
| Proto | `backend/proto/scam_detect.proto` | `AggregatorService { rpc Aggregate }`. `enum Verdict { VERDICT_UNSPECIFIED=0; SAFE=1; UNSURE=2; SCAM=3; }`. Used by the gateway, aggregator, all 6 Python workers, BERT and `tools/zh_check.py`. |
| Gateway | `backend/gateway/src/main.rs`, `calls.rs` | Actix-web. `POST /v1/utterance`, `/submit-transcript`, `/v1/report`, `/healthz`. Keeps per call **in RAM**: the seqs already scored, the aggregator's last answer and the last 5 caller lines (for BERT). Forgets a call after `CALL_TTL_SECS` (1800 s) idle. **1 replica** (k8s; sticky state). |
| Aggregator | `backend/aggregator/src/main.rs` | tonic gRPC `Aggregate` + HTTP `/health`, `/metrics`. **2 replicas** in k8s (`demo/k8s/04-aggregator.yaml`). |
| Redis memory | `backend/aggregator/src/store.rs` | `call:<call_id>` hash: `started_at_ms, window, streak, red, caller_lines, verdict, combined, version`. `calllog:<call_id>` list: one JSON verdict-log entry per caller line (scores only). Both `EXPIRE AGG_CALL_TTL_SECS` (3600) on every save, via the `SAVE_LUA` script (optimistic `version` check). |
| Verdict log | `main.rs` + `store::log_entry` | One entry per caller line, written to **both** the `calllog:<id>` list and stdout (`tracing` target `"verdict"`). Never text. |
| BERT client | `backend/aggregator/src/bert.rs` | `SecondOpinion` → `BertService.ScoreContext(lines, language)`, 300 ms timeout, needs ≥ 3 lines. Text passes through and is never stored. |
| App end of call | `android-app/.../ui/CallViewModel.kt` | Script finished → waits ≤ 5 s for the last answers, cancels them, waits 1.5 s → `endCall()`. Hang up → `endCall()`. `endCall()` sets `Phase.ENDED` and saves a `HistoryEntry` to SharedPreferences. **Nothing is sent to the server.** Back during a call → `vm.reset()` (no end, nothing saved). |
| App history | `data/AppPrefs.kt`, `ui/OtherScreens.kt` (`HistoryScreen`) | SharedPreferences key `history` (JSON list, ≤ 100 rows: time, verdict, topReason, combinedScore). |
| Build | `android-app/gradle/libs.versions.toml` | AGP 9.4.1 with **built-in Kotlin** (no `kotlin-android` plugin applied), Kotlin 2.2.10, Compose BOM 2025.09.00. No Room or KSP yet. `allowBackup="false"` is already set. |

Already done before B: the call key expires after 1 h (`AGG_CALL_TTL_SECS=3600`). Task B.4's "1 h expiry"
therefore only needs the new keys below to get a TTL too.

---

## 2. HTTP: app → gateway

### `POST /v1/call/end` (new)

```json
{
  "call_id": "8f1c...",
  "event": "end",
  "ended_by": "script_finished | user_hung_up | user_discarded",
  "last_seq": 16,
  "caller_lines": [{"seq": 1, "text": "..."}, {"seq": 3, "text": "..."}]
}
```

- `call_id`: required, 1-128 characters (same rule as `/v1/utterance`).
- `event`: optional. If given it must be `"end"`.
- `ended_by`: required. One of `script_finished`, `user_hung_up`, `user_discarded`. `silence` is
  server-only, so the app can't send it (400).
- `last_seq`: optional, default 0. Only logged.
- `caller_lines`: optional. At most 200 lines of at most 2000 characters each (the same caps as
  `/v1/report`); the body limit is 512 KB. Passed to the review hook and then dropped. **Never stored, never logged.**

Response **200**:

```json
{
  "call_id": "8f1c...",
  "final_verdict": "SCAM",
  "call_score": 0.91,
  "live_verdict": "SCAM",
  "review": "fallback",
  "top_window_seqs": [],
  "reason": "Asked for your OTP or money",
  "ended_by": "script_finished",
  "already_ended": false,
  "latency_ms": 42
}
```

| Field | Meaning |
|---|---|
| `final_verdict` | `SAFE` / `UNSURE` / `SCAM`, or `UNKNOWN` when the server never saw a caller line of this call (proto `VERDICT_UNSPECIFIED` → `"UNKNOWN"`). |
| `call_score` | From the review when it ran; otherwise the live `combined` score from Redis (0 if unknown). |
| `live_verdict` | The verdict after the last caller line (`call:<id>.verdict`), or `UNKNOWN`. |
| `review` | `"bert"` if the end-of-call review (C.6) answered; `"fallback"` otherwise. Until C exists, always `"fallback"`. |
| `top_window_seqs` | The seqs of the riskiest window of 5 (C.8). Empty with the fallback. |
| `reason` | The top signal's fixed reason text (the same text as the live `reasons`, e.g. "Asked for your OTP or money"). Empty when SAFE or UNKNOWN. Never transcript text. |
| `ended_by` | The value stored by the **first** end, so a repeat end, or an end that comes after the silence timeout, shows the original value. |
| `already_ended` | *Addition to the brief.* `true` when this call had already ended (a repeat end, or a silence end). Lets the app and tests see that the call was ended only once. |
| `latency_ms` | Time spent in the gateway. |

Rules:
- **Idempotent.** The first end stores its result (scores only) in the tombstone. Every later end for the same
  `call_id` returns that stored result with `already_ended: true`, and the review doesn't run again.
- **Unknown or expired `call_id`** → 200, `final_verdict` = the gateway's last known verdict if it has one,
  else `"UNKNOWN"`, with `review: "fallback"`. Never a 500.
- **Aggregator down or slow** (more than `END_CALL_TIMEOUT_MS`, default 2000) → **200** with the gateway's last
  known verdict, `review: "fallback"`, plus `"error": "aggregator unavailable"`. The app shows its live verdict
  either way. A malformed request is the only case that gets a non-200 answer (400).
- `user_discarded` runs the same path (tombstone, clean-up, a log row), and the app ignores the answer.

### `POST /v1/utterance` after the end (B.6)

The existing fields don't change. Two fields are **added** to every response:

| Field | Normal line | Line after the end |
|---|---|---|
| `ignored` | `false` | `true` |
| `reason` | absent | `"call_ended"` |

A late line gets a 200 with the same body shape as a receiver/duplicate line (`scored: false`, the call's
last verdict, no worker scores) plus `"ignored": true, "reason": "call_ended"`. The gateway logs a warning
`late line after call ended` with `call_id` and `seq` only. **The workers are not called and the aggregator
doesn't score the line.**

How a late line is caught:
1. **Gateway, fast path:** after an end through `/v1/call/end`, the gateway marks the call as ended in its own
   RAM (and drops the call's stored context lines and seqs). Later lines are answered straight away and no
   worker is called.
2. **Aggregator, backstop:** this covers silence ends, a gateway restart, and a line that was already
   in flight when the end arrived. `SAVE_LUA` checks the tombstone in the same atomic step as the save and
   refuses to write. `Aggregate` then returns `call_ended = true` (a new proto field, see below) and the gateway
   turns that into `ignored: true`. In that case the workers already ran, but the line is never added to the
   call's memory and never logged as a verdict.

---

## 3. gRPC: gateway → aggregator (additive only)

```proto
service AggregatorService {
  rpc Aggregate(AggregateRequest) returns (AggregateResponse);
  rpc EndCall(EndCallRequest) returns (EndCallResponse);           // new (B.2)
}

// One caller line sent with the end message, for the end-of-call review. Never stored.
message CallerLine {
  int64 seq = 1;
  string text = 2;
}

message EndCallRequest {
  string call_id = 1;
  string ended_by = 2;          // script_finished | user_hung_up | user_discarded | silence
  int64 last_seq = 3;
  repeated CallerLine caller_lines = 4;
  string language = 5;          // addition: "en" | "zh" | "" (passed to the review; BERT needs it)
}

message EndCallResponse {
  string call_id = 1;
  Verdict final_verdict = 2;    // VERDICT_UNSPECIFIED = never saw a caller line ("UNKNOWN" over HTTP)
  float call_score = 3;
  Verdict live_verdict = 4;
  string review = 5;            // "bert" | "fallback"
  repeated int64 top_window_seqs = 6;
  string reason = 7;
  string ended_by = 8;
  bool already_ended = 9;       // addition
}

message AggregateResponse {
  // ... fields 1-11 unchanged ...
  bool call_ended = 14;         // addition: this call has ended; the line was NOT scored (B.6). 14: A took 12/13
}
```

This reuses the existing `Verdict` enum. The new message names follow the file's style (`XxxRequest` /
`XxxResponse`). The Python workers and BERT don't implement `AggregatorService`, so the new rpc doesn't
affect them. Their generated `_pb2` code is rebuilt in their Docker images, and Step 5 checks that they still build.

---

## 4. Redis (aggregator)

| Key | Type | Written by | TTL | Contents |
|---|---|---|---|---|
| `call:<id>` | hash | `Aggregate` (as now) + new field `last_line_ms` | `AGG_CALL_TTL_SECS` (3600) | Unchanged, plus `last_line_ms` (aggregator clock). **Deleted on end.** |
| `calllog:<id>` | list | `Aggregate` (as now) + **one final `"event":"end"` row** | 3600 | Per-line verdict log. See conflict C3. |
| `callend:<id>` *(new)* | hash | `EndCall` / silence task | `AGG_END_TOMBSTONE_SECS` (3600) | The tombstone and the stored end result: `final_verdict, call_score, live_verdict, review, top_window_seqs (JSON), reason, ended_by, ended_at_ms`. Scores and fixed reason text only. |
| `calls:active` *(new)* | sorted set | `Aggregate` (`ZADD id last_line_ms`), removed on end | none (entries removed on end, or when they are found already expired) | Index the silence task scans, so it never has to `SCAN call:*`. |

The tombstone is named `callend:<id>` instead of the brief's `call:{id}:ended`. That matches the existing
`calllog:<id>` style and keeps `call:*` meaning "live call memory" only. The brief only gave its name as an example.

### End handling (B.3), one function used by `EndCall` and the silence task

1. **Claim:** `HSETNX`-style claim in a Lua script: if `callend:<id>` exists, return the stored result
   (`already_ended = true`). Otherwise create it with `ended_by` and `ended_at_ms` and continue. With 2 aggregator replicas
   and an app end racing a silence end, exactly one of them runs steps 2-5.
2. **Read** `call:<id>` (live verdict, combined, window) for the fallback.
3. **Review hook** `end_of_call_review(call_id, caller_lines, language)` with a timeout of
   `AGG_END_REVIEW_TIMEOUT_MS` (1500). For now it **always returns `None` ("not available")**, so the
   fallback is used. Workstream C fills it in later (C.6, C.8). It also returns `None` on a timeout, an error or when there are no lines.
4. **Fallback:** `final_verdict = live verdict`, `call_score = combined`, `reason` = the reason text of the
   highest signal in the window that is ≥ `AGG_REASON_THRESHOLD` (empty if SAFE), `top_window_seqs = []`,
   `review = "fallback"`. With no `call:<id>` at all: `VERDICT_UNSPECIFIED`, 0, `""`.
5. **Save and clean up** in one Lua call: write the result into `callend:<id>` (+ `EXPIRE`), `DEL call:<id>`,
   `ZREM calls:active <id>`, `RPUSH calllog:<id>` the end row.
6. **Log** one `target: "verdict"` line, `event = "end"`:
   `{call_id, event:"end", time_ms, final_verdict, call_score, live_verdict, ended_by, review, review_ms}`.
   Scores only. Count it in `/metrics` (`calls_ended_total{ended_by,review}`).

### Silence timeout (B.4)

- A background task in every aggregator replica wakes up every `AGG_SILENCE_CHECK_SECS` (default 5) and runs
  `ZRANGEBYSCORE calls:active -inf <now - AGG_SILENCE_SECS*1000> LIMIT 0 100`, then runs the end handling for
  each id with `ended_by = "silence"` and no lines (so `review = "fallback"`). The claim in step 1 makes sure two
  replicas never both end the same call.
- "No sentence" means no **caller** line reached the aggregator. Receiver lines never reach the aggregator.
  The longest gap between caller lines in the 14 demo scripts is 13.1 s, so 30 s never fires during a demo.
- Settings (env vars, also added to `docker-compose.yml`, `demo/make_manifests.py` → `demo/k8s/04-aggregator.yaml`):

| Variable | Default | Meaning |
|---|---|---|
| `AGG_SILENCE_SECS` | 30 | No caller line for this long → the call ends with `ended_by = "silence"`. 0 = off. |
| `AGG_SILENCE_CHECK_SECS` | 5 | How often each replica looks for silent calls. |
| `AGG_END_REVIEW_TIMEOUT_MS` | 1500 | Time limit for the end-of-call review hook. |
| `AGG_END_TOMBSTONE_SECS` | 3600 | How long `callend:<id>` is kept (late lines are ignored for this long). |
| `AGG_CALL_TTL_SECS` | 3600 | *(exists)* Last-resort expiry of `call:<id>` / `calllog:<id>`. |

Gateway: `END_CALL_TIMEOUT_MS` (default 2000), the time limit for the `EndCall` gRPC request.
It must be above the review timeout plus Redis time.

---

## 5. App (B.5, B.7)

### End flow (B.5)
- **Script finished:** the app already waits ≤ 5 s for the last answers and then cancels them. It then sends
  `/v1/call/end` (`script_finished`) with every caller line (`seq`, raw text), shows **"Final review…"** for
  up to 3 s, and then shows the summary.
- **Hang up / Hang up now:** cancels playback and in-flight lines, then sends `user_hung_up`, and the rest is the same.
- **Back while IN_CALL:** sends `user_discarded` fire-and-forget (on an app-wide scope so it isn't cancelled
  with the screen), shows no summary and saves nothing. Back while RINGING does nothing, as now (no call id yet).
- **Pasted text:** sends `script_finished` so the server clears its memory. The summary is shown and history
  isn't saved (as now).
- **Summary screen:** the final verdict in large text, the live verdict in small text, the reason, the bubbles in
  `top_window_seqs` highlighted as "the conversation turned risky here". With `review = "fallback"`, a failed
  request or a 3 s timeout, it shows the live verdict with "Final review unavailable". H's Report and
  "This was fine" buttons stay as they are.
- **`ignored: true` from `/v1/utterance`:** treated as "no change" (the bubble stops pending, and the verdict and
  error are not touched).

### Dashboard (B.7)
- Room and KSP are added via `libs.versions.toml`. AGP 9 uses built-in Kotlin. If KSP doesn't work with it,
  I'll stop and report, as the brief says.
- `CallRecord` and `ScorePoint` follow the brief. `ScorePoint.secondsIntoCall` comes from the phone's own
  clock when each answer arrives. The score fields come from each line's `scores` and `combined_score`.
- The Dashboard replaces History on Home. The existing SharedPreferences `history` rows are migrated once
  (as `CallRecord` with `review = "migrated"` and no score points), then the key is removed.

---

## 6. Tests planned

| Step | Tests |
|---|---|
| 1 Gateway | Request validation (bad `ended_by`, `event`, too many lines → 400), the tombstone in the gateway's RAM makes `/v1/utterance` answer `ignored` without a worker call, `ignored: false` on normal lines, UNSPECIFIED → `"UNKNOWN"` mapping, the fallback answer when the aggregator is unreachable. Uses actix `test::init_service` with an unreachable aggregator address (no Docker needed). |
| 2 Aggregator | Pure: the fallback reason, settings parsing and validation. Redis (run when `REDIS_TEST_URL` is set, skipped otherwise, run in Step 5 against Compose's Redis): end → `call:<id>` gone and the tombstone exists; a second end is idempotent (same result, `already_ended`); a late `apply` is refused (`call_ended`); the silence task ends a call idle for more than the silence time (test uses a 1 s setting); two ends racing → one review. |
| 3-4 App | `bash gradlew assembleDebug` after each step; JVM unit tests for the summary logic and the history migration mapping. |
| 5 End to end | `docker compose up -d --build`, a demo call, `redis-cli EXISTS call:<id>` → 0, a late `curl` → `ignored`, summary in under 2 s, `cargo test` in the gateway and aggregator, and all Python images still build. |

---

## 7. Where the brief and the code disagree

| # | Brief says | Code reality | What this contract does |
|---|---|---|---|
| C1 | Tombstone `call:{id}:ended`. | Existing style is `calllog:<id>`. | `callend:<id>`. Only the name changes. |
| C2 | Unknown call → `final_verdict: "UNKNOWN"`. | The proto `Verdict` enum has no UNKNOWN. | gRPC uses `VERDICT_UNSPECIFIED`, and the gateway turns it into `"UNKNOWN"` over HTTP. No enum change. |
| C3 ✅ | "Delete the call's Redis memory. Keep only the tombstone." | `calllog:<id>` is the per-line verdict log (no text). `tools/aggregator_replay.py` reads it after a replay, and the README and ARCHITECTURE docs point to it. | **Decided:** delete `call:<id>` (the memory), and **keep `calllog:<id>`** until its existing 1 h TTL with the final end row added. Then the "verdict log row" from B.3 lives where the other rows are, and the replay tool keeps working. The alternative is to delete it too, but then the per-line log disappears at hang-up and `aggregator_replay.py` breaks. |
| C4 | The gateway or the aggregator checks the tombstone for late sentences. | The gateway has no Redis connection. Silence ends happen only in the aggregator. | Both: the gateway uses its own RAM for app-sent ends (no worker calls), and the aggregator checks atomically in `SAVE_LUA` as the backstop. That needs one additive proto field, `AggregateResponse.call_ended = 14` (12 at first; renumbered when merging main, where Workstream A took 12 and 13). |
| C5 | Late sentence: "never scored". | For silence-ended calls the gateway doesn't know the call ended, so the 3 workers still run before the aggregator refuses the line. | The line is never added to the call's memory, the verdict log or the verdict. The worker calls are wasted work, not scoring. Removing them would need Redis in the gateway, which goes against "keep gateway changes small". |
| C6 | `/v1/utterance` late response is `{"ignored": true, "reason": "call_ended"}`. | Rule: "existing fields stay the same". | The full existing body (as for a receiver line) **plus** `ignored`, `reason`. |
| C7 | Contract has no `language` or `already_ended`. | BERT's `ScoreContext` needs `language`. Idempotency is easier to test with a flag. | Both are added (additive, optional). The app sends no `language` (the gateway works it out from the lines with the existing 30 % rule, or from the script's `lang`). |
| C8 | 30 s silence. | 2 aggregator replicas in k8s, and no index of active calls. | A `calls:active` sorted set, plus an atomic claim so only one replica ends a call. |
| C9 ✅ | The app's "signals fired" for the dashboard. | **Bug already on main:** the app reads `call_scores`, but the gateway sends `recent_scores`. So `callScores` is always empty, chips never light, and the summary or history reason always falls back to "several small warning signs together". | **Decided:** fix it in Step 3: `@SerializedName("recent_scores", alternate = ["call_scores"])`. It's a one-line change in the app only, but the in-call chips will start lighting up, which is visible behaviour in the demo. |
| C10 | The brief is at `docs/rhaylene-workstreams.md`. | That file isn't in the repo (it was given to me as an attachment). | Nothing. You may want to add it to `docs/` in your commit. |
| C11 | C.6 hook. | `origin/workstream-c-f` (Goutham) touches `aggregator/src/config.rs`, `main.rs`, `bert.rs`, `policy.rs` but has no C.6. | The hook is a separate function in a new `aggregator/src/end.rs`. New settings go at the **end** of `Settings` so the later merge with c-f is easy. `policy.rs` is not touched. |
| C12 | Live mic End button. | Live mic is not in the app. | Skipped, as the brief says. |
| C13 | (not in the brief) | In k8s the gateway reaches the aggregator through Envoy (`envoy-lb:50067`), whose route cuts **every** aggregator call at 0.75 s. That would kill EndCall's 1.5 s review. | `demo/make_envoy_config.py` adds an exact-path route for `/scam_detect.AggregatorService/EndCall` with a 2.20 s timeout (END_CALL_TIMEOUT_MS + the usual 200 ms margin), matched before the catch-all. The generated YAML was regenerated, not hand-edited. Envoy's retry on connect failure is safe because EndCall is idempotent. |
| C14 | (not in the brief) | `/v1/call/end` also accepts an optional `lang` (same as `/v1/utterance`). | Additive. Without it the language is worked out from the caller lines. |

---

## 8. Step 5 results (Docker Compose, 2026-11-05)

| Check (brief's "done when") | Result |
|---|---|
| Ending a demo call shows the final verdict within 2 s | `POST /v1/call/end` answered in 3-8 ms (fallback review); on the Pixel 8 emulator the summary appeared straight after "Hang up now" (OkHttp: 7 ms). A real C.6 review can add up to `AGG_END_REVIEW_TIMEOUT_MS` (1.5 s). |
| The call's Redis key is gone | `redis-cli EXISTS call:<id>` → 0 after script end, hang-up, discard and silence; `callend:<id>` kept with TTL 3600. |
| A sentence after the end is ignored and logged | `{"ignored": true, "reason": "call_ended", "scored": false}`, both from the gateway's memory and (after a silence end) from the aggregator's tombstone; logged as `late line after call ended`; the call is not recreated. |
| 30 s of silence ends a call | Ended between 30 and 35 s, `ended_by = silence`, `review = fallback`. |
| Dashboard shows past calls from Room, no transcripts | List, top reasons, call detail with chart and fired signals; "Save the riskiest sentence" off by default; Back during a call saved nothing. |
| No regressions | gateway `cargo test` 16/16, aggregator 61/61 (with Redis), `tools/gateway_test.py` 28/28, `tools/aggregator_replay.py` 15/15, app unit 18/18, app instrumented 4/4, all 11 Compose images build with the new proto. |

Re-check by hand:

```bash
docker compose up -d --build
curl -s localhost:8080/v1/utterance -H 'content-type: application/json' \
  -d '{"call_id":"demo-b","seq":1,"speaker":"caller","text":"This is the police, give me your OTP","lang":"en"}'
docker compose exec redis redis-cli EXISTS call:demo-b            # 1
curl -s localhost:8080/v1/call/end -H 'content-type: application/json' \
  -d '{"call_id":"demo-b","ended_by":"user_hung_up","caller_lines":[{"seq":1,"text":"This is the police, give me your OTP"}]}'
docker compose exec redis redis-cli EXISTS call:demo-b            # 0
curl -s localhost:8080/v1/utterance -H 'content-type: application/json' \
  -d '{"call_id":"demo-b","seq":2,"speaker":"caller","text":"hello?","lang":"en"}'   # "ignored": true
```
