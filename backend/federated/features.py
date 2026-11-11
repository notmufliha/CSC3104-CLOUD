"""features.py - Workstream G: the fixed feature space every phone and the server share.

Why hashing (G.1): TfidfVectorizer learns its word list from the data it is fitted on, so two phones fitting on
their own calls would get different word lists, and weight 1234 would mean a different word on each phone. Their
weights could not be averaged. HashingVectorizer maps every word (and word pair) to a fixed slot with a hash
function, so slot 1234 means the same thing on every phone without anyone sharing a word list.

The IDF weights (how rare each slot is) are fitted ONCE by the cloud on the public training data and then frozen.
They ship with the model, like the hash settings. Phones never refit them, so they only ever train the logistic
regression weights, and those can be averaged (FedAvg).

The tokenisation copies the TF-IDF urgency model (backend/workers/urgency_en/train.py): same cleaning
(text_norm.normalize), same token pattern (keeps "don't" as one token), same placeholder stop words, unigrams +
bigrams, sublinear tf.
"""
import sys
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import HashingVectorizer, TfidfTransformer
from sklearn.pipeline import Pipeline

REPO = Path(__file__).resolve().parents[2]
URGENCY_DIR = REPO / "backend" / "workers" / "urgency_en"
if str(URGENCY_DIR) not in sys.path:
    sys.path.insert(0, str(URGENCY_DIR))

from text_norm import normalize  # noqa: E402  the urgency worker's own cleaning, so serving matches training

N_FEATURES = 2 ** 18   # 262,144 slots; the TF-IDF model keeps at most 50,000 words, so collisions are rare
NGRAM = (1, 2)
TOKEN_PATTERN = r"(?u)\b[\w']+\b"
STOP_WORDS = ["numtoken", "timetoken", "moneytoken", "phonetoken", "urltoken", "emailtoken"]


def hasher() -> HashingVectorizer:
    # alternate_sign=False: every count stays positive, so the TF-IDF step behaves as it does on word counts.
    # norm=None: the TfidfTransformer after it normalises (after applying idf), exactly like TfidfVectorizer.
    return HashingVectorizer(n_features=N_FEATURES, ngram_range=NGRAM, token_pattern=TOKEN_PATTERN,
                             lowercase=True, stop_words=STOP_WORDS, alternate_sign=False, norm=None)


def featurizer() -> Pipeline:
    return Pipeline([("hash", hasher()), ("tfidf", TfidfTransformer(sublinear_tf=True))])


def clean(texts) -> list:
    return [normalize(t) for t in texts]


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-z))


def score(featurize: Pipeline, coef: np.ndarray, intercept: float, cleaned_texts) -> np.ndarray:
    """P(urgent) for already-cleaned texts, from raw weights (what FedAvg produces)."""
    X = featurize.transform(cleaned_texts)
    return sigmoid(X @ coef + intercept)
