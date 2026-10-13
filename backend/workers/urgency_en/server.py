"""
gRPC worker: signal="urgency", language="en".

Implements WorkerService.ClassifyTranscript with the trained urgency model (see predict.py / train.py).

The score is predict.score(transcript): the highest urgency probability over every sentence and pair of
neighbouring sentences. If the transcript's lines start with speaker tags ("Caller: ...", "Receiver: ..."),
only the caller's lines are scored. The raw probability is returned; the model's own cut-offs travel with the
bundle (threshold for one sentence, call_threshold for a whole call) and are logged at start-up.

The model must exist at start-up: a missing model is a deployment error, not something to paper over with a
neutral score.
"""
import logging
import os
from concurrent import futures

import grpc

import predict
import scam_detect_pb2
import scam_detect_pb2_grpc

SIGNAL = "urgency"
LANGUAGE = "en"
MODEL_PATH = os.environ.get("MODEL_PATH", str(predict.DEFAULT_MODEL))
PORT = os.environ.get("PORT", "50051")

logging.basicConfig(level=os.environ.get("LOGLEVEL", "INFO"))
logger = logging.getLogger(f"worker.{SIGNAL}.{LANGUAGE}")


class WorkerServiceServicer(scam_detect_pb2_grpc.WorkerServiceServicer):
    def __init__(self):
        if not os.path.exists(MODEL_PATH):
            raise FileNotFoundError(f"urgency model not found at {MODEL_PATH} - run train.py or mount the model")
        bundle = predict.load_model(MODEL_PATH)
        logger.info("loaded %s (version=%s, created_at=%s, threshold=%.4f, call_threshold=%.4f)", MODEL_PATH,
                    bundle.get("version", "unversioned"), bundle.get("created_at"), bundle["threshold"],
                    bundle.get("call_threshold", bundle["threshold"]))

    def ClassifyTranscript(self, request, context):
        score, window = predict.score(request.transcript)
        # Call content stays out of normal logs; LOGLEVEL=DEBUG shows which sentence drove the score.
        logger.info("score=%.4f", score)
        logger.debug("strongest window=%r", window[:120])
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
