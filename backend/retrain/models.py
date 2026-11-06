"""models.py - the six fast workers the retraining Job covers. Standard library only (retrain_job.py imports it).

python = which environment in the image trains this model. A joblib model only loads reliably in the
scikit-learn version that wrote it, so each model trains in the same versions its worker serves with:
  sk14 = scikit-learn 1.4.2 (urgency_en and the Chinese workers), sk16 = 1.6.1 (money_en, impersonation_en).
baked = the version number of the model baked into the worker image: the next release is baked + 1.

BERT is not here: it is a 136 MB DistilBERT fine-tuned in a GPU notebook, so it can't retrain in this CPU Job.
"""

MODELS = {
    "urgency_en": {"signal": "urgency", "language": "en", "python": "sk14", "baked": 4},
    "money_en": {"signal": "money", "language": "en", "python": "sk16", "baked": 2},
    "impersonation_en": {"signal": "impersonation", "language": "en", "python": "sk16", "baked": 4},
    "urgency_zh": {"signal": "urgency", "language": "zh", "python": "sk14", "baked": 1},
    "money_zh": {"signal": "money", "language": "zh", "python": "sk14", "baked": 1},
    "impersonation_zh": {"signal": "impersonation", "language": "zh", "python": "sk14", "baked": 1},
}
