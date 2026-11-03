"""mask.py - removes personal details from a caller line before it is stored (Workstream H.3/H.4).

The app masks before sending; the review service masks again on arrival, so an old or modified app can't put
raw numbers in the queue. The tags are the ones the workers' text_norm.py already understands
([PHONE_NUMBER] -> "phonetoken", [PERSON_NAME] -> removed, ...), so a masked line trains exactly like the
training data. Keep this in step with android-app/.../logic/Mask.kt.

What is masked: e-mail addresses, web links, money amounts, phone numbers, ID-like codes (e.g. S1234567D),
any other run of digits (OTPs, account numbers), and a name after "my name is" / "this is" / "I'm" / "Mr" ...
Names said any other way are NOT caught; labellers are told to skip a line that still contains one.
"""
import re

_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b")
_URL = re.compile(r"\b(?:https?://|www\.)\S+|\b[\w-]+\.(?:com|sg|net|org|gov|io|co|my|cn)(?:/\S*)?\b", re.I)
_MONEY = re.compile(
    r"(?:(?:S?\$|SGD|USD|RM|MYR|RMB|¥|€|£)\s?\d[\d,]*(?:\.\d+)?(?:\s?(?:k|m|million|thousand))?)"
    r"|(?:\d[\d,]*(?:\.\d+)?\s?(?:dollars?|sgd|bucks|元|块))", re.I)
_ID = re.compile(r"\b[STFGM]\d{7}[A-Z]\b", re.I)                    # Singapore NRIC/FIN shape
_PHONE = re.compile(r"(?<![\w])\+?\d[\d\s-]{6,}\d(?![\w])")        # 8+ digits with spaces/dashes
_DIGITS = re.compile(r"\d+")
# A cue ("my name is", "this is", ...) and/or a title ("Officer", "Mr"), then a Capitalised name of 1-2 words.
# Cue and title are case-insensitive; the name itself must be capitalised.
_TITLE = r"(?:officer|inspector|sergeant|mr|mrs|ms|madam|dr|doctor)\.?"
_NAME = re.compile(
    r"((?i:\b(?:my name is|this is|i am|i'm|speaking with)\s+(?:" + _TITLE + r"\s+)?|\b" + _TITLE + r"\s+))"
    r"([A-Z][a-z]+(?:\s+[A-Z][a-z]+)?)")
# Not 我是 ("I am"): "我是王警官" would lose 警官 (police officer), the very thing impersonation needs.
_NAME_ZH = re.compile(r"(我叫|我姓)([一-鿿]{1,3})")


def mask(text: str) -> str:
    t = _EMAIL.sub("[EMAIL_ADDRESS]", text)
    t = _URL.sub("[URL]", t)
    t = _MONEY.sub("[MONEY_AMOUNT]", t)
    t = _ID.sub("[NUMBER]", t)
    t = _PHONE.sub("[PHONE_NUMBER]", t)
    t = _DIGITS.sub("[NUMBER]", t)
    # "This is Officer John Tan" -> "This is Officer [PERSON_NAME]". Organisation words after "this is"
    # ("this is the police", "this is DBS") start lowercase or are a known org, so they mostly stay.
    t = _NAME.sub(lambda m: m.group(1) + (
        m.group(2) if m.group(2).split()[0].lower() in _NOT_NAMES else "[PERSON_NAME]"), t)
    t = _NAME_ZH.sub(lambda m: m.group(1) + "[PERSON_NAME]", t)
    return t


# Capitalised words after "this is" / "I'm" that are not people (kept so impersonation stays labelable).
_NOT_NAMES = {
    "police", "singapore", "dbs", "ocbc", "uob", "posb", "maybank", "citibank", "hsbc", "standard", "moh",
    "mom", "iras", "cpf", "ica", "spf", "customs", "immigration", "interpol", "the", "your", "a", "an",
    "calling", "from", "with", "here", "just", "not", "sorry", "sure", "fine", "okay", "ok", "very",
    "shopee", "lazada", "grab", "singtel", "starhub", "m1", "dhl", "fedex", "ninja", "amazon", "apple",
    "microsoft", "google", "manager", "officer", "inspector", "sergeant", "madam", "sir", "doctor",
}
