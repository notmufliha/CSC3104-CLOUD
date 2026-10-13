"""
predict.py - load the trained urgency model and score raw text. Meant to be imported by server.py.

Reads   backend/workers/urgency_en/model/urgency_en.joblib (written by train.py)
Writes  nothing

Usage
  python backend/workers/urgency_en/predict.py "You must pay within 24 hours or your account is frozen"
"""
import sys
from pathlib import Path

import joblib

from text_norm import normalize

DEFAULT_MODEL = Path(__file__).resolve().parent / "model" / "urgency_en.joblib"
_bundle = None


def load_model(path=DEFAULT_MODEL):
    """Load the model once (slow) and keep it in memory so score() is fast per request."""
    global _bundle
    _bundle = joblib.load(path)
    return _bundle


def score(text) -> float:
    """Probability (0-1) that the text is urgent. Compare with _bundle["threshold"] for a yes/no."""
    if _bundle is None:
        load_model()
    pipeline = _bundle["pipeline"]
    # Live text is raw, so it must be cleaned exactly like the training text, otherwise e.g. "24 hours"
    # never matches the learned "numtoken hours".
    proba = pipeline.predict_proba([normalize(text)])[0]
    return float(proba[list(pipeline.classes_).index(1)])


if __name__ == "__main__":
    bundle = load_model()
    examples = sys.argv[1:] or [
        "Your account will be suspended within 24 hours unless you verify now.",
        "Sure, I can send you the menu for tomorrow's dinner.",
    ]
    print(f"threshold = {bundle['threshold']:.4f}")
    for text in examples:
        p = score(text)
        print(f"{p:.4f}  {'URGENT ' if p >= bundle['threshold'] else 'not    '}  {text}")
