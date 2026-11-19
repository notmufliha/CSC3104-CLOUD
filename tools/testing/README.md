# Workstream 6: demo calls and end-to-end testing

| Task | Where |
|---|---|
| 6.1 Call scripts | `android-app/app/src/main/assets/scripts/` (14 new files, numbers 10 to 14; the app and the replay script share them) |
| 6.2 Expected answers | `tools/testing/expected.json` |
| 6.3 Replay script | `tools/testing/replay.py` |
| 6.4 Results table | printed by `replay.py`, saved as `tools/testing/results_<tag>.csv` (git-ignored) |
| 6.5 Bug list | "Bug list candidates" printed by `replay.py`, plus the template below |
| 6.6 Window experiment | `tools/testing/compare_windows.py` |
| Copy check | `tools/testing/similarity_check.py` -> `similarity_check.csv` |

## The scripts

New files follow the app's name rule `<kind>_<lang>_<nn>_<title>.json`. Existing scripts (`*_00_*`, `*_01_*`, `*_02_*`) are untouched.
"Simulate scam call" / "Simulate normal call" still play the first file of that kind in alphabetical order, so they are unchanged.
The new scripts appear under "Choose a call script...".

| File | What it is | Key line (red by here) | Red allowed from | Late if after |
|---|---|---|---|---|
| `scam_en_10_gov_official` (+ `scam_zh_10_gov_official`) | bank, then SPF handoff, "safety account" | 11 | 8 | 14 |
| `scam_en_11_bank_otp` | bank fraud team asks you to read out the OTP | 9 | 7 | 11 |
| `scam_en_12_parcel_customs` (+ `scam_zh_12_parcel_customs`) | customs release fee by PayNow | 10 | 8 | 12 |
| `scam_en_13_tech_support` | remote app, "move your money to a protected account" | 13 | 11 | 14 |
| `scam_en_14_friend_in_trouble` | fake relative, bail, urgent PayNow | 10 | 7 | 12 |
| `normal_en_10_bank_reminder` (+ `normal_zh_10_bank_reminder`) | real bank reminder, "we never ask for your OTP" | never red | | |
| `normal_en_11_clinic_appointment` | appointment, fee at the counter | never red | | |
| `normal_en_12_delivery_driver` | price, cash or PayNow at the door | never red | | |
| `normal_en_13_insurance_sales` | premium and "promotion ends this month" (yellow is OK) | never red | | |
| `normal_en_14_family_call` (+ `normal_zh_14_family_call`) | "I'll PayNow you back" | never red | | |

Line numbers are 1-based positions in the file (caller and receiver lines share one count, the same `seq` the app sends).
Status in `replay.py`: red before "from" = TOO EARLY, after "late if after" = TOO LATE, never = MISSED, a normal call that
goes red = FALSE ALARM, red that later drops back = NOT STICKY. The key lines and ranges are a judgement call: edit
`expected.json` if the team disagrees. Every key line falls after 15 s of call time, so the warm-up rule (3.5) does not hide it.

### Calls added in November 2026 (from SPF's 2026 scam advisories)

Dialogue written by the team, modelled on the scam patterns in the Singapore Police Force's 2026 advisories (each
row in `expected.json` links its advisory). Each scam has a genuine call that uses the same words (a "hard negative").
Measured on 2026-11-13 (Kubernetes, `replay.py --caller-only --speed 4`):

| File | What it is | Key line | Red allowed from | Measured |
|---|---|---|---|---|
| `scam_en_20_ica_to_china_police` (+ `scam_zh_20_…`) | fake ICA officer hands over to "Shanghai police", supervision account | 11 (zh 9) | 7 (zh 5) | red at 7 (zh 7) |
| `scam_en_21_telco_to_mas_officer` | "Singtel" about a second line, hand-off to a fake MAS officer, safety account | 9 | 5 | red at 5 |
| `scam_en_22_apple_support_crypto` | fake Apple support after a sign-in alert, move crypto to a "secure wallet" | 9 | 7 | red at 9 |
| `scam_en_23_bank_card_handover` | fake bank fraud team, courier collects card and PIN, ATM cash | 9 | 5 | red at 5 |
| `scam_en_24_job_task_deposit` | job/task scam: top up to "unlock" commission | 9 | 7 | **MISSED** (UNSURE): no one is impersonated |
| `normal_en_20_ica_passport_collection` | real ICA reminder, book in MyICA, "ICA never asks for money by phone" | never red | | SAFE |
| `normal_en_21_bank_fraud_check` | real bank fraud team: one yes/no question, card blocked, "call the number on your card" | never red | | **FALSE ALARM** (red at 5) |
| `normal_en_22_telco_contract_renewal` | real telco renewal offer, nothing to pay today | never red | | SAFE |

The two misses are kept on purpose: the job scam is a new scam the models don't know yet, and the bank fraud check is
a real false alarm. Both are what the learning loop (`demo/LEARNING_LOOP.md`) is for: report them ("This was a
scam" / "This was fine"), label them on the review website, retrain, and replay to compare.

## Test it

```bash
docker compose up -d --build                  # repo root; wait until `docker compose ps` shows all services Up
python tools/gateway_test.py --no-stop        # quick check that the gateway itself is fine
python tools/testing/replay.py --only scam_en_10 --verbose    # one call, in real time (about 80 s)
python tools/testing/replay.py                    # all 14 calls, in real time (about 12 min)
python tools/testing/replay.py --caller-only      # same, sending only caller lines like the app does
python tools/testing/replay.py --lang auto        # omit "lang" so the gateway's 30% Chinese-character rule is tested
```

Python 3 only (standard library). Use real time for the numbers you report: `--speed 10` plays 10x faster, but the 15 s
warm-up rule (3.5) then sees a shorter call.
In the app: Home -> "Choose a call script..." -> pick one of the new files -> Answer.
Fast check of every script in the app folder (including the old five): `python3 -I android-app/tools/backend_script_test.py android-app/app/src/main/assets/scripts`.

## 6.6 Context experiment

The gateway currently keeps the best score per signal over the whole call (`backend/gateway/src/calls.rs`) and the
aggregator has no window setting yet (3.3/3.7 are not built), so there is nothing to vary until the window exists. Once it
does (for example a `CONTEXT_WINDOW` environment variable): set 3, restart, `python tools/testing/replay.py --tag w3`; repeat for
4 and 6; then `python tools/testing/compare_windows.py tools/testing/results_w3.csv tools/testing/results_w4.csv tools/testing/results_w6.csv`.

## 6.5 Bug list (send to the owner)

| Script | Problem (too early / too late / never / false alarm) | Red at line | Expected | Owner |
|---|---|---|---|---|
| | | | | |

## Notes
- Names, numbers and agencies are invented. The call flows follow the SPF/MAS/CPF advisories (bank call, transfer to a
  "government official", "safety account"), not a recorded call.
- Copy check (`similarity_check.py`): no line is >= 0.90 similar to any sentence in the repo's training/eval CSVs or the app's
  other scripts. The highest is 0.75, a short generic receiver line ("What should I do?"). The money worker's training data is on
  Drive, not in the repo: run `python tools/testing/similarity_check.py --extra <C_train_pool.csv>` before submitting.
- Not yet run against the real stack: `replay.py` was only tested against a mock gateway.
