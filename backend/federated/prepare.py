"""prepare.py - Workstream G.6: split each model's training data across simulated phones.

Each phone gets a different mix of sources, the way real users see different scams: for every source, the share
of its conversations that goes to each phone is drawn from a Dirichlet distribution (alpha = 0.5 by default; lower
= more uneven). This is the standard way federated-learning papers simulate non-IID users. Whole conversations
stay on one phone. Done separately for urgency, money and impersonation (each has its own datasets).

The text is hashed here with the frozen featurizer of the G.1 model (train_hashing.py), as a phone would do on the
device, so the phone image needs only numpy + scipy. Writes, under backend/federated/data/ (git-ignored, rebuilt by
the Docker build):
  phone_<i>_<model>.npz / .npy / _w.npy   features, labels, sample weights of phone i for that model
  slices.csv                              rows per phone, model, source and label (for the report)

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
import specs  # noqa: E402
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
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    tables = []
    for name in specs.SPECS:
        spec = specs.get(name)
        featurize = joblib.load(G1.model_path(name))["featurizer"]
        df = spec.training()["train"].reset_index(drop=True)
        df["phone"] = split(df, args.phones, args.alpha, args.seed)
        X = featurize.transform(df["text"].tolist()).tocsr().astype(np.float32)
        for p in range(args.phones):
            i = np.where(df["phone"].to_numpy() == p)[0]
            sparse.save_npz(OUT / f"phone_{p}_{name}.npz", X[i])
            np.save(OUT / f"phone_{p}_{name}.npy", df["label"].to_numpy()[i].astype(np.float32))
            np.save(OUT / f"phone_{p}_{name}_w.npy", df["weight"].to_numpy()[i].astype(np.float32))
        tables.append(df.groupby(["phone", "source", "label"]).size().rename("rows").reset_index().assign(model=name))
        summary = df.groupby("phone").agg(rows=("label", "size"), positive=("label", "sum"), sources=("source", "nunique"))
        print(f"\n{name}:\n{summary.to_string()}")
    pd.concat(tables).to_csv(OUT / "slices.csv", index=False)


if __name__ == "__main__":
    main()
