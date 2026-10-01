"""
Trains the "urgency" (en) TfidfVectorizer + LogisticRegression
pipeline and pickles it to model/model.pkl for server.py to load.

TODO: CSV_PATH is a placeholder. Point it at a real dataset of
(text, label) pairs for this signal/language before training, where
label=1 means the transcript exhibits "urgency" and label=0 means it
doesn't.
"""
import os

import joblib
import pandas as pd

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline

# TODO: replace with the real dataset path (columns: text,label).
CSV_PATH = "TODO/path/to/urgency_en_dataset.csv"
MODEL_PATH = os.path.join(os.path.dirname(__file__), "model", "model.pkl")


def main():
    df = pd.read_csv(CSV_PATH)

    pipeline = Pipeline(
        [
            ("tfidf", TfidfVectorizer(max_features=5000, ngram_range=(1, 2))),
            ("clf", LogisticRegression(max_iter=1000)),
        ]
    )
    pipeline.fit(df["text"], df["label"])

    os.makedirs(os.path.dirname(MODEL_PATH), exist_ok=True)
    joblib.dump(pipeline, MODEL_PATH)
    print(f"saved {MODEL_PATH}")


if __name__ == "__main__":
    main()
