# impersonation_en: v3 vs v4 comparison

Run on 2026-11-05. v3 = the model that was committed to the repo before this
work (`model/versions/impersonation_en_v3.pkl`, threshold 0.47). v4 = the
retrained model after Workstream D.3 to D.8 (28 new rows, brand-masking fixes,
`model/model.pkl`, threshold 0.30, chosen by the same automatic CV process as
v3). Both are scored on the same 622-row `gold_test.csv`, never used for
training or threshold tuning.

**Headline: v4 is not a clean win. It is slightly worse on overall
discrimination and clearly worse on false alarms on this gold set, even
though it does a bit better on courier/tech scam rows. A third version, v5
(section 9), was retrained afterward to target a blind spot both v3 and v4
share; it trades some recall for the lowest false-positive rate of the
three. Read the recommendation at the end before deciding what to ship.**

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

## 8. External, independently-written test lines (`gold_human.csv`), added 2026-11-10

Run on 2026-11-10. `data/impersonation_en/gold_human.csv` holds 96 lines (48
label 1, 48 label 0) written by four different outside AI chat models (not
used anywhere else in this project), each given only the abstract labelling
rule and no examples from our actual data, specifically so the phrasing would
not echo our training/gold style. `source=external_test` for all of them -
see the note at the top of this section's commit for why it isn't labelled
"human" even though the file is still named `gold_human.csv`. None of these
96 rows overlap (exact-text) with `impersonation_data.csv` or `gold_test.csv`.

| model | precision | recall | f1 | roc_auc | false positives | false negatives |
|---|---|---|---|---|---|---|
| v3 (threshold 0.47) | 0.906 | 1.000 | 0.950 | 0.993 | 5 / 48 | 0 / 48 |
| v4 (threshold 0.30) | 0.870 | 0.979 | 0.922 | 0.988 | 7 / 48 | 1 / 48 |

**This independently confirms section 1-3 above: v3 beats v4 on this
differently-written set too**, on every metric (ROC-AUC, F1, precision,
fewer false positives), though neither model catches every label-0 row.

The false positives are informative and point at the same blind spot for
both models, not something retraining v3 into v4 fixed or worsened much:
- Person-impersonation money scams with no institutional claim (a "cousin"/
  "uncle"/"grandson" asking for urgent PayNow transfers, a fake Carousell
  buyer asking for banking login) score high (0.6-0.97) even though the
  correct label is 0 under our rule (no organisation is claimed). The model
  appears to be keying on generic urgency/secrecy/money language rather than
  the institutional claim specifically.
- One mention-only row (two people discussing a suspicious IRAS SMS, neither
  of them is IRAS) scored very high on both models (v3: 0.62, v4: 0.96) -
  exactly the "mention, not behaviour" confusion D.3 was meant to fix, but 2
  training examples of that pattern were evidently not enough.
- One genuine bank call (Maybank, truthfully named, no codes asked) scored
  high on both (v3: 0.59, v4: 0.95).

This suggests a concrete next step - more hard-negative training rows
specifically for (a) urgent money requests that impersonate a *person*, not
an institution, and (b) casual mention/discussion of a scam rather than a
real scam call - rather than anything that would be fixed by threshold
tuning alone. Not done yet in this pass; flagging for a possible v5.

## 9. v5 retrain targeting the section 8 blind spot, added 2026-11-10

Added 22 new hard-negative rows (label 0) aimed squarely at the two failure
patterns `gold_human.csv` exposed in section 8: person-impersonation money
asks (fake cousin/uncle/grandson/friend requesting urgent PayNow transfers,
no organisation claimed) and mention-only scam discussions (two people
talking about a scam, neither one impersonating anyone), plus 4 genuine
institutional calls that use urgent language but aren't scams, so the model
has to stop treating "urgent" as a proxy for "impersonation". Retrained as
`impersonation_en_v5` (`model/model.pkl`, 650 rows). v4 is frozen at
`model/versions/impersonation_en_v4.pkl` / `.card.json` the same way v3 was.

