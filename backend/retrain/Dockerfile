# Workstream H.6/H.7: the retraining Job image, all six fast workers. BUILD CONTEXT = REPO ROOT (it needs data/):
#   docker build -t scam/retrain:demo -f backend/retrain/Dockerfile .
# Files keep their repo layout under /repo, so each train.py finds its data exactly as on a laptop.
FROM python:3.11-slim

# Two environments, with the versions the workers serve with (a joblib model only loads reliably in the version
# that wrote it). See models.py for which model uses which.
RUN python -m venv /venv/sk14 && /venv/sk14/bin/pip install --no-cache-dir pandas==2.2.2 numpy==1.26.4 scipy==1.13.1 \
      scikit-learn==1.4.2 joblib==1.4.2 jieba==0.42.1 opencc-python-reimplemented==0.1.7
RUN python -m venv /venv/sk16 && /venv/sk16/bin/pip install --no-cache-dir pandas==2.2.2 numpy==2.1.3 scipy \
      scikit-learn==1.6.1 joblib==1.6.0
ENV PYTHONUNBUFFERED=1

WORKDIR /repo
# urgency_en
COPY data/urgency_en/final_cleaned_dataset.csv data/urgency_en/silver_relabel.csv \
     data/urgency_en/augment_review_flips.csv data/urgency_en/augment_urgency_en.csv data/urgency_en/
COPY backend/workers/urgency_en/train.py backend/workers/urgency_en/text_norm.py \
     backend/workers/urgency_en/predict.py backend/workers/urgency_en/
COPY backend/workers/urgency_en/eval backend/workers/urgency_en/eval
COPY backend/workers/urgency_en/checks backend/workers/urgency_en/checks
COPY backend/workers/urgency_en/training backend/workers/urgency_en/training
COPY backend/workers/urgency_en/model/urgency_en_v4.joblib backend/workers/urgency_en/model/
# money_en
COPY data/money_en/C_train_pool.csv data/money_en/
COPY backend/workers/money_en/train.py backend/workers/money_en/money_model.py \
     backend/workers/money_en/money_norm.py backend/workers/money_en/
COPY backend/workers/money_en/eval backend/workers/money_en/eval
COPY backend/workers/money_en/model/money_en_v2c.joblib backend/workers/money_en/model/
# impersonation_en
COPY data/impersonation_en/impersonation_data.csv data/impersonation_en/gold_test.csv data/impersonation_en/
COPY backend/workers/impersonation_en/train.py backend/workers/impersonation_en/text_utils.py \
     backend/workers/impersonation_en/model_card.json backend/workers/impersonation_en/
COPY backend/workers/impersonation_en/model/model.pkl backend/workers/impersonation_en/model/
# The three Chinese workers: their models aren't in git, the worker images build them from these same files. So
# this image builds the same baselines, in the same versions, to compare the first retrain against.
COPY backend/workers/common_zh/text_norm.py backend/workers/common_zh/train_zh.py \
     backend/workers/common_zh/gold_test_zh.csv backend/workers/common_zh/probes_zh.csv backend/workers/common_zh/
COPY backend/workers/urgency_zh/train.py backend/workers/urgency_zh/model_config.json backend/workers/urgency_zh/
COPY backend/workers/urgency_zh/data/urgency_zh.csv backend/workers/urgency_zh/data/
COPY backend/workers/money_zh/train.py backend/workers/money_zh/model_config.json backend/workers/money_zh/
COPY backend/workers/money_zh/data/money_zh.csv backend/workers/money_zh/data/money_zh_extra.csv \
     backend/workers/money_zh/data/
COPY backend/workers/impersonation_zh/train.py backend/workers/impersonation_zh/model_config.json \
     backend/workers/impersonation_zh/
COPY backend/workers/impersonation_zh/data/impersonation_zh.csv backend/workers/impersonation_zh/data/
RUN for s in urgency money impersonation; do (cd backend/workers/${s}_zh && /venv/sk14/bin/python train.py) || exit 1; done

COPY backend/retrain/models.py backend/retrain/adapters.py backend/retrain/retrain_one.py \
     backend/retrain/retrain_job.py backend/retrain/release_check.py backend/retrain/
WORKDIR /repo/backend/retrain
CMD ["python", "retrain_job.py"]
