"""export_phone.py - Workstream G.10: a hashing model as the JSON file the app downloads from the registry.

Format "hashing-lr" v1 (read by the app's model updater):
  hash      the HashingVectorizer settings the phone must copy exactly (murmurhash3_32, seed 0, abs value
            mod n_features; token pattern; stop words; n-grams)
  idf       the frozen IDF: one default value for slots never seen in training + the slots that differ
  weights   logistic regression weights, float32 little-endian, base64 (dense: after DP noise every slot is
            non-zero, so a sparse list would be bigger)
  intercept, threshold
The cleaning step is the urgency worker's text_norm.normalize, which the app already ports (TextCleaning.kt).

  python backend/federated/export_phone.py backend/federated/model/urgency_en_hash_v1.joblib out.json
"""
import base64
import json
import sys
from pathlib import Path

import joblib
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import features as F  # noqa: E402

FORMAT = "hashing-lr"
FORMAT_VERSION = 1


def to_phone_json(coef, intercept, featurize, threshold, source=""):
    idf = featurize.named_steps["tfidf"].idf_.astype(np.float64)
    default = float(np.max(idf))  # slots never seen in training have document frequency 0: the largest idf
    seen = np.where(np.abs(idf - default) > 1e-9)[0]
    return {
        "format": FORMAT, "format_version": FORMAT_VERSION, "signal": "urgency", "language": "en",
        "source": source,
        "hash": {"n_features": F.N_FEATURES, "ngram": list(F.NGRAM), "token_pattern": F.TOKEN_PATTERN,
                 "stop_words": F.STOP_WORDS, "lowercase": True, "alternate_sign": False,
                 "function": "murmurhash3_32(token, seed=0), abs, mod n_features", "sublinear_tf": True,
                 "norm": "l2"},
        "idf": {"default": default, "index": seen.tolist(), "value": [round(float(v), 7) for v in idf[seen]]},
        "weights_b64": base64.b64encode(np.asarray(coef, dtype="<f4").tobytes()).decode(),
        "intercept": float(intercept), "threshold": float(threshold),
    }


def from_phone_json(doc: dict) -> dict:
    """The weights back out of a published file, to use it as the release check's baseline. The featurizer is not
    rebuilt: the server checks the hash settings match its own, so its frozen featurizer applies."""
    want = {"n_features": F.N_FEATURES, "ngram": list(F.NGRAM), "token_pattern": F.TOKEN_PATTERN,
            "stop_words": F.STOP_WORDS}
    got = {k: doc["hash"][k] for k in want}
    if doc.get("format") != FORMAT or got != want:
        raise ValueError(f"published model uses different features: {got} (expected {want})")
    coef = np.frombuffer(base64.b64decode(doc["weights_b64"]), dtype="<f4").astype(np.float64)
    return {"coef": coef, "intercept": float(doc["intercept"]), "threshold": float(doc["threshold"])}


def main():
    m = joblib.load(sys.argv[1])
    doc = to_phone_json(m["coef"], m["intercept"], m["featurizer"], m["threshold"], source=m.get("version", ""))
    Path(sys.argv[2]).write_text(json.dumps(doc))
    print(f"wrote {sys.argv[2]} ({Path(sys.argv[2]).stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
