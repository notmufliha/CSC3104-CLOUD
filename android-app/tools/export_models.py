"""
export_models.py - Workstream G.2: export the English urgency / money / impersonation models for the phone.

Why JSON and not ONNX (the brief's first choice): skl2onnx was tried on all three (tools/try_onnx.py):
  - money_en, impersonation_en: "CountVectorizer cannot be converted, only tokenizer='word' is fully supported"
    (both use character n-grams, analyzer="char_wb").
  - urgency_en: converts, but ONNX Runtime refuses to load it ("Can not digest tokenexp: invalid perl operator:
    (?u") because of the vectoriser's token_pattern.
  - In all three the text cleaning is custom Python regex (text_norm.py / money_norm.py / text_utils.py), which
    ONNX can't hold anyway, so it has to be ported to Kotlin either way.
So every model is exported as plain numbers: each TF-IDF vectoriser's settings, vocabulary and idf, the logistic
regression's weights and intercept, and the Platt calibration (if any). The phone scores with a dot product and
a sigmoid (app/.../ondevice/LinearTextModel.kt), which gives the same numbers as scikit-learn.

Run it with the worker's own scikit-learn version (the pickles only load reliably in the version that wrote them):
  urgency_en         scikit-learn 1.4.2, numpy 1.26.4  (backend/workers/urgency_en/requirements.txt)
  money_en (v2c),
  impersonation_en   scikit-learn 1.6.1, numpy 2.1.3   (backend/workers/money_en/requirements.txt)
The files are the ones each worker's Dockerfile bakes in (urgency stays v4 unless MODEL_VERSION is set).
See tools/README.md for the two virtualenvs.

  python android-app/tools/export_models.py urgency
  python android-app/tools/export_models.py money
  python android-app/tools/export_models.py impersonation

Writes android-app/app/src/main/assets/models/<name>_en.json and updates models/manifest.json (versions + the
sha256 of the source model file, so it's clear which server model the phone copy came from).
"""
import argparse
import datetime as dt
import hashlib
import json
import sys
from pathlib import Path

import joblib
import sklearn

REPO = Path(__file__).resolve().parents[2]
WORKERS = REPO / "backend" / "workers"
OUT = REPO / "android-app" / "app" / "src" / "main" / "assets" / "models"
FORMAT = 1
DIGITS = 9   # significant digits kept for idf / weights: far below the 0.01 parity budget

MODELS = {
    # name: (worker dir, model file the worker's Dockerfile ships)
    "urgency": ("urgency_en", "model/urgency_en_v4.joblib"),
    "money": ("money_en", "model/money_en_v2c.joblib"),
    "impersonation": ("impersonation_en", "model/model.pkl"),
}


def num(x):
    return float(f"{float(x):.{DIGITS}g}")


def vectorizer(v, coef):
    """One TfidfVectorizer -> its settings, terms (in feature order), idf and the classifier weights for them."""
    p = v.get_params()
    for k, want in (("strip_accents", None), ("preprocessor", None), ("tokenizer", None), ("binary", False),
                    ("use_idf", True), ("norm", "l2"), ("input", "content")):
        if p[k] != want:
            raise SystemExit(f"unsupported vectoriser setting {k}={p[k]!r} (the Kotlin scorer assumes {want!r})")
    if p["analyzer"] not in ("word", "char_wb"):
        raise SystemExit(f"unsupported analyzer {p['analyzer']!r}")
    terms = [None] * len(v.vocabulary_)
    for term, i in v.vocabulary_.items():
        terms[i] = term
    if len(coef) != len(terms):
        raise SystemExit("weights and vocabulary sizes differ")
    stop = p["stop_words"]
    if stop not in (None,) and not isinstance(stop, (list, set, frozenset, tuple)):
        raise SystemExit(f"unsupported stop_words {stop!r}")
    return {
        "analyzer": p["analyzer"],
        "ngram_range": list(p["ngram_range"]),
        "lowercase": bool(p["lowercase"]),
        "token_pattern": p["token_pattern"],
        "stop_words": sorted(stop) if stop else [],
        "sublinear_tf": bool(p["sublinear_tf"]),
        "terms": terms,
        "idf": [num(x) for x in v.idf_],
        "coef": [num(x) for x in coef],
    }


