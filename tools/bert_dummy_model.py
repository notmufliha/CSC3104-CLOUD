"""bert_dummy_model.py - a tiny FAKE model with the same inputs/outputs as the real BERT export, so the BERT pod,
the aggregator cascade and the timeouts can be tested end to end before the real model is trained.

Its scores mean NOTHING (random weights, version "DUMMY-not-a-real-model"). Never demo or report them.

    pip install onnx==1.16.1 tokenizers==0.19.1 numpy
    python tools/bert_dummy_model.py                 # writes backend/workers/bert/model/{model.onnx,...}
    docker compose up -d --build bert aggregator     # the cascade now runs with the fake model

Delete backend/workers/bert/model/* (or overwrite them with the notebook's files) before the real build.
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, processors, trainers

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "backend/workers/bert/model"
DIM = 8


def tokenizer():
    tok = Tokenizer(models.WordPiece(unk_token="[UNK]"))
    tok.normalizer = normalizers.BertNormalizer(lowercase=False)
    tok.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    corpus = ["您好我是新加坡警察部队的请马上转账到安全账户", "This is the police transfer the money now OTP",
              "你好这里是诊所确认你星期二的预约", "Hi this is the clinic calling to confirm your appointment |"] * 20
    tok.train_from_iterator(corpus, trainers.WordPieceTrainer(
        vocab_size=400, special_tokens=["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]))
    tok.post_processor = processors.BertProcessing(("[SEP]", tok.token_to_id("[SEP]")), ("[CLS]", tok.token_to_id("[CLS]")))
    return tok


def graph(vocab):
    rng = np.random.default_rng(0)
    inits = [numpy_helper.from_array(rng.normal(0, 1, (vocab, DIM)).astype(np.float32), "E"),
             numpy_helper.from_array(rng.normal(0, 1, (DIM, 2)).astype(np.float32), "W"),
             numpy_helper.from_array(np.zeros(2, np.float32), "b"),
             numpy_helper.from_array(np.array([1], np.int64), "ax1"),
             numpy_helper.from_array(np.array([2], np.int64), "ax2")]
    nodes = [helper.make_node("Gather", ["E", "input_ids"], ["emb"]),
             helper.make_node("Cast", ["attention_mask"], ["mf"], to=TensorProto.FLOAT),
             helper.make_node("Unsqueeze", ["mf", "ax2"], ["m3"]),
             helper.make_node("Mul", ["emb", "m3"], ["masked"]),
             helper.make_node("ReduceSum", ["masked", "ax1"], ["summed"], keepdims=0),
             helper.make_node("ReduceSum", ["m3", "ax1"], ["count"], keepdims=0),
             helper.make_node("Div", ["summed", "count"], ["mean"]),
             helper.make_node("MatMul", ["mean", "W"], ["mw"]),
             helper.make_node("Add", ["mw", "b"], ["logits"])]
    g = helper.make_graph(nodes, "dummy_bert",
                          [helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch", "seq"]),
                           helper.make_tensor_value_info("attention_mask", TensorProto.INT64, ["batch", "seq"])],
                          [helper.make_tensor_value_info("logits", TensorProto.FLOAT, ["batch", 2])], inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 8
    onnx.checker.check_model(m)
    return m


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    tok = tokenizer()
    tok.save(str(OUT / "tokenizer.json"))
    onnx.save(graph(tok.get_vocab_size()), str(OUT / "model.onnx"))
    (OUT / "bert_config.json").write_text(json.dumps(dict(
        version="DUMMY-not-a-real-model", model_file="model.onnx", max_len=256, temperature=1.0, scam_index=1,
        max_turns=5, note="fake model for wiring tests only; scores are meaningless"), indent=2))
    print(f"wrote dummy model.onnx, tokenizer.json, bert_config.json to {OUT}")
