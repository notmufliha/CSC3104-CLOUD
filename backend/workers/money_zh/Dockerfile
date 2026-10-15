FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/money_zh/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

COPY workers/money_zh/ .
# Train inside the image so the model is pickled with the SAME scikit-learn version
# that server.py uses (~1 min). Needs data/money_zh.csv in this folder.
RUN python train.py && rm -rf data
# warm the jieba cache so the first request is fast
RUN python -c "import jieba; jieba.initialize()"

EXPOSE 50051
CMD ["python", "server.py"]
