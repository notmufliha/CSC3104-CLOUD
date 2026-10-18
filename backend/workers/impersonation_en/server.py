"""
gRPC worker: signal="impersonation", language="en".

Scores "is the caller falsely claiming to be an authority or trusted
organisation" (police, bank, government, telco, courier, tech support)
using a calibrated TF-IDF + LogisticRegression pipeline, trained and
pickled by train.py into model/model.pkl. See model_card.json for the
exact training settings/metrics, and ../../../README.md's "impersonation_en"
section for the version-pinning note.

The pickled model is a text_utils.CalibratedModel wrapping a pipeline
whose first step is a FunctionTransformer over text_utils.prep, so
`import text_utils` below must succeed BEFORE joblib.load, or unpickling
fails with "Can't get attribute ...".
"""
import logging
import os
from concurrent import futures

import grpc
import joblib

import text_utils  # noqa: F401 -- required so joblib.load can resolve the pickled pipeline's custom step/class
import scam_detect_pb2
import scam_detect_pb2_grpc

SIGNAL = "impersonation"
LANGUAGE = "en"
MODEL_PATH = os.environ.get("MODEL_PATH", os.path.join(os.path.dirname(__file__), "model", "model.pkl"))
PORT = os.environ.get("PORT", "50051")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


class _DummyModel:
    """Fallback if model/model.pkl is ever missing (e.g. a volume mount
    gone wrong), so the service stays callable end-to-end with a neutral
    0.5 score instead of crashing. The real model is checked into this
    worker's model/ directory, so this should not normally be hit."""

    def predict_proba(self, texts):
        return [[0.5, 0.5] for _ in texts]


def _load_model():
    if os.path.exists(MODEL_PATH):
        logger.info("loading model from %s", MODEL_PATH)
        return joblib.load(MODEL_PATH)
    logger.warning("no model found at %s, using dummy 0.5 scorer -- run train.py", MODEL_PATH)
    return _DummyModel()


class WorkerServiceServicer(scam_detect_pb2_grpc.WorkerServiceServicer):
    def __init__(self):
        self.model = _load_model()

    def ClassifyTranscript(self, request, context):
        proba = self.model.predict_proba([request.transcript])[0]
        # Binary classifier: class index 1 = positive ("impersonation" present).
        score = float(proba[1]) if len(proba) > 1 else float(proba[0])
        return scam_detect_pb2.WorkerScore(signal=SIGNAL, score=score, language=LANGUAGE)


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    scam_detect_pb2_grpc.add_WorkerServiceServicer_to_server(WorkerServiceServicer(), server)
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    logger.info("%s/%s worker listening on :%s", SIGNAL, LANGUAGE, PORT)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
