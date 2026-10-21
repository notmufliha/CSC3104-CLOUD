"""
predict.py - load the trained Money/OTP model and score raw text. Imported by server.py.

Reads   model/E_model.joblib   (money_model.MoneyModel: TF-IDF + LogisticRegression, Platt-calibrated)

The scoring logic is the money team's own, unchanged: MoneyModel.score_chunk splits the transcript into
sentences (money_norm.split_sentences), cleans each one exactly like training (money_norm.clean_text, applied
inside MoneyModel.predict_proba) and returns the highest sentence score. So the RAW transcript goes in.

Usage
  python predict.py "Please read me the six digit code we just sent to your phone."
"""
import sys
from pathlib import Path

import joblib

import money_model  # noqa: F401 -- must be importable before joblib.load: the pickle references money_model.MoneyModel

HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = HERE / "model" / "E_model.joblib"
_model = None


def load_model(path=DEFAULT_MODEL):
    """Load once (slow) and keep in memory so score() is fast per request. Returns the model."""
    global _model
    _model = joblib.load(path)
    return _model


def score(transcript):
    """(highest calibrated P(sentence involves money, a payment or an OTP), the sentence that gave it)."""
    if _model is None:
        load_model()
    return _model.score_chunk(str(transcript))


if __name__ == "__main__":
    m = load_model()
    print(f"model {m.version}  threshold {m.threshold}")
    for text in sys.argv[1:] or ["Please read me the six digit code we just sent to your phone.",
                                 "Hi, this is the clinic calling to confirm your appointment on Tuesday."]:
        p, best = score(text)
        print(f"{p:.4f}  {'MONEY' if p >= m.threshold else 'not  '}  {text}")
