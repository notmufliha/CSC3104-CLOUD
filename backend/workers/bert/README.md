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

## Model files (downloaded automatically, not in git)

`model/` needs 3 files: `model.onnx` (int8, ~136 MB), `tokenizer.json`, `bert_config.json`. They are **not in
git** (`model.onnx` is over GitHub's 100 MB limit). **You don't need to copy them anywhere**:

- `docker compose up -d --build` (or a plain `docker build`) downloads them from the team's Hugging Face repo
  and checks the SHA256. Nothing to set up.
- To run `predict.py` on your laptop, download them once: `cd backend/workers/bert && python fetch_model.py`.

Published model: `https://huggingface.co/gouthamsaravanan/csc3104-scam-bert/resolve/a0ebfbc63c1591a889798d7828cd5c65e063c57a`
(model_sha256 `de0f771d861a5029666d97736f9715bef097b52eead991c973a67855d82f0c1c`).

Keep `model/` empty apart from `.gitkeep` unless you are testing a new model: anything in it is used instead of
the download, and a file with the wrong hash fails the build. Delete old dummy files.

**After retraining** (`notebooks/bert_cascade.ipynb`, last cell uploads to Hugging Face): put the new commit URL
and `model_sha256` in `fetch_model.py` (DEFAULT_URL / DEFAULT_SHA256), the Dockerfile ARGs and
`docker-compose.yml`.

`tools/bert_dummy_model.py` (a FAKE model with meaningless scores) was only for wiring tests before the real
model existed. Don't use it now.

## Check it

```bash
cd backend/workers/bert && python fetch_model.py && python predict.py "您好，我是新加坡警察部队的" "你的户口涉及洗钱案" "请马上把钱转到安全账户"
docker compose up -d --build bert aggregator
docker compose logs -f aggregator | grep bert_status
```

Sizing: CPU only. int8 DistilBERT is about 30-80 ms per request with `ORT_THREADS=2`. Keep
`GRPC_THREADS x ORT_THREADS` at or below the CPU limit.
