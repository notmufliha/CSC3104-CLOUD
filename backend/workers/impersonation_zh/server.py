"""
gRPC worker: signal="impersonation", language="zh".

Implements WorkerService.ClassifyTranscript with the model built by train.py
(TF-IDF + Logistic Regression, sigmoid-calibrated) saved in model/model.joblib.

How a transcript is scored:
  1. split into sentences / short chunks   (zh_normalize.split_sentences)
  2. clean each chunk exactly like training (zh_normalize.prepare: NFKC, traditional->simplified,
     names/numbers/money -> tokens, ORG_* tokens for impersonation)
  3. score every chunk, return the MAX score
     (the model was trained on single sentences; scoring a long chunk at once dilutes the signal)

Quick test without gRPC:   python server.py --test "我是公安局的警官，请马上转账"
"""
import logging
import os
import sys
from concurrent import futures

import joblib

import zh_normalize as zn

SIGNAL = "impersonation"
LANGUAGE = "zh"
MODEL_PATH = os.environ.get("MODEL_PATH", os.path.join(os.path.dirname(os.path.abspath(__file__)), "model", "model.joblib"))
PORT = os.environ.get("PORT", "50051")
ALLOW_DUMMY = os.environ.get("ALLOW_DUMMY_MODEL", "0") == "1"   # only for local wiring tests

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


class _DummyBundle:
    """Neutral 0.5 scorer, used ONLY if ALLOW_DUMMY_MODEL=1 and no model file exists."""
    kind, threshold = "char", 0.5

    class model:
        @staticmethod
        def predict_proba(texts):
            import numpy as np
            return np.full((len(texts), 2), 0.5)

    def __getitem__(self, k):
        return getattr(self, k)


def _load_bundle():
    if os.path.exists(MODEL_PATH):
        b = joblib.load(MODEL_PATH)
        logger.info("loaded %s (kind=%s, threshold=%.2f)", MODEL_PATH, b["kind"], b["threshold"])
        if b["kind"] == "jieba":
            zn.jieba_join("预热")                # load the jieba dictionary now, not on the first request
        return b
    if ALLOW_DUMMY:
        logger.warning("no model at %s -> dummy 0.5 scorer (ALLOW_DUMMY_MODEL=1)", MODEL_PATH)
        return _DummyBundle()
    raise FileNotFoundError(f"no model at {MODEL_PATH}: run train.py (the Dockerfile does this at build time)")


BUNDLE = _load_bundle()


def score_transcript(text):
    score, best = zn.score_text(BUNDLE, text, SIGNAL)
    return score, best


def serve():
    import grpc
    import scam_detect_pb2
    import scam_detect_pb2_grpc

    class WorkerServiceServicer(scam_detect_pb2_grpc.WorkerServiceServicer):
        def ClassifyTranscript(self, request, context):
            try:
                score, best = score_transcript(request.transcript)
            except Exception:                    # never crash the pod on one bad transcript
                logger.exception("scoring failed")
                score, best = 0.0, ""
            logger.info("score=%.3f best=%r", score, best[:40])
            return scam_detect_pb2.WorkerScore(signal=SIGNAL, score=score, language=LANGUAGE)

    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    scam_detect_pb2_grpc.add_WorkerServiceServicer_to_server(WorkerServiceServicer(), server)
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    logger.info("%s/%s worker listening on :%s", SIGNAL, LANGUAGE, PORT)
    server.wait_for_termination()


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--test":
        s, best = score_transcript(" ".join(sys.argv[2:]))
        print(f"{SIGNAL}: {s:.3f} (threshold {BUNDLE['threshold']:.2f}) | strongest chunk: {best}")
    else:
        serve()
