# ScamGuard demo — Shreya's guide

This runs the whole backend (gateway + aggregator + 6 workers + BERT + Redis + Envoy) on Kubernetes, on
your own laptop, for free, using [minikube](https://minikube.sigs.k8s.io/) (a tiny one-node Kubernetes
cluster that runs inside Docker — no cloud account, no cost). `docker compose up` still works exactly as
before and is the easiest way to test on your own laptop day-to-day; this `demo/` folder is the
*presentation* version: multiple replicas, autoscaling, pod-kill resilience — the distributed-systems
story the course wants to see.

Read `demo/ARCHITECTURE.md` once if you want to understand *why* it's built this way (short version: a
proxy called Envoy sits in front of every scalable service so load actually spreads across pods — plain
Kubernetes doesn't do that for gRPC on its own). Read `demo/EXPECTED.md` for exactly what was tested and
measured already, with dates and commands, so you know what's proven and what you should double-check.

Two add-ons have their own guides: [`STABLE_ADDRESS.md`](STABLE_ADDRESS.md) (Workstream E: one HTTPS
address for the phone) and [`LEARNING_LOOP.md`](LEARNING_LOOP.md) (Workstream H: report a call, label it,
retrain, release check, rolling update; all through `python demo/tools/learning.py`). `up` also builds the
review service and the retraining image, so the stack is now 11 images.

## One-time setup (do this once, whenever)

Install, if you don't have them already:

- **Docker Desktop**: https://www.docker.com/products/docker-desktop/ — then open it once and make sure it says "running".
- **minikube**: https://minikube.sigs.k8s.io/docs/start/ (Windows: download `minikube-windows-amd64.exe`,
  rename to `minikube.exe`, put it somewhere on your `PATH`, e.g. a `bin` folder in your user profile).
- **kubectl**: https://kubernetes.io/docs/tasks/tools/#kubectl (Docker Desktop on Windows/Mac already
  ships one — check with `kubectl version --client` first).

`demo/up.sh` (Mac/Linux/WSL/Git-Bash) and `demo/up.ps1` (plain Windows PowerShell) check for all three and
tell you exactly what's missing and where to get it, so you don't strictly need to do this checklist by
hand — just try running `up.sh`/`up.ps1` and follow what it says.

### Laptop size

`up.sh`/`up.ps1` look at what Docker itself reports (`docker info`) and pick sensible minikube/HPA settings
automatically — you don't need to compute anything. Rough guide:

- **Comfortable**: 6+ CPUs, 10+ GiB → full settings (HPA up to 4 replicas, BERT up to 2).
- **Small laptop**: under 6 CPUs or under 10 GiB → the script switches to a "lite" profile on its own
  (HPA up to 2, BERT stays at 1, aggregator stays at 1 replica, fewer simulated load-test users) and tells
  you it did this.
- **Under 8 GiB total**: on Windows, raise WSL2's memory limit. Create/edit
  `%UserProfile%\.wslconfig`:
  ```
  [wsl2]
  memory=8GB
  processors=4
  ```
  then run `wsl --shutdown` in a terminal and restart Docker Desktop. On Mac, raise it in Docker Desktop →
  Settings → Resources instead.

## Day before the presentation (build everything once)

```bash
./demo/up.sh          # Mac/Linux/WSL/Git-Bash
```
```powershell
.\demo\up.ps1          # Windows PowerShell
```

This builds all 9 images (gateway, aggregator, 6 workers, BERT), starts minikube, loads the images into
it, and deploys everything. **The BERT image needs internet** (it downloads the real trained model from
Hugging Face at build time — nothing to set up by hand, see `demo/ARCHITECTURE.md`). If that download
fails (blocked network, Hugging Face down), the script tells you and gives options — see the
troubleshooting table below.

It is safe to run again — re-running it rebuilds (fast, Docker caches unchanged layers) and re-applies
everything without breaking what's already there.

