"""
mobilebert_fixtures.py - Workstream G.4 test data for the phone's MobileBERT loader (no real model needed).

Writes app/src/androidTest/assets/mobilebert_dummy/:
  vocab.txt          a tiny lowercase WordPiece vocabulary, trained here with Hugging Face `tokenizers`
  model.onnx         a DUMMY model with MobileBERT's interface (input_ids / attention_mask / token_type_ids,
                     int64 [1, n] -> logits float [1, 2]). Its scores mean nothing; it only proves the plumbing.
  bert_config.json   max_len 32 (small, so left truncation is exercised), temperature 1.5, scam_index 1
  cases.json         for ~40 caller-line windows: the window text (bert_text.make_window), the reference token
                     ids from `tokenizers` (BertWordPieceTokenizer, left truncation) and the score ONNX Runtime
                     gives with softmax(logits / temperature)[scam_index]

Tests that use it:
  JVM:   app/src/test/.../WordPieceTokenizerTest.kt   (tokenizer ids == tokenizers' ids)
  phone: app/src/androidTest/.../MobileBertDeviceTest.kt (whole pipeline: score == ONNX Runtime's within 1e-4)

  pip install tokenizers onnx onnxruntime numpy
  python android-app/tools/mobilebert_fixtures.py
"""
import json
import unicodedata
import re
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper
from tokenizers import BertWordPieceTokenizer

OUT = Path(__file__).resolve().parents[1] / "app" / "src" / "androidTest" / "assets" / "mobilebert_dummy"
MAX_LEN = 32
TEMPERATURE = 1.5
SCAM_INDEX = 1

CORPUS = [
    "Good afternoon, this is Officer Tan from the Singapore Police Force.",
    "Your bank account has been linked to a money laundering investigation.",
    "To protect your savings you must transfer the money to a safe account today.",
    "Please read me the six digit OTP that was just sent to your phone.",
    "Hi, this is the clinic calling to confirm your appointment on Tuesday.",
    "Your parcel is held at customs, pay the clearance fee now or it will be destroyed.",
    "Hey mum, my phone broke, this is my new number, can you lend me some money?",
    "We are from Microsoft technical support, your computer has a virus.",
] * 30

# bert_text.make_window, as on the server (backend/workers/bert/bert_text.py; t2s left out: English only)
WS = re.compile(r"\s+")


def make_window(lines, max_turns=5):
    lines = [WS.sub(" ", unicodedata.normalize("NFKC", str(x))).strip() for x in lines if str(x).strip()]
    return " | ".join(lines[-max_turns:])


WINDOWS = [
    ["Good afternoon, this is Officer [PERSON_NAME] from the Singapore Police Force."],
    ["This is the police.", "Your account is linked to money laundering.", "Transfer [MONEY_AMOUNT] to the safe account now!"],
    ["Hi, this is the clinic.", "Your appointment is on Tuesday at [NUMBER] pm.", "Please bring your IC.", "Thank you!", "Bye."],
    ["one", "two", "three", "four", "five", "six: only the last five lines count"],
    ["Café résumé naïve — “quotes” and ‘single’ ones", "ＦＵＬＬ-ＷＩＤＴＨ ｔｅｘｔ ＯＴＰ １２３"],
    ["UPPER case and MiXeD CaSe words", "punctuation!!! ??? ... $$$ ### @@@ (brackets) [square] {curly}"],
    ["  lots   of\twhite\nspace  ", "", "   ", "after empty lines"],
    ["supercalifragilisticexpialidocious antidisestablishmentarianism unknownwordzzz"],
    ["您好 mixed 中文 and English 转账"],
    ["a" * 120 + " a word longer than 100 characters becomes UNK"],
    [" ".join(["please listen carefully and do exactly what I say"] * 6)],   # longer than max_len: left truncation
    ["Your parcel is held at customs.", "Pay the clearance fee via PayNow immediately.", "Or it will be destroyed."],
    ["Hey mum, my phone broke.", "This is my new number.", "Can you lend me some money for rent?"],
    ["We are from Microsoft technical support.", "Your computer has a virus.", "Give me remote access now."],
    ["tab\tseparated\tvalues", "zero​width​space and soft­hyphen", "control\u0007char"],
] + [[s] for s in CORPUS[:8]] + [[CORPUS[i], CORPUS[i + 1]] for i in range(0, 16, 2)] + [CORPUS[0:5], CORPUS[3:8]]


