"""
replay.py - Workstream 6.3 / 6.4: play the demo calls through the gateway line by line.

    docker compose up -d --build                      # from the repo root
    python tools/testing/replay.py                        # every script (Workstream 6 + the 2026 calls), in real time (uses delay_ms)
    python tools/testing/replay.py --only scam_en_10 --verbose   # one script (name prefix), print the text too
    python tools/testing/replay.py --caller-only          # send exactly what the app sends (no receiver lines)
    python tools/testing/replay.py --speed 10             # 10x faster (breaks the 15-second warm-up rule, see README)
    python tools/testing/replay.py --tag w4               # saves tools/testing/results_w4.csv

The scripts are the app's own files in android-app/app/src/main/assets/scripts/ (one copy, used by the app and here).

Standard library only. Gateway URL: --gateway or env GATEWAY_URL (default http://localhost:8080).
Sends {call_id, seq, speaker, text, lang} to POST /v1/utterance. Caller lines are scored, receiver lines return the
last verdict. Prints the verdict after every line, then a results table (expected vs actual, seconds after the key
sentence) and checks the 'done when' goals.
"""
import argparse, csv, json, os, sys, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SCRIPTS = os.path.normpath(os.path.join(HERE, "..", "..", "android-app", "app", "src", "main", "assets", "scripts"))
RANK = {None: 0, "SAFE": 1, "UNSURE": 2, "SCAM": 3}


def post(url, body, timeout=10):
    req = urllib.request.Request(f"{url}/v1/utterance", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read()), (time.perf_counter() - t) * 1000
    except urllib.error.HTTPError as e:
        try: body = json.loads(e.read() or b"{}")
        except ValueError: body = {}
        return e.code, body, (time.perf_counter() - t) * 1000
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the gateway at {url} ({e}). Is `docker compose up` running?")


def play(e, args, run_id):
    lines = json.load(open(os.path.join(args.scripts_dir, e["file"]), encoding="utf-8"))
    call_id = f"{e['name']}-{run_id}"
    sent_lang = None if args.lang == "auto" else e["lang"]
    print(f"\n=== {e['name']}  ({e['type']}, {e['lang']}, {len(lines)} lines)  call_id={call_id}")
    t_script, hist = 0.0, []          # t_script = seconds of simulated call time
    for i, ln in enumerate(lines, 1):
        t_script += ln["delay_ms"] / 1000
        if args.speed > 0:
            time.sleep(ln["delay_ms"] / 1000 / args.speed)
        if args.caller_only and ln["speaker"] != "caller":      # the app never sends receiver lines
            prev = hist[-1]["verdict"] if hist else None
            hist.append({"line": i, "t": t_script, "speaker": ln["speaker"], "verdict": prev, "combined": None,
                         "ms": 0.0, "missing": []})
            print(f"  {i:2} [{t_script:5.1f}s] {ln['speaker'][:4]:4} (not sent)")
            continue
        body = {"call_id": call_id, "seq": i, "speaker": ln["speaker"], "text": ln["text"]}
        if sent_lang: body["lang"] = sent_lang
        status, r, ms = post(args.gateway, body)
        verdict = r.get("verdict") if status == 200 else f"HTTP{status}"
        hist.append({"line": i, "t": t_script, "speaker": ln["speaker"], "verdict": verdict,
                     "combined": r.get("combined_score"), "ms": ms, "missing": r.get("missing") or []})
        mark = " <-- key sentence" if e["key_line"] == i else ""
        extra = ""
        if ln["speaker"] == "caller":
            sc = r.get("scores") or {}
            extra = "  scores " + " ".join(f"{k[:3]}={'--' if v is None else f'{v:.2f}'}" for k, v in sc.items()) \
                    + f"  combined={r.get('combined_score') if r.get('combined_score') is None else round(r['combined_score'], 2)}"
            if r.get("missing"): extra += f"  MISSING={r['missing']}"
        print(f"  {i:2} [{t_script:5.1f}s] {ln['speaker'][:4]:4} {str(verdict):7}{extra}{mark}")
        if args.verbose: print(f"       {ln['text']}")
    return hist


