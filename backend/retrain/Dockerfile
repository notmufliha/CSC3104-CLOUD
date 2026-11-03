# Workstream H.6/H.7: the retraining Job image (urgency_en). BUILD CONTEXT = REPO ROOT (it needs data/):
#   docker build -t scam/retrain:demo -f backend/retrain/Dockerfile .
# Files keep their repo layout under /repo, so train.py finds data/ exactly as on a laptop.
FROM python:3.11-slim
# Same versions the worker serves with (a joblib model only loads reliably in the version that wrote it).
RUN pip install --no-cache-dir pandas==2.2.3 numpy==1.26.4 scikit-learn==1.4.2 joblib==1.4.2
WORKDIR /repo
COPY data/urgency_en/final_cleaned_dataset.csv data/urgency_en/silver_relabel.csv \
     data/urgency_en/augment_review_flips.csv data/urgency_en/augment_urgency_en.csv data/urgency_en/
COPY backend/workers/urgency_en/train.py backend/workers/urgency_en/text_norm.py \
     backend/workers/urgency_en/predict.py backend/workers/urgency_en/
COPY backend/workers/urgency_en/eval backend/workers/urgency_en/eval
COPY backend/workers/urgency_en/checks backend/workers/urgency_en/checks
COPY backend/workers/urgency_en/training backend/workers/urgency_en/training
# The baseline the first retrain is compared against (v4, committed on purpose).
COPY backend/workers/urgency_en/model/urgency_en_v4.joblib backend/workers/urgency_en/model/
COPY backend/retrain/retrain_job.py backend/retrain/release_check.py backend/retrain/
WORKDIR /repo/backend/retrain
CMD ["python", "retrain_job.py"]
