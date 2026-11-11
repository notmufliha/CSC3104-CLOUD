# Workstream G.6-G.10: one image for the Flower server and the simulated phones. BUILD CONTEXT = REPO ROOT:
#   docker build -t scam/federated:demo -f backend/federated/Dockerfile .
# The build trains the G.1 hashing models (urgency, money, impersonation) and splits their data across the phones
# (prepare.py), so the image holds everything a run needs: phone slices (hashed features only), the frozen
# featurizers and calibrations, and the fixed test sets.
FROM python:3.12-slim
RUN pip install --no-cache-dir flwr==1.12.0 scikit-learn==1.4.2 numpy==1.26.4 scipy==1.13.1 pandas==2.2.2 \
      joblib==1.4.2
ENV PYTHONUNBUFFERED=1 PYTHONIOENCODING=utf-8
WORKDIR /repo
# urgency_en
COPY data/urgency_en/final_cleaned_dataset.csv data/urgency_en/silver_relabel.csv \
     data/urgency_en/augment_review_flips.csv data/urgency_en/augment_urgency_en.csv data/urgency_en/
COPY backend/workers/urgency_en/train.py backend/workers/urgency_en/text_norm.py backend/workers/urgency_en/
COPY backend/workers/urgency_en/eval backend/workers/urgency_en/eval
COPY backend/workers/urgency_en/checks backend/workers/urgency_en/checks
COPY backend/workers/urgency_en/training backend/workers/urgency_en/training
# money_en
COPY data/money_en/C_train_pool.csv data/money_en/
COPY backend/workers/money_en/money_norm.py backend/workers/money_en/
COPY backend/workers/money_en/eval backend/workers/money_en/eval
# impersonation_en
COPY data/impersonation_en/impersonation_data.csv data/impersonation_en/gold_test.csv data/impersonation_en/
COPY backend/workers/impersonation_en/train.py backend/workers/impersonation_en/text_utils.py \
     backend/workers/impersonation_en/
# The app's bundled model files: the published phone files copy their cleaning settings (brands, org types).
COPY android-app/app/src/main/assets/models/urgency_en.json android-app/app/src/main/assets/models/money_en.json \
     android-app/app/src/main/assets/models/impersonation_en.json android-app/app/src/main/assets/models/
COPY backend/retrain/release_check.py backend/retrain/
COPY backend/federated/*.py backend/federated/
COPY backend/federated/reports/tfidf_baselines.json backend/federated/reports/
# G.1 (deterministic: fixed seeds), then the phone slices. The training text is deleted afterwards: phones only
# carry their own hashed features, and the server only needs the test sets.
RUN cd backend/federated && python train_hashing.py && python prepare.py \
    && rm -f /repo/data/urgency_en/*.csv /repo/data/money_en/*.csv /repo/data/impersonation_en/impersonation_data.csv
WORKDIR /repo/backend/federated
# The Job sets the command: server.py for the coordinator, client.py for each phone.
CMD ["python", "server.py"]
