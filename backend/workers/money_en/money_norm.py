"""money_norm.py: shared text code for the English Money/OTP worker.

Written by the notebook and imported by every later step (and later by the live worker),
so training data, test data and new sentences are always processed by the same code.
"""
import re
import unicodedata

# ---------------------------------------------------------------- splitting
SENT_END = re.compile(r"(?<=[.!?])\s+")
NEWLINES = re.compile(r"\s*\n+\s*")


def split_sentences(text, max_words=40, chunk_words=20):
    """Split a turn/call into sentences. Pieces longer than max_words are cut into ~chunk_words chunks."""
    out = []
    text = re.sub(r"([a-z][.!?])([A-Z])", r"\1 \2", str(text))   # "done.Then" -> "done. Then"
    for block in NEWLINES.split(text):
        for s in SENT_END.split(block):
            s = s.strip()
            if not s:
                continue
            words = s.split()
            if len(words) > max_words:
                for i in range(0, len(words), chunk_words):
                    out.append(" ".join(words[i:i + chunk_words]))
            else:
                out.append(s)
    return out


def light_norm(text):
    """Lowercase, straight quotes, single spaces."""
    t = str(text).replace("\u2019", "'").replace("\u2018", "'")
    t = t.replace("\u201c", '"').replace("\u201d", '"')
    return re.sub(r"\s+", " ", t).strip().lower()


# ------------------------------------------------------- not-money phrases
# Removed BEFORE cue matching, so "pay attention" or "transfer to the Circle Line" are not money.
NOT_MONEY = [
    r"pay(?:s|ing)? (?:close |more |full |special |extra )?attention",
    r"pay(?:s|ing)? (?:\w+ ){0,3}a visit",
    r"pay(?:s|ing)? (?:my |our |their |your |his |her )?respects",
    r"\bin charge\b",
    r"charg(?:e|es|ed|ing) (?:my|your|the|his|her|our) (?:phone|laptop|battery|device|tablet|car)",
    r"(?:phone|laptop|battery|device) (?:needs|need|is|was) (?:to )?(?:be )?charg\w*",
    r"\bneeds? to charge\b", r"\bchargers?\b",
    r"into account", r"on account of",
    r"(?:instagram|facebook|tiktok|twitter|google|email|e-mail|gmail|social media|school|student|game|gaming|user) accounts?",
    r"accounts? on (?:instagram|facebook|tiktok|twitter|x)\b",
    r"dress code", r"code of conduct", r"(?:source|programming|python|java) code",
    r"(?:write|writing|wrote|written|run|running|debug|debugging|fix|fixing|test|testing) (?:the |my |this |some |your |our )?code",
    r"transfer(?:s|red|ring)? (?:to|at) (?:the )?(?:[\w-]+ ){0,3}(?:line|station|interchange|mrt|lrt|bus|train)",
    r"transfer(?:s|red|ring)? (?:you|your call|the call|this call|calls?)\b",
    r"transfer(?:s|red|ring)? (?:to|from) (?:another|a different|a new) (?:school|department|team|class|unit|branch|office)",
    r"credit (?:goes|to the team|where)", r"give (?:\w+ )?(?:some )?credit", r"course credits?", r"(?:extra|bonus) credits?",
    r"river ?bank", r"bank of the (?:river|reservoir|lake|canal|stream)", r"(?:food|blood) bank",
    r"(?:lost|lose|losing|no|any|little|much|great|keen|an|my|his|her|their|special) interest in",
    r"\binterest(?:ed|ing)\b",
    r"\bpin(?:ned|ning|s)? (?:the |this |that |it |a |my |your )?(?:\w+ )?(?:location|message|post|address|place)",
    r"(?:balance|balancing) (?:work|life|school|study|studies|between|it|things|my|your)",
    r"(?:lost|lose|keep|keeping) (?:my |his |her |your )?balance", r"work-life balance",
    r"(?:thank[- ]you|birthday|greeting|christmas|festive|sim|identity|id|business|report|game|playing|memory|sd|graphics|name|library|student) cards?",
    r"token of (?:appreciation|thanks|gratitude|love)",
    r"(?:school|student|portal|email|e-mail|wifi|wi-fi|computer|laptop|instagram|facebook|game) (?:login|password)s?",
    r"password for (?:the |your |my )?(?:school|student|portal|email|e-mail|wifi|wi-fi|computer|laptop)",
]

