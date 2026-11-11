"""F.6 + F.7: what each mode says, and what happens when the BERT pod dies mid-call.
Plays a scam script and a normal script in each mode, stops BERT partway through each call, and prints every line's
verdict, BERT status, latency and reasons. Restarts BERT between calls and leaves the stack in cascade mode.
From the repo root, stack running: python3 tools/loadtest/failure_test.py > data/eval/failure_test.md"""
import json
import subprocess
import time
import urllib.request

DIR = "android-app/app/src/main/assets/scripts"
SCRIPTS = ["scam_en_12_parcel_customs", "normal_en_11_clinic_appointment"]
STOP_AFTER = 3  # caller lines answered before BERT is stopped
MODES = ["fast", "bert_only", "cascade"]


def compose(*args, mode="cascade"):
    subprocess.run(
        ["env", f"AGG_MODE={mode}", "docker", "compose", *args],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def post(body):
    req = urllib.request.Request("http://localhost:8080/v1/utterance", json.dumps(body).encode(), {"Content-Type": "application/json"})
    start = time.time()
    with urllib.request.urlopen(req, timeout=10) as r:
        reply = json.loads(r.read())
    return reply, (time.time() - start) * 1000


def warm_up_bert(mode, stamp):
    """Three throwaway lines, so a lazy reconnect doesn't count as the failure being tested."""
    for i in (1, 2, 3):
        post(dict(call_id=f"warm-{mode}-{stamp}", seq=i, speaker="caller", lang="en", text="Hello, this is a test line.", t_ms=20000))


summary = []
stamp = time.strftime("%H%M%S")
for mode in MODES:
    print(f"## {mode}\n")
    for name in SCRIPTS:
        compose("up", "-d", "bert")
        compose("up", "-d", "aggregator", mode=mode)
        time.sleep(10)
        warm_up_bert(mode, f"{stamp}-{name}")
        print(f"### {name}\n\n| seq | t s | BERT | verdict | status | wall ms | reasons |\n|---|---|---|---|---|---|---|")
        t, answered, stopped, after = 0, 0, False, []
        for i, line in enumerate(json.load(open(f"{DIR}/{name}.json", encoding="utf-8")), 1):
            t += line.get("delay_ms", 0)
            if line["speaker"] != "caller":
                continue
            if answered == STOP_AFTER:
                compose("stop", "bert")
                stopped = True
            r, ms = post(dict(call_id=f"fail-{mode}-{stamp}-{name}"[:128], seq=i, speaker="caller", text=line["text"],
                              lang=name.split("_")[1], t_ms=t))
            answered += 1
            if stopped:
                after.append((r["verdict"], r.get("bert_status"), ms))
            reasons = "; ".join(x["text"] for x in r.get("reasons") or [])
            print(f"| {i} | {t / 1000:.1f} | {'down' if stopped else 'up'} | {r['verdict']} | {r.get('bert_status')} | {ms:.0f} | {reasons} |")
        print()
        summary.append((mode, name, after[-1][0], sorted({a[1] for a in after}), max(a[2] for a in after)))
compose("up", "-d", "bert")
compose("up", "-d", "aggregator")

print("## Summary, after BERT stopped\n\n| mode | script | verdict at end of call | BERT statuses | slowest line ms |\n|---|---|---|---|---|")
for mode, name, verdict, statuses, ms in summary:
    print(f"| {mode} | {name} | {verdict} | {', '.join(str(s) for s in statuses)} | {ms:.0f} |")
