"""
parity_cases.py - Workstream G.2 parity test, Python side: score sentences exactly as the server's workers do.

For each sentence it records, per model, the cleaned text and the score from the worker's own predict.score()
(the function the gRPC server calls for every caller line). The app's tests score the same sentences with the
exported JSON models and the Kotlin cleaning, and fail if any score differs by more than 0.01:
  - JVM:    app/src/test/.../OnDeviceParityTest.kt         (./gradlew testDebugUnitTest)
  - phone:  app/src/androidTest/.../OnDeviceParityDeviceTest.kt (./gradlew connectedDebugAndroidTest)

Sentences: every caller line of the English demo scripts (app/src/main/assets/scripts), the robocall test set
(backend/workers/urgency_en/eval/robocall_gold.csv) and hand-written edge cases for the cleaning rules.

Run once per model, each with that worker's scikit-learn version (see tools/README.md):
  python android-app/tools/parity_cases.py urgency
  python android-app/tools/parity_cases.py money
  python android-app/tools/parity_cases.py impersonation
Writes android-app/app/src/androidTest/assets/parity/parity_cases.json (merged across the three runs).
"""
import argparse
import csv
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKERS = REPO / "backend" / "workers"
APP = REPO / "android-app" / "app" / "src"
OUT = APP / "androidTest" / "assets" / "parity" / "parity_cases.json"
ROBOCALL = WORKERS / "urgency_en" / "eval" / "robocall_gold.csv"
MODEL_FILES = {
    "urgency": "urgency_en/model/urgency_en_v4.joblib",
    "money": "money_en/model/money_en_v2c.joblib",
    "impersonation": "impersonation_en/model/model.pkl",
}

# Edge cases for the cleaning rules: money and number formats, phone numbers, NRIC, dates, times, links,
# placeholders, contractions, curly quotes, speaker tags, sentence splitting, long unpunctuated text, non-ASCII.
EDGE = [
    "Transfer $5,000.50 to the safe account now.",
    "You owe S$1,200 in unpaid fines, pay 300 dollars today",
    "Pay USD 49.99 or SGD 2k by 5pm tomorrow!",
    "Call me back at +65 9123 4567 or 6123-4567 within 24hrs.",
    "Your NRIC S1234567D was used to open account 1234-5678-9012-3456.",
    "Read me the 6-digit OTP sent to your phone, it expires in 12 mins.",
    "Meet me on 15th March 2024 at 3:30 p.m., or 2024-03-15 at 10am.",
    "Visit www.dbs-secure.com/login or email help@bank.sg for a refund.",
    "[MONEY_AMOUNT] must be paid to [PERSON_NAME] at [PHONE_NUMBER] before [DATE].",
    "Caller: This is Officer Tan from SPF. Receiver: Oh no, what happened?",
    "Person A: you won't get another chance, can't wait, don't hang up",
    "I’m calling from the Ministry of Health — you’re under investigation.",
    "This is DBS bank. This is OCBC. This is the Singapore Police Force. This is ICA.",
    "done.Then transfer the money.Now please! Quickly? ok",
    "hello hello hello",
    "",
    "   ",
    "12345678",
    "ok",
    "cb12lf 7t9k2b codes: 4491 and 0021",
    " ".join(["please listen carefully and do exactly what I say"] * 8),
    "Your Amazon parcel is held by customs, pay the clearance fee via PayNow immediately or it will be destroyed",
    "Hi mum, my phone broke, this is my new number, can you lend me 800 for the rent?",
    "Hey, are we still on for dinner at 7? I'll bring the cake.",
    "Your Singtel bill of $45.20 is due on 3rd Jan, pay via GIRO as usual.",
    "Grandma, it's me, I'm in trouble with the police and need bail money, don't tell mum.",
    "Microsoft technical support here, your computer has a virus, give me remote access.",
    "Café résumé naïve – “quotes” ‘single’ £100 €50 ¥30",
    "ＡＢＣ １２３ full-width text, pay ＄５００",
    "line one\nline two with urgency now\r\nline three",
    "This is the police.\n\nTransfer to the safe account.",
    # money_en v2 cleaning: brand names -> type tokens, written amounts -> moneytoken
    "Transfer fifty dollars to my PayNow, or two hundred and fifty SGD via PayLah! now",
    "This is DBS bank, use Grab Pay or GrabPay, the OCBC app or Standard Chartered Bank to pay 2k",
    "Send three thousand ringgit by Western Union or a grand in cash, apple pay also can",
    "Can you top up my GXS account with a hundred bucks? Then buy Google Play cards on Shopee or Carousell.",
    "My mom uses CPF and grab to work; Bank of China and ICBC are banks; Revolut, YouTrip, Alipay, WeChat Pay.",
]


def sentences():
    out = []
    for f in sorted((APP / "main" / "assets" / "scripts").glob("*_en_*.json")):
        for line in json.loads(f.read_text()):
            if str(line.get("speaker", "")).lower() == "caller" and line.get("text"):
                out.append(("script:" + f.stem, line["text"]))
    with ROBOCALL.open(newline="") as fh:
        for row in csv.DictReader(fh):
            out.append(("robocall:" + row["id"], row["text"]))
    out += [("edge", t) for t in EDGE]
    return out


def scorer(name):
    wdir = WORKERS / MODEL_FILES[name].split("/")[0]
    sys.path.insert(0, str(wdir))
    import predict
    predict.load_model(str(WORKERS / MODEL_FILES[name]))
    if name == "urgency":
        from text_norm import normalize as clean
        return lambda t: float(predict.score(t)[0]), clean
    if name == "money":
        return lambda t: float(predict.score(t)[0]), predict._model.clean   # the model's own cleaning (mask_brands)
    from text_utils import prep
    return lambda t: float(predict.score(t)), lambda t: prep([t], mask=True, caller=True)[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", choices=sorted(MODEL_FILES))
    name = ap.parse_args().model
    score, clean = scorer(name)
    doc = json.loads(OUT.read_text()) if OUT.exists() else {"tolerance": 0.01, "cases": []}
    cases = {c["text"]: c for c in doc["cases"]}
    for source, text in sentences():
        c = cases.setdefault(text, {"source": source, "text": text})
        c[name] = {"clean": clean(text), "score": round(score(text), 6)}
    doc["cases"] = list(cases.values())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n")
    print(f"{OUT.relative_to(REPO)}: {len(doc['cases'])} sentences, {name} scored")


if __name__ == "__main__":
    main()
