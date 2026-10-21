"""money_model.py: the trained Money/OTP model with calibration and explanations."""
import numpy as np
from money_norm import clean_text, split_sentences


class MoneyModel:
    """TF-IDF + logistic regression, calibrated with Platt scaling.
    score = probability that a sentence involves money, a payment or an OTP."""

    def __init__(self, pipe, calibrator, version, threshold=0.5, info=None):
        self.pipe = pipe              # sklearn Pipeline: features -> LogisticRegression
        self.calibrator = calibrator  # LogisticRegression on the raw decision score
        self.version = version
        self.threshold = threshold
        self.info = info or {}
        self._names = None

    def predict_proba(self, texts, clean=True):
        X = [clean_text(t) for t in texts] if clean else list(texts)
        raw = self.pipe.decision_function(X).reshape(-1, 1)
        return self.calibrator.predict_proba(raw)[:, 1]

    def score(self, text):
        return float(self.predict_proba([text])[0])

    def score_chunk(self, text):
        """For a transcript chunk: highest sentence score and the sentence it came from."""
        sents = split_sentences(text) or [str(text)]
        p = self.predict_proba(sents)
        i = int(np.argmax(p))
        return float(p[i]), sents[i]

    def _feature_names(self):
        if self._names is None:
            self._names = np.array([n.replace("word__", "") for n in self.pipe[:-1].get_feature_names_out()])
        return self._names

    def explain(self, text, top=8):
        c = clean_text(text)
        vec = self.pipe[:-1].transform([c]).tocsr()
        coef = self.pipe[-1].coef_[0]
        contrib = vec.data * coef[vec.indices]
        order = np.argsort(-np.abs(contrib))[:top]
        names = self._feature_names()
        return {"text": text, "clean": c, "score": round(self.score(text), 3),
                "words": [(str(names[vec.indices[k]]), round(float(contrib[k]), 3)) for k in order]}

    def top_words(self, n=25, words_only=True):
        names = self._feature_names()
        coef = self.pipe[-1].coef_[0]
        idx = np.arange(len(coef))
        if words_only:
            idx = idx[~np.char.startswith(names.astype(str), "char__")]
        up = idx[np.argsort(-coef[idx])][:n]
        down = idx[np.argsort(coef[idx])][:n]
        return ([(str(names[i]), round(float(coef[i]), 3)) for i in up],
                [(str(names[i]), round(float(coef[i]), 3)) for i in down])