def build_graph(vocab_size):
    """Embedding-bag + linear: logits = mean(E[input_ids] * mask) @ W + b, plus a token_type_ids term so every
    declared input is used. Deterministic weights (seed 0)."""
    rng = np.random.default_rng(0)
    dim = 8
    inits = [numpy_helper.from_array(rng.normal(0, 1, (vocab_size, dim)).astype(np.float32), "E"),
             numpy_helper.from_array(rng.normal(0, 1, (dim, 2)).astype(np.float32), "W"),
             numpy_helper.from_array(np.array([0.1, -0.2], np.float32), "b"),
             numpy_helper.from_array(np.array([1], np.int64), "ax1")]
    nodes = [
        helper.make_node("Gather", ["E", "input_ids"], ["emb"]),                       # [1, n, dim]
        helper.make_node("Cast", ["attention_mask"], ["mf"], to=TensorProto.FLOAT),    # [1, n]
        helper.make_node("Unsqueeze", ["mf", "ax2"], ["mf3"]),                         # [1, n, 1]
        helper.make_node("Mul", ["emb", "mf3"], ["masked"]),
        helper.make_node("ReduceSum", ["masked", "ax1"], ["summed"], keepdims=0),       # [1, dim]
        helper.make_node("ReduceSum", ["mf", "ax1"], ["count"], keepdims=1),            # [1, 1]
        helper.make_node("Div", ["summed", "count"], ["pooled"]),
        helper.make_node("MatMul", ["pooled", "W"], ["lin"]),                           # [1, 2]
        helper.make_node("Cast", ["token_type_ids"], ["tf"], to=TensorProto.FLOAT),
        helper.make_node("ReduceSum", ["tf", "ax1"], ["tsum"], keepdims=1),             # [1, 1] (0 for one segment)
        helper.make_node("Add", ["lin", "tsum"], ["lin2"]),
        helper.make_node("Add", ["lin2", "b"], ["logits"]),
    ]
    inits.append(numpy_helper.from_array(np.array([2], np.int64), "ax2"))
    ins = [helper.make_tensor_value_info(n, TensorProto.INT64, [1, "n"]) for n in ("input_ids", "attention_mask", "token_type_ids")]
    out = [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, 2])]
    g = helper.make_graph(nodes, "mobilebert_dummy", ins, out, inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)], producer_name="mobilebert_fixtures.py")
    m.ir_version = 8
    onnx.checker.check_model(m)
    return m


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    trainer_tok = BertWordPieceTokenizer(lowercase=True)
    trainer_tok.train_from_iterator(CORPUS, vocab_size=300, min_frequency=1,
                                    special_tokens=["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"])
    vocab = sorted(trainer_tok.get_vocab().items(), key=lambda kv: kv[1])
    (OUT / "vocab.txt").write_text("".join(t + "\n" for t, _ in vocab), encoding="utf-8")

    # the reference tokenizer, loaded from that vocab.txt exactly as an uncased BERT would be
    tok = BertWordPieceTokenizer(str(OUT / "vocab.txt"), lowercase=True)
    tok._tokenizer.enable_truncation(max_length=MAX_LEN, direction="left")   # the wrapper hides `direction`
    tok._tokenizer.no_padding()

    model = build_graph(len(vocab))
    (OUT / "model.onnx").write_bytes(model.SerializeToString())
    sess = ort.InferenceSession(str(OUT / "model.onnx"), providers=["CPUExecutionProvider"])

    cfg = {"version": "DUMMY-not-a-real-model", "max_len": MAX_LEN, "temperature": TEMPERATURE,
           "scam_index": SCAM_INDEX, "max_turns": 5, "lower_case": True, "model_file": "model.onnx"}
    (OUT / "bert_config.json").write_text(json.dumps(cfg, indent=2) + "\n")

    cases = []
    for lines in WINDOWS:
        text = make_window(lines)
        enc = tok.encode(text)
        ids = np.array([enc.ids], np.int64)
        logits = sess.run(None, {"input_ids": ids, "attention_mask": np.ones_like(ids),
                                 "token_type_ids": np.zeros_like(ids)})[0][0].astype(np.float64)
        z = logits / TEMPERATURE
        z = np.exp(z - z.max())
        cases.append({"lines": lines, "window": text, "ids": enc.ids, "tokens": enc.tokens,
                      "score": float(z[SCAM_INDEX] / z.sum())})
    (OUT / "cases.json").write_text(json.dumps({"max_len": MAX_LEN, "cases": cases}, indent=1, ensure_ascii=False) + "\n")
    print(f"{OUT}: vocab {len(vocab)}, {len(cases)} windows, model {(OUT / 'model.onnx').stat().st_size} bytes")


if __name__ == "__main__":
    main()
