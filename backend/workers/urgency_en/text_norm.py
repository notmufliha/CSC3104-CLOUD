"""
text_norm.py - ONE cleaning function for the English urgency worker.

Used in three places, and they must all call this same function:
  1. train.py          -> builds the training text from text_raw
  2. evaluate_gold.py  -> cleans the human-labelled test sentences
  3. server.py         -> cleans live call transcripts before scoring

Why it matters: TF-IDF only knows the exact words it saw in training.
If training saw "within numtoken hours" but the server sends "within 24 hours",
the model has never seen "24" and the urgency signal is lost.

It reproduces the team's cleaning (final_cleaned_dataset.csv) rules:
lowercase, keep apostrophes, drop emojis/markdown/punctuation, keep stopwords,
replace values with placeholder tokens, keep time-unit words ("numtoken hours").
"""
import re
import unicodedata

# Redaction tags that appear in CallCenterEN transcripts, e.g. [MONEY_AMOUNT].
# Shared, meaningful tags are kept as tokens; source-specific ones are removed
# (the team's audit found they only appeared in negative sources -> shortcut).
_TAG_MAP = {
    "MONEY_AMOUNT": " moneytoken ", "PHONE_NUMBER": " phonetoken ", "EMAIL_ADDRESS": " emailtoken ",
    "URL": " urltoken ", "DATE": " timetoken ", "DATE_OF_BIRTH": " timetoken ", "TIME": " timetoken ",
    "DURATION": " numtoken ", "NUMBER": " numtoken ",
}
_TAG = re.compile(r"\[([A-Z_]+)\]")
_SPEAKER = re.compile(r"^\s*(person [ab]|caller|receiver|agent|customer|suspect|innocent|user|system|assistant)\s*:\s*",
                      re.I)

_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.]+\b")
_URL = re.compile(r"(https?://\S+|www\.\S+|\b[\w-]+\.(com|org|net|gov|sg|cn|io|co)(/\S*)?\b)", re.I)
_MONEY = re.compile(r"([$¥€£]\s?\d[\d,]*(\.\d+)?|\b\d[\d,]*(\.\d+)?\s?(dollars|sgd|usd|yuan|rmb|cny)\b)", re.I)
_DATE = re.compile(r"\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b")   # 2024-03-15, 15/03/2024
_PHONE_CAND = re.compile(r"(?<![\w])\+?\(?\d[\d\s().-]{5,18}\d(?![\w])")             # digits with spaces/dashes


def _phone(m):
    """Only call it a phone number if it has 7-15 digits; '20 30' stays as numbers."""
    n = sum(ch.isdigit() for ch in m.group(0))
    return " phonetoken " if 7 <= n <= 15 else m.group(0)


_TIME = re.compile(r"\b\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?\b|\b\d{1,2}\s?[ap]\.?m\.?\b", re.I)
_HOURS = re.compile(r"\b\d+\s?(h|hr|hrs)\b", re.I)                        # "24hrs", "2h" -> numtoken hours
_MINS = re.compile(r"\b\d+\s?(m|min|mins)\b", re.I)                       # "17m", "12 MIN" -> numtoken minutes
_CODE = re.compile(r"\b(?=[a-z]*\d)(?=\d*[a-z])[a-z\d]{3,}\b", re.I)      # "7t9k2b", "cb12lf" -> numtoken
_NUM = re.compile(r"\d[\d,.]*")


def normalize(text) -> str:
    t = unicodedata.normalize("NFKC", str(text))
    t = _SPEAKER.sub("", t)                                   # "Person B: hi" -> "hi"
    t = _TAG.sub(lambda m: _TAG_MAP.get(m.group(1), " "), t)  # [MONEY_AMOUNT] -> moneytoken, [PERSON_NAME] -> removed
    t = t.replace("\u2019", "'").replace("\u2018", "'")      # curly apostrophes -> straight
    t = _EMAIL.sub(" emailtoken ", t)                         # order matters: email before url,
    t = _URL.sub(" urltoken ", t)                             # money/date/time/phone before plain numbers
    t = _MONEY.sub(" moneytoken ", t)
    t = _DATE.sub(" timetoken ", t)
    t = _TIME.sub(" timetoken ", t)
    t = _PHONE_CAND.sub(_phone, t)
    t = _HOURS.sub(" numtoken hours ", t)
    t = _MINS.sub(" numtoken minutes ", t)
    t = _CODE.sub(" numtoken ", t)
    t = _NUM.sub(" numtoken ", t)
    t = t.lower()
    t = re.sub(r"[^a-z' ]+", " ", t)                          # drop punctuation, emojis, leftover digits
    t = re.sub(r"(?<![a-z])'|'(?![a-z])", " ", t)             # keep "don't", drop stray quotes
    return re.sub(r"\s+", " ", t).strip()