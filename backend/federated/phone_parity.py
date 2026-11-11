"""phone_parity.py - Workstream G.10 parity, Python side: the downloaded (hashing) models must score the same on
the phone as in Python.

Exports the three G.1 hashing models in the phone's format 2 (export_phone.py) to
backend/federated/data/phone_export/<signal>_en.json, and writes parity_expected.json there: for each sentence of
the app's existing parity set (android-app/app/src/androidTest/assets/parity/parity_cases.json, 317 sentences), the
score each model gives it the way the PHONE scores a caller line (OnDeviceScorer):
  urgency        highest score over the worker's windows (sentences + neighbouring pairs, predict.windows)
  money          highest score over the line's sentences
  impersonation  the whole line
The Kotlin test HashingParityTest reads both and fails if any score differs by more than 0.001.

  python backend/federated/phone_parity.py
  cd android-app && ./gradlew testDebugUnitTest --tests '*HashingParityTest*' --tests '*Murmur3Test*'
"""
import json
import sys
from pathlib import Path

import joblib
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import export_phone  # noqa: E402
import specs  # noqa: E402
import train_hashing as G1  # noqa: E402

OUT = HERE / "data" / "phone_export"
CASES = HERE.parents[1] / "android-app" / "app" / "src" / "androidTest" / "assets" / "parity" / "parity_cases.json"
TOLERANCE = 0.001   # float32 weights in the file vs float64 in Python: differences are around 1e-6


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    texts = [c["text"] for c in json.loads(CASES.read_text(encoding="utf-8"))["cases"]]
    expected = {t: {} for t in texts}
    for name in specs.SPECS:
        spec = specs.get(name)
        m = joblib.load(G1.model_path(name))
        doc = export_phone.to_phone_json(name, m["coef"], m["intercept"], m["featurizer"], m["platt"], m["threshold"],
                                         f"{spec.registry_name} {m['version']}", source="phone_parity")
        (OUT / spec.asset).write_text(json.dumps(doc), encoding="utf-8")
        # Score with the weights as the phone reads them (float32), so only the Kotlin port can make a difference.
        m = {**m, **export_phone.from_phone_json(doc)}
        if name == "urgency":
            predict = specs.load_module(specs.WORKERS / "urgency_en" / "predict.py", "urgency_predict")
            for t in texts:
                w = predict.windows(t)
                expected[t][name] = float(spec.proba(m, m["featurizer"], w).max()) if w else 0.0
        else:
            for t, p in zip(texts, spec.proba(m, m["featurizer"], texts)):
                expected[t][name] = float(p)
        print(f"{name}: wrote {spec.asset} ({(OUT / spec.asset).stat().st_size / 1e6:.1f} MB)")
    doc = {"tolerance": TOLERANCE, "cases": [{"text": t, **expected[t]} for t in texts]}
    (OUT / "parity_expected.json").write_text(json.dumps(doc, indent=1), encoding="utf-8")
    print(f"{len(texts)} sentences -> {OUT / 'parity_expected.json'}")


if __name__ == "__main__":
    main()
