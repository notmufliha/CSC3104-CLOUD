# Urgency (English) - labelling rule

One rule for every label in this worker: training data, audits, and gold sets.

## Urgent = 1

The speaker **takes away the listener's time to think**. Either:

- they demand action **now** or **within a short window** ("right now", "within 24 hours", "before the end of
  this call", "by 5pm today or ..."), or
- they claim something bad is **happening or about to happen right now** unless the listener acts
  ("officers are on their way", "your account is being frozen as we speak"), or
- they **stop the listener from pausing** ("don't hang up", "stay on the line", "don't tell anyone, there's no
  time", "you can't call your family first").

Examples
- You must pay within 24 hours or a warrant will be issued.
- Stay on the line, if you hang up the case goes to the police.
- Your account is being drained as we speak, we need to move the money now.
- This offer ends in ten minutes, press 1 now.
- Once I put the phone down I can't help you anymore.

## Not urgent = 0

- **Threats or alarm with no time pressure**: "your social security number was used for fraud",
  "there is a legal case against you". (Threat/impersonation is the other workers' job.)
- **Introductions / impersonation**: "this is the police", "I am calling from your bank".
- **Normal schedules and deadlines**: "your bill is due on the 15th", "the shop closes at 9pm",
  "your appointment is tomorrow at 3".
- **Reassurance**: "no rush", "take your time", "this is not urgent".
- **Anti-scam advice**: "we will never ask you to transfer money urgently", "if anyone pressures you, hang up".

Examples
- Your social security number has been suspended due to suspicious activity.
- This is officer Tan from the Singapore Police Force.
- Your next payment is due on the first of the month.
- There's no rush, call us back whenever you are ready.
- Banks will never ask you to act immediately over the phone.

## Edge cases

- A deadline counts only if it is used to **pressure** ("pay by Friday or your service is cut") - not a neutral
  fact ("the bill is due Friday").
- "Press 1 now" with nothing at stake is **not** urgent; "press 1 now or your account will be suspended" is.
- A sentence too short or broken to judge is **left unlabelled**, not guessed.

## Who labels what

- `eval/` files: **humans only**. Never AI-labelled, never edited by scripts, never used to write training rows.
  - Exception, PROVISIONAL: `eval/robocall_gold.csv` column `label_time_ai` was AI-labelled by this rule
    (2026-10-13) because no human was available. The same assistant also relabelled the training data, so scores
    on this column measure agreement with one labeller, not independent truth. Replace it with a human
    `label_time` column (two people, disagreements settled) before quoting robocall numbers anywhere.
- Training rows labelled by an AI assistant: `label_method = ai_label_ide`, plus a one-line `reason`.
- Training rows written (not mined) by an AI assistant: `label_method = ai_written`.
