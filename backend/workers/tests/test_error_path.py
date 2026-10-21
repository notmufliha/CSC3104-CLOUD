"""
test_error_path.py - prove a worker reports a scoring failure as a gRPC error, not a made-up score.

Runs INSIDE a worker image (it needs that worker's server.py, predict.py, model and generated proto stubs):

    docker compose up -d urgency_en
    docker compose exec -T urgency_en python - < backend/workers/tests/test_error_path.py

What it does, without changing any worker file:
  1. starts the worker's real servicer (this loads the real model) on a free local port
  2. swaps predict.score for a function that raises, sends one request
       -> must get grpc.StatusCode.INTERNAL, no score
       -> the log must say "scoring failed" and must NOT contain the transcript text
  3. puts the real predict.score back, sends a normal request
       -> must get a real score in [0, 1] (the worker survived the failure)
  4. checks the health service still says SERVING
Exits 0 when everything passes, 1 otherwise.
"""
import io
import logging
import sys
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc

import predict
import scam_detect_pb2 as pb
import scam_detect_pb2_grpc as rpc
import server

SECRET = "TRANSCRIPT-MARKER 请把钱转到安全账户 transfer the money now"
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{('  (' + detail + ')') if detail else ''}")


log_buffer = io.StringIO()
handler = logging.StreamHandler(log_buffer)
logging.getLogger().addHandler(handler)

print(f"error-path test: {server.SIGNAL}_{server.LANGUAGE}")
servicer = server.WorkerServiceServicer()  # real model load, same as production
grpc_server = grpc.server(futures.ThreadPoolExecutor(max_workers=2))
rpc.add_WorkerServiceServicer_to_server(servicer, grpc_server)
health_servicer = health.HealthServicer()
health_pb2_grpc.add_HealthServicer_to_server(health_servicer, grpc_server)
health_servicer.set(server.SERVICE_NAME, health_pb2.HealthCheckResponse.SERVING)
port = grpc_server.add_insecure_port("127.0.0.1:0")
grpc_server.start()
stub = rpc.WorkerServiceStub(grpc.insecure_channel(f"127.0.0.1:{port}"))
request = pb.TranscriptRequest(transcript=SECRET, language=server.LANGUAGE)

# 1) forced failure
real_score = predict.score


def broken(_):
    raise RuntimeError("forced failure for the error-path test")


predict.score = broken
try:
    reply = stub.ClassifyTranscript(request, timeout=10)
    check("failure returns a gRPC error", False, f"got a score instead: {reply.score}")
except grpc.RpcError as e:
    check("failure returns gRPC INTERNAL", e.code() == grpc.StatusCode.INTERNAL, f"got {e.code().name}")
    check("error message has no transcript text", "MARKER" not in (e.details() or ""), e.details())

logs = log_buffer.getvalue()
check("log records the failure", "scoring failed" in logs)
check("log has no transcript text", "MARKER" not in logs and "安全账户" not in logs)

# 2) recovery
predict.score = real_score
try:
    reply = stub.ClassifyTranscript(request, timeout=10)
    check("next request gets a real score", 0.0 <= reply.score <= 1.0, f"score={reply.score:.3f}")
except grpc.RpcError as e:
    check("next request gets a real score", False, e.code().name)

h = health_pb2_grpc.HealthStub(grpc.insecure_channel(f"127.0.0.1:{port}")).Check(
    health_pb2.HealthCheckRequest(service=server.SERVICE_NAME), timeout=5)
check("health still SERVING", h.status == health_pb2.HealthCheckResponse.SERVING)

grpc_server.stop(0)
print(f"  -> {sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
