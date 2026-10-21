"""
embed.py - sentence-transformers encoder as a scikit-learn step, so a MiniLM model saves/loads like the TF-IDF one.

The encoder weights are NOT pickled into the joblib bundle. They are loaded on first use from MINILM_PATH
(set in the Dockerfile to a copy downloaded at build time) or, outside the container, from the HuggingFace cache.

Windows only: `import torch` BEFORE scikit-learn is imported (i.e. first line of your script), otherwise torch's
c10.dll fails to initialise (WinError 1114) when a MiniLM bundle is loaded. Linux containers are not affected.
"""
import os

from sklearn.base import BaseEstimator, TransformerMixin

DEFAULT_MODEL = "sentence-transformers/all-MiniLM-L6-v2"
_loaded = {}


def load_encoder(name=DEFAULT_MODEL):
    """Load once per process. MINILM_PATH wins so the container never downloads at request time."""
    path = os.environ.get("MINILM_PATH") or name
    if path not in _loaded:
        from sentence_transformers import SentenceTransformer  # heavy import, only when actually used
        _loaded[path] = SentenceTransformer(path, device="cpu")
    return _loaded[path]


class MiniLMEncoder(BaseEstimator, TransformerMixin):
    """Text -> 384-d L2-normalised sentence vector. Stateless: fit() learns nothing."""

    def __init__(self, model_name=DEFAULT_MODEL, batch_size=64):
        self.model_name = model_name
        self.batch_size = batch_size

    def fit(self, X, y=None):
        return self

    def transform(self, X):
        return load_encoder(self.model_name).encode(list(X), batch_size=self.batch_size,
                                                    normalize_embeddings=True, show_progress_bar=False)
