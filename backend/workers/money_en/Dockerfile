FROM python:3.11-slim
# NOTE: build context must be backend/ (see docker-compose.yml), since the
# proto file is shared across all workers and lives at backend/proto.
WORKDIR /app

COPY proto ./proto
COPY workers/money_en/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Generate scam_detect_pb2.py / scam_detect_pb2_grpc.py from the shared proto.
RUN python -m grpc_tools.protoc -I proto --python_out=. --grpc_python_out=. scam_detect.proto

# Only what serving needs. money_model.py and money_norm.py must keep these exact names:
# the pickled model references money_model.MoneyModel, which imports money_norm.
COPY workers/money_en/server.py workers/money_en/predict.py workers/money_en/text_norm.py \
     workers/money_en/money_model.py workers/money_en/money_norm.py ./
COPY workers/money_en/model/money_en_v2c.joblib model/money_en_v2c.joblib

EXPOSE 50051
CMD ["python", "server.py"]
