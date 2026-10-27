"""predict.py - load the BERT second-opinion model (ONNX) and score a call's recent caller lines.
Imported by server.py.

model/ (filled at image build by fetch_model.py, or copied in by hand):
    model.onnx         fine-tuned distilbert-base-multilingual-cased, exported to ONNX (int8-quantised)
    tokenizer.json     the SAME tokenizer the model was trained with
    bert_config.json   {"version", "max_len", "temperature", "max_turns", ...} written by the notebook

score = softmax(logits / temperature)[scam]: temperature scaling (fitted on the validation split) makes the
probability honest, which matters because the aggregator AVERAGES it with the workers' calibrated vote.

Quick test without gRPC:   python predict.py "您好，我是警察" "你的户口涉及洗钱案" "请马上转账"
"""
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

import bert_text

HERE = Path(__file__).resolve().parent
MODEL_DIR = Path(os.environ.get("MODEL_DIR", HERE / "model"))
_m = None


def text_prep_sha256():
    """Hash of bert_text.py (line endings ignored). The notebook stores the hash of the copy it trained with."""
    import hashlib
    src = (HERE / "bert_text.py").read_bytes().replace(b"\r\n", b"\n")
    return hashlib.sha256(src).hexdigest()


def load_model(model_dir=MODEL_DIR):
    """Load once and keep in memory. Raises if a file is missing (no model, no start), or if bert_text.py differs
    from the copy the model was trained with (train/serve parity; BERT_TEXT_MISMATCH_OK=1 to override)."""
    global _m
    model_dir = Path(model_dir)
    cfg = json.loads((model_dir / "bert_config.json").read_text())
    want = cfg.get("bert_text_sha256")
    if want and want != text_prep_sha256() and os.environ.get("BERT_TEXT_MISMATCH_OK") != "1":
        raise RuntimeError("bert_text.py differs from the copy this model was trained with "
                           f"({text_prep_sha256()[:12]} vs {want[:12]}): retrain, or restore the file")
    tok = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
    tok.enable_truncation(max_length=int(cfg["max_len"]), direction="left")   # MUST match training
    tok.no_padding()
    so = ort.SessionOptions()
    so.intra_op_num_threads = int(os.environ.get("ORT_THREADS", "2"))
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(str(model_dir / cfg.get("model_file", "model.onnx")), so,
                                providers=["CPUExecutionProvider"])
    _m = dict(cfg=cfg, tok=tok, sess=sess, inputs={i.name for i in sess.get_inputs()},
              T=float(cfg.get("temperature", 1.0)), scam=int(cfg.get("scam_index", 1)),
              max_turns=int(cfg.get("max_turns", bert_text.MAX_TURNS)))
    score(["warm up 预热"])  # the first ONNX run is slow; do it before reporting SERVING
    return _m


def score(lines):
    """P(scam) for the call's recent caller lines (oldest first). Empty -> 0.0."""
    if _m is None:
        load_model()
    text = bert_text.make_window(lines, _m["max_turns"])
    if not text:
        return 0.0
    enc = _m["tok"].encode(text)
    feed = {"input_ids": np.array([enc.ids], dtype=np.int64),
            "attention_mask": np.array([enc.attention_mask], dtype=np.int64),
            "token_type_ids": np.zeros((1, len(enc.ids)), dtype=np.int64)}
    logits = _m["sess"].run(None, {k: v for k, v in feed.items() if k in _m["inputs"]})[0][0].astype(np.float64)
    z = logits / _m["T"]
    z = np.exp(z - z.max())
    return float(z[_m["scam"]] / z.sum())


def version():
    return str(_m["cfg"].get("version", "unknown")) if _m else "not loaded"


if __name__ == "__main__":
    load_model()
    lines = sys.argv[1:] or ["您好，我是新加坡警察部队的。", "你的户口涉及一宗洗钱案。", "请马上把钱转到安全账户。"]
    t0 = time.perf_counter()
    s = score(lines)
    print(f"bert: {s:.3f}  ({(time.perf_counter() - t0) * 1000:.0f} ms, model {version()})")
    print("window:", bert_text.make_window(lines, _m["max_turns"]))
