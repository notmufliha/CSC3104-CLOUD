# Money / OTP (English) - labelling rule

One rule for every label in this worker: training data, audits, recheck sheets and test sets.
Rule version: money_en_rule_v2 (2026-11-01). Implemented in `money_norm.request_label`.

## Money = 1

The speaker **asks the listener** to do one of these (now, on this call, or through a channel the speaker names):

- **pay, transfer, buy, top up or withdraw** money ("transfer it to the safe account", "buy four Google Play cards",
  "go to the ATM and stay on the line", "settle what you owe before 5pm"), or
- **give card or bank details, an OTP, PIN, password or code** ("read me the six digits we just texted you",
  "what's the number on the back of your card?"), or
- **approve a payment or a login** ("approve the notification on your Singpass app").

The ask can be an order, a question ("can you confirm your card number?"), a need ("I'll need your account number")
or a threat ("if you don't pay today, a warrant will be issued").

Examples
- Please read me the OTP we just sent.
- You need to pay the release fee of $480 through PayNow.
- Settle what you owe before five o'clock.
- Put everything into the account I'm going to give you.
- If you don't pay the fine today, you will be arrested.

## Not money = 0

- **Warnings and anti-scam advice**: "never share your OTP", "your bank will never ask you to transfer money",
  "if anyone asks for your PIN, hang up".
- **Receipts and confirmations**: "your PayNow transfer of $50 was successful".
- **Prices, bills and balances stated as facts**: "your payment of $240 is due on the 25th", "the total is $23.50",
  "it starts from about $30 a month".
- **Payment later through an official channel**: "the consultation fee can be paid at the counter after your visit".
- **Scam set-up with no ask**: "a charge of $3,200 was made from your account" (threat/impersonation are the other
  workers' job; the ask usually comes in a later sentence).
- **Stories and past spending**: "I paid the electricity bill last week".
- **Idioms**: "pay attention", "pay heed", "pay tribute", "priceless", "hard work paid off", "transfer your call".
- **The listener's own words** (victim / customer side): "why do you need my OTP?", "I'm not giving you my card number".

Examples
- Never share your OTP with anyone, including bank staff.
- Your credit card payment of two hundred and forty dollars is due on the twenty-fifth.
- We noticed a suspicious payment of $3,200 from your account.
- Please pay heed to the safety instructions.
- There is no need to pay anything today.

## Edge cases

- A **real agent asking for a card number** is still **1**. The worker detects the ask; whether the call is a scam is
  decided by the aggregator with the urgency and impersonation scores.
- "You **can** pay by cash or card" (an option, no pressure) is **0**; "you **need to** pay by cash" is **1**.
- A **routine reminder** to pay a bill through the usual channel ("please settle your balance before the due date",
  "pay the driver on delivery", "pay the balance when you pick up the cake") is **0**. It becomes **1** with pressure
  now ("before midnight or your line is cut"), or a channel / recipient the speaker names ("PayNow it to this number",
  "use the link I sent").
- **Robocall menu options** that only confirm or authorise an order ("press 2 to authorize this order", "to continue
  with the purchase simply hang up") are **0**: nothing is paid or handed over. "Press 1 to pay your balance" is **1**.
- An ask **for something else** with money only as context ("to validate your purchase, can I have your username?",
  "can I offer you 10% off your next purchase?") is **left unlabelled** in the rule-labelled data, and **0** by hand.
- An ask about something only vaguely money ("check your account") is **left unlabelled**, not guessed.
- A sentence too short or broken to judge is **left unlabelled**.

## Who labels what

- Test sets (`sg_gold.csv`, `test_set_binary_classifier.csv`, `D_ftc_labelled.csv`, `recheck_labelled.csv`, `probes.csv`):
  every label must be **checked by a person** against this rule before the set is used for scores. Test sets are never
  edited by the training code and never used to write training rows.
- Training rows labelled by `request_label`: `label_method = rule_ask` / `rule_mention` / `rule_none`. They are checked by
  the audit in Part D (`D_audit_sample.csv`); if more than ~20% of a group looks wrong, fix the rule and re-run from Part C.
- Training rows written by a person: `label_method = handwritten`. Written by an AI assistant and checked by a person:
  `label_method = ai_written` (column in `handwritten_extra.csv`).
