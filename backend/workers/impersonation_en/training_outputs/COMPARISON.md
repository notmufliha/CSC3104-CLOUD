# impersonation_en: v3 vs v4 comparison

Run on 2026-11-05. v3 = the model that was committed to the repo before this
work (`model/versions/impersonation_en_v3.pkl`, threshold 0.47). v4 = the
retrained model after Workstream D.3 to D.8 (28 new rows, brand-masking fixes,
`model/model.pkl`, threshold 0.30, chosen by the same automatic CV process as
v3). Both are scored on the same 622-row `gold_test.csv`, never used for
training or threshold tuning.

**Headline: v4 is not a clean win. It is slightly worse on overall
discrimination and clearly worse on false alarms on this gold set, even
though it does a bit better on courier/tech scam rows. Read the recommendation
at the end before deciding whether to ship it.**

## 1. Threshold-free discrimination (ROC-AUC)

| | ROC-AUC |
|---|---|
| v3 | 0.9864 |
| v4 | 0.9805 |

v4 is worse here. This measures how well each model ranks impersonation vs
not, independent of where the cut-off is drawn.

## 2. Precision / recall / F1 at a fixed threshold (apples to apples)

| threshold | model | precision | recall | f1 |
|---|---|---|---|---|
| 0.47 (v3's own) | v3 | 0.9636 | 0.9014 | 0.9315 |
| 0.47 (v3's own) | v4 | 0.9375 | 0.9184 | 0.9278 |
| 0.30 (v4's own) | v3 | 0.9441 | 0.9184 | 0.9310 |
| 0.30 (v4's own) | v4 | 0.9186 | 0.9218 | 0.9202 |

At EITHER threshold, v3's F1 is slightly higher than v4's. v4's recall edges
up a little, but precision drops more than recall gains.

## 3. False alarms on label-0 (genuine) gold rows, each model at its own threshold

| | false positives | out of |
|---|---|---|
| v3 (threshold 0.47) | 10 | 328 |
| v4 (threshold 0.30) | 24 | 328 |

**This is the clearest regression.** v4 more than doubles the false-alarm
count on genuine calls in the gold set, mostly because its chosen threshold
(0.30) is much lower than v3's (0.47). A lower threshold was picked
automatically by the same cross-validation process that picked v3's, on the
larger dev set (533 rows after adding the D.3/D.4 rows) - it is not something
I hand-tuned.

## 4. Mistakes by org_category (courier and tech, flagged by the plan as hard categories)

| category | rows | v3 errors | v4 errors |
|---|---|---|---|
| courier | 43 | 8 | 6 |
| tech | 35 | 5 | 4 |

v4 does slightly better here - 2 fewer courier mistakes, 1 fewer tech mistake.
This is the one place v4 is a real improvement, though the samples are small
(43 and 35 rows) so this could be noise.

## 5. Keyword-dependence test (D.2), both models

| | ROC-AUC before | ROC-AUC after masking top 20 words | relative drop | recall before | recall after |
|---|---|---|---|---|---|
| v3 | 0.987 | 0.977 | 0.9% | 0.901 | 0.602 |
| v4 | 0.981 | 0.974 | 0.7% | 0.922 | 0.762 |

By the agreed pass rule (fail if ROC-AUC drops by more than about a third),
**neither model is a keyword detector** - masking the top 20 words barely
moves ROC-AUC for either. Recall drops a lot more than ROC-AUC for both
(v3: -0.299, v4: -0.160), meaning a sizeable minority of positive rows lean on
those words to clear the threshold, but the model does not collapse into
guessing without them. v4 leans on the top words somewhat less than v3
(smaller recall drop), which is a small positive sign for the D.3/D.4 data
additions, separate from the threshold/false-alarm regression above.
Full reports: `keyword_test_impersonation_en_v2.json` (v3, labelled v2 inside
the file because that was still the model_version string when it was frozen)
and `keyword_test_impersonation_en_v4.json`.

## 6. Single-sentence experiment (D.6.4)

The plan asked to split each multi-turn gold row into individual caller turns
and check how often the single highest-scoring turn alone crosses 0.6, for
scam rows and for genuine rows. **`gold_test.csv` turned out to already be
single-sentence: 0 of 622 rows contain a "Caller:"/"Receiver:" label**, so
there is nothing to split there - the whole gold set is already a
single-line benchmark, which is actually good news for believing the gold
numbers above reflect live, line-by-line scoring.

To still run the experiment as asked, I used `impersonation_data.csv`
(training data, multi-turn, 592 of 628 rows have 2+ caller turns) instead,
scoring is fine even though it was used for training:

| model | scam rows: some turn >= 0.6 | genuine rows: some turn >= 0.6 |
|---|---|---|
| v3 | 228/231 (98.7%) | 7/361 (1.9%) |
| v4 | 227/231 (98.3%) | 10/361 (2.8%) |

Both models catch almost every scam row on at least one single turn (as
expected - this is training data, not held out). v4 fires on slightly more
genuine single turns (2.8% vs 1.9%), consistent with the false-alarm
regression in section 3.

## 7. Live demo scripts, before vs after (D.6/D.0 recheck)

Full replay (`tools/testing/results_baseline_v3.csv` vs
`results_v4_after_retrain.csv`, run via `python tools/testing/replay.py
--speed 0 --caller-only`): **the end-to-end verdict table is identical before
and after retraining.** Same 3 English scam scripts TOO EARLY
(`scam_en_10_gov_official`, `scam_en_11_bank_otp`, `scam_en_12_parcel_customs`),
same 1 false alarm (`normal_en_10_bank_reminder`, driven by money/aggregator
logic, not impersonation - see D.0 below), same 2 scam scripts OK. v4 neither
fixed nor worsened the TOO EARLY problem on these particular 14 scripts.

Per-line impersonation scores did shift on individual lines (see
`baseline_lines_2026-11-05.csv` vs `v4_lines_2026-11-05.csv`): 19 of 99 caller
lines moved by 0.05 or more. The shifts are mostly on later, money-ask lines
(e.g. `scam_en_13` line 7: 0.36 -> 0.90), not on the early opener lines that
cause TOO EARLY. No normal-call line crossed 0.45 that wasn't already close.

## Recommendation

v4 is a mixed result, not a clear upgrade:
- Better: slightly fewer courier/tech mistakes, slightly less keyword-reliant.
- Worse: lower ROC-AUC, lower F1 at either threshold, **more than double the
  false alarms on the gold set**, slightly worse single-sentence false-trigger
  rate.

The false-alarm regression is the one I would not ship without a decision
from the team. It is mainly a consequence of the automatic threshold dropping
from 0.47 to 0.30 on the bigger dev set, which the training script - not me -
picked by cross-validated F1. **I have left v4 live in the running Docker
stack for testing (see below), but I recommend keeping `model/versions/
impersonation_en_v3.pkl` as the fallback and deciding with the team (Mridula
especially, since she owns the aggregator thresholds) whether v4 ships as is,
needs a manually-raised threshold, or needs more genuine-call training data
before the deadline.**
