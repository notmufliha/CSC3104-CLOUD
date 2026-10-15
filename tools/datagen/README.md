# Synthetic call transcript generator

Generates Singapore scam (GOIS etc.) and normal call transcripts with an LLM.
Adapted from AI-FraudCall-Detector (MIT), see `THIRD_PARTY.md`.

```bash
cd tools/datagen
pip install -r requirements.txt
export LLM_API_KEY=...            # see .env.example
python generate_transcripts.py --dry-run --lang zh      # check the prompt, no API call
python generate_transcripts.py --lang zh --per-category 5
```

Flags: `--lang en|zh|ms|ta|singlish`, `--num-datasets`, `--per-category`,
`--batch-size`, `--workers`, `--out-dir`, `--seed`, `--dry-run`.

Output (`call_transcript_dataset_output/`, gitignored): JSON list of
`{type, category, language, synthetic, source, model, hard_negative,
first_scam_turn_index, transcript:[{speaker, gender, message}]}`.

- `first_scam_turn_index` is 1-based and is `null` for normal calls, or if the
  model didn't mark it (flag those for manual review).
- `hard_negative` is true for legit bank/government/money calls.
- Synthetic rows must keep `synthetic=true` so they can be excluded from the test set.