# ------------------------------------------------------------ money cues
STRONG = [
    # amounts and currency
    r"(?:s\$|us\$|\$|\u00a3|\u20ac|\u00a5)\s?\d",
    r"\b\d[\d,]*(?:\.\d+)?\s?(?:k )?(?:dollars?|bucks|cents?|sgd|usd|yuan|rmb|ringgit|pounds|euros?)\b",
    r"\b(?:dollars?|bucks|sgd|usd|rmb)\b",
    # OTP and banking credentials
    r"\botps?\b", r"one[- ]time (?:password|pin|passcode|code)",
    r"(?:verification|security|authentication|sms) codes?",
    r"\b(?:four|five|six|4|5|6)[- ]digit (?:code|pin|number|otp)",
    r"\btac\b", r"\bpasscodes?\b", r"digital token", r"two[- ]factor|\b2fa\b",
    r"\bcvv\b|\bcvc\b", r"card (?:number|details|expiry)", r"\bpin (?:number|code)\b",
    r"(?:internet|online|mobile) banking",
    r"bank(?:ing)? (?:login|password|details|account details|account number|username)",
    r"account number",
    # paying and moving money
    r"\bpay(?:s|ing|ment|ments|able|out)?\b", r"\bpaid\b", r"\brepa(?:y|id)\w*",
    r"\bpaynow\b", r"\bpaylah\b", r"\bpaypal\b", r"\bgiro\b", r"\bvenmo\b|\bzelle\b",
    r"\bmoney\b", r"\bcash(?:back)?\b", r"\bcheques?\b", r"\bfunds?\b", r"\bsavings\b",
    r"\bsalar(?:y|ies)\b", r"\bwages?\b", r"\bloans?\b", r"\bdebts?\b",
    r"\bdeposit(?:s|ed|ing)?\b", r"\bwithdraw(?:al|als|n|ing|s)?\b",
    r"\bfees?\b", r"\b(?:outstanding|overdue|unpaid) (?:fine|amount|balance|bill|payment|sum|charges?)\b", r"\bfine of\b",
    r"\brefund\w*", r"\breimburs\w*", r"\brebates?\b",
    r"\binvest(?:s|ed|ing|ment|ments|or|ors)?\b", r"\bprofits?\b", r"\bdividends?\b", r"\bcrypto\w*", r"\bbitcoin\b", r"\bforex\b",
    r"\btrading (?:platform|account|app)\b",
    r"gift ?cards?", r"(?:google play|itunes|apple|steam|amazon|razer) (?:gift )?cards?", r"\bvouchers?\b",
    r"\btop[- ]?up\b", r"\bpremiums?\b", r"\btax(?:es)?\b", r"\binvoices?\b", r"\bpurchas\w*",
    r"\bprices?\b", r"\bcost(?:s|ing)?\b", r"\bhow much (?:is|was|does|did|do|for|money|to pay|are)\b",
    r"(?:credit|debit|atm|bank|ez-link|nets) cards?", r"(?:account|card) balance", r"\bbalance (?:is|of)\b",
    r"\binterest (?:rate|will be|of|earned|charged|payment)",
    r"(?:electricity|phone|water|utility|utilities|credit card|monthly|medical|hospital|mobile|internet|telco) bills?",
    r"\bbills? (?:for|of|is due|amount|payment)\b",
    r"\b(?:bank|wire|fund|money|telegraphic) transfers?\b",
    r"transfer(?:s|red|ring)? (?:the |your |my |this |that |all )?(?:money|funds|amount|savings|sum|payment)",
    r"\bcpf\b", r"\bmedisave\b", r"\bang ?(?:bao|pao)\b", r"\bhongbao\b", r"\bred packets?\b",
    r"\bsafe account\b", r"\be-?wallets?\b",
]
WEAK = [
    r"\baccounts?\b", r"\bcards?\b", r"\bcodes?\b", r"\bbank(?:s|ing)?\b", r"\bcharg(?:e|es|ed|ing)\b",
    r"\btransfer(?:s|red|ring)?\b", r"\bbalance\b", r"\bcredit(?:s|ed)?\b", r"\binterest\b", r"\bpin\b",
    r"\bbills?\b", r"\btokens?\b", r"\bpasswords?\b", r"\blog ?in\b", r"\bcommissions?\b", r"\bqr\b",
]
NOT_MONEY_RE = re.compile("|".join(f"(?:{p})" for p in NOT_MONEY))
STRONG_RE = re.compile("|".join(f"(?:{p})" for p in STRONG))
WEAK_RE = re.compile("|".join(f"(?:{p})" for p in WEAK))