def judge(e, hist):
    first = lambda v: next((h for h in hist if h["verdict"] == v), None)
    red, yellow = first("SCAM"), first("UNSURE")
    final = hist[-1]["verdict"]
    sticky = True
    if red:
        sticky = all(h["verdict"] == "SCAM" for h in hist if h["line"] >= red["line"])
    row = {"script": e["name"], "lang": e["lang"], "type": e["type"], "expected": e["expected_verdict"],
           "final_verdict": final, "first_unsure_line": yellow["line"] if yellow else "",
           "first_red_line": red["line"] if red else "", "key_line": e["key_line"] or "",
           "lines_after_key": "", "secs_after_key": "", "api_ms_at_red": round(red["ms"]) if red else "",
           "max_api_ms": round(max(h["ms"] for h in hist)), "worker_missing_lines": sum(1 for h in hist if h["missing"]),
           "stays_red": "" if not red else ("yes" if sticky else "NO")}
    if e["type"] == "scam":
        if not red: st = "MISSED (never red)"
        elif red["line"] < e["earliest_ok_line"]: st = "TOO EARLY"
        elif red["line"] > e["latest_ok_line"]: st = "TOO LATE"
        else: st = "OK"
        if red:
            row["lines_after_key"] = red["line"] - e["key_line"]
            row["secs_after_key"] = round(red["t"] - hist[e["key_line"] - 1]["t"], 1)
        if st == "OK" and not sticky: st = "NOT STICKY (red went back)"
    else:
        st = "FALSE ALARM (red)" if red else "OK"
    row["status"] = st
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gateway", default=os.environ.get("GATEWAY_URL", "http://localhost:8080").rstrip("/"))
    ap.add_argument("--speed", type=float, default=1.0, help="1 = real time using delay_ms, 10 = 10x faster, 0 = no waiting")
    ap.add_argument("--only", default="", help="run scripts whose name starts with this")
    ap.add_argument("--lang", choices=["expected", "auto"], default="expected",
                    help="expected = send the script's lang field; auto = omit it so the gateway detects the language")
    ap.add_argument("--tag", default="", help="label for this run, e.g. w4 (window size 4)")
    ap.add_argument("--out", default="", help="results CSV path (default results_<tag or time>.csv)")
    ap.add_argument("--scripts-dir", default=DEFAULT_SCRIPTS, help="folder with the call scripts (default: the app's assets/scripts)")
    ap.add_argument("--caller-only", action="store_true", help="do not send receiver lines (what the Android app does)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    exp = [e for e in json.load(open(os.path.join(HERE, "expected.json"), encoding="utf-8"))
           if e["name"].startswith(args.only)]
    if not exp: sys.exit("no script matches --only")
    missing = [e["file"] for e in exp if not os.path.exists(os.path.join(args.scripts_dir, e["file"]))]
    if missing: sys.exit(f"script files not found in {args.scripts_dir}: {missing}")
    run_id = str(int(time.time()))
    rows = [judge(e, play(e, args, run_id)) for e in exp]

    out = args.out or os.path.join(HERE, f"results_{args.tag or run_id}.csv")
    with open(out, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]) + ["tag"]); w.writeheader()
        for r in rows: w.writerow({**r, "tag": args.tag})

    print("\n\n## End-to-end results (6.4)\n")
    print("| script | expected | final | red at line (key) | secs after key | status |")
    print("|---|---|---|---|---|---|")
    for r in rows:
        red = f"{r['first_red_line']} ({r['key_line']})" if r["key_line"] != "" else (r["first_red_line"] or "-")
        print(f"| {r['script']} | {r['expected']} | {r['final_verdict']} | {red} | {'-' if r['secs_after_key'] == '' else r['secs_after_key']} | {r['status']} |")

    scams, normals = [r for r in rows if r["type"] == "scam"], [r for r in rows if r["type"] == "normal"]
    s_ok, n_ok = sum(r["status"] == "OK" for r in scams), sum(r["status"] == "OK" for r in normals)
    s_red = sum(bool(r["first_red_line"]) for r in scams)
    print(f"\nScams that went red: {s_red}/{len(scams)} (on time: {s_ok}) | normal calls not red: {n_ok}/{len(normals)}")
    print("Done-when (>=4 of 5 English scams red, >=4 of 5 English normal calls not red): "
          + ("PASS" if sum(r["first_red_line"] != "" for r in scams if r["lang"] == "en") >= 4
             and sum(r["status"] == "OK" for r in normals if r["lang"] == "en") >= 4 else "not yet"))
    bugs = [r for r in rows if r["status"] != "OK"]
    if bugs:
        print("\nBug list candidates (6.5):")
        for r in bugs: print(f"  - {r['script']}: {r['status']} (red at line {r['first_red_line'] or 'never'}, key {r['key_line'] or '-'})")
    print(f"\nSaved {out}")


if __name__ == "__main__":
    main()
