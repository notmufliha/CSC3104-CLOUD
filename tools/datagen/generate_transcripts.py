# Adapted from AI-FraudCall-Detector
#   https://github.com/anand-adwaith/AI-FraudCall-Detector
#   dataset/sms_audio_dataset/generate_scam_call_transcript.py
#   Copyright (c) 2025 Adwaith Anand, MIT License (see THIRD_PARTY.md)
#
# Changes for CSC3104 scam-detect:
#   - Singapore content: GOIS (govt-official impersonation) subtypes, SG banks,
#     PayNow / Singpass / S$, SG normal calls incl. hard negatives
#   - --lang en | zh | ms | ta | singlish  (native generation, not translation)
#   - model / base URL / API key from env vars (LLM_MODEL, LLM_BASE_URL, LLM_API_KEY)
#   - FIX: dedup across processes (set was copied into each worker and never
#     merged back); workers now return results and the parent dedups
#   - output dir is created; output adds synthetic, language,
#     first_scam_turn_index, hard_negative, source, model
#   - FIX: token usage was always {} (isinstance check was inverted)
#   - --dry-run prints a prompt without calling the API

import argparse
import json
import os
import random
import re
import time
from concurrent.futures import ProcessPoolExecutor

import backoff
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

import misc_utils as misc

DEFAULT_OUTPUT_DIR = "call_transcript_dataset_output"
OUTPUT_BASE_NAME = "generated_transcript_dataset"
MIN_TURNS = 10

LANGUAGES = {
    "en": "Singapore English (standard, as spoken by Singaporeans)",
    "singlish": "Singlish (colloquial Singapore English with lah, leh, lor, can or not, "
                "and Hokkien/Malay loanwords mixed in naturally)",
    "zh": "Mandarin Chinese in Simplified characters, as spoken by Singaporean Chinese",
    "ms": "Malay (Bahasa Melayu) as spoken in Singapore",
    "ta": "Tamil in Tamil script, as spoken by Singaporean Tamils",
}

# ---------------------------------------------------------------------------
# Categories. Description is injected into the prompt.
# hard_negative=True marks legit calls that look scam-like (bank/govt/money talk).
# ---------------------------------------------------------------------------
FRAUD_CATEGORIES = {
    "GOIS - Police (SPF)":
        "caller pretends to be a Singapore Police Force officer, says the victim's bank "
        "account or phone number is linked to money laundering, threatens arrest, "
        "demands secrecy, asks to transfer funds to a 'safe account' for investigation",
    "GOIS - ICA":
        "caller pretends to be from ICA (Immigration & Checkpoints Authority), claims a "
        "problem with passport/NRIC/parcel at customs, then 'transfers' the call to a police officer",
    "GOIS - MinLaw / Court":
        "caller pretends to be from the Ministry of Law or State Courts, says there is a "
        "warrant or court case, demands a 'bail' or 'clearance' payment",
    "GOIS - MAS":
        "caller pretends to be from the Monetary Authority of Singapore, says the "
        "victim's bank accounts must be 'audited' and funds moved to a MAS-controlled account",
    "GOIS - China Officials":
        "caller pretends to be from Chinese police / Chinese embassy / China customs, says the "
        "victim's identity was used in a crime in China, transfers to a 'Chinese police officer', "
        "may ask victim to install an app or do a video call in a 'police station'",
    "Bank Impersonation":
        "caller pretends to be from a Singapore bank (DBS, OCBC, UOB, POSB) fraud team, "
        "reports suspicious transactions, asks for OTP, card details, or Singpass login",
    "Fake Delivery / Parcel":
        "caller pretends to be from SingPost, Ninja Van or a courier, says a parcel contains "
        "illegal items or needs a fee, then escalates to an 'officer'",
    "Investment Scam":
        "caller pitches a guaranteed-return crypto/forex/stock scheme, pressures quick PayNow transfer",
    "Job Scam":
        "caller offers easy online job (liking videos, rating products), later asks victim to "
        "top up money to 'unlock' commissions",
    "Phishing / OTP":
        "caller pretends to be a telco (Singtel, StarHub, M1) or SingPass helpdesk and tricks "
        "the victim into reading out an OTP or 2FA code",
}

