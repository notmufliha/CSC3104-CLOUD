"""Flatten the android scripts' caller lines into lines.json for load_test.js. Usage: build_lines.py <scripts dir> <out>"""
import glob
import json
import os
import sys

out = []
for call, path in enumerate(sorted(glob.glob(os.path.join(sys.argv[1], "*.json")))):
    lang = os.path.basename(path).split("_")[1]
    for i, line in enumerate(json.load(open(path, encoding="utf-8"))):
        if line["speaker"] == "caller":
            out.append({"call": call, "seq": i + 1, "lang": lang, "text": line["text"]})
json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False)
print(len(out), "caller lines")
