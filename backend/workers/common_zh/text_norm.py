"""text_norm.py (was zh_normalize.py): shared text normalisation for the Chinese (zh) scam-call workers.

ONE copy for all three ZH workers: each *_zh Dockerfile copies this file into its image as text_norm.py,
so urgency_zh, money_zh and impersonation_zh always clean text the same way.

Used by:
  * zh_train.ipynb          (to test your own sentences)
  * train.py / predict.py    (in each *_zh worker image)

MUST stay identical to Cell 2 + Cell 4 of zh_clean.ipynb (clean / org_tokenize).
zh_train.ipynb checks this automatically against the gold set (common_zh/gold_test_zh.csv: 100% match).
Known gap: zh_clean.ipynb also removed each training row's known person names; live text can't know
those, so 1-3% of training rows differ from what the server produces (names left in).

Note: unlike the English workers this DOES lowercase (Latin words like DBS / OTP), and only
impersonation_zh splits words with jieba; urgency_zh and money_zh read characters. That is how the
models were trained, so serving must do the same.

Usage:
    from text_norm import prepare
    x = prepare("我是星展银行的客服，请马上转5000元", worker="impersonation", kind="jieba")
    p = model.predict_proba([x])[:, 1]

Requirements: opencc-python-reimplemented, jieba
"""
import re
import unicodedata

try:
    from opencc import OpenCC
    _t2s = OpenCC('t2s').convert
except ImportError:  # still runs, but traditional characters won't be converted
    _t2s = lambda s: s

# ---------------------------------------------------------------- org lexicon (zh_clean Cell 2)
ORG = {  # org_category -> words (lowercase; first category that matches wins)
    'police': ['新加坡警察部队', '警察部队', '警察局', '警察', '警官', '公安局', '公安', '派出所', '检察院', '检察官', '法院',
               '法官', '公检法', '民警', '刑警', '刑侦', '武警', '部队', '军官', 'spf'],
    'gov': ['移民与关卡局', '关卡局', '移民局', '金融管理局', '金管局', '银保监会', '银监会', '海关', '大使馆', '领事馆', '卫生部',
            '卫生局', '社保局', '税务局', '人力部', '政府', 'ica', 'mas', 'iras'],
    'bank': ['星展银行', '华侨银行', '大华银行', '银行', '理赔中心', '信用卡中心', 'dbs', 'ocbc', 'uob', 'posb'],
    'courier': ['新加坡邮政', '邮政', '快递', '物流', '顺丰', 'singpost', 'ninja van', 'ninjavan'],
    'telco': ['新加坡电信', '电信', '中国移动', '移动公司', '联通', '运营商', 'singtel', 'starhub', 'm1'],
    'platform_cs': ['客服', '支付宝', '京东', '淘宝', '天猫', '拼多多', '抖音', 'shopee', 'lazada', 'grab', 'carousell'],
}
ORG_TOKEN = {'police': 'ORG_POLICE', 'gov': 'ORG_GOV', 'bank': 'ORG_BANK', 'courier': 'ORG_COURIER',
             'telco': 'ORG_TELCO', 'platform_cs': 'ORG_PLATFORM'}


def _w(word):
    if word.startswith('re:'):
        return word[3:]
    if word == '京东':
        return r'(?<!北)京东(?!大溶洞)'                     # 北京东城区 / 京东大溶洞 are places, not JD
    if word.isascii():                                     # dbs, ica, m1 need word boundaries
        return rf'(?<![a-z0-9]){re.escape(word)}(?![a-z0-9])'
    return re.escape(word)


def _rx(words):
    return re.compile('|'.join(_w(x) for x in sorted(words, key=len, reverse=True)))


_ORG_RX_ALL = _rx(sum(ORG.values(), []))
_WORD2TOK = {x: ORG_TOKEN[k] for k, v in ORG.items() for x in v}


def org_tokenize(t):
    """impersonation worker only: 星展银行 -> ORG_BANK, 公安局 -> ORG_POLICE ..."""
    t = _ORG_RX_ALL.sub(lambda m: _WORD2TOK[m.group(0)], t)
    return re.sub(r'(ORG_[A-Z]+)(?:\1)+', r'\1', t)        # 京东客服 -> one ORG_PLATFORM


# ---------------------------------------------------------------- clean() (zh_clean Cell 4)
SURNAMES = '王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾萧田董袁潘蒋蔡余杜叶程苏魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方邹熊白孟秦邱侯江尹薛闫段雷黎史龙陶贺顾毛郝龚邵万钱严覃武戴莫孔向汤'
TITLES = '先生|女士|小姐|警官|经理|主任|老师|总|科长|队长|处长|局长|专员|阿姨|叔叔'
NAME_RXS = [
    (re.compile(rf'[{SURNAMES}][一-龥]?(?=({TITLES}))'), ''),             # 王警官 -> 警官
    (re.compile(rf'(客服|专员|助理|员工)[小老][{SURNAMES}]'), r'\1'),       # 客服小张 -> 客服
    (re.compile(rf'(?<![一-龥])[小老][{SURNAMES}](?![一-龥])'), ''),       # standalone 小张 / 老王
    (re.compile(rf'[{SURNAMES}]?某某?'), ''),                              # 某某, 陈某
    (re.compile(r'(我叫|我姓)[一-龥]{1,3}'), r'\1'),
]
TOKENS = [  # applied in this order, after lowercasing
    ('URLTOKEN', re.compile(r'https?://\S+|www\.\S+|[a-z0-9-]+\.(?:com|cn|net|org|sg|top|xyz|cc|vip)(?:/\S*)?')),
    ('ACCOUNTTOKEN', re.compile(r'\d{12,}')),
    ('PHONETOKEN', re.compile(r'\+?\d[\d-]{5,}\d')),
    ('MONEYTOKEN', re.compile(r'(?:s\$|\$|￥|¥|rmb)\s*\d+(?:\.\d+)?|\d+(?:\.\d+)?\s*[万千百]?\s*(?:元|块钱|块|人民币|新币|美元)'
                              r'|[一二三四五六七八九十百千万两几]+\s*(?:元|块钱|块)')),
    ('NUMTOKEN', re.compile(r'\d+(?:\.\d+)?')),
]
LEAD_SPEAKER = re.compile(rf'^(?:[一-龥]{{0,3}}(?:客服|先生|女士|小姐|经理|警官|主播|某|发言人\d*)[一-龥]{{0,3}}'
                          rf'|[{SURNAMES}][一-龥]{{1,2}})?\s*[:：]\s*')
