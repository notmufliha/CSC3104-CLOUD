"""
similarity_check.py - are the Workstream 6 demo scripts too close to data the workers were trained or tested on?

    python tools/testing/similarity_check.py                          # repo CSVs (data/, backend/workers/**) + the app's other scripts
    python tools/testing/similarity_check.py --extra C_train_pool.csv # add the money worker's training CSV (it lives on Drive)

Needs pandas + scikit-learn (the worker virtualenv has both). Prints the highest cosine similarity per script and writes
tools/testing/similarity_check.csv with the nearest reference sentence for every line. Rule of thumb: >= 0.90 = copied,
0.70-0.90 = reword it, short generic lines such as "Okay, what do you need?" can reach 0.6 harmlessly.
"""
import argparse, glob, json, os, re, sys
import numpy as np, pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
SCRIPTS = os.path.join(ROOT, "android-app", "app", "src", "main", "assets", "scripts")
ap = argparse.ArgumentParser()
ap.add_argument("--extra", nargs="*", default=[], help="more CSVs with a text column (e.g. the money worker's training data)")
args = ap.parse_args()
sys.stdout.reconfigure(encoding="utf-8")

exp = json.load(open(os.path.join(HERE, "expected.json"), encoding="utf-8"))
mine = {e["file"] for e in exp}
refs = {}
for f in glob.glob(os.path.join(ROOT, "**", "*.csv"), recursive=True) + args.extra:
    if os.sep + "testing" + os.sep in f or "results_" in os.path.basename(f): continue
    try: d = pd.read_csv(f, low_memory=False)
    except Exception: continue
    for col in ("text_raw", "text", "sentence", "transcript", "TEXT"):
        if col in d.columns:
            for t in d[col].dropna().astype(str):
                if len(t) > 8: refs.setdefault(t, os.path.relpath(f, ROOT) if os.path.isabs(f) else f)
            break
for p in glob.glob(os.path.join(SCRIPTS, "*.json")):          # the app's other scripts (not ours)
    if os.path.basename(p) in mine: continue
    for ln in json.load(open(p, encoding="utf-8")):
        if len(ln.get("text") or "") > 8: refs.setdefault(ln["text"], "assets/scripts/" + os.path.basename(p))
print("reference sentences:", len(refs))
is_zh = lambda s: bool(re.search(r"[\u4e00-\u9fff]", s))
rows = []
for lang in ("en", "zh"):
    items = []
    for e in exp:
        if e["lang"] != lang: continue
        for i, l in enumerate(json.load(open(os.path.join(SCRIPTS, e["file"]), encoding="utf-8")), 1):
            items.append((e["name"], i, l["speaker"], l["text"]))
    pool = [(t, s) for t, s in refs.items() if is_zh(t) == (lang == "zh")]
    pt, ps = [p[0] for p in pool], [p[1] for p in pool]
    vec = (TfidfVectorizer(analyzer="char", ngram_range=(1, 3), sublinear_tf=True) if lang == "zh"
           else TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True)).fit(pt + [x[3] for x in items])
    S = (vec.transform([x[3] for x in items]) @ vec.transform(pt).T).tocsr()
    for k, (n, i, spk, t) in enumerate(items):
        row = S.getrow(k)
        j, sim = (int(row.indices[np.argmax(row.data)]), float(row.data.max())) if row.nnz else (-1, 0.0)
        rows.append(dict(script=n, line=i, speaker=spk, max_cosine=round(sim, 3), nearest_file=ps[j] if j >= 0 else "",
                         nearest_text=pt[j][:90] if j >= 0 else "", text=t))
df = pd.DataFrame(rows)
df.to_csv(os.path.join(HERE, "similarity_check.csv"), index=False, encoding="utf-8-sig")
print(df.groupby("script")["max_cosine"].max().round(3).to_string())
print(f"\nlines >= 0.90: {(df.max_cosine >= .9).sum()}   >= 0.70: {(df.max_cosine >= .7).sum()}")
print(df.sort_values("max_cosine", ascending=False).head(5)[["script", "line", "max_cosine", "text"]].to_string())