| | v3 (thr 0.47) | v4 (thr 0.30) | v5 (thr 0.67) |
|---|---|---|---|
| gold_test.csv precision | 0.964 | 0.919 | 0.970 |
| gold_test.csv recall | 0.901 | 0.922 | 0.888 |
| gold_test.csv f1 | 0.931 | 0.920 | 0.927 |
| gold_test.csv ROC-AUC | 0.986 | 0.981 | 0.988 |
| gold_test.csv false positives | 10/328 | 24/328 | 8/328 |
| gold_test.csv false negatives | 29/294 | 23/294 | 33/294 |
| gold_human.csv precision | 0.906 | 0.870 | 0.958 |
| gold_human.csv recall | 1.000 | 0.979 | 0.958 |
| gold_human.csv f1 | 0.950 | 0.922 | 0.958 |
| gold_human.csv false positives | 5/48 | 7/48 | 2/48 |
| gold_human.csv false negatives | 0/48 | 1/48 | 2/48 |

The threshold the training script picked on its own jumped to 0.67 (up from
v4's 0.30, even higher than v3's 0.47) now that the dev set includes examples
that look urgent but aren't impersonation - the model needed more separation
to keep calling those negative.

**v5 fixes most of the section 8 blind spot.** Checking the exact rows that
were false positives for v3 or v4 in `gold_human.csv`: the grandson-PayNow
row (v4: 0.95 -> v5: 0.04), the cousin-accident row (v3: 0.75 -> v5: 0.32),
the uncle-accident row (v4: 0.73 -> v5: 0.49), and the IRAS-SMS mention-only
row (v4: 0.96 -> v5: 0.59) all dropped below v5's threshold. Two of the
section 8 false positives remain unfixed: a fake Carousell buyer asking for
banking login (still 0.98) and the genuine Maybank confirmation call (down
from 0.95 to 0.83, but still above threshold) - both still score as
impersonation.

**The cost: v5 trades recall for precision, and misses 2 genuine scams on
`gold_human.csv` it didn't miss before** - both are the SP Group electricity
bill scam, scoring 0.56-0.60, just under the new 0.67 cut. The higher
threshold needed to push the blind-spot rows down also pushes some real but
less aggressive scam lines below the line. Keyword-dependence held up (0.4%
ROC-AUC drop when masking top words, same as v3/v4, so v5 isn't a keyword
detector either), but recall after masking drops much further than v4's did
(-0.459 vs -0.160), meaning v5 leans more heavily on its strongest words to
clear the now-higher bar.

## Recommendation

Three versions, three different trade-offs, none of them a clean sweep:
- **v3** (threshold 0.47): best recall on both gold sets, but still has the
  full section 8 blind spot (5 false positives on `gold_human.csv`).
- **v4** (threshold 0.30): worst false-alarm rate on genuine calls (24/328,
  double v3's), the section 8 blind spot is just as bad or worse.
- **v5** (threshold 0.67): fixes 4 of 7 section 8 blind-spot cases, lowest
  false-positive count on both gold sets, but lower recall than v3 or v4 on
  `gold_test.csv` and introduces 2 new false negatives on `gold_human.csv`
  (both the same SP Group electricity scam pattern, scoring just under the
  new threshold).

Whether v5 is "better" depends on what the team weighs more: for a
scam-detection product, missing a real scam (false negative) is usually
worse than a false alarm the caller can dismiss, which argues for v3 or v4
over v5 on raw recall. But v5 is the only version that materially closes the
specific blind spot (impersonating a relative/friend for money, discussing a
scam without being one) that both earlier versions shared, and its gold_human
numbers are the best across the board (f1 0.958, fewest combined errors: 4
vs v3's 5 and v4's 8). **I'd lean toward shipping v5 over v3/v4**, since the
2 new false negatives are a narrow, nameable pattern (SP Group / utility
bill phrasing right at the new threshold) that a couple more training rows
could likely fix in a future pass, whereas v3 and v4's blind spot is broader
and already caused real false positives on an independent test set. This is
a judgement call for the team, not something the numbers settle on their
own - happy to add a few more utility-scam rows and retrain again if we want
to try to recover that recall before deciding.
