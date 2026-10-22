"""
gateway_test.py - end-to-end checks for Workstream 2 (gateway), against a running `docker compose up` stack.

    docker compose up -d --build
    python tools/gateway_test.py                 # all checks, including stopping one worker
    python tools/gateway_test.py --no-stop       # skip the stop-a-worker check

Standard library only. Talks to the gateway at http://localhost:8080 (override with GATEWAY_URL).
Exits 0 if every check passes.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

URL = os.environ.get("GATEWAY_URL", "http://localhost:8080").rstrip("/")
MARKER = "zz-marker-never-log-me"
results = []


def post(body):
    req = urllib.request.Request(f"{URL}/v1/utterance", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read()), time.perf_counter() - t
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), time.perf_counter() - t


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"   [{detail}]" if detail else ""))


def compose(*args):
    return subprocess.run(["docker", "compose", *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace")


def short(r):
    keys = ("verdict", "combined_score", "language", "language_source", "scores", "missing", "scored", "latency_ms")
    return json.dumps({k: r.get(k) for k in keys if k in r}, ensure_ascii=False)


print(f"gateway: {URL}")
call = f"test-{int(time.time())}"

print("\n2.1 + 2.4  caller line -> three scores + a verdict")
s, r, dt = post({"call_id": call, "speaker": "caller", "lang": "en",
                 "text": f"This is the police. Transfer the money to the safe account now and read me the OTP. {MARKER}"})
print("   ", short(r))
check("HTTP 200", s == 200, str(s))
check("three scores, none missing", len([v for v in r.get("scores", {}).values() if v is not None]) == 3, r.get("missing"))
check("has a verdict", r.get("verdict") in ("SAFE", "UNSURE", "SCAM"), r.get("verdict"))
first_verdict = r.get("verdict")

print("\n2.2  receiver line -> last verdict, no workers called")
before = compose("logs", "gateway", "--no-color").stdout.count('stage="worker"') + \
    compose("logs", "gateway", "--no-color").stdout.count("stage=worker")
s, r, dt = post({"call_id": call, "speaker": "receiver", "text": "Oh no, what should I do?"})
time.sleep(0.5)
logs = compose("logs", "gateway", "--no-color").stdout
after = logs.count('stage="worker"') + logs.count("stage=worker")
print("   ", short(r))
check("scored = false", r.get("scored") is False)
check("returns the call's last verdict", r.get("verdict") == first_verdict, r.get("verdict"))
check("no worker was called", after == before, f"worker log lines {before} -> {after}")

print("\n2.3  language routing")
s, r, _ = post({"call_id": call + "-zh", "speaker": "caller", "text": "我是警察，你必须马上把钱转到安全账户，并告诉我验证码。"})
check("no lang + Chinese text -> zh (detected)", r.get("language") == "zh" and r.get("language_source") == "detected",
      short(r))
s, r, _ = post({"call_id": call + "-en", "speaker": "caller", "text": "Please transfer the money now"})
check("no lang + English text -> en (detected)", r.get("language") == "en" and r.get("language_source") == "detected")
s, r, _ = post({"call_id": call + "-ph", "speaker": "caller", "lang": "zh-SG", "text": "ok can"})
check("phone's lang field wins (zh-SG -> zh)", r.get("language") == "zh" and r.get("language_source") == "phone")
s, r, _ = post({"call_id": call + "-bad", "speaker": "caller", "lang": "fr", "text": "bonjour"})
check("unsupported lang -> 400", s == 400, str(r))
s, r, _ = post({"call_id": call + "-bad", "speaker": "caller"})
check("missing field -> 400 JSON error", s == 400 and "error" in r, str(r)[:80])

print("\nseq  line numbers: echoed, a resent line is not scored twice")
sc = call + "-seq"
s, r1, _ = post({"call_id": sc, "seq": 1, "speaker": "caller", "lang": "en", "text": "Transfer the money now."})
check("seq echoed back", r1.get("seq") == 1 and r1.get("scored") is True and r1.get("duplicate") is False, short(r1))
logs_before = compose("logs", "gateway", "--no-color").stdout.count('stage="worker"')
s, r2, _ = post({"call_id": sc, "seq": 1, "speaker": "caller", "lang": "en", "text": "Transfer the money now."})
time.sleep(0.5)
logs_after = compose("logs", "gateway", "--no-color").stdout.count('stage="worker"')
check("resend (same seq) -> duplicate, not scored", r2.get("duplicate") is True and r2.get("scored") is False)
check("resend returns the current verdict", r2.get("verdict") == r1.get("verdict"), r2.get("verdict"))
check("resend calls no worker", logs_after == logs_before, f"worker log lines {logs_before} -> {logs_after}")
check("resend doesn't count as a new line", r2.get("caller_lines") == 1, r2.get("caller_lines"))
s, r3, _ = post({"call_id": sc, "seq": 3, "speaker": "caller", "lang": "en", "text": "Read me the OTP."})
s, r4, _ = post({"call_id": sc, "seq": 2, "speaker": "receiver", "text": "Ok wait."})
s, r5, _ = post({"call_id": sc, "seq": 4, "speaker": "caller", "lang": "en", "text": "Thank you."})
check("out-of-order and receiver seqs are fine", r3.get("scored") and r4.get("seq") == 2 and r5.get("scored")
      and r5.get("caller_lines") == 3, f"caller_lines={r5.get('caller_lines')}")
s, r6, _ = post({"call_id": sc, "seq": -1, "speaker": "caller", "text": "hi"})
check("negative seq -> 400", s == 400, str(r6)[:80])

print("\n2.5  stop one worker -> still an answer within 2 s, missing one marked")
if "--no-stop" in sys.argv:
    print("  (skipped)")
else:
    compose("stop", "money_en")
    try:
        s, r, dt = post({"call_id": call + "-stop", "speaker": "caller", "lang": "en",
                         "text": "Transfer the money to the safe account now."})
        print("   ", short(r), f"wall={dt * 1000:.0f} ms")
        check("HTTP 200 with a verdict", s == 200 and r.get("verdict") is not None, str(s))
        check("money marked missing", r.get("missing") == ["money"], r.get("missing"))
        check("answered within 2 s", dt < 2.0, f"{dt * 1000:.0f} ms")
    finally:
        compose("start", "money_en")

print("\n2.7  timing log, never the text")
logs = compose("logs", "gateway", "--no-color").stdout
timing = [l for l in logs.splitlines() if "timing" in l and "stage" in l]
for l in timing[-4:]:
    print("   ", l.strip()[-160:])
check("timing lines with ms per worker", any("ms=" in l or '"ms"' in l for l in timing), f"{len(timing)} lines")
check("transcript text never logged", MARKER not in logs and "安全账户" not in logs)

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
