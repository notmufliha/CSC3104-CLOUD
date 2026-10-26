#!/usr/bin/env python3
"""load_sim.py - generates realistic concurrent load against a running gateway, to show load balancing
(demo/spread.sh) and autoscaling (demo/watch.sh) actually working under load.

Each simulated "user" plays one of the app's own scripted calls (android-app/app/src/main/assets/scripts/
*.json -- the same files tools/testing/replay.py uses) through POST /v1/utterance, sending only caller
lines (exactly what the real app sends -- see DEMO_TASK.md section 7: the app never sends receiver
lines or t_ms), with the same call_id and increasing seq for the whole call, then immediately starts a
new call with a fresh call_id, for as long as --duration lasts.

Standard library only (urllib + concurrent.futures), so there's nothing to `pip install` on stage.

Usage:
    python demo/tools/load_sim.py                                   # defaults, against http://localhost:8080
    python demo/tools/load_sim.py --url http://localhost:8080 --users 30 --duration 60 --ramp 10
    python demo/tools/load_sim.py --scam-ratio 0.5 --zh-ratio 0.3 --pace 8
    python demo/tools/load_sim.py --error-threshold 0.1             # exit 1 if >10% of requests error

Run `python demo/tools/load_sim.py --help` for every flag and its default.
"""
import argparse
import concurrent.futures
import json
import os
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SCRIPTS_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "android-app", "app", "src", "main", "assets", "scripts"))


def load_scripts(scripts_dir: str):
    """Returns {(kind, lang): [ (filename, lines) ]} for every *.json script under scripts_dir whose
    filename starts with scam_/normal_ and <lang> (en/zh), the app's own naming rule (6.1)."""
    buckets: dict = {}
    for name in sorted(os.listdir(scripts_dir)):
        if not name.endswith(".json"):
            continue
        parts = name[:-5].split("_")
        if len(parts) < 2 or parts[0] not in ("scam", "normal"):
            continue
        kind, lang = parts[0], parts[1]
        if lang not in ("en", "zh"):
            continue
        with open(os.path.join(scripts_dir, name), encoding="utf-8") as f:
            lines = json.load(f)
        buckets.setdefault((kind, lang), []).append((name, lines))
    return buckets


