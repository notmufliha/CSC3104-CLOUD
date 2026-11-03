"""store.py - the review queue and model registry storage (SQLite on a persistent volume).

Tables
  reports  one row per consented report: kind (scam = "Report", fine = "This was fine"), what the app showed,
           language, when it expires. No phone number, no user id.
  lines    the report's caller lines, masked (mask.py) and ENCRYPTED (Fernet, key from the REVIEW_KEY Secret,
           which is not stored on the same volume). Deleted with their report after RETENTION_DAYS.
  labels   one row per (line, signal): 0/1 label, who labelled it, an optional second-person spot check, and
           which model version first trained on it (used_in).
  models   released model versions: name, version, sha256, metadata JSON; the file sits next to the DB.
           A version is never overwritten (frozen), matching the workers' "never overwrite" rule.
"""
import hashlib
import json
import sqlite3
import threading
import time
from pathlib import Path

from cryptography.fernet import Fernet

SIGNALS = ("urgency", "money", "impersonation")
DAY = 86400

SCHEMA = """
CREATE TABLE IF NOT EXISTS reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('scam', 'fine')),
    app_verdict TEXT,
    language TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS lines (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    seq INTEGER NOT NULL,
    text_enc BLOB NOT NULL,
    skipped INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS labels (
    line_id INTEGER NOT NULL REFERENCES lines(id) ON DELETE CASCADE,
    signal TEXT NOT NULL,
    label INTEGER NOT NULL CHECK (label IN (0, 1)),
    labeller TEXT NOT NULL,
    labelled_at INTEGER NOT NULL,
    checked_by TEXT,
    check_agrees INTEGER,
    used_in TEXT,
    PRIMARY KEY (line_id, signal)
);
CREATE TABLE IF NOT EXISTS models (
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    seq INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    meta TEXT NOT NULL,
    PRIMARY KEY (name, version)
);
"""