NORMAL_CATEGORIES = {
    "Food / Grab Delivery": ("Grab, Foodpanda or Deliveroo rider calling about an order or address", False),
    "Clinic Appointment": ("polyclinic or GP clinic confirming or rescheduling an appointment", False),
    "Bank Callback (legit)": (
        "real bank staff returning a customer's call about a card replacement or branch "
        "appointment; never asks for OTP, PIN or transfers, tells customer to use the official app", True),
    "Government Reminder (legit)": (
        "real agency staff (e.g. HDB, CPF, IRAS) reminding about a scheduled appointment or "
        "letter; never asks for payment by phone, refers to official website or Singpass app", True),
    "Family Chat": ("family members chatting about dinner, school pickup, weekend plans", False),
    "Work Update": ("colleagues discussing a meeting, a deadline or a shift change", False),
    "Service Inquiry": ("customer calling a telco, aircon servicing or renovation company", False),
    "Payment Between Friends": (
        "friends splitting a bill and agreeing to PayNow each other; money talk but no pressure", True),
}

# ---------------------------------------------------------------------------
# Few-shot examples. Format per line: speaker<TAB>gender<TAB>message
# Speaker labels stay English (scammer / person 1 / person 2) in every language.
# Pools are per language; missing languages fall back to "en" examples and the
# prompt tells the model to write natively in the target language.
# ---------------------------------------------------------------------------
FRAUD_EXAMPLES = {
    "en": [
        "scammer\tmale\tHello, is this Mr Tan? I'm Inspector Lim from the Singapore Police Force, Commercial Affairs Department.\n"
        "person 1\tmale\tYes, this is Tan. What's this about?\n"
        "scammer\tmale\tYour DBS account was used to receive money from a money laundering syndicate. There is a warrant pending.\n"
        "person 1\tmale\tWhat? I never did anything like that.\n"
        "scammer\tmale\tI understand. To clear your name we need to verify your funds. Do not tell anyone, this is a confidential investigation.\n"
        "person 1\tmale\tCan I come down to the police station instead?\n"
        "scammer\tmale\tNo time, sir. If you leave the line the case goes to court today. Please log in to your banking app now.\n"
        "person 1\tmale\tOkay, I'm logging in. What do I do?\n"
        "scammer\tmale\tTransfer your savings to our safe account by PayNow. It will be returned after the investigation.\n"
        "person 1\tmale\tPolice don't ask people to transfer money. Give me your officer number.\n"
        "scammer\tmale\tYou are obstructing an investigation! Transfer S$20,000 now or we arrest you.\n"
        "person 1\tmale\tI'm calling 999 to check. Goodbye.\n"
        "FIRST_SCAM_TURN: 3\n",
        "scammer\tfemale\tGood afternoon, this is DBS fraud prevention. We detected a S$4,800 transaction on your card in Malaysia.\n"
        "person 1\tfemale\tI didn't make any transaction in Malaysia.\n"
        "scammer\tfemale\tDon't worry, we can block it. I will send you a code, please read it to me to confirm you are the cardholder.\n"
        "person 1\tfemale\tA code? The SMS says never share it with anyone.\n"
        "scammer\tfemale\tThat is for outsiders. I am the bank, so it is safe. Quickly, the transaction is being processed.\n"
        "person 1\tfemale\tWhich branch are you calling from?\n"
        "scammer\tfemale\tCentral fraud team at Marina Bay. Please, the code now, or the money will be gone.\n"
        "person 1\tfemale\tI'll call the number on the back of my card instead.\n"
        "scammer\tfemale\tIf you hang up we cannot reverse it. You will lose everything.\n"
        "person 1\tfemale\tThen I'll take that risk. I'm hanging up.\n"
        "FIRST_SCAM_TURN: 3\n",
    ],
    "zh": [
        "scammer\tmale\t喂，你好，请问是陈小姐吗？我是中国上海公安局的王警官。\n"
        "person 1\tfemale\t是我，什么事？\n"
        "scammer\tmale\t你的身份证在上海被人用来开了银行账户，涉及一宗洗钱案，金额有两百万人民币。\n"
        "person 1\tfemale\t不可能啊，我人在新加坡，很久没回中国了。\n"
        "scammer\tmale\t所以我们需要配合调查。这是机密案件，你不可以告诉家人或者新加坡警察。\n"
        "person 1\tfemale\t那我要怎么证明不是我？\n"
        "scammer\tmale\t你现在下载一个视频软件，我们视频做笔录。然后你要把你银行里的钱转到我们的安全账户，查清楚后会还给你。\n"
        "person 1\tfemale\t为什么要转钱？警察不会这样做的吧。\n"
        "scammer\tmale\t如果你不配合，我们会马上发通缉令，你在新加坡也会被抓。\n"
        "person 1\tfemale\t我要先打给新加坡警察确认一下。\n"
        "scammer\tmale\t不行！你一挂电话案子就转给法院了，后果你自己负责！\n"
        "person 1\tfemale\t那我也要确认。再见。\n"
        "FIRST_SCAM_TURN: 3\n",
        "scammer\tfemale\t您好，这里是新加坡邮政。您有一个包裹被海关扣留了，里面有违禁物品。\n"
        "person 1\tmale\t包裹？我最近没有寄东西啊。\n"
        "scammer\tfemale\t寄件人是您的名字和身份证号码。我现在帮您转接到警察局，请不要挂断。\n"
        "person 1\tmale\t好吧……\n"
        "scammer\tmale\t我是刑事调查局的李警官。你的银行账户可能涉及犯罪，请告诉我你在哪家银行有存款。\n"
        "person 1\tmale\t这个我不方便说。\n"
        "scammer\tmale\t你必须配合，否则今天就会被逮捕。请登录你的手机银行，把钱用PayNow转到我们指定的账户。\n"
        "person 1\tmale\t这听起来像骗局。我会自己报警。\n"
        "scammer\tmale\t你这是妨碍调查！\n"
        "person 1\tmale\t我挂了。\n"
        "FIRST_SCAM_TURN: 2\n",
    ],
}

