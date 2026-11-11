"""prepare.py - Workstream G.6: split the urgency training data across simulated phones.

Each phone gets a different mix of sources, the way real users see different scams: for every source, the share
of its conversations that goes to each phone is drawn from a Dirichlet distribution (alpha = 0.5 by default; lower
= more uneven). This is the standard way federated-learning papers simulate non-IID users. Whole conversations
stay on one phone.

The text is hashed here with the frozen featurizer from G.1 (train_hashing.py), as a phone would do on the device,
so the phone image needs only numpy + scipy. Writes, under backend/federated/data/ (git-ignored, rebuilt by the
Docker build):
  featurizer.joblib       hashing settings + frozen IDF (from the G.1 model; no weights)
  phone_<i>.npz / .npy    features and labels of phone i
  slices.csv              rows per phone, source and label (for the report)

  python backend/federated/prepare.py [--phones 10] [--alpha 0.5]
"""
import argparse
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from scipy import sparse

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import train_hashing as G1  # noqa: E402

OUT = HERE / "data"


def split(df, phones, alpha, seed):
    rng = np.random.default_rng(seed)
    phone_of = {}
    for _, rows in df.groupby("source"):
        groups = rows["group"].drop_duplicates().to_numpy()
        rng.shuffle(groups)
        share = rng.dirichlet([alpha] * phones)
        cuts = (np.cumsum(share)[:-1] * len(groups)).astype(int)
        for p, chunk in enumerate(np.split(groups, cuts)):
            phone_of.update({g: p for g in chunk})
    return df["group"].map(phone_of).to_numpy()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phones", type=int, default=10)
    ap.add_argument("--alpha", type=float, default=0.5)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--model", type=Path, default=G1.MODEL_DIR / "urgency_en_hash_v1.joblib")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    featurize = joblib.load(args.model)["featurizer"]
    joblib.dump(featurize, OUT / "featurizer.joblib")

    df, _ = G1.load_training()
    df["phone"] = split(df, args.phones, args.alpha, args.seed)
    X = featurize.transform(df["text"].tolist()).tocsr().astype(np.float32)
    y = df["label"].to_numpy().astype(np.float32)
    for p in range(args.phones):
        i = np.where(df["phone"].to_numpy() == p)[0]
        sparse.save_npz(OUT / f"phone_{p}.npz", X[i])
        np.save(OUT / f"phone_{p}.npy", y[i])
    table = df.groupby(["phone", "source", "label"]).size().rename("rows").reset_index()
    table.to_csv(OUT / "slices.csv", index=False)
    summary = df.groupby("phone").agg(rows=("label", "size"), urgent=("label", "sum"),
                                      sources=("source", "nunique"))
    print(summary.to_string())


if __name__ == "__main__":
    main()
