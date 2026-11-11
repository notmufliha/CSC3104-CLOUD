"""money_norm.py: shared text code for the English Money/OTP worker (v2).

Written by the notebook and imported by every later step (and later by the live worker),
so training data, test data and new sentences are always processed by the same code.

v2 changes (Workstream D):
  - request_label(): the label rule from LABELING.md. 1 only when the speaker ASKS the listener
    for money, card/bank details or a code. A money word alone ("never share your OTP",
    "your transfer was successful") is 0. money_label() is kept as the old keyword baseline.
  - clean_text(mask_brands=True): bank/shop/app names become their type (DBS -> orgbank,
    PayNow -> payapp), the same idea and token names as the impersonation_en worker.
    mask_brands=False reproduces v1's cleaning exactly (used to score the frozen v1 model).
  - more idioms in NOT_MONEY ("pay heed", "pay tribute", "priceless", "the bill was passed").
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
    # v2: idioms that the v1 model flagged as money (test-set false alarms)
    r"pa(?:y|ys|id|ying) (?:\w+ )?(?:heed|tribute|homage|lip service)",
    r"pa(?:y|ys|id|ying) (?:\w+ )?(?:a |the |my |his |her |some )?compliments?",
    r"pa(?:y|ys|id|ying) (?:a |the )?(?:heavy |high |big )?price for",
    r"(?:it|that|this|which|work|effort|efforts|practice|training|patience) (?:\w+ )?(?:will |has |have |had |really |finally |all )?pa(?:y|ys|id) off",
    r"\bpriceless\b", r"\bbill (?:was|is|has been) (?:passed|tabled|debated|read)", r"pass(?:ed|es|ing)? the bill",
    r"invest(?:s|ed|ing)? (?:\w+ )?(?:time|effort|energy|attention)",
    r"(?:time|attention|focus) (?:well |wisely )?spent",
    r"(?:room|meeting|zoom|locker|postal|zip|door|gate|wifi|wi-fi|promo|promotion|discount|referral|invite|invitation|class|course|module|booking|tracking|qr) codes?(?! (?:we|i|that|which|sent|from (?:the |your )?(?:bank|sms)))",
    r"drop (?:a |the |me a )?pin", r"social security (?:numbers?|cards?|administration|office|benefits?|payments?|account)", r"(?:files?|photos?|pictures?|data|documents?|videos?|contacts?|folder) (?:to|into|onto|over)",
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


# ------------------------------------------------- v2: the request rule (LABELING.md)
# 1 = the speaker ASKS the listener to pay / transfer / buy / top up, or to give card or bank
#     details, an OTP / PIN / code, or to approve a payment or login.
# 0 = no ask: warnings, receipts, prices and bills stated as facts, stories, idioms, questions.
# None = an ask whose object is too vague to judge ("check your account"): dropped, like v1's weak-only rows.
LABEL_RULE_VERSION = "money_en_rule_v2 (2026-11-01)"

_EXPAND = [(re.compile(r"\bwon't\b"), "will not"), (re.compile(r"\bcan't\b"), "cannot"),
           (re.compile(r"\bdon't\b"), "do not"), (re.compile(r"\bdoesn't\b"), "does not"),
           (re.compile(r"\bdidn't\b"), "did not"), (re.compile(r"\bisn't\b"), "is not"),
           (re.compile(r"\bi'm\b"), "i am"), (re.compile(r"\bi'll\b"), "i will"),
           (re.compile(r"\bwe'll\b"), "we will"), (re.compile(r"\byou'll\b"), "you will"),
           (re.compile(r"\bwhat's\b"), "what is"), (re.compile(r"\bn't\b"), " not")]

ASK_VERBS = (r"send|pay|transfer|wire|remit|lend|loan me|reimburse|repeat|invest|paynow|paylah|bring|pass (?:it|them|the \w+) to|give|hand (?:over|me)|pass me|tell|read(?: out)?|share|provide|"
             r"confirm|key(?: it)? in|enter|type(?: in)?|input|fill in|approve|authori[sz]e|accept|verify|"
             r"buy|purchase|get me|top(?:[- ]?up)?|deposit|withdraw|move|put|drop|bank in|settle|clear|"
             r"scan|click|tap|press (?:approve|yes|accept|ok|confirm)|download|install|log ?in(?:to)?|sign ?in|go (?:to|down to)|head to|"
             r"open|forward|screenshot|snap|take a (?:photo|picture)|let me (?:have|know)|make (?:the |a )?payment|"
             r"cash out|redeem|transact|make (?:a |the )?transfer")
# imperative at the start of a clause (after "please", "and", "then", "now", "just", "ok" ...)
_LEAD = r"(?:^|[,;:]\s*|\b(?:and|then|so|now|just|first|next|quickly|please|pls|kindly|ok|okay|alright|sir|madam|maam|auntie|uncle|boss|lah)\s+)"
ASK_RE = re.compile("|".join([
    _LEAD + r"(?:please |pls |kindly |just |quickly |also )?(?:" + ASK_VERBS + r")\b",
    r"\b(?:you|u|ya)\s+(?:will |would |also |just |still |first |now )?(?:need|needs|have|has|must|got|gotta|are required|are going to have)\s+(?:to\s+)?(?:" + ASK_VERBS + r")\b",
    r"\b(?:you|u)\s+(?:must|should|have to|need to|can|could)\s+(?:quickly |first |now )?(?:" + ASK_VERBS + r")\b(?=.*\b(?:now|today|immediately|first|before|or else|otherwise|quickly)\b)",
    r"\b(?:can|could|would|will) (?:you|u) (?:please |kindly |just |quickly )?(?:" + ASK_VERBS + r")\b",
    r"\b(?:i|we) (?:will |would |just |also |still )?(?:need|require) (?:you to|your|the|that|this)\b",
    r"\b(?:is|are) (?:needed|required)\b(?! for (?:the|your) (?:meeting|event|class|appointment))",
    r"\b(?:i|we) (?:will |would |just |also |still )?need to (?:see|have|get|verify|confirm|know|take) your\b",
    r"\b(?:would|do) (?:you|u) (?:like|want|wish) to (?:" + ASK_VERBS + r")\b",
    r"\b(?:if|unless) (?:you|u) (?:do not |did not |fail to |refuse to |cannot |still )?(?:" + ASK_VERBS + r")\b",
    r"\b(?:may|can|could) (?:i|we) (?:please )?(?:have|get|know|take|confirm|verify)\b",
    r"\bwhat (?:is|are|was|were|did) (?:\w+ ){0,4}(?:otp|pin|code|password|card|account|cvv|numbers?|digits)\b",
    r"\b(?:otp|pin|code|tac|passcode),? what is it\b",
    r"^what (?:otp|pin|code|tac|numbers?|digits)\b",
    r"\b(?:when|which month|what (?:is|are|was) the) (?:\w+ ){0,8}?(?:expir\w*|expiry)\b",
    r"\b(?:it is|you are) (?:required|necessary|mandatory) (?:to|that you)\b",
    r"\b(?:need|required) to (?:pay|transfer|make (?:a |the )?payment|settle|clear)\b(?!.*\b(?:i|we) need to\b)",
    r"\bpress (?:\w+ )?(?:to|and) (?:pay|make|settle|clear|confirm|authori[sz]e|approve)\b",
    r"\b(?:otp|pin|code|card number|cvv|account number|payment|the amount|the money)\s*,?\s*(?:please|pls)\s*[.!?]?\s*$",
]))
# objects an ask can be about, beyond the STRONG money cues
ASK_OBJECT_RE = re.compile("|".join([
    r"\b(?:\w+[- ])?digits?\b", r"\bnumbers? (?:that|which|we|i|you|on|from|in|at|sent|texted)\b",
    r"\b(?:the|those|these|six|four|\w+-digit) numbers\b", r"\b(?:verification|security|reference|card|pin|otp|tac|six-digit|\d-digit) numbers?\b",
    r"\b(?:a few |some |one |two |three |five |\w+ )?(?:hundred|thousand|grand)\b", r"\bsome (?:cash|money)\b",
    r"\b(?:bank |the )?sms\b", r"\b(?:the |a )?deposit\b", r"\b(?:the |a )?penalty\b",
    r"\b(?:the |that |this |your )?codes?\b", r"\bpins?\b", r"\bpasswords?\b", r"\bcards?\b",
    r"\bwhat (?:you|u) owe\b", r"\b(?:outstanding )?dues\b", r"\bowe\b", r"\barrears\b", r"\bthe (?:full |remaining )?(?:amount|sum|balance)\b",
    r"\b(?:into|to|in) (?:the|this|my|our|a|that|another|new|safe|holding|secure|following) (?:\w+ )?accounts?\b",
    r"\baccount (?:details|info|information|number|login|password)\b",
    r"\b(?:notification|prompt|request|transaction|login|log-in|authori[sz]ation|approval)\b",
    r"\b(?:singpass|banking|bank) app\b", r"\b(?:card|visa|mastercard)\b.*\b(?:expir\w*|expiry)\b|\b(?:expir\w*|expiry)\b.*\b(?:card|on it|visa|mastercard)\b", r"\bqr\b", r"\batms?\b", r"\bgift\b",
    r"\b(?:everything|all of it|all your savings|the rest)\b",
]))
# warnings, refusals and "no need to pay": never a 1
WARNING_RE = re.compile("|".join([
    r"\b(?:never|not|no|do not|does not|did not|will not|would not|cannot|should not|must not|don't|dont)\s+(?:\w+\s+){0,3}?(?:" + ASK_VERBS + r"|ask|asks|asking|request|need)\b",
    r"\bno need (?:to|for)\b", r"\bnothing to pay\b", r"\bfree of charge\b",
    r"\bbeware\b", r"\bscam(?:s|mer|mers)?\b", r"\bscamshield\b", r"\bfraud(?:ster|sters)?\b(?=.*\b(?:never|not|careful|report|beware|hang up)\b)",
    r"\bif (?:anyone|someone|somebody|they|a caller|the caller|anybody) (?:\w+ ){0,3}(?:ask|asks|calls|requests|tells)\b",
    r"\bhang up\b", r"\breport (?:it|this|them) to\b", r"\bbe careful\b", r"\bstay alert\b",
]))
# questions and statements that start like an ask but are not one ("why do you need my OTP?")
NOT_ASK_RE = re.compile(r"^(?:why|how come|how did|what for|who|did|have|has|were|was)\b"
                        r"|^(?:(?:but|and|ok|okay|yes|sorry|no),? )?(?:i|we) (?:already|just) (?:paid|sent|transferred|gave)\b"
                        r"|\bgive me (?:a |one )?(?:moment|minute|min|second|sec|call|chance|break)\b"
                        # routine reminders through the usual channel (LABELING.md edge case)
                        r"|\b(?:before|by|on) (?:the|its|your) due date\b|\b(?:upon|on|at) delivery\b|\bwhen it arrives\b"
                        r"|\bwhen (?:you )?(?:pick (?:it |them )?up|collect)\b")
IF_NOT_RE = re.compile(r"\b(?:if|unless) (?:you|u) (?:do not|did not|does not|cannot|will not|fail to|refuse to)\b")
LISTENER_SPEAKERS = {"victim", "receiver", "customer"}


def _expand(text):
    t = light_norm(text)
    for rx, rep in _EXPAND:
        t = rx.sub(rep, t)
    return t


# Money words that are only CONTEXT in an ask ("to validate your purchase, can I have your username?",
# "10% off your next purchase"): they say what the call is about, not what is being asked for. An ask whose only
# money words are these is left unlabelled (None) instead of 1. "Pay for your purchase" still counts through "pay".
CONTEXT_ONLY_RE = re.compile(r"^(?:purchas\w*|prices?|costs?|costing|invoices?|tax(?:es)?|premiums?|refund\w*|reimburs\w*|"
                             r"rebates?|vouchers?|cashback|cash back|profits?|dividends?|salar\w*|wages?|savings|loans?|"
                             r"debts?|how much .*|interest .*|.* bills?|bills? .*|(?:account|card) balance|balance (?:is|of))$")


def request_parts(text):
    """What request_label sees: {'ask': bool, 'warning': bool, 'strong': [...], 'object': [...]}."""
    t = _expand(text)
    t_money = NOT_MONEY_RE.sub(" ", t)
    strong = [m.group(0) for m in STRONG_RE.finditer(t_money)]
    obj = [m.group(0) for m in ASK_OBJECT_RE.finditer(t_money)]
    weak = [m.group(0) for m in WEAK_RE.finditer(t_money)]
    # the ask is searched with idioms removed ("please pay attention" is not an ask) ...
    ask = bool(ASK_RE.search(t_money)) and not NOT_ASK_RE.search(t)
    # ... and the warning check ignores threats like "if you do not pay ..." (those are pressure to pay, not advice)
    warning = bool(WARNING_RE.search(IF_NOT_RE.sub(" ", t)))
    action = [w for w in strong if not CONTEXT_ONLY_RE.match(w)]     # money words that can BE the thing asked for
    return {"ask": ask, "warning": warning, "strong": strong, "action": action, "object": obj, "weak": weak}


def request_label(text, speaker=None):
    """LABELING.md rule: 1 = speaker asks for money / card or bank details / a code; 0 = no ask; None = unclear.

    speaker: listener-side turns (victim, receiver, customer) are never 1: an ask from them is dropped (None),
    because the live worker only scores the caller and "can you refund me" from a customer is not a scam signal.
    """
    p = request_parts(text)
    if p["warning"]:
        return 0
    if p["ask"] and (p["action"] or p["object"]):
        return None if speaker in LISTENER_SPEAKERS else 1
    if p["ask"] and (p["strong"] or p["weak"]):
        return None          # "check your account", "can I have your username to validate your purchase": an ask,
                             # but not FOR money / details / a code: too unclear to label
    return 0


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

# v2 (D.5): names become their TYPE so the model learns "asks you to pay via a payment app", not a list of brands.
# Token names match impersonation_en/text_utils.py ORG_TYPES (orgbank, orggovt, ...), plus payapp for payment apps.
# Differences from impersonation, on purpose: plain "grab" is left alone (usually the verb), "cpf" is left alone
# (CPF savings are money talk), "mom" is left alone ("send it to my mom"), and PayPal is a payapp here
# (impersonation calls it orgtech).
BRAND_TYPES = {
    "payapp": ["paynow", "paylah", "paylah!", "grabpay", "grab pay", "paypal", "venmo", "zelle", "cash app", "cashapp",
               "revolut", "youtrip", "shopeepay", "alipay", "wechat pay", "apple pay", "google pay", "nets pay"],
    "orgbank": ["dbs", "ocbc", "uob", "posb", "citibank", "citi", "hsbc", "standard chartered", "standard chartered bank",
                "maybank", "trust bank", "gxs", "bank of china", "icbc"],
    "orgpolice": ["spf", "singapore police force", "interpol"],
    "orggovt": ["ica", "iras", "mas", "singpass", "irs", "immigration and checkpoints authority",
                "monetary authority of singapore", "ministry of manpower", "inland revenue authority of singapore",
                "cyber security agency", "state courts", "supreme court", "ministry of home affairs",
                "ministry of health", "hdb", "lta", "csa", "govtech", "moh", "town council", "singapore customs", "mha"],
    "orgtelco": ["singtel", "starhub", "m1"],
    "orgtech": ["microsoft", "apple", "google", "amazon"],
    "orgcourier": ["singpost", "dhl", "fedex", "ninja van"],
    "orgshop": ["lazada", "shopee", "carousell", "taobao", "qoo10", "gojek"],
    "orgutility": ["sp group", "sp services"],
}
_BRAND_TO_TYPE = {n: t for t, names in BRAND_TYPES.items() for n in names}
# v2 cleaning: amounts written as words become moneytoken too, so "transfer fifty dollars" looks like "transfer $50"
_NUMW = (r"(?:a|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|"
         r"seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|"
         r"and|numtoken)")
WORD_MONEY_RE = re.compile(r"\b(?:" + _NUMW + r"[ -]){1,6}(?:dollars?|bucks|sgd|ringgit)\b"
                           r"|\bnumtoken ?(?:k|grand)\b|\b(?:a|one|two|three|five|ten) grand\b")
BRAND_RE = re.compile(r"\b(" + "|".join(re.escape(n) for n in sorted(_BRAND_TO_TYPE, key=len, reverse=True)) + r")(?![\w])")


def mask_brands(t):
    """Lower-case text in, brand names replaced by their type token."""
    return BRAND_RE.sub(lambda m: _BRAND_TO_TYPE[m.group(1)], t)


def clean_text(text, mask_brands=True):
    """Full clean used for TF-IDF. Keeps stopwords, no stemming.
    mask_brands=True is the v2 cleaning: brand names -> type tokens, and written amounts ("fifty dollars", "2k")
    -> moneytoken. mask_brands=False gives exactly the v1 cleaning (needed to score the frozen v1 model fairly)."""
    t = str(text)
    t = PLACEHOLDER.sub(_placeholder, t)
    t = re.sub(r"<[^>]+>", " ", t)
    for rx, rep in CLEAN_STEPS:
        t = rx.sub(rep, t)
    t = unicodedata.normalize("NFKD", t).encode("ascii", "ignore").decode()
    t = light_norm(t)
    if mask_brands:                                            # v2 cleaning (v1 models keep v1's exactly)
        t = BRAND_RE.sub(lambda m: _BRAND_TO_TYPE[m.group(1)], t)
        t = WORD_MONEY_RE.sub(" moneytoken ", t)
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
