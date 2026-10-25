FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/urgency_zh/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

COPY workers/urgency_zh/server.py workers/urgency_zh/predict.py workers/urgency_zh/train.py workers/urgency_zh/model_config.json ./
COPY workers/urgency_zh/data/urgency_zh.csv data/urgency_zh.csv
# The ONE shared ZH cleaning + training code, gold set and probes (same files for all three ZH workers).
COPY workers/common_zh/text_norm.py workers/common_zh/train_zh.py ./
COPY workers/common_zh/gold_test_zh.csv workers/common_zh/probes_zh.csv data/
# Train inside the image so the model is pickled with the SAME scikit-learn version
# that server.py uses (~1 min). The build FAILS if the train/serve parity check or a canonical
# test sentence fails (see common_zh/train_zh.py). Training data is removed afterwards.
RUN python train.py && rm -rf data
# warm the jieba cache so the first request is fast
RUN python -c "import jieba; jieba.initialize()"

EXPOSE 50051
CMD ["python", "server.py"]