NORMAL_EXAMPLES = {
    "en": [
        "person 1\tmale\tHello, is this Ms Wong? I'm calling from OCBC Tampines branch, returning your call yesterday.\n"
        "person 2\tfemale\tOh yes, about my replacement card.\n"
        "person 1\tmale\tYour new card is ready for collection. You can come any weekday before 4pm.\n"
        "person 2\tfemale\tGreat. Do I need to bring anything?\n"
        "person 1\tmale\tJust your NRIC. We will never ask for your PIN or OTP over the phone.\n"
        "person 2\tfemale\tGood to know. Can my husband collect it for me?\n"
        "person 1\tmale\tSorry, it has to be you in person for security reasons.\n"
        "person 2\tfemale\tOkay, I'll come on Thursday then.\n"
        "person 1\tmale\tSure. You can also check the status in the OCBC app.\n"
        "person 2\tfemale\tThanks for calling back.\n"
        "person 1\tmale\tYou're welcome, have a good day.\n",
        "person 1\tfemale\tEh, you reach home already or not?\n"
        "person 2\tmale\tNot yet lah, MRT damn crowded. Why?\n"
        "person 1\tfemale\tMa say dinner at Ah Ma's place tonight, seven o'clock.\n"
        "person 2\tmale\tOkay can. Need to buy anything?\n"
        "person 1\tfemale\tBuy some fruits from the market near the interchange.\n"
        "person 2\tmale\tOranges okay or not?\n"
        "person 1\tfemale\tCan, and maybe some kueh also.\n"
        "person 2\tmale\tOkay, I PayNow you back for the cake you bought last week also.\n"
        "person 1\tfemale\tNo need lah, small thing.\n"
        "person 2\tmale\tAiyah just take it. See you later.\n"
        "person 1\tfemale\tOkay bye.\n",
    ],
    "zh": [
        "person 1\tfemale\t喂，妈，我今天要加班，可能晚一点回家。\n"
        "person 2\tfemale\t好啊，那晚饭要不要帮你留？\n"
        "person 1\tfemale\t要，帮我留一点就好。弟弟去补习了吗？\n"
        "person 2\tfemale\t去了，七点半才下课，我等下去接他。\n"
        "person 1\tfemale\t外面在下雨，你记得带伞。\n"
        "person 2\tfemale\t知道啦。你周末要不要一起去巴刹买菜？\n"
        "person 1\tfemale\t好啊，星期六早上吧。\n"
        "person 2\tfemale\t那顺便去吃个早餐，你爸说想吃那间鱼片米粉。\n"
        "person 1\tfemale\t可以啊。我先忙了，晚点见。\n"
        "person 2\tfemale\t好，路上小心。\n",
    ],
}


