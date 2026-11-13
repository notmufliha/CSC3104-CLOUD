"""test_mask.py - mask.py against the cases shared with the app (logic/Mask.kt, MaskParityTest.kt) and the web app.

Run: python -m pytest backend/review/test_mask.py   (or: python backend/review/test_mask.py)
If a case changes here, change mask_cases.json, Mask.kt and web/app.js together so all three keep masking alike.
"""
import json
from pathlib import Path

from mask import mask

CASES = json.loads((Path(__file__).parent / "mask_cases.json").read_text(encoding="utf-8"))["cases"]


def test_mask_matches_shared_cases():
    wrong = [(c["text"], mask(c["text"]), c["masked"]) for c in CASES if mask(c["text"]) != c["masked"]]
    assert not wrong, wrong


if __name__ == "__main__":
    test_mask_matches_shared_cases()
    print(f"{len(CASES)} shared masking cases OK")
