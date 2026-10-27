"""bert_text.py - the ONE function that turns a call's recent caller lines into the text BERT reads.

Used by BOTH the training notebook (notebooks/bert_cascade.ipynb builds every training window with
make_window) and the BERT pod (predict.py), so training and serving can never prepare text differently
(the lesson from the ZH workers' name gap).

Light normalisation only: BERT needs natural text, so no lowercasing (the model is cased), no placeholder
tokens and no name stripping. NFKC (full-width -> normal), traditional -> simplified Chinese, whitespace
collapsed. Lines are joined oldest -> newest with " | "; the tokenizer truncates from the LEFT, so when the
window is too long the OLDEST words are dropped and the newest line is always read.
"""
import re
import unicodedata

try:
    from opencc import OpenCC
    _t2s = OpenCC("t2s").convert
except ImportError:  # still runs; traditional characters are then not converted
    _t2s = lambda s: s  # noqa: E731

MAX_TURNS = 5          # caller lines per window (the gateway sends up to 5)
JOIN = " | "           # a visible separator the tokenizer keeps ("\n" would be dropped as whitespace)
_ZH = re.compile(r"[一-鿿㐀-䶿]")
_WS = re.compile(r"\s+")


def normalize(line):
    s = unicodedata.normalize("NFKC", str(line))
    if _ZH.search(s):
        s = _t2s(s)
    return _WS.sub(" ", s).strip()


def make_window(lines, max_turns=MAX_TURNS):
    """caller lines, oldest first -> one string. Keeps the last `max_turns` non-empty lines."""
    lines = [normalize(x) for x in lines if str(x).strip()]
    return JOIN.join(lines[-max_turns:])


if __name__ == "__main__":
    print(make_window(["您好，我是新加坡警察部隊的。", "  你的戶口涉及洗錢案  ", "", "請馬上轉賬"]))
