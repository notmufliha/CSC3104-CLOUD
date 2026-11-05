"""
gRPC worker: signal="money", language="en".

Implements WorkerService.ClassifyTranscript with the trained Money/OTP model (see predict.py, money_model.py,
money_norm.py), plus the standard gRPC health service (grpc.health.v1) so Kubernetes can tell when the
worker is ready. Same shape as the urgency_en worker.

The score is predict.score(transcript): the highest calibrated probability, over the transcript's
sentences, that the speaker ASKS for money, card/bank details or a code (model money_en_v2c, rule in
LABELING.md). A money word alone ("never share your OTP") scores low, but a genuine agent asking for a card
number still scores high; telling scams apart is the aggregator's job with the other signals.

Rules every worker follows:
  - no model, no start: a missing model is a deployment error, not something to paper over with 0.5
  - health = SERVING only once the model has loaded
  - a scoring failure returns a gRPC INTERNAL error (the gateway then leaves this score out),
    never a made-up score
  - transcript text is never logged, only scores
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

SIGNAL = "money"
LANGUAGE = "en"
SERVICE_NAME = "scam_detect.WorkerService"
MODEL_PATH = os.environ.get("MODEL_PATH", str(predict.DEFAULT_MODEL))
PORT = os.environ.get("PORT", "50051")

logging.basicConfig(level=os.environ.get("LOGLEVEL", "INFO"))
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


class WorkerServiceServicer(scam_detect_pb2_grpc.WorkerServiceServicer):
    def __init__(self):
        if not os.path.exists(MODEL_PATH):
            raise FileNotFoundError(f"money model not found at {MODEL_PATH} - the model file is committed in model/")
        m = predict.load_model(MODEL_PATH)
        self.version = m.version or "unversioned"  # H.8: sent back with every score (e.g. "money_en_v2c")
        logger.info("loaded %s (version=%s, created=%s, threshold=%.2f)", MODEL_PATH, self.version,
                    m.info.get("created"), m.threshold)

    def ClassifyTranscript(self, request, context):
        try:
            score, _ = predict.score(request.transcript)
        except Exception as e:  # report the failure; never invent a score
            logger.error("scoring failed: %s", type(e).__name__)
            context.abort(grpc.StatusCode.INTERNAL, "money scoring failed")
        logger.info("score=%.4f version=%s", score, self.version)
        return scam_detect_pb2.WorkerScore(signal=SIGNAL, score=float(score), language=LANGUAGE,
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
