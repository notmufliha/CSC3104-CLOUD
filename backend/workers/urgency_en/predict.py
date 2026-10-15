"""
predict.py - load the trained urgency model and score raw text. Meant to be imported by server.py.

Reads   backend/workers/urgency_en/model/urgency_en.joblib (written by train.py / experiments.py)
Writes  nothing

  score_sentence(text) -> probability for ONE sentence (what the model was trained on)
  score(transcript)    -> (max probability, the window that produced it) over a whole call
  score_call(transcript) -> (probability, window, urgent yes/no) using the whole-call threshold

Speaker tags: if lines start with "Caller:" / "Receiver:" (also Agent/Customer, Suspect/Innocent, ...), only the
CALLER's lines are scored. Urgency is pressure the caller puts on the listener; a customer saying "as soon as
possible" about their own booking is not a scam signal. Untagged transcripts are scored whole.

Usage
  python backend/workers/urgency_en/predict.py "You must pay within 24 hours or your account is frozen"
"""
import re
import sys
from pathlib import Path

import joblib

from text_norm import normalize

DEFAULT_MODEL = Path(__file__).resolve().parent / "model" / "urgency_en.joblib"
# Split after . ! ? (speech-to-text output usually has these). A "sentence" longer than MAX_WORDS means the
# transcript has no punctuation, so it is cut into overlapping word windows instead.
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
MAX_WORDS = 40
WINDOW_WORDS, WINDOW_STEP = 20, 10
# Who rang the victim (scored) vs who answered (ignored). Same names text_norm strips as speaker prefixes.
CALLER_TAGS = {"caller", "agent", "suspect", "scammer"}
LISTENER_TAGS = {"receiver", "customer", "innocent", "victim", "user"}
_TAG_LINE = re.compile(r"^\s*([a-z]+)\s*:\s*(.*)$", re.I)
_bundle = None


def load_model(path=DEFAULT_MODEL):
    """Load the model once (slow) and keep it in memory so scoring is fast per request."""
    global _bundle
    _bundle = joblib.load(path)
    return _bundle


def _proba(texts):
    if _bundle is None:
        load_model()
    pipeline = _bundle["pipeline"]
    # Live text is raw, so it must be cleaned exactly like the training text, otherwise e.g. "24 hours"
    # never matches the learned "numtoken hours".
    proba = pipeline.predict_proba([normalize(t) for t in texts])
    return proba[:, list(pipeline.classes_).index(1)]


def score_sentence(text) -> float:
    """Probability (0-1) that ONE sentence is urgent. Compare with _bundle["threshold"] for a yes/no."""
    return float(_proba([text])[0])


def caller_turns(transcript):
    """The caller's turns if the transcript is speaker-tagged, else [whole transcript].

    A line without a tag continues the previous speaker's turn.
    """
    turns, tagged, speaker = [], False, None
    for line in str(transcript).splitlines():
        m = _TAG_LINE.match(line)
        if m and m.group(1).lower() in CALLER_TAGS | LISTENER_TAGS:
            tagged, speaker = True, m.group(1).lower()
            line = m.group(2)
        if speaker is None or speaker in CALLER_TAGS:
            turns.append(line)
    return turns if tagged else [str(transcript)]


def windows(transcript):
    """Sentences plus pairs of neighbouring sentences (so pressure split over two sentences still counts)."""
    sentences = []
    for turn in caller_turns(transcript):
        for s in _SENTENCE_END.split(turn):
            words = s.split()
            if len(words) <= MAX_WORDS:
                sentences.append(s)
            else:
                sentences += [" ".join(words[i:i + WINDOW_WORDS])
                              for i in range(0, max(len(words) - WINDOW_STEP, 1), WINDOW_STEP)]
    sentences = [s for s in sentences if normalize(s)]
    pairs = [f"{a} {b}" for a, b in zip(sentences, sentences[1:])]
    return sentences + pairs


def score(transcript):
    """(max probability over all windows, the window that gave it).

    WHY: the model learned from single sentences. Scoring a whole call as one text dilutes one urgent
    sentence among hundreds of normal words (0.96 alone -> 0.01 inside a call), so take the worst window.
    """
    w = windows(transcript)
    if not w:
        return 0.0, ""
    p = _proba(w)
    i = int(p.argmax())
    return float(p[i]), w[i]


def score_call(transcript):
    """(probability, strongest window, urgent?) for a whole call.

    Taking the max over many windows pushes normal calls up too, so calls use their own threshold
    (bundle["call_threshold"], set by train.py on normal calls only); single sentences use bundle["threshold"].
    """
    p, why = score(transcript)
    thr = _bundle.get("call_threshold", _bundle["threshold"])
    return p, why, p >= thr


if __name__ == "__main__":
    bundle = load_model()
    examples = sys.argv[1:] or [
        "Your account will be suspended within 24 hours unless you verify now.",
        "Sure, I can send you the menu for tomorrow's dinner.",
    ]
    print(f"sentence threshold = {bundle['threshold']:.4f}  call threshold = "
          f"{bundle.get('call_threshold', bundle['threshold']):.4f}")
    for text in examples:
        p, why, urgent = score_call(text)
        print(f"{p:.4f}  {'URGENT ' if urgent else 'not    '}  {text}")
        if why != text:
            print(f"        strongest window: {why}")
