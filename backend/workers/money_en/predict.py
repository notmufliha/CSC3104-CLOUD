"""
predict.py - load the trained Money/OTP model and score raw text. Imported by server.py.

Reads   model/money_en_v2c.joblib   (money_model.MoneyModel: word TF-IDF + LogisticRegression, Platt-calibrated;
                                     settings, data checksum and scores in model/money_en_v2c.json)

Score = P(the speaker ASKS for money, card/bank details or a code), per LABELING.md. A money word on its own
("never share your OTP", "your transfer was successful") scores low.

The scoring logic is the money team's own, unchanged: MoneyModel.score_chunk splits the transcript into
sentences (money_norm.split_sentences), cleans each one exactly like training (MoneyModel.clean, i.e.
money_norm.clean_text with the model's own mask_brands setting, applied inside predict_proba) and returns the
highest sentence score. So the RAW transcript goes in.

Usage
  python predict.py "Please read me the six digit code we just sent to your phone."
"""
import sys
from pathlib import Path

import joblib

import money_model  # noqa: F401 -- must be importable before joblib.load: the pickle references money_model.MoneyModel

HERE = Path(__file__).resolve().parent
DEFAULT_MODEL = HERE / "model" / "money_en_v2c.joblib"
_model = None


def load_model(path=DEFAULT_MODEL):
    """Load once (slow) and keep in memory so score() is fast per request. Returns the model."""
    global _model
    _model = joblib.load(path)
    return _model


def score(transcript):
    """(highest calibrated P(the speaker asks for money, card/bank details or a code), the sentence that gave it)."""
    if _model is None:
        load_model()
    return _model.score_chunk(str(transcript))


if __name__ == "__main__":
    m = load_model()
    print(f"model {m.version}  threshold {m.threshold}")
    for text in sys.argv[1:] or ["Please read me the six digit code we just sent to your phone.",
                                 "Never share your OTP with anyone.",
                                 "Hi, this is the clinic calling to confirm your appointment on Tuesday."]:
        p, best = score(text)
        print(f"{p:.4f}  {'MONEY' if p >= m.threshold else 'not  '}  {text}")
