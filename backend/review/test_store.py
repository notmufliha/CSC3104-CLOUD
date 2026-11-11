"""test_store.py - the review queue's retention and retraining-trigger rules.

    pip install -r backend/review/requirements.txt pytest && pytest backend/review
"""
import sqlite3

import pytest
from cryptography.fernet import Fernet

from store import Store


@pytest.fixture
def store(tmp_path):
    return Store(str(tmp_path), Fernet.generate_key(), 30)


def report(store, language="en", consent=2, lines=("line one", "line two")):
    store.add_report("scam", "SAFE", language, list(enumerate(lines)), consent)
    return [x["line_id"] for x in store.queue(200)]


def expire_all(store):
    with store.db:
        store.db.execute("UPDATE reports SET expires_at = 0")


def test_old_consent_is_deleted_in_full_even_when_labelled(store):
    ids = report(store, consent=1)
    store.label(ids[0], {"urgency": 1}, "alice")
    expire_all(store)
    assert store.purge_expired() == 1
    assert store.stats()["lines"] == 0
    assert store.labelled("urgency", "en", False) == []


def test_new_consent_keeps_only_reviewed_lines(store):
    labelled, unlabelled, skipped, disputed = report(store, lines=("a", "b", "c", "d"))
    store.label(labelled, {"urgency": 1}, "alice")
    store.label(skipped, {"urgency": 1}, "alice")
    store.skip(skipped)
    store.label(disputed, {"urgency": 1}, "alice")
    store.spot_check(disputed, "urgency", False, "bob")
    expire_all(store)
    store.purge_expired()
    assert [r["line_id"] for r in store.labelled("urgency", "en", False)] == [labelled]
    s = store.stats()
    assert s["lines"] == 1 and s["kept_lines"] == 1 and s["reports"]["scam"] == 0
    assert store.purge_expired() == 0   # a kept line is not processed again


def test_report_with_nothing_reviewed_is_deleted(store):
    report(store)
    expire_all(store)
    store.purge_expired()
    assert store.db.execute("SELECT COUNT(*) FROM reports").fetchone()[0] == 0


def test_kept_lines_stay_in_training_after_being_used(store):
    ids = report(store, lines=("a",))
    store.label(ids[0], {"urgency": 1}, "alice")
    store.mark_used("urgency", ids, "v5")
    expire_all(store)
    store.purge_expired()
    rows = store.labelled("urgency", "en", False)
    assert [(r["text"], r["used_in"]) for r in rows] == [("a", "v5")]


def test_each_language_counts_only_its_own_lines(store):
    zh = report(store, language="zh", lines=("甲", "乙"))
    for i in zh:
        store.label(i, {"urgency": 1, "money": 0, "impersonation": 0}, "alice")
    s = store.stats()
    assert s["new_lines"] == {"en": 0, "zh": 2}
    assert s["unused_by_language"]["en"]["urgency"] == 0
    assert s["unused_by_language"]["zh"]["urgency"] == 2
    store.mark_used("urgency", zh, "v2")
    s = store.stats()
    assert s["unused_by_language"]["zh"] == {"urgency": 0, "money": 2, "impersonation": 2}
    assert s["new_lines"]["zh"] == 2    # money and impersonation still have to train on them


def test_skipped_line_never_trains_or_counts(store):
    ids = report(store, lines=("a",))
    store.label(ids[0], {"urgency": 1}, "alice")
    store.skip(ids[0])
    assert store.labelled("urgency", "en", False) == []
    assert store.stats()["new_lines"]["en"] == 0


def test_volume_from_before_consent_versions_is_migrated(tmp_path):
    db = sqlite3.connect(tmp_path / "review.db")
    db.execute("CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, "
               "expires_at INTEGER NOT NULL, kind TEXT NOT NULL, app_verdict TEXT, language TEXT NOT NULL)")
    db.execute("INSERT INTO reports (created_at, expires_at, kind, language) VALUES (0, 0, 'scam', 'en')")
    db.commit()
    db.close()
    store = Store(str(tmp_path), Fernet.generate_key(), 30)
    row = store.db.execute("SELECT consent_version, kept FROM reports").fetchone()
    assert (row["consent_version"], row["kept"]) == (1, 0)
    assert store.purge_expired() == 1
