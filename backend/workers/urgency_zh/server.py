"""
gRPC worker: signal="urgency", language="zh".

Implements WorkerService.ClassifyTranscript with the model built by train.py (TF-IDF + Logistic Regression,
sigmoid-calibrated, trained inside the image at build time), plus the standard gRPC health service
(grpc.health.v1) so Kubernetes can tell when the worker is ready. Same shape as the urgency_en worker;
the scoring itself is the ZH team's logic (see predict.py and common_zh/text_norm.py).

Rules every worker follows:
  - no model, no start: a missing model is a deployment error, not something to paper over with 0.5
  - health = SERVING only once the model has loaded
  - a scoring failure returns a gRPC INTERNAL error (the gateway then leaves this score out),
    never a made-up score
  - transcript text is never logged, only scores

Quick test without gRPC:   python predict.py "我是公安局的警官，请马上转账"
"""
import logging
import os
import signal
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

import predict
import scam_detect_pb2
import scam_detect_pb2_grpc

SIGNAL = "urgency"
LANGUAGE = "zh"
SERVICE_NAME = "scam_detect.WorkerService"
MODEL_PATH = os.environ.get("MODEL_PATH", str(predict.DEFAULT_MODEL))
PORT = os.environ.get("PORT", "50051")

logging.basicConfig(level=os.environ.get("LOGLEVEL", "INFO"))
logging.getLogger("jieba").setLevel(logging.WARNING)
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


class WorkerServiceServicer(scam_detect_pb2_grpc.WorkerServiceServicer):
    def __init__(self):
        if not os.path.exists(MODEL_PATH):
            raise FileNotFoundError(f"no model at {MODEL_PATH}: run train.py (the Dockerfile does this at build time)")
        b = predict.load_model(MODEL_PATH)
        logger.info("loaded %s (kind=%s, C=%s, threshold=%.2f)", MODEL_PATH, b["kind"], b.get("C"), b["threshold"])

    def ClassifyTranscript(self, request, context):
        try:
            score, _ = predict.score(request.transcript)
        except Exception as e:  # report the failure; never invent a score
            logger.error("scoring failed: %s", type(e).__name__)
            context.abort(grpc.StatusCode.INTERNAL, "urgency scoring failed")
        logger.info("score=%.3f", score)
        return scam_detect_pb2.WorkerScore(signal=SIGNAL, score=score, language=LANGUAGE)


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
