# bert: the BERT second opinion

One multilingual model (`distilbert-base-multilingual-cased`, fine-tuned) that reads a call's **last 5
caller lines together** and returns P(scam). The aggregator calls it only when the six fast workers are unsure
or alarmed, which is the **cascade** (task 7.4). See the "BERT second opinion" section of the main README for
the rules.

| | |
|---|---|
| RPC | `BertService.ScoreContext(ContextRequest{lines, language}) -> BertScore{score, model_version}` (port 50051) |
| Health | `grpc.health.v1`: SERVING once the model has loaded and answered a warm-up request |
| Errors | gRPC INTERNAL, never a made-up score (the aggregator then decides without BERT) |
| Logs | score, number of lines, language, milliseconds. Never text |
| Text prep | `bert_text.py` = the same file the training notebook used (NFKC, traditional to simplified, lines joined with " \| ", last 5 lines, tokenizer truncates from the LEFT) |
| Score | softmax(logits / temperature)[scam]. The temperature was fitted on the validation split so the score is a real probability (the aggregator averages it with the workers' calibrated vote) |

## Model files (not in git)

`model/` needs the 3 files the notebook (`notebooks/bert_cascade.ipynb`) writes:

```
model.onnx          fine-tuned model, ONNX, int8-quantised
tokenizer.json      the tokenizer it was trained with
bert_config.json    version, max_len, temperature, max_turns, model_sha256, metrics
```

Either:

1. **Local**: copy them into `backend/workers/bert/model/`, then `docker compose build bert`; or
2. **Download at build**: upload them (Hugging Face model repo, or a GitHub release) and build with the folder URL,
   pinned to a commit so a later upload can't silently change the image:
   ```bash
   echo 'MODEL_BASE_URL=https://huggingface.co/<user>/csc3104-scam-bert/resolve/<commit>' >> .env
   docker compose build bert
   ```
   `fetch_model.py` checks `model.onnx` against `model_sha256` in `bert_config.json`.

No model yet? `python tools/bert_dummy_model.py` writes a FAKE model (meaningless scores) so the wiring,
timeouts and fallbacks can be tested.

## Check it

```bash
cd backend/workers/bert && python predict.py "您好，我是新加坡警察部队的" "你的户口涉及洗钱案" "请马上把钱转到安全账户"
docker compose up -d --build bert aggregator
docker compose logs -f aggregator | grep bert_status
```

Sizing: CPU only. int8 DistilBERT is about 30-80 ms per request with `ORT_THREADS=2`. Keep
`GRPC_THREADS x ORT_THREADS` at or below the CPU limit.
