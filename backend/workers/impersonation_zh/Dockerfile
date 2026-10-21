FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/impersonation_zh/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

COPY workers/impersonation_zh/server.py workers/impersonation_zh/predict.py workers/impersonation_zh/train.py ./
COPY workers/impersonation_zh/data/impersonation_zh.csv data/impersonation_zh.csv
# The ONE shared ZH cleaning code + gold set (same files for all three ZH workers).
COPY workers/common_zh/text_norm.py ./
COPY workers/common_zh/gold_test_zh.csv data/gold_test_zh.csv
# Train inside the image so the model is pickled with the SAME scikit-learn version
# that server.py uses (~1 min). Training data is removed afterwards.
RUN python train.py && rm -rf data
# warm the jieba cache so the first request is fast
RUN python -c "import jieba; jieba.initialize()"

EXPOSE 50051
CMD ["python", "server.py"]
