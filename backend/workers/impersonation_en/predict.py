"""
predict.py - load the trained impersonation model and score raw text. Imported by server.py.

Reads   model/model.pkl          (text_utils.CalibratedModel written by train.py)
        model_card.json          (version + reporting threshold, for start-up logging)

The saved model contains its own cleaning step (text_utils.prep: caller-only, lowercase, digit runs,
brand masking), so the RAW transcript goes straight in: training and serving cannot drift apart.

Usage
  python predict.py "This is the police. Transfer the money to the safe account now."
"""
import json
import sys
from pathlib import Path

import joblib

import text_utils  # noqa: F401 -- must be importable before joblib.load: the pickle references text_utils.*

HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = HERE / "model" / "model.pkl"
MODEL_CARD = HERE / "model_card.json"
_model = None
_card = {}


def load_model(path=DEFAULT_MODEL):
    """Load once (slow) and keep in memory so score() is fast per request. Returns the model card."""
    global _model, _card
    _model = joblib.load(path)
    _card = json.loads(MODEL_CARD.read_text(encoding="utf-8")) if MODEL_CARD.exists() else {}
    return _card


def score(transcript) -> float:
    """Calibrated probability (0-1) that the caller is impersonating an authority / trusted organisation."""
    if _model is None:
        load_model()
    return float(_model.predict_proba([str(transcript)])[0][1])


if __name__ == "__main__":
    card = load_model()
    thr = card.get("threshold_used_for_reporting", 0.5)
    print(f"model {card.get('model_version', '?')}  threshold {thr}")
    for text in sys.argv[1:] or ["This is the Singapore Police Force, your bank account is under investigation.",
                                 "Hi, this is the clinic calling to confirm your appointment on Tuesday."]:
        p = score(text)
        print(f"{p:.4f}  {'IMPERSONATION' if p >= thr else 'not         '}  {text}")
