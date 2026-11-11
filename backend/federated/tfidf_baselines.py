"""tfidf_baselines.py - Workstream G.1: score the deployed TF-IDF models on their fixed test sets, for comparison.

A joblib model only loads in the scikit-learn version that saved it, so run this once per environment:
  python backend/federated/tfidf_baselines.py urgency                    (scikit-learn 1.4.2)
  python backend/federated/tfidf_baselines.py money impersonation        (scikit-learn 1.6.1)
Scores with each worker's own code (backend/retrain/adapters.py, the release check's adapters) and merges the result
into backend/federated/reports/tfidf_baselines.json, which train_hashing.py reads.
"""
import json
import sys
import tempfile
from pathlib import Path

from sklearn.metrics import f1_score, roc_auc_score

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "retrain"))
import adapters  # noqa: E402

OUT = HERE / "reports" / "tfidf_baselines.json"
NAMES = {"urgency": ("urgency_en", "urgency"), "money": ("money_en", "money"),
         "impersonation": ("impersonation_en", "impersonation")}


def main():
    out = json.loads(OUT.read_text()) if OUT.exists() else {}
    for model in sys.argv[1:]:
        name, signal = NAMES[model]
        a = adapters.get(name, signal, Path(tempfile.mkdtemp()))
        m, thr = a.load(a.baked_path), float(a.baked_threshold())
        rows = {}
        for set_name, path, text_col, label_col in a.gold_sets:
            import pandas as pd
            df = pd.read_csv(path)
            df = df[df[label_col].isin([0, 1])]
            y = df[label_col].astype(int)
            p = a.score(m, df[text_col].astype(str).tolist())
            rows[set_name] = {"rows": int(len(df)), "roc_auc": round(float(roc_auc_score(y, p)), 4),
                              "f1": round(float(f1_score(y, (p >= thr).astype(int))), 4)}
        out[model] = {"model": a.baked_path.name, "threshold": thr, "sets": rows}
        print(model, json.dumps(out[model]))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