EMOJI = re.compile(r'[\U00010000-\U0010ffff☀-➿️]')
BRACKET = re.compile(r'\([^()]{0,20}\)')


def clean(t, names=()):
    t = _t2s(unicodedata.normalize('NFKC', str(t)))
    t = re.sub(r'【[^】]{0,6}】', ' ', t)
    for n in names:
        if n:
            t = t.replace(n, '')
    for rx, rep in NAME_RXS:
        t = rx.sub(rep, t)
    t = BRACKET.sub('', EMOJI.sub('', t.lower()))
    t = LEAD_SPEAKER.sub('', t.strip())
    for tok, rx in TOKENS:
        t = rx.sub(tok, t)
    return re.sub(r'\s+', ' ', t).strip()


# ---------------------------------------------------------------- model input (zh_train Cell 2 + 4)
# every placeholder -> ONE character, so the char model keeps it whole (MONEYTOKEN -> Ⓜ)
TOKEN_MAP = {"ORG_POLICE": "❶", "ORG_BANK": "❷", "ORG_GOV": "❸", "ORG_PLATFORM": "❹", "ORG_COURIER": "❺", "ORG_TELCO": "❻",
             "ACCOUNTTOKEN": "Ⓐ", "PHONETOKEN": "Ⓟ", "MONEYTOKEN": "Ⓜ", "URLTOKEN": "Ⓤ", "NUMTOKEN": "Ⓝ"}
_TOK_RE = re.compile("|".join(sorted(TOKEN_MAP, key=len, reverse=True)))


def tokens_to_chars(s):
    return _TOK_RE.sub(lambda m: TOKEN_MAP[m.group(0)], str(s))


def jieba_join(s):
    import jieba
    return " ".join(t for t in jieba.lcut(s) if t.strip())


def prepare(text, worker, kind="char"):
    """raw sentence -> exactly what the trained model expects.
    worker: 'urgency' | 'money' | 'impersonation';  kind: 'char' | 'jieba' (saved in the model bundle)."""
    t = clean(text)
    if worker == "impersonation":
        t = org_tokenize(t)
    t = tokens_to_chars(t)
    return jieba_join(t) if kind == "jieba" else t


# ---------------------------------------------------------------- live transcripts (server.py / zh_cli.py)
# The models were trained on SINGLE sentences. A long transcript chunk dilutes the signal (style-swap test:
# a scam sentence glued to polite chatter was missed up to 50% of the time). So: split -> score each -> max.
SHORT, LONG, WIN, STRIDE = 30, 60, 24, 12     # training sentences are mostly 10-35 characters
_SENT_END = re.compile(r'(?<=[。！？!?；;\n])')
_SOFT_BREAK = re.compile(r'(?<=[，,、])|\s+')


def split_sentences(text):
    """candidate chunks to score: each sentence (if <= LONG chars) + its comma pieces packed to <= SHORT chars.
    ASR text with no punctuation at all falls back to 24-char windows (stride 12)."""
    out = []
    for s in _SENT_END.split(str(text)):
        s = s.strip()
        if not s:
            continue
        if len(s) <= SHORT:
            out.append(s); continue
        if len(s) <= LONG:
            out.append(s)                          # keep the whole sentence (context across commas) ...
        buf, pieces = '', []
        for piece in _SOFT_BREAK.split(s):        # ... and also its comma pieces (no dilution)
            piece = (piece or '').strip()
            if not piece:
                continue
            if buf and len(buf) + len(piece) > SHORT:
                pieces.append(buf); buf = ''
            buf += piece
        if buf:
            pieces.append(buf)
        for q in pieces:
            if len(q) <= SHORT:
                out.append(q)
            else:                                  # no punctuation: sliding windows
                out += [q[i:i + WIN] for i in range(0, max(len(q) - WIN, 0) + STRIDE, STRIDE) if q[i:i + WIN]]
    seen, final = set(), []
    for s in out:
        if s not in seen and len(re.sub(r'[\W_]', '', s)) >= 2:
            seen.add(s); final.append(s)
    return final


def score_text(bundle, text, worker):
    """max calibrated score over the sentences of `text`. Returns (score, best_sentence)."""
    sents = split_sentences(text)
    if not sents:
        return 0.0, ''
    X = [prepare(s, worker, bundle["kind"]) for s in sents]
    p = bundle["model"].predict_proba(X)[:, 1]
    i = int(p.argmax())
    return float(p[i]), sents[i]


if __name__ == "__main__":
    for s in ["您好，我是上海市公安局的王警官，请马上把5万元转到安全账户。", "我是星展銀行的客服，請提供OTP"]:
        print(s, "->", prepare(s, "impersonation"), "|", prepare(s, "money", "jieba"))
