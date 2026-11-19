#!/usr/bin/env python3
"""
Learn content for BOTH apps: tools/learn/scam_types.json (hand-written cards) + the call scripts + the key lines in
tools/testing/expected.json -> learn.json, copied into
  android-app/app/src/main/assets/learn/learn.json
  ios-app/ScamGuard/Resources/Learn/learn.json

Quiz pool (real script lines only, English, never the learning-loop test scripts numbered 90+):
  Scam: each scam script's caller lines from its earliest_ok_line (expected.json: by then the call is clearly a scam).
  Safe: each normal script's caller lines of 7+ words (greetings and goodbyes are too easy).
Run:  python3 tools/learn/build_learn.py
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPTS = os.path.join(ROOT, "android-app", "app", "src", "main", "assets", "scripts")
OUTS = [os.path.join(ROOT, "android-app", "app", "src", "main", "assets", "learn", "learn.json"),
        os.path.join(ROOT, "ios-app", "ScamGuard", "Resources", "Learn", "learn.json"),
        os.path.join(ROOT, "web", "learn.json")]   # the web app (flat: the Kubernetes ConfigMap holds web/'s files)


def is_test_script(name):
    parts = name.replace(".json", "").split("_")
    return len(parts) > 2 and parts[2].isdigit() and int(parts[2]) >= 90


def caller_lines(name):
    with open(os.path.join(SCRIPTS, name), encoding="utf-8") as f:
        lines = json.load(f)
    return [(i, l["text"].strip()) for i, l in enumerate(lines, 1)
            if (l.get("speaker") or "").strip().lower() == "caller" and (l.get("text") or "").strip()]


def main():
    with open(os.path.join(ROOT, "tools", "learn", "scam_types.json"), encoding="utf-8") as f:
        src = json.load(f)
    with open(os.path.join(ROOT, "tools", "testing", "expected.json"), encoding="utf-8") as f:
        expected = {r["file"]: r for r in json.load(f)}
    types, quiz = [], []
    for t in src["types"]:
        script = t["script"]
        if is_test_script(script) or not os.path.exists(os.path.join(SCRIPTS, script)):
            sys.exit(f"{t['id']}: bad script {script}")
        if len(t["signs"]) != 3:
            sys.exit(f"{t['id']}: needs exactly 3 signs")
        source = src["sources"][t["source"]]
        types.append({"id": t["id"], "title": t["title"], "signs": t["signs"], "todo": t["todo"], "script": script,
                      "source_name": source["name"], "source_url": source["url"]})
        start = expected[script]["earliest_ok_line"]
        for seq, text in caller_lines(script):
            if seq >= start:
                quiz.append({"text": text, "scam": True, "why": t["quiz_why"], "script": script, "type": t["id"]})
    for script, why in src["safe_why"].items():
        if is_test_script(script):
            sys.exit(f"safe_why: test script {script}")
        for seq, text in caller_lines(script):
            if len(text.split()) >= 7:
                quiz.append({"text": text, "scam": False, "why": why, "script": script})
    out = {"version": 1, "generated_by": "tools/learn/build_learn.py", "types": types, "quiz": quiz}
    data = json.dumps(out, ensure_ascii=False, indent=1) + "\n"
    for p in OUTS:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(data)
    print(f"{len(types)} scam types, {sum(q['scam'] for q in quiz)} scam lines, "
          f"{sum(not q['scam'] for q in quiz)} safe lines -> {len(OUTS)} copies (Android, iOS, web)")


if __name__ == "__main__":
    main()
