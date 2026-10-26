FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/bert/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

# Code + model/ (model/ is git-ignored: either copy the notebook's 3 files into backend/workers/bert/model/
# before building, or let fetch_model.py download them from MODEL_BASE_URL).
COPY workers/bert/ ./
ARG MODEL_BASE_URL="https://huggingface.co/gouthamsaravanan/csc3104-scam-bert/resolve/a0ebfbc63c1591a889798d7828cd5c65e063c57a"
ARG MODEL_SHA256="de0f771d861a5029666d97736f9715bef097b52eead991c973a67855d82f0c1c"
RUN MODEL_BASE_URL="$MODEL_BASE_URL" MODEL_SHA256="$MODEL_SHA256" python fetch_model.py \
    && python predict.py "您好，我是警察" "请马上转账"

ENV ORT_THREADS=2 GRPC_THREADS=2 OMP_NUM_THREADS=2
EXPOSE 50051
CMD ["python", "server.py"]