Takes a few minutes the very first time (longer if any image has to build from scratch rather than hit
Docker's cache — the Chinese workers each train a small model during their build, about a minute each).

When it finishes, it prints `kubectl get pods,hpa` — everything should say `Running` / `1/1` or `2/2`
(aggregator and impersonation-en start with 2 pods on purpose).

## 1 hour before (start it up, sanity-check it)

If your laptop was off or you closed Docker Desktop, just run `./demo/up.sh` / `.\demo\up.ps1` again —
it reuses everything already built, so this is fast.

Then, in **3 separate terminals**:

1. **Expose the Ingress** (leave this running the whole time):
   ```bash
   ./demo/expose.sh        # or .\demo\expose.ps1
   ```
   This prints the HTTPS address to save in the app (`https://10.0.2.2:8443/api/` for the emulator,
   `https://<Wi-Fi IP>:8443/api/` for a phone). It goes through the Ingress, not a gateway pod, so the address
   keeps working when the gateway pod or the whole cluster restarts. First time only, install
   `demo/tls/ca.crt` on the device. Details, diagram and checks: [`demo/STABLE_ADDRESS.md`](STABLE_ADDRESS.md)
   (Workstream E). Run `python demo/tools/address_check.py` to confirm it answers over HTTPS.

   **Plain-HTTP fallback** (the steps below use it): `./demo/port-forward.sh` (or `.ps1`) forwards
   `http://…:8080` straight to one gateway pod. If that pod restarts, the script reconnects on its own.

2. **Check it's alive**:
   ```bash
   curl http://127.0.0.1:8080/healthz             # should print {"status":"ok",...}
   python3 demo/tools/demo_flow.py                 # plays 4 real scripted calls, prints the verdicts
   ```
   Use `127.0.0.1`, not `localhost`: on Windows, resolving `localhost` can add 400-2000ms to **every**
   request (confirmed on this laptop: `127.0.0.1` ~50-110ms, `localhost` ~500-2300ms for the exact same
   request) — enough to make a live demo look sluggish or even blow the gateway's 2-second budget. The
   tools in `demo/tools/` already default to `127.0.0.1`; just avoid typing `localhost` yourself on stage.
   `demo_flow.py`'s table is "what the app should show" — if a line's `verdict` looks wrong, something is
   genuinely broken; if it just looks aggressive (e.g. turns SCAM earlier than you'd expect), see
   `demo/EXPECTED.md` section 5 — this is a known, measured model-tuning issue, not a demo bug.

3. **Phone/emulator check**:
   ```bash
   ./demo/tools/phone_check.sh      # Mac/Linux/WSL/Git-Bash
   ```
   ```powershell
   .\demo\tools\phone_check.ps1      # Windows
   ```
   Confirms the gateway is reachable the way a real phone would reach it (by your Wi-Fi IP, not
   `localhost`). Do this **before** going on stage, not during.

### Android Studio — emulator (do this first; it's simpler than a real phone)

1. Make sure `demo/port-forward.sh`/`.ps1` is running (step 1 above) — the emulator talks to
   `http://10.0.2.2:8080/`, which is its own alias for your laptop's `localhost:8080`, exactly the port
   `port-forward` exposes. (`docker compose up` also works here unchanged, since it publishes the same
   port — you don't need Kubernetes running just to test the app.)
2. Open `android-app/` in Android Studio, let Gradle sync, run the `app` configuration on an emulator.
3. In the app: Home → "Choose a call script..." → pick one (e.g. `scam_en_10_gov_official`) → Answer.
4. **Expected**: the app plays the lines with realistic pauses; partway through, a banner should show
   `UNSURE` then `SCAM` with a reason ("Claims to be police, a bank or the government" / "Asked for your
   OTP or money") and an action ("Hang up now...").

**This step itself (the Android app + emulator) was not run in this repo's automated testing session** —
there's no Android Studio in that environment. The backend side it depends on (the gateway, the exact
JSON it returns) was fully tested — see `demo/EXPECTED.md`. Please run through this once yourself before
the big day.

### Real phone (after the emulator works)

1. Phone and laptop on the **same Wi-Fi**. (Venue/conference Wi-Fi often blocks device-to-device traffic
   — if so, turn on your **phone's hotspot** and join it from your laptop instead; `port-forward.sh`/`.ps1`
   will print whatever IP your laptop gets on that network.)
2. In the app's Settings, enter `http://<the IP port-forward.sh printed>:8080/`.
3. **Windows Firewall** may block the connection the first time. Run once, as Administrator:
   ```powershell
   New-NetFirewallRule -DisplayName "ScamGuard demo 8080" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow -Profile Private
   ```
4. **USB alternative** (sidesteps Wi-Fi entirely, if `adb` is installed): `adb reverse tcp:8080 tcp:8080`,
   then use `http://localhost:8080/` in the app, same as the emulator.
5. **Backup plan if Wi-Fi fights you on stage**: fall back to the emulator and a screen recording made
   beforehand. Don't burn presentation time debugging a venue network live.

## On stage (what to run, what to say)

A suggested order — adjust to your own style, this is just a safe default:

1. **Show the architecture** (`demo/ARCHITECTURE.md`'s diagram, or a slide from it): "gateway fans out to
   6 language workers and an aggregator; the aggregator asks a BERT model for a second opinion when it's
   unsure; Envoy load-balances gRPC traffic because plain Kubernetes doesn't do that well for it."
2. **Live call**: play a scripted scam call on the emulator/phone. Narrate: "watch it start green, go
   amber, then red with a reason."
3. **Ops view** (second terminal): `./demo/watch.sh` (or `.ps1`) — a pods+HPA table that refreshes every
   2s, left running on screen the whole time so pod counts visibly change later.
4. **Load + scaling**: `python3 demo/tools/load_sim.py --users 40 --duration 70 --pace 25` in a third
   terminal. Narrate: "CPU is climbing — watch the REPLICAS column." (Measured in testing: this reliably
   pushes several workers' CPU utilization well past 100% of target and triggers visible scale-up within
   10-20 seconds — see `demo/EXPECTED.md` section 7 for real numbers.)
5. **Spread**: once load has run a bit, in the first terminal: `./demo/spread.sh` (or `.ps1`) — shows the
   request count landing on each pod, roughly equal. "This is the Envoy fix — without it, one pod would
   have 100% and the rest would sit idle."
6. **Chaos**: `./demo/chaos.sh impersonation-en` (or `.ps1`) while load is still running (or run it again).
   Narrate while the deleted pod is replaced: "that pod just died — the call that was in flight wasn't
   dropped, Kubernetes just started a new one."
7. **(Optional, if asked) Failure-by-design**: `kubectl scale deployment redis -n scamguard --replicas=0`,
   send one line → 502 → `kubectl scale deployment redis --replicas=1` → works again. Narrate: "the system
   fails loudly and honestly instead of guessing when its memory is gone." On Mac/Linux, a plain
   `curl -X POST .../v1/utterance -d '{"call_id":"test1",...}'` works. **On PowerShell, don't hand-type
   that JSON** — use `Invoke-RestMethod` with a real object instead (PowerShell mangles embedded quotes
   passed to `curl.exe`, see the troubleshooting table):
   ```powershell
   $body = @{call_id="test1";seq=1;speaker="caller";lang="en";text="test"} | ConvertTo-Json
   Invoke-RestMethod -Uri http://127.0.0.1:8080/v1/utterance -Method Post -ContentType application/json -Body $body
   ```

## If something goes wrong

| Symptom | What it means | Fix |
|---|---|---|
| `docker pull` / build fails with `429 Too Many Requests` | Docker Hub's anonymous rate limit | `docker login` with a free Docker Hub account, then retry. Build the day before, not minutes before. |
| Pods stuck `Pending` | Not enough CPU/memory on the node for everything requested | `kubectl describe pod <name> -n scamguard` will say "Insufficient cpu/memory". Either free up RAM (close other apps), raise Docker Desktop's / `.wslconfig`'s memory limit, or re-run `up.sh`/`.ps1` — it auto-picks a smaller "lite" profile on a small host. |
| `bert` pod never becomes `Ready` | Model is loading (can take up to a minute: it has a generous `startupProbe`) — or the build downloaded a bad/partial model | Give it ~60s first. If still not ready: `kubectl logs -n scamguard deploy/bert`. If it mentions a hash mismatch, delete `backend/workers/bert/model/*` and rebuild (`docker build ... workers/bert/Dockerfile`), or see `backend/workers/bert/README.md`. |
| Gateway returns `502` | The aggregator (or Redis behind it) is down/unreachable, or every worker timed out | `kubectl get pods -n scamguard` — is `redis`/`aggregator` `Running`? `kubectl logs -n scamguard deploy/aggregator`. This is often *correct* behaviour (see `demo/EXPECTED.md` section 10) if you were deliberately testing a failure. |
| Phone/emulator can't connect | Usually network, not the backend | Run `demo/tools/phone_check.sh`/`.ps1` first — it tells you exactly which step fails. Common causes: `port-forward.sh`/`.ps1` isn't running; Windows Firewall; venue Wi-Fi blocking device-to-device traffic (use a phone hotspot); wrong URL typed into the app's Settings (must end in `/`, include `http://`). |
| `kubectl get hpa` shows `<unknown>` under TARGETS | metrics-server hasn't reported yet (needs ~30-60s after startup) or a container is missing a CPU `request` | Wait a minute and check again. If it's still `<unknown>` after that, `minikube addons enable metrics-server` and `kubectl get pods -n kube-system` to see if the metrics-server pod itself is healthy. |
| `port-forward.sh`/`.ps1` seems to have died / app stopped responding | `kubectl port-forward` is tied to ONE gateway pod; if that pod restarts, the old forward silently stops working | The script already loops and restarts this for you — check its terminal for "forward dropped, restarting". If it's stuck restarting repeatedly, the gateway pod itself is crash-looping: `kubectl get pods -n scamguard -l app=gateway`. |
| `minikube start` fails mentioning memory/resources | Docker Desktop's own resource limit is too low | Docker Desktop → Settings → Resources → raise CPU/Memory limits, restart Docker Desktop, retry. |
| Requests to `localhost:8080` go to the wrong backend, or `port-forward.sh`/`.ps1` seems to silently do nothing | `docker compose up`'s gateway and this demo's `port-forward` both default to host port 8080 — whichever started first "wins" the port, and the second one fails to bind without always making it obvious | Don't run both at once. Stop `docker compose down` before using the Kubernetes demo (or vice versa), or point one of them at a different host port, e.g. `kubectl port-forward -n scamguard svc/gateway 18080:8080` and use `http://localhost:18080`. (Found for real while testing this demo — see `demo/EXPECTED.md`.) |
| `bert_status` shows `timeout` or `error` even though BERT looks fine | BERT's 300ms budget is tight; it's sensitive to CPU contention (e.g. another heavy stack — Compose, another minikube, a big build — running on the same machine at the same time) | Check `docker stats` / `kubectl top pods` for anything hogging CPU. Close other heavy processes. This is not usually a BERT code problem — see `demo/EXPECTED.md` for a reproduced example. |
| Everything feels slow (every request ~0.5-2s, even a plain `/healthz`) on Windows | Windows resolving `localhost` tries IPv6 (`::1`) first and times out before falling back to IPv4 — adds 400-2000ms to every single request | Use `http://127.0.0.1:8080` instead of `http://localhost:8080`, in curl, the browser, and any script's `--url`. The tools in `demo/tools/` already default to `127.0.0.1` for this reason. Confirmed for real on this laptop — see `demo/EXPECTED.md`. |
| A hand-typed `curl -X POST ... -d '{"call_id":"...",...}'` in PowerShell returns `{"error":"Json deserialize error..."}` | PowerShell re-quotes arguments before handing them to a native .exe like `curl.exe`; embedded double-quotes inside a single-quoted string often arrive mangled | In PowerShell, don't hand-type JSON for curl. Use `Invoke-RestMethod` with a real object instead: `$body = @{call_id="test1";seq=1;speaker="caller";lang="en";text="test"} \| ConvertTo-Json; Invoke-RestMethod -Uri http://127.0.0.1:8080/v1/utterance -Method Post -ContentType application/json -Body $body`. (`demo/tools/phone_check.ps1` already does this correctly — copy its pattern.) |

## Tearing down

```bash
./demo/down.sh            # deletes the 'scamguard' namespace, keeps minikube running (fast to bring back)
./demo/down.sh --all       # also deletes minikube itself (slow to bring back — full rebuild next time)
```

## A note on Docker Desktop's licence

Docker Desktop has its own licensing terms for larger organisations (free for personal use, education, and
small business, last the team checked) — if this matters for your submission, check
https://www.docker.com/pricing/ yourself; this guide doesn't make that call for you.
