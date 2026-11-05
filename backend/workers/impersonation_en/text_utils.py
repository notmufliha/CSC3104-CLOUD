"""
Text cleaning + the calibration wrapper for the impersonation_en model.

This module is NOT just a helper: the pickled model in model/model.pkl
references `text_utils.prep` (via a FunctionTransformer) and, when
calibrated, is itself an instance of `text_utils.CalibratedModel`. joblib
needs this exact module importable under the name `text_utils` to
unpickle it, so this file must sit next to server.py/train.py in the
container and must not be renamed or moved without re-saving the model.
"""
import re

TAG_RE = re.compile(r"\[[A-Za-z_ ]{2,30}\]")
SPEAKER_RE = re.compile(r"\b(?:caller|receiver|agent|customer|person\s*[ab12]|speaker\s*\d|scammer|victim|user)\s*:", re.I)
DIGITS_RE = re.compile(r"\d{4,}")
SPACE_RE = re.compile(r"\s+")

# Organisation names are replaced by their TYPE (police, government, bank...), so the model learns "claims to be a bank/police/government body"
# without memorising individual brands like SPF or DBS.
ORG_TYPES = {
    "orgpolice": ["spf", "singapore police force", "interpol", "commercial affairs department",
                  "central police division", "bedok police division", "jurong police division",
                  "woodlands police division"],
    "orggovt": ["ica", "mom", "iras", "mas", "cpf", "singpass", "irs", "immigration and checkpoints authority", "monetary authority of singapore",
                "ministry of manpower", "inland revenue authority of singapore", "cyber security agency", "cpf board", "state courts",
                "supreme court", "ministry of home affairs", "ministry of education", "hdb", "lta", "csa", "govtech",
                "moh", "ministry of health", "town council", "singapore customs", "mha"],
    "orgbank": ["dbs", "ocbc", "uob", "posb", "citibank", "hsbc", "standard chartered", "standard chartered bank", "maybank"],
    "orgtelco": ["singtel", "starhub", "m1"],
    "orgtech": ["microsoft", "apple", "google", "amazon", "paypal"],
    "orgcourier": ["singpost", "dhl", "fedex", "ninja van"],
    "orgshop": ["lazada", "shopee", "grab", "gojek"],
    "orgutility": ["sp group", "sp services"],
    "orghealth": ["sgh", "singapore general hospital", "changi general hospital", "ttsh", "tan tock seng hospital", "nuh", "khoo teck puat hospital",
                  "synapxe", "national university hospital"],
    "orginsurance": ["great eastern", "prudential"],
}
ORG_NAMES = [n for names in ORG_TYPES.values() for n in names]
NAME_TO_TYPE = {n: t for t, names in ORG_TYPES.items() for n in names}
ORG_RE = re.compile(r"\b(" + "|".join(re.escape(o) for o in sorted(ORG_NAMES, key=len, reverse=True)) + r")\b")


def clean_text(t):
    t = str(t)
    t = TAG_RE.sub(" ", t)
    t = SPEAKER_RE.sub(" ", t)
    t = t.lower()
    t = DIGITS_RE.sub("longnumber", t)
    return SPACE_RE.sub(" ", t).strip()


def mask_brands(t):
    return ORG_RE.sub(lambda m: NAME_TO_TYPE[m.group(1)], t)


# Speaker labels. The "caller" side is the remote party whose identity we judge.
REMOTE_LABELS = {"caller", "scammer", "agent"}
LOCAL_LABELS = {"receiver", "customer", "victim", "user"}
LABEL_SPLIT_RE = re.compile(r"\b(caller|scammer|agent|receiver|customer|victim|user)\s*:", re.I)


def caller_only(text):
    """Keep every word the caller says and drop the receiver's turns.
    If the text has no recognised speaker labels, return it unchanged (nothing is cut)."""
    text = str(text)
    parts = LABEL_SPLIT_RE.split(text)
    if len(parts) == 1:
        return text
    kept = [parts[0]]
    for i in range(1, len(parts), 2):
        if parts[i].lower() in REMOTE_LABELS:
            kept.append(parts[i + 1])
    return " ".join(kept)


def prep(texts, mask=True, caller=True):
    out = []
    for t in texts:
        if caller:
            t = caller_only(t)
        t = clean_text(t)
        if mask:
            t = mask_brands(t)
        out.append(t)
    return out


def preprocess(texts):
    return prep(texts, mask=False, caller=True)


def preprocess_masked(texts):
    return prep(texts, mask=True, caller=True)


class CalibratedModel:
    """A fitted pipeline plus a small Platt-scaling step, so the 0 to 1 score behaves more like a real probability."""

    def __init__(self, base, calibrator):
        self.base = base
        self.calibrator = calibrator

    def predict_proba(self, X):
        z = self.base.decision_function(X).reshape(-1, 1)
        return self.calibrator.predict_proba(z)
