"""adapters.py - how to train, load and score each worker's model, behind one small interface.

The workers were built by different people and differ on purpose (data files, cleaning, model class). Each
adapter calls that worker's OWN train.py and scores with its OWN code, so a retrained model is built and served
exactly like the original. Runs inside one model's environment (retrain_one.py), one model per process: the
workers have modules with the same names (text_norm.py), so they can't share a process.

    gold_sets        fixed human test sets: (name, path, text column, label column). Never used for training.
    baked_path       the model in the worker image, the baseline until a version is released
    baked_threshold  that model's saved threshold
    load(path)       the model object
    score(model, texts)   P(signal) per text, the way the worker scores it
    train(reports, out, version)   trains on the original data + the report rows; returns the new threshold
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parents[2]
WORKERS = REPO / "backend" / "workers"


def run(cmd, cwd, env=None):
    subprocess.run([sys.executable, *map(str, cmd)], check=True, cwd=cwd, env={**os.environ, **(env or {})})


class Adapter:
    def __init__(self, name: str, signal: str, work: Path):
        self.name, self.signal, self.work = name, signal, work
        self.dir = WORKERS / name
        sys.path.insert(0, str(self.dir))

    def model_text(self, text: str) -> str:
        """A masked report line as this model should see it. English cleaning understands the [TAG]s."""
        return text


class UrgencyEn(Adapter):
    baked_path = WORKERS / "urgency_en" / "model" / "urgency_en_v4.joblib"

    @property
    def gold_sets(self):
        return [("gold_handwritten", self.dir / "eval" / "gold_handwritten.csv", "text", "label"),
                ("robocall_gold", self.dir / "eval" / "robocall_gold.csv", "text", "label_time_ai")]

    def baked_threshold(self):
        return joblib.load(self.baked_path)["threshold"]

    def load(self, path):
        return joblib.load(path)

    def score(self, model, texts):
        from text_norm import normalize
        pipe = model["pipeline"]
        return pipe.predict_proba([normalize(t) for t in texts])[:, list(pipe.classes_).index(1)]

    def train(self, reports, out, version):
        extra = self.work / "augment_user_reports.csv"
        reports.drop(columns=["line_id"]).to_csv(extra, index=False)
        data = REPO / "data" / "urgency_en"
        run([self.dir / "train.py", "--augment", data / "augment_review_flips.csv", data / "augment_urgency_en.csv",
             extra, "--out", out], self.dir)
        bundle = joblib.load(out)
        bundle["version"] = version
        joblib.dump(bundle, out)
        return float(bundle["threshold"])


class MoneyEn(Adapter):
    baked_path = WORKERS / "money_en" / "model" / "money_en_v2c.joblib"

    @property
    def gold_sets(self):
        e = self.dir / "eval"
        return [("sg_gold", e / "sg_gold.csv", "text", "label"),
                ("test_set", e / "test_set_binary_classifier.csv", "text", "label"),
                ("ftc", e / "D_ftc_labelled.csv", "sentence", "label")]

    def baked_threshold(self):
        return self.load(self.baked_path).threshold

    def load(self, path):
        import money_model  # noqa: F401  the pickle references money_model.MoneyModel
        return joblib.load(path)

    def score(self, model, texts):
        return np.array([model.score_chunk(t)[0] for t in texts])

    def train(self, reports, out, version):
        from money_norm import clean_text
        pool = pd.read_csv(REPO / "data" / "money_en" / "C_train_pool.csv", keep_default_na=False, na_values=[""])
        # source "Handwritten" = train.py's name for human-written and human-labelled rows: they always go into
        # training (never the choose/calibrate splits) and count extra.
        rows = pd.DataFrame({"sentence_id": "report_" + reports["line_id"].astype(str),
                             "sentence": reports["text_raw"],
                             "text_clean": reports["text_raw"].map(lambda t: clean_text(t, mask_brands=True)),
                             "label": reports["label"], "label_method": reports["label_method"],
                             "source": "Handwritten", "call_type": "reported",
                             "conversation_id": reports["conversation_id"], "script_id": reports["conversation_id"]})
        merged = self.work / "pool_with_reports.csv"
        pd.concat([pool, rows], ignore_index=True).to_csv(merged, index=False)
        run([self.dir / "train.py", "--pool", merged, "--out-dir", out.parent, "--version", version,
             "--report-dir", self.work], self.dir)
        return float(self.load(out).threshold)


class ImpersonationEn(Adapter):
    baked_path = WORKERS / "impersonation_en" / "model" / "model.pkl"

    def __init__(self, *a):
        super().__init__(*a)
        # train.py rewrites model_card.json, so the baked threshold is read now, before any training.
        card = json.loads((self.dir / "model_card.json").read_text())
        self._baked_threshold = float(card["threshold_used_for_reporting"])

    @property
    def gold_sets(self):
        return [("gold_test", REPO / "data" / "impersonation_en" / "gold_test.csv", "text", "label")]

    def baked_threshold(self):
        return self._baked_threshold

    def load(self, path):
        import text_utils  # noqa: F401  the pickle references text_utils.*
        return joblib.load(path)

    def score(self, model, texts):
        return model.predict_proba(list(texts))[:, 1]

    def train(self, reports, out, version):
        base = pd.read_csv(REPO / "data" / "impersonation_en" / "impersonation_data.csv")
        rows = pd.DataFrame({"text": reports["text_raw"], "label": reports["label"], "source": "user_report",
                             "scenario_id": reports["conversation_id"], "org_category": "", "is_hard_negative": 0})
        merged = self.work / "data_with_reports.csv"
        pd.concat([base, rows], ignore_index=True).to_csv(merged, index=False)
        run([self.dir / "train.py", "--data", merged, "--out", out], self.dir)
        return float(json.loads((self.dir / "model_card.json").read_text())["threshold_used_for_reporting"])


# The Chinese cleaning has no [TAG]s: it turns raw numbers, amounts and links into its own tokens. So a masked
# line gets a stand-in that the same cleaning turns into the right token, and it trains like live traffic.
ZH_STAND_INS = {"MONEY_AMOUNT": "500元", "PHONE_NUMBER": "91234567", "NUMBER": "123", "URL": "www.example.com",
                "EMAIL_ADDRESS": "", "PERSON_NAME": ""}
ZH_TAG = re.compile(r"\[(" + "|".join(ZH_STAND_INS) + r")\]")


class Zh(Adapter):
    def __init__(self, *a):
        super().__init__(*a)
        sys.path.insert(0, str(WORKERS / "common_zh"))

    @property
    def baked_path(self):
        return self.dir / "model" / "model.joblib"   # built by the retrain image, the way the worker image builds it

    @property
    def gold_sets(self):
        return [("gold_test_zh", WORKERS / "common_zh" / "gold_test_zh.csv", "text", self.signal)]

    def model_text(self, text):
        return ZH_TAG.sub(lambda m: ZH_STAND_INS[m.group(1)], text)

    def baked_threshold(self):
        return float(joblib.load(self.baked_path)["threshold"])

    def load(self, path):
        return joblib.load(path)

    def score(self, model, texts):
        import text_norm as zn
        return np.array([zn.score_text(model, t, self.signal)[0] for t in texts])

    def train(self, reports, out, version):
        base = pd.read_csv(self.dir / "data" / f"{self.signal}_zh.csv")
        rows = pd.DataFrame({"text_raw": reports["text_raw"].map(self.model_text), "label": reports["label"],
                             "source": "user_report", "scenario_id": reports["conversation_id"],
                             "conversation_id": reports["conversation_id"],
                             "row_id": "report_" + reports["line_id"].astype(str)})
        merged = self.work / "data_with_reports.csv"
        pd.concat([base, rows], ignore_index=True).to_csv(merged, index=False)
        run([self.dir / "train.py"], self.dir, {"CSV_PATH": str(merged), "MODEL_OUT": str(out)})
        return float(joblib.load(out)["threshold"])


CLASSES = {"urgency_en": UrgencyEn, "money_en": MoneyEn, "impersonation_en": ImpersonationEn,
           "urgency_zh": Zh, "money_zh": Zh, "impersonation_zh": Zh}


def get(name: str, signal: str, work: Path) -> Adapter:
    return CLASSES[name](name, signal, work)
