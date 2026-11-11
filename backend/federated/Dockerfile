# Workstream G.6-G.9: one image for the Flower server and the simulated phones. BUILD CONTEXT = REPO ROOT:
#   docker build -t scam/federated:demo -f backend/federated/Dockerfile .
# The build trains the G.1 hashing model and splits the data across the phones (prepare.py), so the image holds
# everything a run needs: phone slices (hashed features only), the frozen featurizer, the fixed test sets.
FROM python:3.12-slim
RUN pip install --no-cache-dir flwr==1.12.0 scikit-learn==1.4.2 numpy==1.26.4 scipy==1.13.1 pandas==2.2.2 \
      joblib==1.4.2
ENV PYTHONUNBUFFERED=1 PYTHONIOENCODING=utf-8
WORKDIR /repo
COPY data/urgency_en/final_cleaned_dataset.csv data/urgency_en/silver_relabel.csv \
     data/urgency_en/augment_review_flips.csv data/urgency_en/augment_urgency_en.csv data/urgency_en/
COPY backend/workers/urgency_en/train.py backend/workers/urgency_en/text_norm.py backend/workers/urgency_en/
COPY backend/workers/urgency_en/eval backend/workers/urgency_en/eval
COPY backend/workers/urgency_en/checks backend/workers/urgency_en/checks
COPY backend/workers/urgency_en/training backend/workers/urgency_en/training
COPY backend/workers/urgency_en/model/urgency_en_v4.joblib backend/workers/urgency_en/model/
COPY backend/retrain/release_check.py backend/retrain/
COPY backend/federated/*.py backend/federated/
# G.1 (deterministic: fixed seed), then the phone slices. The training text is deleted afterwards: phones only
# carry their own hashed features, and the server only needs the test sets.
RUN python backend/federated/train_hashing.py && python backend/federated/prepare.py \
    && rm -f data/urgency_en/*.csv
WORKDIR /repo/backend/federated
# The Job sets the command: server.py for the coordinator, client.py for each phone.
CMD ["python", "server.py"]
