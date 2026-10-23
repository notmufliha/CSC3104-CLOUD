"""
aggregator_replay.py - Workstream 3 "done when" checks, end to end (phone -> gateway -> workers -> aggregator
-> Redis), against a running `docker compose up` stack.

    docker compose up -d --build
    python tools/aggregator_replay.py               # all checks, including restarting the aggregator mid-call
    python tools/aggregator_replay.py --no-restart  # skip the restart

  1. A scripted scam call turns red at the right line: the OTP request. That line alone is not enough (money
     1.0 but nothing else >= 0.5); it is red because the call remembers "Officer Lim from the Singapore Police
     Force" two caller lines earlier (impersonation 1.0) -> money rule.
  2. The same line in a fresh call (no memory) is NOT red - proves the memory is what made the difference.
  3. The same scam call with `docker compose restart aggregator` just before the OTP line still turns red at
     the same line: the memory lives in Redis, not in the aggregator.
  4. A normal call (clinic appointment) stays green on every line.

Each line carries t_ms (when it was spoken in the call), so the 15 s warm-up behaves as in a real call even
though the replay sends the lines in about a second. Standard library only. Gateway at http://localhost:8080
(override with GATEWAY_URL). Exits 0 if every check passes.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

URL = os.environ.get("GATEWAY_URL", "http://localhost:8080").rstrip("/")
results = []

# (seconds into the call, speaker, text)
SCAM_CALL = [
    (0, "caller", "Hello, good afternoon, am I speaking with Mr Tan?"),
    (3, "receiver", "Yes, speaking. Who is this?"),
    (6, "caller", "This is Officer Lim calling from the Singapore Police Force."),
    (11, "receiver", "Police? Is something wrong?"),
    (18, "caller", "Please read me the six digit OTP that was just sent to your phone."),  # <- red here
    (24, "receiver", "Okay, wait, let me check."),
    (27, "caller", "Okay, thank you, have a nice day."),  # harmless, but the call stays red
]
RED_AT = 5  # seq of the OTP line

NORMAL_CALL = [
    (0, "caller", "Hi, this is Sarah from Raffles Medical clinic."),
    (3, "receiver", "Hi Sarah."),
    (5, "caller", "I'm calling to confirm your appointment with Dr Lee on Tuesday at 3pm."),
    (10, "receiver", "Yes, that's right."),
    (13, "caller", "Please remember to bring your NRIC and arrive ten minutes early."),
    (18, "caller", "If you need to reschedule, just call the front desk anytime."),
    (24, "receiver", "Will do, thanks."),
    (26, "caller", "Okay, thank you, have a nice day."),
]


def post(body, retries=20):
    """POST one line; a 502/503 (aggregator restarting) is retried - the gateway lets a failed seq be resent."""
    data = json.dumps(body).encode()
    for attempt in range(retries):
        req = urllib.request.Request(f"{URL}/v1/utterance", data=data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code in (502, 503) and attempt < retries - 1:
                time.sleep(0.5)
                continue
            raise
        except urllib.error.URLError:
            if attempt < retries - 1:
                time.sleep(0.5)
                continue
            raise


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"   [{detail}]" if detail else ""))


def compose(*args):
    return subprocess.run(["docker", "compose", *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace")


def replay(call_id, script, before_seq=None):
    """Send the script line by line; before_seq: {seq: callable} run just before that line. Returns {seq: answer}."""
    answers = {}
    for seq, (t, speaker, text) in enumerate(script, 1):
        if before_seq and seq in before_seq:
            before_seq[seq]()
        r = post({"call_id": call_id, "seq": seq, "speaker": speaker, "lang": "en", "text": text, "t_ms": t * 1000})
        answers[seq] = r
        scores = r.get("scores") or {}
        line = " ".join(f"{k[0]}={v:.2f}" for k, v in sorted(scores.items()) if v is not None) or "(not scored)"
        reasons = ", ".join(x["text"] for x in r.get("reasons", []))
        print(f"    {seq} t={t:>2}s {speaker:<8} {str(r.get('verdict')):<6} comb={r.get('combined_score') or 0:.2f} "
              f"{'warm ' if r.get('warming_up') else '     '}{line:<22} {reasons}")
    return answers


def caller_verdicts(answers, script):
    return {seq: a.get("verdict") for seq, a in answers.items() if script[seq - 1][1] == "caller"}


def check_scam_call(answers, label):
    v = caller_verdicts(answers, SCAM_CALL)
    check(f"{label}: not red before the OTP line", all(x != "SCAM" for s, x in v.items() if s < RED_AT),
          str({s: x for s, x in v.items() if s < RED_AT}))
    check(f"{label}: red at the OTP line (seq {RED_AT})", v.get(RED_AT) == "SCAM", v.get(RED_AT))
    check(f"{label}: stays red afterwards", all(a.get("verdict") == "SCAM" for s, a in answers.items() if s > RED_AT),
          str({s: a.get("verdict") for s, a in answers.items() if s > RED_AT}))
    red = answers[RED_AT]
    check(f"{label}: reasons + action at the red line",
          [x["signal"] for x in red.get("reasons", [])][:2] == ["money", "impersonation"] and red.get("action"),
          "; ".join(x["text"] for x in red.get("reasons", [])))
    check(f"{label}: caller_lines counts only caller lines", red.get("caller_lines") == 3, red.get("caller_lines"))


run = int(time.time())
print(f"gateway: {URL}")

print("\n1. scripted scam call")
check_scam_call(replay(f"replay-scam-{run}", SCAM_CALL), "scam call")

print("\n2. the OTP line alone, fresh call (no memory)")
t, _, text = SCAM_CALL[RED_AT - 1]
alone = replay(f"replay-alone-{run}", [(t, "caller", text)])
check("OTP line alone is not red (memory is what makes it red)", alone[1].get("verdict") != "SCAM",
      alone[1].get("verdict"))

print("\n3. scripted scam call, aggregator restarted just before the OTP line")
if "--no-restart" in sys.argv:
    print("  (skipped)")
else:
    call_id = f"replay-restart-{run}"

    def restart():
        print("    -- docker compose restart aggregator --")
        out = compose("restart", "aggregator")
        if out.returncode != 0:
            print(out.stderr)
        mem = compose("exec", "-T", "redis", "redis-cli", "HGET", f"call:{call_id}", "caller_lines").stdout.strip()
        check("call memory is in redis while the aggregator restarts", mem == "2", f"caller_lines={mem!r}")

    check_scam_call(replay(call_id, SCAM_CALL, before_seq={RED_AT: restart}), "after restart")
    ttl = compose("exec", "-T", "redis", "redis-cli", "TTL", f"call:{call_id}").stdout.strip()
    check("call memory expires after about 1 hour", ttl.isdigit() and 3500 < int(ttl) <= 3600, f"TTL={ttl}")
    log = compose("exec", "-T", "redis", "redis-cli", "LRANGE", f"calllog:{call_id}", "0", "-1").stdout
    check("verdict log: one entry per caller line, no text", log.count('"call_id"') == 4
          and "OTP" not in log and "Officer" not in log, f"{log.count(chr(10))} lines")

print("\n4. normal call (clinic appointment)")
normal = replay(f"replay-normal-{run}", NORMAL_CALL)
v = caller_verdicts(normal, NORMAL_CALL)
check("normal call stays green on every line", all(x == "SAFE" for x in v.values()), str(v))

print(f"\n{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
