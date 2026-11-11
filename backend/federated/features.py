"""features.py - Workstream G: the fixed feature space every phone and the server share (hashing).

Why hashing (G.1): TfidfVectorizer learns its word list from the data it is fitted on, so two phones fitting on
their own calls would get different word lists, and weight 1234 would mean a different word on each phone. Their
weights could not be averaged. HashingVectorizer maps every word (or character n-gram) to a fixed slot with a hash
function, so slot 1234 means the same thing on every phone without anyone sharing a word list.

The IDF weights (how rare each slot is) are fitted ONCE by the cloud on the public training data and then frozen.
They ship with the model, like the hash settings. Phones never refit them, so they only ever train the logistic
regression weights, and those can be averaged (FedAvg). The same holds for the Platt calibration of money and
impersonation (specs.py): fitted centrally, frozen during federated rounds.

A model has one or more "parts" (impersonation: words + character n-grams), each hashed into its own N_FEATURES
slots, TF-IDF weighted (sublinear tf) and l2-normalised on its own, exactly like the TF-IDF workers do it.
"""
from dataclasses import asdict, dataclass, field

import numpy as np
from sklearn.feature_extraction.text import HashingVectorizer, TfidfTransformer
from sklearn.pipeline import FeatureUnion, Pipeline

N_FEATURES = 2 ** 18   # 262,144 slots per part; the TF-IDF workers keep 30,000-50,000 words, so collisions are rare
DEFAULT_TOKEN = r"(?u)\b\w\w+\b"   # scikit-learn's default


@dataclass(frozen=True)
class Part:
    analyzer: str = "word"                  # "word" or "char_wb"
    ngram: tuple = (1, 2)
    token_pattern: str = DEFAULT_TOKEN
    stop_words: tuple = ()
    lowercase: bool = True
    n_features: int = N_FEATURES
    extra: dict = field(default_factory=dict, compare=False)

    def hasher(self) -> HashingVectorizer:
        # alternate_sign=False: counts stay positive, so the TF-IDF step behaves as it does on word counts.
        # norm=None: the TfidfTransformer after it normalises (after applying idf), exactly like TfidfVectorizer.
        return HashingVectorizer(n_features=self.n_features, analyzer=self.analyzer, ngram_range=tuple(self.ngram),
                                 token_pattern=self.token_pattern if self.analyzer == "word" else None,
                                 stop_words=list(self.stop_words) or None, lowercase=self.lowercase,
                                 alternate_sign=False, norm=None)

    def describe(self) -> dict:
        d = asdict(self)
        d.pop("extra")
        d["ngram"], d["stop_words"] = list(self.ngram), list(self.stop_words)
        return d


def featurizer(parts) -> FeatureUnion:
    return FeatureUnion([(f"part{i}", Pipeline([("hash", p.hasher()), ("tfidf", TfidfTransformer(sublinear_tf=True))]))
                         for i, p in enumerate(parts)])


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))
