"""specs.py - Workstream G: the three on-device models (urgency, money, impersonation) as hashing models.

Each spec keeps everything about its worker that is NOT the vectoriser: the worker's own cleaning, its training
rows and weights, its C, its calibration and threshold rule, and its fixed human-labelled test sets (the same ones
the release check uses, backend/retrain/adapters.py). Only the TF-IDF word list is swapped for hashing.

What each model is scored on (so the numbers match the workers' and the release check's):
  urgency        the whole text, cleaned with text_norm.normalize            (adapters.UrgencyEn.score)
  money          the highest score over the text's sentences (money_norm)     (MoneyModel.score_chunk)
  impersonation  the whole text, caller-only + cleaned + brand-masked         (CalibratedModel.predict_proba)

The Chinese workers are not here: the phone runs only the three English models (Workstream G.2).
"""
import importlib.util
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import precision_recall_curve

import features as F

REPO = Path(__file__).resolve().parents[2]
WORKERS = REPO / "backend" / "workers"
SEED = 42


def load_module(path: Path, name: str, add_dir=False):
    """Import a worker file under a unique name (several workers have a train.py / text_norm.py)."""
    if add_dir and str(path.parent) not in sys.path:
        sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def best_f1_threshold(y, p, w=None):
    prec, rec, thr = precision_recall_curve(y, p, sample_weight=w)
    f1 = 2 * prec[:-1] * rec[:-1] / np.clip(prec[:-1] + rec[:-1], 1e-12, None)
    return float(thr[int(np.argmax(f1))])


class Spec:
    name = ""            # urgency / money / impersonation
    parts = ()
    C = 1.0
    calibrated = False
    tfidf_name = ""      # the deployed TF-IDF version, for the G.1 comparison

    @property
    def registry_name(self):
        return f"{self.name}_en_phone"

    @property
    def asset(self):
        return f"{self.name}_en.json"

    # --- set by subclasses
    def units(self, text) -> list:
        """The cleaned piece(s) of one raw text that get scored; the text's score is the highest."""
        raise NotImplementedError

    def training(self) -> dict:
        """{"train": DataFrame(text, label, source, group, weight), "calibrate": DataFrame(text, label) or None}.
        text is already cleaned."""
        raise NotImplementedError

    @property
    def gold_sets(self):
        raise NotImplementedError

    # --- shared
    def fit_lr(self, X, y, w):
        return LogisticRegression(C=self.C, class_weight="balanced", solver="liblinear", max_iter=3000,
                                  random_state=SEED).fit(X, y, sample_weight=w)

    def proba(self, model, featurize, texts) -> np.ndarray:
        """P(signal) per raw text, from raw weights (what FedAvg produces) + the frozen calibration."""
        pieces, owner = [], []
        for i, t in enumerate(texts):
            u = self.units(t) or [""]
            pieces += u
            owner += [i] * len(u)
        z = featurize.transform(pieces) @ model["coef"] + model["intercept"]
        a, b = model.get("platt") or (1.0, 0.0)
        p = F.sigmoid(a * z + b)
        out = np.zeros(len(texts))
        np.maximum.at(out, np.array(owner), p)
        return out

    def load_gold(self):
        out = []
        for name, path, text_col, label_col in self.gold_sets:
            g = pd.read_csv(path)
            g = g[g[label_col].isin([0, 1])]
            out.append((name, g[text_col].astype(str).tolist(), g[label_col].astype(int).to_numpy()))
        return out


