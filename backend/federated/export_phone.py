"""export_phone.py - Workstream G.10: a hashing model as the file the app downloads from the registry.

Same layout the app already reads for its bundled TF-IDF models (android-app/.../ondevice/LinearTextModel.kt,
tools/export_models.py), as format 2: a vectoriser can now be a hashing one instead of a word list:
  n_features                 slots; a token's slot = abs(murmurhash3_32(utf-8 bytes, seed 0)) % n_features
                             (scikit-learn's HashingVectorizer, alternate_sign=False)
  idf_default                idf of every slot not listed (slots never seen in training)
  idf_index / idf_value      the slots whose idf differs
  coef_b64                   logistic regression weights for this vectoriser, float32 little-endian, base64
                             (dense: after federated averaging + noise nearly every slot is non-zero)
Everything else (calibration, threshold, and the cleaning settings: mask_brands / brand_types / org_types) is copied
from the bundled TF-IDF file of the same signal, so the phone cleans text exactly as before.

  python backend/federated/export_phone.py money backend/federated/model/money_en_hash_v1.joblib out.json
"""
import base64
import json
import sys
from pathlib import Path

import joblib
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import features as F  # noqa: E402

FORMAT = 2
ASSETS = Path(__file__).resolve().parents[2] / "android-app" / "app" / "src" / "main" / "assets" / "models"
CLEANING_KEYS = ("signal", "language", "cleaning", "mask_brands", "brand_types", "org_types")


def to_phone_json(name, coef, intercept, featurize, platt, threshold, version, source="") -> dict:
    bundled = json.loads((ASSETS / f"{name}_en.json").read_text(encoding="utf-8"))
    parts = [step for _, step in featurize.transformer_list]
    coef = np.asarray(coef, dtype=np.float64)
    vecs, offset = [], 0
    for (_, bundled_vec), pipe in zip([(None, v) for v in bundled["vectorizers"]], parts):
        h, tf = pipe.named_steps["hash"], pipe.named_steps["tfidf"]
        n = h.n_features
        idf = tf.idf_.astype(np.float64)
        default = float(idf.max())            # slots never seen in training: document frequency 0, the largest idf
        seen = np.where(np.abs(idf - default) > 1e-9)[0]
        vecs.append({"analyzer": h.analyzer, "ngram_range": list(h.ngram_range), "lowercase": bool(h.lowercase),
                     "token_pattern": h.token_pattern or bundled_vec["token_pattern"],
                     "stop_words": sorted(h.stop_words or []), "sublinear_tf": bool(tf.sublinear_tf),
                     "n_features": int(n), "idf_default": default, "idf_index": seen.tolist(),
                     "idf_value": [float(f"{v:.9g}") for v in idf[seen]],
                     "coef_b64": base64.b64encode(coef[offset:offset + n].astype("<f4").tobytes()).decode()})
        offset += n
    assert offset == len(coef), f"{name}: {len(coef)} weights for {offset} slots"
    doc = {"format": FORMAT, "name": f"{name}_en", "version": version, "source": source,
           "threshold": float(threshold), "vectorizers": vecs, "intercept": float(intercept),
           "calibration": {"a": platt[0], "b": platt[1]} if platt else None}
    doc.update({k: bundled[k] for k in CLEANING_KEYS if k in bundled})
    return doc


def from_phone_json(doc: dict) -> dict:
    """The weights back out of a published file (the release check's baseline). The server uses its own frozen
    featurizer, so the hash settings must be the ones it knows."""
    if doc.get("format") != FORMAT:
        raise ValueError(f"format {doc.get('format')}, expected {FORMAT}")
    coef = np.concatenate([np.frombuffer(base64.b64decode(v["coef_b64"]), dtype="<f4").astype(np.float64)
                           for v in doc["vectorizers"]])
    cal = doc.get("calibration")
    return {"coef": coef, "intercept": float(doc["intercept"]), "threshold": float(doc["threshold"]),
            "platt": (cal["a"], cal["b"]) if cal else None}


def main():
    name, src, out = sys.argv[1], sys.argv[2], sys.argv[3]
    m = joblib.load(src)
    doc = to_phone_json(name, m["coef"], m["intercept"], m["featurizer"], m["platt"], m["threshold"], m["version"])
    Path(out).write_text(json.dumps(doc))
    print(f"wrote {out} ({Path(out).stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
