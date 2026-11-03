# Workstream H: review queue + labelling page + model registry (internal only, see app.py).
# Build context: backend/ (like every other service).
FROM python:3.11-slim
WORKDIR /app
COPY review/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY review/app.py review/store.py review/mask.py review/labelling.html ./
# Queue database + released model files. A PersistentVolume in Kubernetes, a named volume in docker compose.
VOLUME /data
ENV REVIEW_DATA_DIR=/data
EXPOSE 8090
# One process: SQLite + the purge thread assume a single writer (the Deployment has 1 replica).
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