class Urgency(Spec):
    name = "urgency"
    C = 0.3                                   # the value v4 was chosen with (model/v4.json)
    tfidf_name = "urgency_en_v4"
    parts = (F.Part("word", (1, 2), r"(?u)\b[\w']+\b",
                    ("numtoken", "timetoken", "moneytoken", "phonetoken", "urltoken", "emailtoken")),)

    def __init__(self):
        d = WORKERS / "urgency_en"
        self.text_norm = load_module(d / "text_norm.py", "text_norm", add_dir=True)   # urgency train.py imports it
        self._dir = d

    def units(self, text):
        return [self.text_norm.normalize(text)]

    def training(self):
        t = load_module(self._dir / "train.py", "urgency_train")
        df, _, _ = t.load_data(t.DEFAULT_DATA, t.DEFAULT_AUGMENT)
        return {"train": pd.DataFrame({"text": df["text"], "label": df["label"].astype(int), "source": df["source"],
                                       "group": df["group"], "weight": t.fit_weights(df)}),
                "calibrate": None}

    @property
    def gold_sets(self):
        e = self._dir / "eval"
        return [("gold_handwritten", e / "gold_handwritten.csv", "text", "label"),
                ("robocall_gold", e / "robocall_gold.csv", "text", "label_time_ai")]

    def threshold(self, train, featurize_fn):
        """Best source-weighted F1 on a 20% group split (whole conversations on one side)."""
        from sklearn.model_selection import GroupShuffleSplit
        tr, va = next(GroupShuffleSplit(1, test_size=0.2, random_state=SEED).split(train, train["label"], train["group"]))
        a, b = train.iloc[tr], train.iloc[va]
        feat = featurize_fn(a["text"])
        lr = self.fit_lr(feat.transform(a["text"]), a["label"], a["weight"])
        p = lr.predict_proba(feat.transform(b["text"]))[:, 1]
        return best_f1_threshold(b["label"].to_numpy(), p, b["weight"].to_numpy())


class Money(Spec):
    name = "money"
    C = 3.0                                   # money_en_v2c.json: config word12_C3
    calibrated = True
    tfidf_name = "money_en_v2c"
    parts = (F.Part("word", (1, 2)),)         # placeholders kept (v2c card: placeholders_removed false)
    HANDWRITTEN_WEIGHT = 10.0                 # train.py default

    def __init__(self):
        self._dir = WORKERS / "money_en"
        self.norm = load_module(self._dir / "money_norm.py", "money_norm")

    def units(self, text):
        return [self.norm.clean_text(s, mask_brands=True) for s in (self.norm.split_sentences(text) or [str(text)])]

    def training(self):
        """train.py's split: hand-written rows always train; the rest 70/15/15 by call (train/choose/calibrate)."""
        data = pd.read_csv(REPO / "data" / "money_en" / "C_train_pool.csv", keep_default_na=False, na_values=[""])
        data = data[data["text_clean"].fillna("").str.len() > 0].copy()
        data["label"] = data["label"].astype(int)
        data["group"] = data["script_id"].fillna(data["conversation_id"]).astype(str)
        from sklearn.model_selection import GroupShuffleSplit

        def split(df, size, seed):
            a, b = next(GroupShuffleSplit(1, test_size=size, random_state=seed).split(df, df["label"], df["group"]))
            return df.iloc[a], df.iloc[b]
        hw = data["source"] == "Handwritten"
        tr, hold = split(data[~hw], 0.30, SEED)
        tr = pd.concat([tr, data[hw]])
        _, cal = split(hold, 0.5, SEED + 1)
        train = pd.DataFrame({"text": tr["text_clean"], "label": tr["label"], "source": tr["source"],
                              "group": tr["group"],
                              "weight": np.where(tr["source"] == "Handwritten", self.HANDWRITTEN_WEIGHT, 1.0)})
        return {"train": train, "calibrate": pd.DataFrame({"text": cal["text_clean"], "label": cal["label"]})}

    @property
    def gold_sets(self):
        e = self._dir / "eval"
        return [("sg_gold", e / "sg_gold.csv", "text", "label"),
                ("test_set", e / "test_set_binary_classifier.csv", "text", "label"),
                ("ftc", e / "D_ftc_labelled.csv", "sentence", "label")]

    def threshold(self, train, featurize_fn):
        return 0.5                            # money train.py: fixed 0.5 on the calibrated score