def build_prompt(kind, category, lang, num_examples=2, batch_size=3):
    lang_desc = LANGUAGES[lang]
    pool = (FRAUD_EXAMPLES if kind == "fraud" else NORMAL_EXAMPLES)
    examples = pool.get(lang) or pool["en"]
    native_note = "" if lang in pool else (
        f" The examples below are in English for format only; write your transcripts "
        f"natively in {lang_desc}, the way real people speak, NOT as a translation.")
    selected = random.sample(examples, min(num_examples, len(examples)))
    genders = ["male", "female"]

    if kind == "fraud":
        desc = FRAUD_CATEGORIES[category]
        prompt = (
            f"Generate {batch_size} realistic scam phone call transcripts set in Singapore. "
            f"Scam type: {category} — {desc}. "
            f"Language: {lang_desc}.{native_note} "
            f"Each transcript must have 10-20 turns, scammer (gender: {random.choice(genders)}) and "
            f"person 1 (gender: {random.choice(genders)}). The scammer speaks first and may open "
            f"innocently before the scam becomes clear. Use Singapore references (SPF, ICA, MAS, "
            f"DBS/OCBC/UOB, PayNow, Singpass, OTP, S$, NRIC). Vary how the victim reacts: some "
            f"comply, some are suspicious, some hang up. "
            f"Each line must be 'scammer<TAB>gender<TAB>message' or 'person 1<TAB>gender<TAB>message' "
            f"(speaker labels in English, real tab characters). Speakers must alternate. "
            f"After the last line of each transcript add 'FIRST_SCAM_TURN: n' where n is the "
            f"1-based turn number where the scam first becomes evident. "
            f"Return a numbered list, each transcript starting with a line like '1:'. "
            f"Examples:\n")
    else:
        desc, _ = NORMAL_CATEGORIES[category]
        prompt = (
            f"Generate {batch_size} realistic, legitimate (NOT scam) phone call transcripts set in Singapore. "
            f"Call type: {category} — {desc}. "
            f"Language: {lang_desc}.{native_note} "
            f"Each transcript must have 10-20 turns, person 1 (gender: {random.choice(genders)}) and "
            f"person 2 (gender: {random.choice(genders)}). Use natural Singapore references "
            f"(HDB, MRT, hawker centre, PayNow, polyclinic). "
            f"Each line must be 'person 1<TAB>gender<TAB>message' or 'person 2<TAB>gender<TAB>message' "
            f"(speaker labels in English, real tab characters). Speakers must alternate, starting with person 1. "
            f"Return a numbered list, each transcript starting with a line like '1:'. "
            f"Examples:\n")

    for t in selected:
        prompt += "\nTranscript:\n" + t + "\n"
    prompt += "New call transcripts (numbered list):"
    return prompt


def _valid_speakers(first, speaker):
    if first == "scammer":
        return speaker in ("scammer", "person 1")
    return speaker in ("person 1", "person 2")


def parse_batch_response(text):
    """Return list of (turns, first_scam_turn_index). turns = [(speaker, gender, msg)]."""
    results = []
    turns, first_scam, active = [], None, False

    def flush():
        if not active:
            return
        if len(turns) >= MIN_TURNS:
            results.append((list(turns), first_scam))
        elif turns:
            print(f"Discarded transcript: only {len(turns)} turns")

    for raw in text.split("\n"):
        line = raw.strip()
        if re.match(r"^\d+[.:]\s*$", line):
            flush()
            turns, first_scam, active = [], None, True
            continue
        if not line or not active:
            continue
        m = re.match(r"^FIRST_SCAM_TURN:\s*(\d+)", line)
        if m:
            first_scam = int(m.group(1))
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            print(f"Malformed line: {line[:80]}")
            continue
        speaker, gender, msg = (p.strip() for p in parts)
        if gender not in ("male", "female"):
            continue
        if turns and not _valid_speakers(turns[0][0], speaker):
            continue
        if not turns and speaker not in ("scammer", "person 1"):
            continue
        if turns and turns[-1][0] == speaker:
            continue  # non-alternating
        turns.append((speaker, gender, msg))
    flush()
    return results


def _client():
    return ChatOpenAI(
        api_key=os.environ["LLM_API_KEY"],
        base_url=os.getenv("LLM_BASE_URL", "https://api.x.ai/v1"),
        model=os.getenv("LLM_MODEL", "grok-3"),
        temperature=float(os.getenv("LLM_TEMPERATURE", "0.9")),
        max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4000")),
    )


@backoff.on_exception(backoff.expo, Exception, max_tries=3)
def call_llm(prompt):
    chain = ChatPromptTemplate.from_messages([("human", "{prompt}")]) | _client()
    resp = chain.invoke({"prompt": prompt})
    usage = (resp.response_metadata or {}).get("token_usage", {})
    return resp.content.strip(), usage


