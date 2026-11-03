"""
gRPC worker: signal="urgency", language="en".

Implements WorkerService.ClassifyTranscript with the trained urgency model (see predict.py / train.py), plus
the standard gRPC health service (grpc.health.v1) so Kubernetes can tell when the worker is ready.

The score is predict.score(transcript): the highest urgency probability over every sentence and pair of
neighbouring sentences. If the transcript's lines start with speaker tags ("Caller: ...", "Receiver: ..."),
only the caller's lines are scored. The raw probability is returned; the model's own cut-offs travel with the
bundle (threshold for one sentence, call_threshold for a whole call) and are logged at start-up.

Rules every worker follows:
  - no model, no start: a missing model is a deployment error, not something to paper over with 0.5
  - health = SERVING only once the model has loaded
  - a scoring failure returns a gRPC INTERNAL error (the gateway then leaves this score out),
    never a made-up score
  - transcript text is never logged, only scores

Model versions (Workstream H.8): by default the model baked into the image is used (BAKED_MODEL_VERSION, v4).
If MODEL_VERSION is set (e.g. "v5"), that version is downloaded at start-up from the model registry
(MODEL_REGISTRY_URL, the review service) and its sha256 checked. The registry only holds versions that passed
the release check. If the download or check fails the worker refuses to start, so a rolling update to a bad
version stalls with the old pods still serving. Every reply carries model_version.
"""
import hashlib
import json
import logging
import os
import signal
import tempfile
import urllib.request
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

import predict
import scam_detect_pb2
import scam_detect_pb2_grpc

SIGNAL = "urgency"
LANGUAGE = "en"
SERVICE_NAME = "scam_detect.WorkerService"
MODEL_PATH = os.environ.get("MODEL_PATH", str(predict.DEFAULT_MODEL))
MODEL_NAME = f"{SIGNAL}_{LANGUAGE}"
MODEL_VERSION = os.environ.get("MODEL_VERSION", "").strip()
MODEL_REGISTRY_URL = os.environ.get("MODEL_REGISTRY_URL", "http://review:8090").rstrip("/")
BAKED_MODEL_VERSION = os.environ.get("BAKED_MODEL_VERSION", "")
PORT = os.environ.get("PORT", "50051")

logging.basicConfig(level=os.environ.get("LOGLEVEL", "INFO"))
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


def download_model(version: str) -> str:
    """Fetches a released model version from the registry into a temp file; checks its sha256."""
    base = f"{MODEL_REGISTRY_URL}/models/{MODEL_NAME}/{version}"
    with urllib.request.urlopen(base, timeout=30) as r:
        meta = json.load(r)
    path = os.path.join(tempfile.gettempdir(), f"{MODEL_NAME}_{version}.joblib")
    digest = hashlib.sha256()
    with urllib.request.urlopen(base + "/file", timeout=120) as r, open(path, "wb") as f:
        for chunk in iter(lambda: r.read(1 << 20), b""):
            digest.update(chunk)
            f.write(chunk)
    if digest.hexdigest() != meta["sha256"]:
        raise ValueError(f"{MODEL_NAME} {version}: sha256 mismatch, refusing to load")
    return path


class WorkerServiceServicer(scam_detect_pb2_grpc.WorkerServiceServicer):
    def __init__(self):
        if MODEL_VERSION:
            path = download_model(MODEL_VERSION)
            logger.info("downloaded %s %s from %s", MODEL_NAME, MODEL_VERSION, MODEL_REGISTRY_URL)
        else:
            path = MODEL_PATH
        if not os.path.exists(path):
            raise FileNotFoundError(f"urgency model not found at {path} - run train.py or mount the model")
        bundle = predict.load_model(path)
        self.version = MODEL_VERSION or bundle.get("version") or BAKED_MODEL_VERSION or "unversioned"
        logger.info("loaded %s (version=%s, created_at=%s, threshold=%.4f, call_threshold=%.4f)", path,
                    self.version, bundle.get("created_at"), bundle["threshold"],
                    bundle.get("call_threshold", bundle["threshold"]))

    def ClassifyTranscript(self, request, context):
        try:
            score, _ = predict.score(request.transcript)
        except Exception as e:  # report the failure; never invent a score
            logger.error("scoring failed: %s", type(e).__name__)
            context.abort(grpc.StatusCode.INTERNAL, "urgency scoring failed")
        logger.info("score=%.4f version=%s", score, self.version)
        return scam_detect_pb2.WorkerScore(signal=SIGNAL, score=score, language=LANGUAGE,
                                           model_version=self.version)


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    scam_detect_pb2_grpc.add_WorkerServiceServicer_to_server(WorkerServiceServicer(), server)  # loads the model
    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    for name in ("", SERVICE_NAME):
        health_servicer.set(name, health_pb2.HealthCheckResponse.SERVING)
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    logger.info("%s/%s worker listening on :%s", SIGNAL, LANGUAGE, PORT)

    def stop(*_):  # Kubernetes sends SIGTERM: report NOT_SERVING, finish in-flight calls, exit
        for name in ("", SERVICE_NAME):
            health_servicer.set(name, health_pb2.HealthCheckResponse.NOT_SERVING)
        server.stop(grace=5)

    signal.signal(signal.SIGTERM, stop)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