class Store:
    def __init__(self, data_dir: str, key: bytes, retention_days: int):
        self.dir = Path(data_dir)
        self.models_dir = self.dir / "models"
        self.models_dir.mkdir(parents=True, exist_ok=True)
        self.fernet = Fernet(key)
        self.retention = retention_days * DAY
        self.lock = threading.Lock()
        self.db = sqlite3.connect(self.dir / "review.db", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.execute("PRAGMA journal_mode = WAL")
        self.db.executescript(SCHEMA)

    # ------------------------------------------------------------------------------------------ reports
    def add_report(self, kind: str, app_verdict: str, language: str, lines: list) -> dict:
        now = int(time.time())
        with self.lock, self.db:
            cur = self.db.execute(
                "INSERT INTO reports (created_at, expires_at, kind, app_verdict, language) VALUES (?,?,?,?,?)",
                (now, now + self.retention, kind, app_verdict, language))
            rid = cur.lastrowid
            self.db.executemany(
                "INSERT INTO lines (report_id, seq, text_enc) VALUES (?,?,?)",
                [(rid, seq, self.fernet.encrypt(text.encode("utf-8"))) for seq, text in lines])
        return {"report_id": rid, "lines_stored": len(lines), "expires_at": now + self.retention}

    def purge_expired(self) -> int:
        with self.lock, self.db:
            return self.db.execute("DELETE FROM reports WHERE expires_at <= ?", (int(time.time()),)).rowcount

    def _text(self, row) -> str:
        return self.fernet.decrypt(row["text_enc"]).decode("utf-8")

    # ----------------------------------------------------------------------------------------- labelling
    def queue(self, limit: int) -> list:
        """Lines with no labels yet (and not skipped), oldest report first."""
        rows = self.db.execute(
            """SELECT l.id, l.seq, l.text_enc, r.id AS report_id, r.kind, r.app_verdict, r.language, r.expires_at
               FROM lines l JOIN reports r ON r.id = l.report_id
               WHERE l.skipped = 0 AND NOT EXISTS (SELECT 1 FROM labels b WHERE b.line_id = l.id)
               ORDER BY r.created_at, l.seq LIMIT ?""", (limit,)).fetchall()
        return [{"line_id": r["id"], "report_id": r["report_id"], "seq": r["seq"], "text": self._text(r),
                 "kind": r["kind"], "app_verdict": r["app_verdict"], "language": r["language"],
                 "expires_at": r["expires_at"]} for r in rows]

    def label(self, line_id: int, labels: dict, labeller: str) -> int:
        now = int(time.time())
        with self.lock, self.db:
            if not self.db.execute("SELECT 1 FROM lines WHERE id = ?", (line_id,)).fetchone():
                raise KeyError(line_id)
            for signal, value in labels.items():
                self.db.execute(
                    """INSERT INTO labels (line_id, signal, label, labeller, labelled_at) VALUES (?,?,?,?,?)
                       ON CONFLICT (line_id, signal) DO UPDATE SET label = excluded.label,
                         labeller = excluded.labeller, labelled_at = excluded.labelled_at,
                         checked_by = NULL, check_agrees = NULL""",
                    (line_id, signal, int(value), labeller, now))
        return len(labels)

    def skip(self, line_id: int) -> None:
        """A line that can't be labelled (still has a name in it, unclear, not English/Chinese...)."""
        with self.lock, self.db:
            self.db.execute("UPDATE lines SET skipped = 1 WHERE id = ?", (line_id,))

    def spot_check_sample(self, checker: str, limit: int) -> list:
        """Labels made by someone else and not checked yet, in random order (H.5 second-person check)."""
        rows = self.db.execute(
            """SELECT b.line_id, b.signal, b.label, b.labeller, l.text_enc FROM labels b
               JOIN lines l ON l.id = b.line_id
               WHERE b.checked_by IS NULL AND b.labeller != ? ORDER BY RANDOM() LIMIT ?""",
            (checker, limit)).fetchall()
        return [{"line_id": r["line_id"], "signal": r["signal"], "label": r["label"],
                 "labeller": r["labeller"], "text": self._text(r)} for r in rows]

    def spot_check(self, line_id: int, signal: str, agrees: bool, checker: str) -> None:
        with self.lock, self.db:
            n = self.db.execute(
                "UPDATE labels SET checked_by = ?, check_agrees = ? WHERE line_id = ? AND signal = ? "
                "AND labeller != ?", (checker, int(agrees), line_id, signal, checker)).rowcount
        if n == 0:
            raise KeyError((line_id, signal))

    def labelled(self, signal: str, language: str, only_unused: bool) -> list:
        """Training rows for one signal. A label a spot-checker disagreed with is left out until relabelled."""
        rows = self.db.execute(
            f"""SELECT b.line_id, b.label, b.labeller, l.text_enc, r.id AS report_id FROM labels b
                JOIN lines l ON l.id = b.line_id JOIN reports r ON r.id = l.report_id
                WHERE b.signal = ? AND r.language = ? AND COALESCE(b.check_agrees, 1) = 1
                {"AND b.used_in IS NULL" if only_unused else ""} ORDER BY b.line_id""",
            (signal, language)).fetchall()
        return [{"line_id": r["line_id"], "label": r["label"], "text": self._text(r),
                 "report_id": r["report_id"], "labeller": r["labeller"]} for r in rows]

    def mark_used(self, signal: str, line_ids: list, version: str) -> int:
        with self.lock, self.db:
            return self.db.executemany(
                "UPDATE labels SET used_in = ? WHERE signal = ? AND line_id = ? AND used_in IS NULL",
                [(version, signal, i) for i in line_ids]).rowcount

    def stats(self) -> dict:
        q = lambda sql, *a: self.db.execute(sql, a).fetchone()[0]
        out = {
            "reports": {k: q("SELECT COUNT(*) FROM reports WHERE kind = ?", k) for k in ("scam", "fine")},
            "lines": q("SELECT COUNT(*) FROM lines"),
            "unlabelled": q("SELECT COUNT(*) FROM lines l WHERE skipped = 0 AND NOT EXISTS "
                            "(SELECT 1 FROM labels b WHERE b.line_id = l.id)"),
            "skipped": q("SELECT COUNT(*) FROM lines WHERE skipped = 1"),
            "labelled": {}, "unused": {}, "spot_checked": {}, "disputed": {},
        }
        for s in SIGNALS:
            out["labelled"][s] = q("SELECT COUNT(*) FROM labels WHERE signal = ?", s)
            out["unused"][s] = q("SELECT COUNT(*) FROM labels WHERE signal = ? AND used_in IS NULL "
                                 "AND COALESCE(check_agrees, 1) = 1", s)
            out["spot_checked"][s] = q("SELECT COUNT(*) FROM labels WHERE signal = ? AND checked_by IS NOT NULL", s)
            out["disputed"][s] = q("SELECT COUNT(*) FROM labels WHERE signal = ? AND check_agrees = 0", s)
        return out

    # -------------------------------------------------------------------------------------- model registry
    def add_model(self, name: str, version: str, data: bytes, meta: dict) -> dict:
        sha = hashlib.sha256(data).hexdigest()
        with self.lock, self.db:
            if self.db.execute("SELECT 1 FROM models WHERE name = ? AND version = ?", (name, version)).fetchone():
                raise FileExistsError(f"{name} {version} already exists; versions are never overwritten")
            seq = (self.db.execute("SELECT MAX(seq) FROM models WHERE name = ?", (name,)).fetchone()[0] or 0) + 1
            path = self.models_dir / f"{name}_{version}.joblib"
            path.write_bytes(data)
            meta = {**meta, "name": name, "version": version, "sha256": sha, "size_bytes": len(data)}
            self.db.execute("INSERT INTO models VALUES (?,?,?,?,?,?)",
                            (name, version, seq, sha, int(time.time()), json.dumps(meta)))
        return meta

    def models(self, name: str) -> list:
        rows = self.db.execute("SELECT meta FROM models WHERE name = ? ORDER BY seq", (name,)).fetchall()
        return [json.loads(r["meta"]) for r in rows]

    def model(self, name: str, version: str):
        if version == "latest":
            row = self.db.execute("SELECT meta FROM models WHERE name = ? ORDER BY seq DESC LIMIT 1",
                                  (name,)).fetchone()
        else:
            row = self.db.execute("SELECT meta FROM models WHERE name = ? AND version = ?",
                                  (name, version)).fetchone()
        return json.loads(row["meta"]) if row else None

    def model_path(self, name: str, version: str) -> Path:
        return self.models_dir / f"{name}_{version}.joblib"