def find_money_cues(text):
    """Cues in a sentence: {'strong': [...], 'weak': [...], 'not_money': [...]}."""
    t = light_norm(text)
    not_money = [m.group(0) for m in NOT_MONEY_RE.finditer(t)]
    t = NOT_MONEY_RE.sub(" ", t)
    return {"strong": [m.group(0) for m in STRONG_RE.finditer(t)],
            "weak": [m.group(0) for m in WEAK_RE.finditer(t)],
            "not_money": not_money}


def money_label(text):
    """Keyword label: 1 if a strong cue, None if only weak cues (unclear), else 0."""
    c = find_money_cues(text)
    if c["strong"]:
        return 1
    if c["weak"]:
        return None
    return 0


def mentions_money(text):
    return bool(STRONG_RE.search(NOT_MONEY_RE.sub(" ", light_norm(text))))


# ------------------------------------------------------------------ cleaning
PLACEHOLDER = re.compile(r"\[([A-Za-z_ ]{2,40})\]")


def _placeholder(m):
    k = m.group(1).upper()
    if any(w in k for w in ("MONEY", "AMOUNT", "PRICE", "BALANCE")):
        return " moneytoken "
    if any(w in k for w in ("OTP", "PIN", "CVV", "TAC", "PASSCODE", "PASSWORD", "CODE")):
        return " codetoken "
    if any(w in k for w in ("ORG", "COMPANY", "BUSINESS")):
        return " orgtoken "
    if "NAME" in k:
        return " nametoken "
    if any(w in k for w in ("DATE", "BIRTH", "DOB")):
        return " datetoken "
    if "PHONE" in k:
        return " phonetoken "
    if any(w in k for w in ("EMAIL", "URL", "WEBSITE", "LINK")):
        return " linktoken "
    if any(w in k for w in ("ADDRESS", "LOCATION", "CITY", "ZIP", "POSTAL", "STREET")):
        return " loctoken "
    if "CARD" in k:
        return " cardtoken "
    if any(w in k for w in ("ACCOUNT", "NUMBER", "SSN", "ID")):
        return " numtoken "
    if any(w in k for w in ("TIME", "DURATION")):
        return " timetoken "
    return " "


MONTHS = (r"(?:january|february|march|april|may|june|july|august|september|october|"
          r"november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)")