class Impersonation(Spec):
    name = "impersonation"
    C = 30.0                                  # model_card.json (v5): C 30, character n-grams on
    calibrated = True
    tfidf_name = "impersonation_en_v5"
    parts = (F.Part("word", (1, 2)), F.Part("char_wb", (3, 5)))

    def __init__(self):
        self._dir = WORKERS / "impersonation_en"
        self.tu = load_module(self._dir / "text_utils.py", "text_utils", add_dir=True)

    def units(self, text):
        return self.tu.prep([text], mask=True, caller=True)

    def fit_lr(self, X, y, w):
        # train.py's settings: no class weighting (its data is roughly balanced), lbfgs
        return LogisticRegression(C=self.C, max_iter=2000, random_state=SEED).fit(X, y, sample_weight=w)

    def training(self):
        """train.py's development set (its 15% test split and the gold set stay out), plus its one speech-to-text
        noisy copy per row. Platt calibration is fitted on 5-fold out-of-fold scores, like train.py."""
        t = load_module(self._dir / "train.py", "impersonation_train")
        df = t.tidy(pd.read_csv(t.DEFAULT_DATA))
        df["clean"] = self.tu.prep(df["text"], mask=False, caller=True)
        df = df.drop_duplicates(subset="clean").reset_index(drop=True)
        groups = t.build_groups(df["clean"].tolist(), df["scenario_id"].tolist(), t.NEAR_DUP_THRESHOLD)
        dev_i, _ = t.make_test_split(df, groups, t.TEST_SIZE, t.RANDOM_STATE)
        dev = df.iloc[dev_i].reset_index(drop=True)
        dev["group"] = groups[dev_i]
        aug = t.with_noise(dev, t.RANDOM_STATE)
        self._t, self._dev = t, dev
        return {"train": pd.DataFrame({"text": self.tu.prep(aug["text"], mask=True, caller=True),
                                       "label": aug["label"].to_numpy(), "source": aug["source"].to_numpy(),
                                       "group": aug["group"].astype(str).to_numpy(), "weight": 1.0}),
                "calibrate": "oof"}

    @property
    def gold_sets(self):
        return [("gold_test", REPO / "data" / "impersonation_en" / "gold_test.csv", "text", "label")]

    def oof_decision(self, featurize_fn):
        """5-fold out-of-fold raw scores on the development set (each fold trained with its noisy copies)."""
        from sklearn.model_selection import StratifiedGroupKFold
        t, dev = self._t, self._dev
        y = dev["label"].to_numpy()
        for attempt in range(50):
            folds = list(StratifiedGroupKFold(n_splits=t.N_FOLDS, shuffle=True,
                                              random_state=t.RANDOM_STATE + attempt).split(dev, y, dev["group"]))
            if all(len(set(y[a])) == 2 and len(set(y[b])) == 2 for a, b in folds):
                break
        z = np.zeros(len(dev))
        for k, (a, b) in enumerate(folds):
            aug = t.with_noise(dev.iloc[a], t.RANDOM_STATE + 100 * k)
            tx = self.tu.prep(aug["text"], mask=True, caller=True)
            feat = featurize_fn(tx)
            lr = self.fit_lr(feat.transform(tx), aug["label"], None)
            z[b] = lr.decision_function(feat.transform(self.tu.prep(dev.iloc[b]["text"], mask=True, caller=True)))
        return z, y

    def threshold_from(self, p, y):
        """train.py: best F1 on out-of-fold probabilities over 0.05..0.95, ties to the one nearest 0.5."""
        from sklearn.metrics import f1_score
        return round(float(max(np.linspace(0.05, 0.95, 91),
                               key=lambda th: (round(f1_score(y, (p >= th).astype(int), zero_division=0), 6),
                                               -abs(th - 0.5)))), 2)


SPECS = {"urgency": Urgency, "money": Money, "impersonation": Impersonation}


def get(name) -> Spec:
    return SPECS[name]()
