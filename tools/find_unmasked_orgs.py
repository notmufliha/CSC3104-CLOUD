"""
find_unmasked_orgs.py - Workstream D.5: list capitalised / all-caps names in the
impersonation_en CSVs that are not already in text_utils.ORG_NAMES, so Shreya can
check which ones are real organisations worth adding to ORG_TYPES (brand masking).

Not every capitalised word is an organisation (character names, place names, days
of the week...), so this only lists candidates; a human still decides what to add.

Usage
  python tools/find_unmasked_orgs.py
  python tools/find_unmasked_orgs.py --min-count 2
"""
import argparse
import re
import sys
from collections import Counter
from pathlib import Path

import pandas as pd

REPO = Path(__file__).resolve().parent.parent
WORKER_DIR = REPO / "backend" / "workers" / "impersonation_en"
sys.path.insert(0, str(WORKER_DIR))
import text_utils  # noqa: E402

CSVS = [REPO / "data" / "impersonation_en" / "impersonation_data.csv",
        REPO / "data" / "impersonation_en" / "gold_test.csv"]

# A capitalised word/phrase: one or more Title-Case or ALL-CAPS words in a row.
NAME_RE = re.compile(r"\b(?:[A-Z][a-z]+|[A-Z]{2,})(?:\s+(?:[A-Z][a-z]+|[A-Z]{2,})){0,3}\b")

# Common words that are capitalised for reasons other than being an organisation
# (sentence-start words, days, speaker tags, common first names in the scripts...).
STOPWORDS = {
    "Caller", "Receiver", "Good", "Hello", "Hi", "Yes", "No", "Okay", "Sure", "Thank", "Thanks",
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
    "January", "February", "March", "April", "May", "June", "July", "August", "September",
    "October", "November", "December", "NRIC", "OTP", "PIN", "SGD",
}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--min-count", type=int, default=3)
    args = ap.parse_args()

    counts = Counter()
    for path in CSVS:
        if not path.exists():
            continue
        df = pd.read_csv(path)
        for text in df["text"].dropna():
            for m in NAME_RE.finditer(str(text)):
                phrase = m.group(0)
                if phrase in STOPWORDS:
                    continue
                if phrase.lower() in text_utils.ORG_NAMES:
                    continue  # already masked
                counts[phrase] += 1

    candidates = [(name, n) for name, n in counts.items() if n >= args.min_count]
    candidates.sort(key=lambda p: -p[1])

    print(f"{len(candidates)} capitalised names appearing >= {args.min_count} times, not already in ORG_TYPES:\n")
    for name, n in candidates:
        print(f"  {n:3}  {name}")


if __name__ == "__main__":
    main()