def linear_parts(features, clf):
    """A TfidfVectorizer or a FeatureUnion of them, followed by a binary LogisticRegression."""
    if list(clf.classes_) != [0, 1]:
        raise SystemExit(f"expected classes [0, 1], got {clf.classes_}")
    coef = clf.coef_[0]
    parts = features.transformer_list if hasattr(features, "transformer_list") else [("word", features)]
    if hasattr(features, "transformer_weights") and features.transformer_weights:
        raise SystemExit("FeatureUnion transformer_weights are not supported")
    out, start = [], 0
    for _, v in parts:
        n = len(v.vocabulary_)
        out.append(vectorizer(v, coef[start:start + n]))
        start += n
    if start != len(coef):
        raise SystemExit("feature count mismatch")
    return out, num(clf.intercept_[0])


def platt(cal):
    """Calibrator = LogisticRegression on the raw decision: p = sigmoid(a * z + b)."""
    return {"a": num(cal.coef_[0][0]), "b": num(cal.intercept_[0])}


def export(name):
    worker, rel = MODELS[name]
    wdir = WORKERS / worker
    sys.path.insert(0, str(wdir))           # the pickles reference money_model / text_utils by module name
    src = wdir / rel
    m = joblib.load(src)
    doc = {"format": FORMAT, "name": f"{name}_en", "signal": name, "language": "en",
           "sklearn_version": sklearn.__version__, "source": str(src.relative_to(REPO)),
           "source_sha256": hashlib.sha256(src.read_bytes()).hexdigest()}
    if name == "urgency":
        pipe = m["pipeline"]
        doc["version"] = m.get("version", "v4")
        doc["threshold"] = num(m["threshold"])
        doc["cleaning"] = "urgency_en/text_norm.py + predict.windows (max over sentences and sentence pairs)"
        doc["vectorizers"], doc["intercept"] = linear_parts(pipe.named_steps["tfidf"], pipe.named_steps["clf"])
        doc["calibration"] = None
    elif name == "money":
        doc["version"] = m.version
        doc["threshold"] = num(m.threshold)
        doc["cleaning"] = "money_en/money_norm.py clean_text(mask_brands) + split_sentences (max over sentences)"
        # v2: the model says whether it was trained on brand-masked text; the brand list is data, kept with it
        import money_norm
        doc["mask_brands"] = bool(m.info.get("mask_brands", False))
        if doc["mask_brands"]:
            doc["brand_types"] = money_norm.BRAND_TYPES
        if len(m.pipe.steps) != 2:
            raise SystemExit(f"expected money pipeline features -> clf, got {[s for s, _ in m.pipe.steps]}")
        doc["vectorizers"], doc["intercept"] = linear_parts(m.pipe.steps[0][1], m.pipe.steps[1][1])
        doc["calibration"] = platt(m.calibrator)
    else:
        import text_utils
        base = m.base
        prep = base.steps[0][1]
        kw = prep.func.keywords if hasattr(prep.func, "keywords") else {}
        if not (kw.get("mask") and kw.get("caller")):
            raise SystemExit(f"impersonation prep must be mask=True, caller=True; got {kw}")
        card = json.loads((wdir / "model_card.json").read_text())
        doc["version"] = card.get("model_version", "unknown")
        doc["threshold"] = num(card.get("threshold_used_for_reporting", 0.5))
        doc["cleaning"] = "impersonation_en/text_utils.py prep(mask=True, caller=True), whole text"
        doc["org_types"] = text_utils.ORG_TYPES   # data used by mask_brands, kept with the model it was trained for
        doc["vectorizers"], doc["intercept"] = linear_parts(base.steps[1][1], base.steps[2][1])
        doc["calibration"] = platt(m.calibrator)

    OUT.mkdir(parents=True, exist_ok=True)
    out = OUT / f"{name}_en.json"
    out.write_text(json.dumps(doc, separators=(",", ":"), ensure_ascii=False))

    manifest_path = OUT / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {"format": FORMAT, "models": {}}
    manifest["models"][doc["name"]] = {
        "file": out.name, "version": doc["version"], "sklearn_version": doc["sklearn_version"],
        "source": doc["source"], "source_sha256": doc["source_sha256"],
        "sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
        "features": sum(len(v["terms"]) for v in doc["vectorizers"]),
        "exported_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
    }
    manifest["export"] = "json (vocabulary + idf + logistic regression weights); see tools/export_models.py for why not ONNX"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(f"{out.relative_to(REPO)}: {out.stat().st_size / 1e6:.2f} MB, "
          f"{manifest['models'][doc['name']]['features']} features, version {doc['version']}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", choices=sorted(MODELS))
    export(ap.parse_args().model)