def post(url: str, body: dict, timeout: float):
    req = urllib.request.Request(
        f"{url}/v1/utterance", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST",
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(r.read())
            return r.status, data, (time.perf_counter() - t0) * 1000
    except urllib.error.HTTPError as e:
        try:
            data = json.loads(e.read() or b"{}")
        except ValueError:
            data = {}
        return e.code, data, (time.perf_counter() - t0) * 1000
    except (urllib.error.URLError, OSError) as e:
        return 0, {"error": str(e)}, (time.perf_counter() - t0) * 1000


def pick_script(buckets: dict, scam_ratio: float, zh_ratio: float):
    kind = "scam" if random.random() < scam_ratio else "normal"
    lang = "zh" if random.random() < zh_ratio else "en"
    options = buckets.get((kind, lang)) or buckets.get((kind, "en")) or next(iter(buckets.values()))
    return random.choice(options)


def user_worker(user_id: int, url: str, buckets: dict, args, end_time: float, results: list, lock: threading.Lock, stop: threading.Event):
    while time.time() < end_time and not stop.is_set():
        name, lines = pick_script(buckets, args.scam_ratio, args.zh_ratio)
        call_id = f"load-{user_id}-{uuid.uuid4().hex[:8]}"
        lang = name.split("_")[1]
        for seq, line in enumerate(lines, start=1):
            if time.time() >= end_time or stop.is_set():
                return
            delay_s = line.get("delay_ms", 0) / 1000 / max(args.pace, 0.01)
            time.sleep(delay_s)
            if line.get("speaker") != "caller":
                continue  # the real app never sends receiver lines
            body = {"call_id": call_id, "seq": seq, "speaker": "caller", "text": line["text"], "lang": lang}
            status, data, ms = post(url, body, args.timeout)
            rec = {
                "status": status,
                "ms": ms,
                "verdict": data.get("verdict"),
                "missing": data.get("missing") or [],
                "quorum_met": data.get("quorum_met"),
                "bert_status": data.get("bert_status"),
            }
            with lock:
                results.append(rec)


def percentile(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    k = (len(sorted_vals) - 1) * p
    f, c = int(k), min(int(k) + 1, len(sorted_vals) - 1)
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    # Every default also reads an env var of the same name (LOAD_*), so the in-cluster Job
    # (demo/k8s/loadgen/loadgen.yaml) can be tuned with `kubectl set env job/loadgen LOAD_USERS=50` /
    # editing the Job spec, without touching the script. A flag on the command line always wins.
    ap.add_argument("--url", default=os.environ.get("GATEWAY_URL", "http://localhost:8080"), help="gateway base URL (default: %(default)s, or $GATEWAY_URL)")
    ap.add_argument("--users", type=int, default=int(os.environ.get("LOAD_USERS", 20)), help="concurrent simulated users (default: %(default)s, or $LOAD_USERS)")
    ap.add_argument("--duration", type=float, default=float(os.environ.get("LOAD_DURATION", 60)), help="seconds to run the test for (default: %(default)s, or $LOAD_DURATION)")
    ap.add_argument("--ramp", type=float, default=float(os.environ.get("LOAD_RAMP", 10)), help="seconds to stagger user start-up over, 0 = all at once (default: %(default)s, or $LOAD_RAMP)")
    ap.add_argument("--pace", type=float, default=float(os.environ.get("LOAD_PACE", 10)), help="speed-up factor for each script's delay_ms (10 = 10x faster than a real call, default: %(default)s, or $LOAD_PACE)")
    ap.add_argument("--scam-ratio", type=float, default=float(os.environ.get("LOAD_SCAM_RATIO", 0.5)), help="fraction of calls that play a scam script (default: %(default)s, or $LOAD_SCAM_RATIO)")
    ap.add_argument("--zh-ratio", type=float, default=float(os.environ.get("LOAD_ZH_RATIO", 0.3)), help="fraction of calls in Mandarin (default: %(default)s, or $LOAD_ZH_RATIO)")
    ap.add_argument("--scripts-dir", default=os.environ.get("LOAD_SCRIPTS_DIR", DEFAULT_SCRIPTS_DIR), help="where the *.json call scripts live (default: the app's assets/scripts/, or $LOAD_SCRIPTS_DIR)")
    ap.add_argument("--timeout", type=float, default=5.0, help="per-request timeout, seconds (default: %(default)s)")
    ap.add_argument("--error-threshold", type=float, default=float(os.environ.get("LOAD_ERROR_THRESHOLD", 0.05)), help="exit 1 if the HTTP-error rate exceeds this fraction (default: %(default)s, or $LOAD_ERROR_THRESHOLD)")
    args = ap.parse_args()

    if not os.path.isdir(args.scripts_dir):
        print(f"scripts dir not found: {args.scripts_dir}", file=sys.stderr)
        return 2
    buckets = load_scripts(args.scripts_dir)
    if not buckets:
        print(f"no scam_*/normal_* *.json scripts found under {args.scripts_dir}", file=sys.stderr)
        return 2
    print(f"Loaded {sum(len(v) for v in buckets.values())} call scripts: "
          + ", ".join(f"{k[0]}/{k[1]}={len(v)}" for k, v in sorted(buckets.items())))

    try:
        import urllib.request as _u
        with _u.urlopen(f"{args.url}/healthz", timeout=3) as r:
            print(f"Gateway reachable at {args.url} ({r.read().decode().strip()})")
    except Exception as e:
        print(f"WARNING: could not reach {args.url}/healthz ({e}). Starting anyway.", file=sys.stderr)

    results: list = []
    lock = threading.Lock()
    stop = threading.Event()
    start = time.time()
    end_time = start + args.duration

    print(f"\nRunning {args.users} users for {args.duration:.0f}s (ramp {args.ramp:.0f}s, pace {args.pace}x, "
          f"scam_ratio={args.scam_ratio}, zh_ratio={args.zh_ratio})...")

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.users) as pool:
        futures = []
        for i in range(args.users):
            if args.ramp > 0:
                time.sleep(args.ramp / args.users)
            futures.append(pool.submit(user_worker, i, args.url, buckets, args, end_time, results, lock, stop))
        # Progress ticker while the ramp + duration play out.
        while time.time() < end_time:
            time.sleep(min(5, max(0.5, end_time - time.time())))
            with lock:
                n = len(results)
            print(f"  ... {time.time() - start:5.0f}s elapsed, {n} requests sent so far")
        stop.set()
        concurrent.futures.wait(futures, timeout=args.timeout + 2)

    elapsed = time.time() - start
    n = len(results)
    if n == 0:
        print("\nNo requests completed -- is the gateway reachable? Nothing to report.", file=sys.stderr)
        return 1

    latencies = sorted(r["ms"] for r in results)
    errors = [r for r in results if r["status"] != 200]
    verdict_counts: dict = {}
    bert_status_counts: dict = {}
    non_empty_missing = 0
    quorum_false = 0
    for r in results:
        verdict_counts[r["verdict"]] = verdict_counts.get(r["verdict"], 0) + 1
        bert_status_counts[r["bert_status"]] = bert_status_counts.get(r["bert_status"], 0) + 1
        if r["missing"]:
            non_empty_missing += 1
        if r["quorum_met"] is False:
            quorum_false += 1

    error_rate = len(errors) / n

    print("\n" + "=" * 60)
    print("LOAD TEST RESULTS")
    print("=" * 60)
    print(f"Duration:            {elapsed:.1f}s (requested {args.duration:.0f}s)")
    print(f"Requests sent:       {n}")
    print(f"Requests/s:          {n / elapsed:.2f}")
    print(f"Latency p50/p95/p99: {percentile(latencies, 0.50):.0f} / {percentile(latencies, 0.95):.0f} / {percentile(latencies, 0.99):.0f} ms")
    print(f"HTTP errors:         {len(errors)}  ({error_rate:.1%})")
    print(f"Verdict counts:      {verdict_counts}")
    print(f"bert_status counts:  {bert_status_counts}")
    print(f"Non-empty 'missing': {non_empty_missing}")
    print(f"quorum_met=false:    {quorum_false}")
    print("=" * 60)

    if error_rate > args.error_threshold:
        print(f"\nFAIL: error rate {error_rate:.1%} exceeds --error-threshold {args.error_threshold:.1%}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
