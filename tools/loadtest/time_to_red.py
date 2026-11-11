"""F.4 time to alert: replay the 7 scam scripts at their real call times (delay_ms), so the warm-up applies as on a
phone, and report seconds from the key scam sentence to the first SCAM. Also checks the 7 normal scripts never go SCAM.
Run once per mode, from the repo root: AGG_MODE=<mode> docker compose up -d aggregator; python3 tools/loadtest/time_to_red.py <mode>
Negative = red before the key sentence."""
import glob
import json
import os
import statistics
import sys
import time
import urllib.request

# Key sentence (script line number): the first actual request for money, a code or card details. Chosen by hand.
KEY = {
    "scam_en_10_gov_official": 11,
    "scam_en_11_bank_otp": 9,
    "scam_en_12_parcel_customs": 10,
    "scam_en_13_tech_support": 9,
    "scam_en_14_friend_in_trouble": 7,
    "scam_zh_10_gov_official": 11,
    "scam_zh_12_parcel_customs": 10,
}
mode = sys.argv[1]
DIR = "android-app/app/src/main/assets/scripts"


def post(body):
    req = urllib.request.Request("http://localhost:8080/v1/utterance", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


delays, false_alarms, missed = [], [], []
stamp = time.strftime("%H%M%S")
print(f"## {mode}\n\n| script | key line | key at s | red line | red at s | seconds key -> red |\n|---|---|---|---|---|---|")
for path in sorted(glob.glob(os.path.join(DIR, "*.json"))):
    name = os.path.basename(path)[:-5]
    if name.startswith("scam") and name not in KEY:
        print(f"(skipped {name}: no key sentence set)\n")
        continue
    t, red, key_t = 0, None, None
    for i, line in enumerate(json.load(open(path, encoding="utf-8")), 1):
        t += line.get("delay_ms", 0)
        if i == KEY.get(name):
            key_t = t
        if line["speaker"] != "caller":
            continue
        r = post(dict(call_id=f"ttr-{mode}-{stamp}-{name}"[:128], seq=i, speaker="caller", text=line["text"],
                      lang=name.split("_")[1], t_ms=t))
        if r["verdict"] == "SCAM" and red is None:
            red = (i, t)
    if name.startswith("normal"):
        if red:
            false_alarms.append(name)
    elif red is None:
        missed.append(name)
        print(f"| {name} | {KEY[name]} | {key_t / 1000:.1f} | never | - | - |")
    else:
        delays.append((red[1] - key_t) / 1000)
        print(f"| {name} | {KEY[name]} | {key_t / 1000:.1f} | {red[0]} | {red[1] / 1000:.1f} | {delays[-1]:.1f} |")
print(f"\nmedian seconds key -> red: {statistics.median(delays) if delays else 'n/a'}; missed: {missed or 'none'}; "
      f"normal scripts that went SCAM: {false_alarms or 'none'}\n")
