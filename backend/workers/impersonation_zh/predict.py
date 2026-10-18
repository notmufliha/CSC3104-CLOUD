"""
predict.py - load the impersonation_zh model and score raw Chinese text. Imported by server.py.

Reads   model/model.joblib   dict(model, threshold, kind, worker, C) written by train.py (at image build)

The scoring logic is the ZH team's own, unchanged: text_norm.score_text splits the transcript into
sentences / short chunks, cleans each chunk exactly like training (text_norm.prepare), scores every chunk
and returns the MAX (the model was trained on single sentences; a long chunk dilutes the signal).
text_norm.py is the ONE shared copy from backend/workers/common_zh/.

Quick test without gRPC:   python predict.py "我是公安局的警官，请马上转账"
"""
import sys
from pathlib import Path

import joblib

HERE = Path(__file__).resolve().parent
# In the image text_norm.py sits next to this file; for local runs fall back to the shared copy.
sys.path.insert(1, str(HERE.parent / "common_zh"))
import text_norm as zn  # noqa: E402

SIGNAL = "impersonation"
DEFAULT_MODEL = HERE / "model" / "model.joblib"
_bundle = None


def load_model(path=DEFAULT_MODEL):
    """Load once and keep in memory. Returns the bundle (model, threshold, kind, worker, C)."""
    global _bundle
    _bundle = joblib.load(path)
    if _bundle["kind"] == "jieba":
        zn.jieba_join("预热")  # load the jieba dictionary now, not on the first request
    return _bundle


def score(transcript):
    """(max calibrated probability over the transcript's chunks, the chunk that gave it)."""
    if _bundle is None:
        load_model()
    return zn.score_text(_bundle, transcript, SIGNAL)


if __name__ == "__main__":
    b = load_model()
    s, best = score(" ".join(sys.argv[1:]) or "我是公安局的警官，请马上转账")
    print(f"{SIGNAL}: {s:.3f} (threshold {b['threshold']:.2f}) | strongest chunk: {best}")
