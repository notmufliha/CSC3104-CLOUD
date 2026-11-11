"""fedcore.py - Workstream G: what one simulated phone does with the global model, and the shared settings.

Only numpy and scipy, so the phone image stays small. The features are already hashed (prepare.py), the way a
real phone would hash its own caller lines on the device.

The model is logistic regression on the fixed hashed features: weights (N_FEATURES) + one intercept. Flower
sends these as two numpy arrays and FedAvg averages them, weighted by how many lines each phone trained on.
"""
import os

import numpy as np
from scipy import sparse

N_FEATURES = 2 ** 18

# Local training: a few passes of mini-batch gradient descent on the phone's own labelled lines, starting from the
# global model. Defaults were tuned so 10 rounds of FedAvg land near the centrally trained model (README.md).
LOCAL_EPOCHS = int(os.environ.get("FL_LOCAL_EPOCHS", 3))
LEARNING_RATE = float(os.environ.get("FL_LR", 20.0))
BATCH = int(os.environ.get("FL_BATCH", 32))
L2 = float(os.environ.get("FL_L2", 1e-5))


def zeros():
    return [np.zeros(N_FEATURES, dtype=np.float32), np.zeros(1, dtype=np.float32)]


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))


def class_weights(y):
    """Balanced weights from this phone's own counts (a phone never knows the global counts).
    A phone with only one class keeps weight 1: there is nothing to balance against."""
    n, pos = len(y), int(y.sum())
    if pos == 0 or pos == n:
        return np.ones(n)
    return np.where(y == 1, n / (2 * pos), n / (2 * (n - pos)))


def local_train(params, X, y, epochs=None, lr=None, batch=None, l2=None, seed=0):
    """Mini-batch gradient descent on weighted log loss + L2, from the global params. Returns new params."""
    epochs, lr = epochs or LOCAL_EPOCHS, lr or LEARNING_RATE
    batch, l2 = batch or BATCH, (L2 if l2 is None else l2)
    w, b = params[0].astype(np.float64).copy(), float(params[1][0])
    sw = class_weights(y)
    rng = np.random.default_rng(seed)
    steps = -(-len(y) // batch)
    for _ in range(epochs):
        order = rng.permutation(len(y))
        for s in range(0, len(y), batch):
            i = order[s:s + batch]
            Xi = X[i]
            err = (sigmoid(Xi @ w + b) - y[i]) * sw[i]
            # Sparse gradient: only the slots these lines touch change (a phone's lines touch a few hundred).
            g = sparse.csr_matrix(err.reshape(1, -1)) @ Xi
            w[g.indices] -= lr * g.data / len(i)
            b -= lr * float(err.mean())
        # L2 shrink once per epoch (same total as a per-step shrink, without touching every weight each step).
        w *= (1.0 - lr * l2) ** steps
    return [w.astype(np.float32), np.array([b], dtype=np.float32)]


def clip_update(new, old, clipping_norm):
    """G.8: scale the phone's change (new - old) down so its L2 norm is at most clipping_norm. Clipping bounds how
    much one phone can move the model, which is what lets the server's noise hide any single phone."""
    delta = [n.astype(np.float64) - o.astype(np.float64) for n, o in zip(new, old)]
    norm = float(np.sqrt(sum(float((d ** 2).sum()) for d in delta)))
    scale = min(1.0, clipping_norm / norm) if norm > 0 else 1.0
    return [(o + d * scale).astype(np.float32) for o, d in zip(old, delta)], norm
