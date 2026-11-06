FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/urgency_en/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

# Only what serving needs: no training scripts, data, eval sets or reports.
COPY workers/urgency_en/server.py workers/urgency_en/predict.py workers/urgency_en/text_norm.py ./
# The frozen v4 model (model/v4.json has its sha256). It is gitignored, so it must exist locally before
# building: run train.py, or copy urgency_en_v4.joblib into workers/urgency_en/model/.
COPY workers/urgency_en/model/urgency_en_v4.joblib model/urgency_en.joblib
# Workstream H.8: the version the baked model reports. Set MODEL_VERSION to load a newer released version
# from the model registry instead (see server.py).
ENV BAKED_MODEL_VERSION=v4

# H.8: loads a released version from the model registry when MODEL_VERSION is set.
COPY workers/common/model_registry.py ./

EXPOSE 50051
CMD ["python", "server.py"]
