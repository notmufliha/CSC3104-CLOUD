# Labelling rule: impersonation_en

This worker scores one thing: is the caller falsely claiming to be from an
authority or a trusted organisation (police, government, bank, telco, courier,
tech support, shop, utility, health)? It does not score "is this a scam call"
in general. A scam call with no authority claim (a job scam, a friend or
relative impersonation scam, a romance scam) is label 0 here even though the
call is still a scam. Money and urgency signals are scored by the other two
workers.

## Label 1: impersonation

The caller states or clearly implies that they ARE a named institution or
one of its staff, AND the call has scam cues (the claim is used to create
fear, urgency, secrecy, or to ask for money, OTPs, or personal details).

Both parts must be true. A real staff member who truthfully says who they
are is not label 1 (see label 0 below).

Real examples from `impersonation_data.csv`:

1. "Caller: Good afternoon, this is Inspector Ahmad from the Singapore Police
   Force. Your call was transferred to me by the bank. Receiver: Transferred?
   Caller: Yes, your name is linked to a money laundering case and I need to
   verify some details with you." (claims to be police, used to create fear)
2. "Caller: Hello, I am Officer Priya from the Anti-Scam Command. We have
   arrested a person carrying a bank card under your name. Receiver: What?
   Caller: Please stay on the line and do not tell anyone, this is a
   confidential investigation." (claims to be police, demands secrecy)
3. "Caller: Hello ah, this is Sergeant Wong, Cantonment Police. You got a
   case under your NRIC, you know or not? Receiver: Huh, what case? Caller:
   Don't cut the line, I explain to you." (claims to be police, pressure to
   stay on the line)

## Label 0: not impersonation

Use label 0 for any of these:
- The caller is a genuine staff member who truthfully names their bank,
  clinic, courier, telco, etc, with no scam cues.
- An organisation is only mentioned (for example the receiver asks about a
  bank, or the caller warns "the bank will never ask for your OTP"), not
  claimed as the caller's own identity.
- The caller is impersonating a person, not an institution (a friend, a
  relative, a boss). This includes the "friend in trouble" and romance scam
  patterns, even though they are still scams.
- A scam with no authority claim at all (a job scam, a shopping scam that
  never claims to be the shop).

Real examples from `impersonation_data.csv`:

1. "Caller: Hello, this is Priya from DBS card services. We are calling about
   a transaction you reported yesterday. Receiver: Yes, I raised that.
   Caller: It has been refunded. We will never ask for your PIN or OTP over
   the phone." (genuine bank call, no scam cues)
2. "Caller: Good afternoon, this is Mr Lim from OCBC. You requested a
   callback about your home loan. Receiver: Yes, thanks for calling. Caller:
   Sure, which part of the loan would you like to go through?" (genuine
   bank call, caller truthfully named, no pressure)
3. "Caller: Hi, UOB branch here ah, just confirming your appointment
   tomorrow at 3 pm to update your account details. Receiver: Okay, I will
   be there. Caller: Great, please bring your NRIC." (genuine bank call,
   routine appointment confirmation)

## Label U: cannot tell

Use U when a human labeller genuinely cannot decide from the text alone
(for example the clip is too short, or it is ambiguous whether the caller
is claiming to be staff or just a customer talking about an organisation).
Rows labelled U are excluded from training and from the gold set. As of
5 Nov 2026 there are no U rows in either CSV; this project has not yet
collected any labelling disagreements that would need it.

## Known hard cases (see D.0 baseline and D.3/D.4 additions)

- A single caller sentence that only names an organisation ("this is the
  bank", "this is the police") without any visible scam behaviour yet is
  genuinely hard: in a live call the model only sees one line at a time, and
  a real staff member's opening line looks identical to a scammer's opening
  line. Label strictly on the two-part rule above (claim AND scam cues in the
  same row), not on vibes. If a window has no scam cues yet, it is label 0,
  even if the full call later turns out to be a scam.
- "Mention, not behaviour" rows (D.3) and "behaviour, no keyword" rows (D.4)
  exist to stop the model from keying on words like "police" or "bank"
  alone, in either direction.
