"""
gRPC worker: signal="money", language="zh".

Implements WorkerService.ClassifyTranscript using a scikit-learn
TfidfVectorizer + LogisticRegression pipeline pickled by train.py into
model/model.pkl. See train.py for how to (re)train it.

TODO: this is a skeleton. Before relying on scores from this worker:
  - run train.py against a real labeled dataset for the "money" signal
  - validate the model file actually exists at container start, not just
    fall back silently (see _load_model below)
"""
import logging
import os
from concurrent import futures

import grpc
import joblib
import jieba

import scam_detect_pb2
import scam_detect_pb2_grpc

SIGNAL = "money"
LANGUAGE = "zh"
MODEL_PATH = os.environ.get("MODEL_PATH", os.path.join(os.path.dirname(__file__), "model", "model.pkl"))
PORT = os.environ.get("PORT", "50051")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


class _DummyModel:
    """Fallback used when model.pkl hasn't been trained yet, so the service
    is still callable end-to-end (always returns a neutral 0.5 score).
    TODO: remove once every worker has a real trained model checked in /
    mounted at deploy time.
    """

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
        # Binary classifier: class index 1 = positive ("money" present).
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
