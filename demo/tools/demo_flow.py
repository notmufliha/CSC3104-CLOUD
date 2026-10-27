#!/usr/bin/env python3
"""demo_flow.py - plays 4 of the app's own scripted calls through a running gateway and prints what the
app itself would show for each line: verdict, combined_score, bert_score, bert_status, action, latency.
This is the "what the app should show" reference DEMO_TASK.md section 4 asks for -- run it before going
on stage to confirm the backend still behaves the way the demo script expects.

The 4 calls (from android-app/app/src/main/assets/scripts/, see tools/testing/README.md for the full set):
  - scam_en_10_gov_official   : scam, English (bank -> fake police, "safety account")
  - normal_en_10_bank_reminder: normal, English (never turns red)
  - scam_zh_10_gov_official   : scam, Mandarin (same story, in Chinese)
  - scam_en_12_parcel_customs : "parcel" style -- starts SAFE, turns SCAM partway through

Standard library only. Sends only caller lines with call_id/seq/speaker/text/lang, exactly what the real
app sends (no t_ms -- the app does not send it either, see DEMO_TASK.md section 7).

Usage:
    python demo/tools/demo_flow.py                      # real time (~serveral minutes total)
    python demo/tools/demo_flow.py --pace 10             # 10x faster (breaks the 15s warm-up timing)
    python demo/tools/demo_flow.py --url http://127.0.0.1:8080

Use 127.0.0.1, not localhost, in --url/$GATEWAY_URL: on Windows, resolving "localhost" can take 400-2000ms
extra per request (IPv6 ::1 attempted and timed out before falling back to IPv4) -- measured for real while
testing this script (plain IP: ~50ms; "localhost": up to ~2.3s). The default below already uses 127.0.0.1.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS_DIR = os.path.normpath(os.path.join(HERE, "..", "..", "android-app", "app", "src", "main", "assets", "scripts"))

CALLS = [
    ("scam_en_10_gov_official.json", "en"),
    ("normal_en_10_bank_reminder.json", "en"),
    ("scam_zh_10_gov_official.json", "zh"),
    ("scam_en_12_parcel_customs.json", "en"),
]


def post(url: str, body: dict, timeout: float):
    req = urllib.request.Request(
        f"{url}/v1/utterance", data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST",
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read()), (time.perf_counter() - t0) * 1000
    except urllib.error.HTTPError as e:
        try:
            data = json.loads(e.read() or b"{}")
        except ValueError:
            data = {}
        return e.code, data, (time.perf_counter() - t0) * 1000
    except (urllib.error.URLError, OSError) as e:
        sys.exit(f"Cannot reach the gateway at {url} ({e}). Is the demo up (demo/up.sh) and port-forwarded "
                  f"(demo/port-forward.sh), or is `docker compose up` running?")


def run_call(url: str, filename: str, lang: str, pace: float, timeout: float):
    path = os.path.join(SCRIPTS_DIR, filename)
    if not os.path.exists(path):
        print(f"  SKIPPED: {filename} not found at {path}")
        return []
    with open(path, encoding="utf-8") as f:
        lines = json.load(f)
    call_id = f"demoflow-{filename[:-5]}-{int(time.time())}"
    rows = []
    for seq, line in enumerate(lines, start=1):
        delay_s = line.get("delay_ms", 0) / 1000 / max(pace, 0.01)
        time.sleep(delay_s)
        if line.get("speaker") != "caller":
            rows.append({"line": seq, "speaker": "receiver", "sent": False})
            continue
        body = {"call_id": call_id, "seq": seq, "speaker": "caller", "text": line["text"], "lang": lang}
        status, r, ms = post(url, body, timeout)
        rows.append({
            "line": seq, "speaker": "caller", "sent": True, "status": status,
            "verdict": r.get("verdict"), "combined_score": r.get("combined_score"),
            "bert_score": r.get("bert_score"), "bert_status": r.get("bert_status"),
            "action": (r.get("action") or "")[:40], "latency_ms": round(ms, 1),
        })
    return rows


def print_table(name: str, lang: str, rows: list):
    print(f"\n=== {name}  (lang={lang}) ===")
    if not rows:
        return
    header = f"{'line':>4} {'spk':>4} {'verdict':>7} {'combined':>8} {'bert':>6} {'bert_status':>12} {'ms':>6}  action"
    print(header)
    print("-" * len(header))
    for row in rows:
        if not row.get("sent"):
            print(f"{row['line']:>4} {'recv':>4} {'':>7} {'':>8} {'':>6} {'':>12} {'':>6}  (not sent)")
            continue
        cs = "" if row["combined_score"] is None else round(row["combined_score"], 2)
        bs = "" if row["bert_score"] is None else round(row["bert_score"], 2)
        print(f"{row['line']:>4} {'call':>4} {str(row['verdict']):>7} "
              f"{str(cs):>8} {str(bs):>6} "
              f"{str(row['bert_status']):>12} {row['latency_ms']:>6}  {row['action']}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default=os.environ.get("GATEWAY_URL", "http://127.0.0.1:8080"))
    ap.add_argument("--pace", type=float, default=1.0, help="speed-up factor for delay_ms (1 = real time, default: %(default)s)")
    ap.add_argument("--timeout", type=float, default=5.0)
    args = ap.parse_args()

    print(f"Gateway: {args.url}  (pace {args.pace}x)")
    for filename, lang in CALLS:
        rows = run_call(args.url, filename, lang, args.pace, args.timeout)
        print_table(filename, lang, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
