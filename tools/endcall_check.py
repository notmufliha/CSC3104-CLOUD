#!/usr/bin/env python3
"""Workstream C.6-C.8 check: the end-of-call review on the demo scripts, against a running stack.

Plays every script in android-app/app/src/main/assets/scripts through the gateway as the app does at level 1
(each caller line to POST /v1/utterance with its call time), then ends the call with POST /v1/call/end and all
caller lines, and prints the live verdict next to the final one (the review's), the review status
("bert" | "fallback"), the call score, the riskiest window's seqs (C.8) and how long the end took.

Correct = a scam call ends SCAM or UNSURE, a normal call ends SAFE (as the G.5 benchmark counts it).
H's two learning-loop tests (scam_en_90/91) are meant to be missed until the models are retrained.

    docker compose up -d --build
    python3 tools/endcall_check.py > data/eval/endcall_check.md
    docker compose stop bert && python3 tools/endcall_check.py > data/eval/endcall_check_bert_down.md
    docker compose start bert

With BERT stopped every call must still end, with review "fallback" and final verdict = live verdict.
Standard library only.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.join(HERE, "..", "android-app", "app", "src", "main", "assets", "scripts")
# A normal call whose LAST caller line sounds like a scam (C.7 test: the final verdict reads the whole call).
BAD_LAST = ("normal_en_11_clinic_appointment", "By the way, please read me the OTP we just sent so we can confirm it.")


def post(gateway, path, body, timeout=10.0):
    req = urllib.request.Request(gateway.rstrip("/") + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode(errors="replace")[:200]}
    except Exception as e:  # noqa: BLE001 - report and carry on
        return -1, {"error": f"{type(e).__name__}: {e}"}


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def play(gateway, name, lines, lang):
    """One call: every caller line with its call time, then the end. Returns a result row."""
    call_id = f"endcheck-{name[:60]}-{uuid.uuid4().hex[:8]}"
    t, live, caller = 0, "LISTENING", []
    for i, line in enumerate(lines):
        t += int(line.get("delay_ms") or 1500)
        if str(line.get("speaker", "")).strip().lower() != "caller":
            continue
        seq, text = i + 1, line.get("text") or ""
        caller.append({"seq": seq, "text": text})
        body = {"call_id": call_id, "seq": seq, "speaker": "caller", "text": text, "t_ms": t}
        if lang:
            body["lang"] = lang
        code, r = post(gateway, "/v1/utterance", body)
        if code == 200 and r.get("verdict"):
            live = r["verdict"]
    start = time.perf_counter()
    code, end = post(gateway, "/v1/call/end", {"call_id": call_id, "event": "end", "ended_by": "script_finished",
                                               "last_seq": len(lines), "caller_lines": caller})
    end_ms = (time.perf_counter() - start) * 1000
    if code != 200:
        return dict(name=name, live=live, final=f"ERROR {code}", review="-", score=None, top=[], reason=end.get("error", ""),
                    end_ms=end_ms, n=len(caller))
    return dict(name=name, live=end.get("live_verdict") or live, final=end.get("final_verdict"), review=end.get("review"),
                score=end.get("call_score"), top=end.get("top_window_seqs") or [], reason=end.get("reason") or "",
                end_ms=end_ms, n=len(caller))


def correct(kind, verdict):
    return verdict in ("SCAM", "UNSURE") if kind == "scam" else verdict == "SAFE"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gateway", default=os.environ.get("GATEWAY_URL", "http://localhost:8080"))
    ap.add_argument("--scripts", default=SCRIPTS)
    ap.add_argument("--no-extra", action="store_true", help="skip the 'bad last sentence' variant")
    a = ap.parse_args()

    code, _ = post(a.gateway, "/v1/call/end", {"call_id": "endcheck-ping", "ended_by": "user_discarded"}, timeout=3)
    if code != 200:
        sys.exit(f"gateway not answering at {a.gateway} (POST /v1/call/end -> {code}); is docker compose up?")

    cases = []
    for f in sorted(os.listdir(a.scripts)):
        if f.endswith(".json"):
            name = f[:-5]
            parts = name.split("_")
            cases.append((name, parts[0], parts[1] if len(parts) > 1 and parts[1] in ("en", "zh") else None,
                          load(os.path.join(a.scripts, f))))
    if not a.no_extra:
        base, extra = BAD_LAST
        lines = load(os.path.join(a.scripts, base + ".json")) + [{"speaker": "caller", "text": extra, "delay_ms": 2500}]
        cases.append((base + "+bad_last_sentence", "normal", "en", lines))

    rows = [dict(play(a.gateway, name, lines, lang), kind=kind) for name, kind, lang, lines in cases]

    print("# End-of-call review (C.6-C.8)\n")
    print(f"- Gateway {a.gateway}, {time.strftime('%Y-%m-%d %H:%M')}; correct = scam ends SCAM/UNSURE, normal ends SAFE\n")
    print("| Call | Kind | Caller lines | Live | Final | Review | Call score | Risky window (seqs) | Reason | End ms |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        mark = lambda v: v + ("" if correct(r["kind"], v) else " ✗")  # noqa: E731
        score = "-" if r["score"] is None else f"{r['score']:.2f}"
        print(f"| {r['name']} | {r['kind']} | {r['n']} | {mark(r['live'])} | {mark(str(r['final']))} | {r['review']} | "
              f"{score} | {','.join(map(str, r['top'])) or '-'} | {r['reason'] or '-'} | {r['end_ms']:.0f} |")
    core = [r for r in rows if "new_scam_test" not in r["name"] and "bad_last" not in r["name"]]
    reviewed = [r for r in rows if r["review"] == "bert"]
    ms = sorted(r["end_ms"] for r in rows)
    print()
    print(f"- Live verdict right: {sum(correct(r['kind'], r['live']) for r in core)} of {len(core)}; "
          f"final verdict right: {sum(correct(r['kind'], str(r['final'])) for r in core)} of {len(core)} "
          "(the original 14 scripts)")
    print(f"- Reviewed by BERT: {len(reviewed)} of {len(rows)} calls (the rest: fallback = live verdict)")
    print(f"- Final verdict changed by the review: {sum(r['final'] != r['live'] for r in rows)} calls")
    print(f"- End request time: median {ms[len(ms) // 2]:.0f} ms, max {ms[-1]:.0f} ms")


if __name__ == "__main__":
    main()
