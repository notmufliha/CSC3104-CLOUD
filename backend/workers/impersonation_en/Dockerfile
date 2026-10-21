FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/impersonation_en/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

# Only what serving needs: no training script, notebook, data or training outputs.
# text_utils.py must sit next to predict.py under this exact name: the pickled model references it.
COPY workers/impersonation_en/server.py workers/impersonation_en/predict.py workers/impersonation_en/text_norm.py \
     workers/impersonation_en/text_utils.py workers/impersonation_en/model_card.json ./
# model/model.pkl is committed in this worker's folder (see README), so it's baked in with no training step.
# Re-run train.py and rebuild if data/impersonation_en/*.csv changes.
COPY workers/impersonation_en/model/model.pkl model/model.pkl

EXPOSE 50051
CMD ["python", "server.py"]
