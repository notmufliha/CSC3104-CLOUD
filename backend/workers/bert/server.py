"""
gRPC worker: the BERT second opinion (BertService.ScoreContext), see backend/proto/scam_detect.proto.

The aggregator calls it only when the six fast workers' vote is unsure or alarmed (the cascade, task 7.4),
sending the call's last caller lines; it returns P(scam) for those lines read together. Same rules as every
other worker:
  - no model, no start (predict.load_model raises; the container exits)
  - gRPC health (grpc.health.v1) = SERVING only once the model has loaded and answered a warm-up request
  - a scoring failure returns gRPC INTERNAL (the aggregator then decides without BERT), never a made-up score
  - transcript text is never logged, only scores and timings

Quick test without gRPC:   python predict.py "您好，我是警察" "请马上转账"
"""
import logging
import os
import signal
import time
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

import predict
import scam_detect_pb2
import scam_detect_pb2_grpc

SERVICE_NAME = "scam_detect.BertService"
PORT = os.environ.get("PORT", "50051")
MAX_LINES = 10

logging.basicConfig(level=os.environ.get("LOGLEVEL", "INFO"))
logger = logging.getLogger("worker.bert")


class BertServicer(scam_detect_pb2_grpc.BertServiceServicer):
    def __init__(self):
        predict.load_model()
        logger.info("loaded BERT model %s (threads=%s)", predict.version(), os.environ.get("ORT_THREADS", "2"))

    def ScoreContext(self, request, context):
        t0 = time.perf_counter()
        try:
            s = predict.score(list(request.lines)[-MAX_LINES:])
        except Exception as e:  # report the failure; never invent a score
            logger.error("scoring failed: %s", type(e).__name__)
            context.abort(grpc.StatusCode.INTERNAL, "bert scoring failed")
        logger.info("score=%.3f lines=%d lang=%s ms=%.0f", s, len(request.lines), request.language or "-",
                    (time.perf_counter() - t0) * 1000)
        return scam_detect_pb2.BertScore(score=s, model_version=predict.version())


def serve():
    # gRPC threads x ORT threads should not exceed the pod's CPU limit (2 x 2 = 4 by default)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=int(os.environ.get("GRPC_THREADS", "2"))))
    scam_detect_pb2_grpc.add_BertServiceServicer_to_server(BertServicer(), server)  # loads the model
    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    for name in ("", SERVICE_NAME):
        health_servicer.set(name, health_pb2.HealthCheckResponse.SERVING)
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    logger.info("bert worker listening on :%s", PORT)

    def stop(*_):  # Kubernetes sends SIGTERM: report NOT_SERVING, finish in-flight calls, exit
        for name in ("", SERVICE_NAME):
            health_servicer.set(name, health_pb2.HealthCheckResponse.NOT_SERVING)
        server.stop(grace=5)

    signal.signal(signal.SIGTERM, stop)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
