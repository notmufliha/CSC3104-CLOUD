FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/urgency_zh/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

COPY workers/urgency_zh/ .
# TODO: bake a trained model/model.pkl into the image (or mount one at
# runtime) once train.py has been run against real data.

EXPOSE 50051
CMD ["python", "server.py"]