def generate_category(kind, category, lang, num_required, batch_size=3):
    """Runs in a worker process. Returns candidates; dedup happens in the parent."""
    out, local_seen, attempts, tokens = [], set(), 0, 0
    max_attempts = max(5, 2 * num_required // max(batch_size, 1))
    while len(out) < num_required and attempts < max_attempts:
        attempts += 1
        try:
            text, usage = call_llm(build_prompt(kind, category, lang, batch_size=batch_size))
            tokens += usage.get("total_tokens", 0)
        except Exception as e:
            print(f"[{lang}] {kind}/{category} attempt {attempts} failed: {e}")
            time.sleep(1)
            continue
        for turns, first_scam in parse_batch_response(text):
            key = _dedup_key(turns)
            if key in local_seen:
                continue
            local_seen.add(key)
            out.append(_to_record(kind, category, lang, turns, first_scam))
    print(f"[{lang}] {kind}/{category}: {len(out)} candidates, {tokens} tokens")
    return out


def _dedup_key(turns):
    return "\n".join(re.sub(r"\s+", " ", m).strip().lower() for _, _, m in turns)


def _to_record(kind, category, lang, turns, first_scam):
    if kind == "fraud" and (first_scam is None or not 1 <= first_scam <= len(turns)):
        first_scam = None  # model omitted or gave nonsense; flag for review
    return {
        "type": kind,
        "category": category,
        "language": lang,
        "synthetic": True,
        "source": "llm-synthetic",
        "model": os.getenv("LLM_MODEL", "grok-3"),
        "hard_negative": kind == "normal" and NORMAL_CATEGORIES[category][1],
        "first_scam_turn_index": first_scam if kind == "fraud" else None,
        "transcript": [{"speaker": s, "gender": g, "message": m} for s, g, m in turns],
    }


def generate_dataset(lang, per_category, seen, workers, batch_size):
    jobs = [("fraud", c) for c in FRAUD_CATEGORIES] + [("normal", c) for c in NORMAL_CATEGORIES]
    dataset = []
    with ProcessPoolExecutor(max_workers=workers) as ex:
        futures = {ex.submit(generate_category, k, c, lang, per_category, batch_size): (k, c)
                   for k, c in jobs}
        for fut, (k, c) in futures.items():
            try:
                records = fut.result()
            except Exception as e:
                print(f"{k}/{c} failed: {e}")
                continue
            kept = 0
            for r in records:  # dedup in the PARENT, across categories and runs
                key = _dedup_key([(t["speaker"], t["gender"], t["message"]) for t in r["transcript"]])
                if key in seen or kept >= per_category:
                    continue
                seen.add(key)
                dataset.append(r)
                kept += 1
    random.shuffle(dataset)
    return dataset


def main():
    p = argparse.ArgumentParser(description="Generate synthetic Singapore scam/normal call transcripts.")
    p.add_argument("--lang", choices=list(LANGUAGES), default="en")
    p.add_argument("--num-datasets", type=int, default=1)
    p.add_argument("--per-category", type=int, default=5)
    p.add_argument("--batch-size", type=int, default=3)
    p.add_argument("--workers", type=int, default=4)
    p.add_argument("--out-dir", default=DEFAULT_OUTPUT_DIR)
    p.add_argument("--seed", type=int)
    p.add_argument("--dry-run", action="store_true", help="print one fraud + one normal prompt and exit")
    a = p.parse_args()

    if a.seed is not None:
        random.seed(a.seed)
    if a.dry_run:
        print(build_prompt("fraud", next(iter(FRAUD_CATEGORIES)), a.lang))
        print("\n" + "=" * 60 + "\n")
        print(build_prompt("normal", next(iter(NORMAL_CATEGORIES)), a.lang))
        return
    if not os.getenv("LLM_API_KEY"):
        raise SystemExit("Set LLM_API_KEY (and optionally LLM_BASE_URL, LLM_MODEL). See .env.example")

    os.makedirs(a.out_dir, exist_ok=True)
    seen = set()
    for i in range(a.num_datasets):
        t0 = time.time()
        data = generate_dataset(a.lang, a.per_category, seen, a.workers, a.batch_size)
        path = os.path.join(a.out_dir, f"{OUTPUT_BASE_NAME}_{a.lang}_{i}.json")
        misc.save_json_file(path, data)
        print(f"Saved {len(data)} transcripts to {path} in {time.time() - t0:.1f}s")


if __name__ == "__main__":
    main()