CLEAN_STEPS = [
    (re.compile(r"\S+@\S+\.\S+"), " linktoken "),
    (re.compile(r"(?:https?://|www\.)\S+", re.I), " linktoken "),
    (re.compile(r"\b[\w-]+\.(?:com|net|org|sg|gov|io|co|info|biz)\b\S*", re.I), " linktoken "),
    (re.compile(r"(?:s\$|us\$|\$|\u00a3|\u20ac|\u00a5|\bsgd|\busd|\brm)\s?\d[\d,]*(?:\.\d+)?"
                r"(?:\s?(?:k|m|million|billion|thousand)\b)?", re.I), " moneytoken "),
    (re.compile(r"\b\d[\d,]*(?:\.\d+)?\s?(?:dollars?|bucks|yuan|rmb|cents?|sgd|usd|"
                r"ringgit|pounds?|euros?)\b", re.I), " moneytoken "),
    (re.compile(r"\b(?:\d[ -]?){12,19}\b"), " cardtoken "),
    (re.compile(r"(?:\+?\d{1,3}[\s-]?)?\(?\d{3}\)?X?[\s.-]?\(?\d{3}\)?X?[\s.-]?\(?\d{4}\)?"), " phonetoken "),
    (re.compile(r"\b[689]\d{3}\s?\d{4}\b"), " phonetoken "),
    (re.compile(r"\b\d{1,2}(?:st|nd|rd|th)?\s+(?:of\s+)?" + MONTHS + r"\b\.?(?:,?\s+\d{4})?", re.I), " datetoken "),
    (re.compile(r"\b" + MONTHS + r"\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?\b", re.I), " datetoken "),
    (re.compile(r"\b\d{1,4}[/-]\d{1,2}[/-]\d{2,4}\b"), " datetoken "),
    (re.compile(r"\b\d{1,2}(?::\d{2})?\s?(?:am|pm|a\.m\.|p\.m\.)", re.I), " timetoken "),
    (re.compile(r"\b\d+(?:st|nd|rd|th)\b", re.I), " numtoken "),
    (re.compile(r"\d+(?:[.,]\d+)*"), " numtoken "),
]
CONTRACTIONS = [
    (re.compile(r"\bwon't\b"), "will not"), (re.compile(r"\bcan't\b"), "cannot"),
    (re.compile(r"\bshan't\b"), "shall not"), (re.compile(r"n't\b"), " not"),
]
REPEATED_TOKEN = re.compile(r"\b(\w+token)(?:\s+\1\b)+")


def clean_text(text):
    """Full clean used for TF-IDF. Keeps stopwords, no stemming."""
    t = str(text)
    t = PLACEHOLDER.sub(_placeholder, t)
    t = re.sub(r"<[^>]+>", " ", t)
    for rx, rep in CLEAN_STEPS:
        t = rx.sub(rep, t)
    t = unicodedata.normalize("NFKD", t).encode("ascii", "ignore").decode()
    t = light_norm(t)
    for rx, rep in CONTRACTIONS:
        t = rx.sub(rep, t)
    t = t.replace("'", "")
    t = re.sub(r"[^a-z\s]", " ", t)
    t = REPEATED_TOKEN.sub(r"\1", t)
    return re.sub(r"\s+", " ", t).strip()


FILLER = {"uh", "um", "umm", "hmm", "mm", "mhm", "ok", "okay", "yeah", "yes", "yep", "no", "nope",
          "hello", "hi", "hey", "thanks", "thank", "you", "bye", "goodbye", "alright", "right",
          "sure", "oh", "ah", "so", "well", "the", "a", "and", "is", "it", "that", "i", "im"}


def is_filler(clean, has_cue):
    """True if a sentence is too empty to teach anything: under 3 real words and no cue."""
    real = [w for w in clean.split() if not w.endswith("token") and w not in FILLER and len(w) > 1]
    return len(real) < 3 and not has_cue


# -------------------------------------------------------------------- topics
TOPICS = [
    ("otp", r"\botp\b|codetoken|verification code|one time password|\btac\b|digital token|security code|passcode"),
    ("gift_card", r"gift ?cards?|vouchers?|google play|itunes|steam cards?|redeem"),
    ("refund", r"refund|reimburs|money back|cashback|rebate"),
    ("bank_transfer", r"transfer|wire|paynow|paylah|remit|safe account|send (?:the )?money|western union"),
    ("investment", r"invest|portfolio|stocks?|crypto|bitcoin|trading|dividend|profit"),
    ("credit_card", r"credit card|debit card|card number|cvv|cardtoken|\bcard\b"),
    ("prize", r"prize|\bwon\b|winner|lottery|reward|congratulations"),
    ("insurance", r"insurance|policy|claim|coverage|premium"),
    ("loan", r"\bloans?\b|\bdebts?\b|borrow|lend|owe"),
    ("bill", r"\bbill|invoice|payment|overdue|subscription|charge|\bfees?\b|giro|tax"),
    ("bank_account", r"\bbank|account|\bpin\b|password|balance|savings|cpf|salary"),
]
TOPIC_RE = [(name, re.compile(rx)) for name, rx in TOPICS]


def tag_topic(clean):
    for name, rx in TOPIC_RE:
        if rx.search(clean):
            return name
    return "other"
