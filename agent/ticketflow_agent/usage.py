"""Persistent request/token reservations; no credentials, prompts or tool results."""

import hashlib
import sqlite3
import time
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from .config import Settings
from .errors import AgentError


class UsageLedger:
    def __init__(self, settings: Settings):
        self.settings = settings
        Path(settings.usage_path).parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS model_usage (
                id TEXT PRIMARY KEY, day TEXT NOT NULL, user_hash TEXT NOT NULL,
                sent_at REAL NOT NULL, reserved INTEGER NOT NULL,
                reported INTEGER, state TEXT NOT NULL DEFAULT 'RESERVED')""")
            db.execute("CREATE INDEX IF NOT EXISTS model_usage_day ON model_usage(day)")
            db.execute("CREATE INDEX IF NOT EXISTS model_usage_time ON model_usage(sent_at)")
            db.execute("""CREATE TABLE IF NOT EXISTS ledger_meta (
                name TEXT PRIMARY KEY, value TEXT NOT NULL)""")
            db.execute("INSERT OR IGNORE INTO ledger_meta VALUES ('salt', ?)", (uuid.uuid4().hex,))
            self.salt = db.execute("SELECT value FROM ledger_meta WHERE name='salt'").fetchone()[0]

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.settings.usage_path, timeout=3)
        try:
            db.execute("PRAGMA synchronous=FULL")
            with db:
                yield db
        finally:
            db.close()

    def reserve(self, user_id: str, amount: int, now: float | None = None) -> str:
        if type(amount) is not int or amount <= 0:
            raise ValueError("Reservation amount must be a positive integer")
        now = time.time() if now is None else now
        day = datetime.fromtimestamp(now, UTC).date().isoformat()
        user = hashlib.sha256((self.salt + user_id).encode()).hexdigest()
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            count, tokens = db.execute(
                "SELECT COUNT(*),COALESCE(SUM(COALESCE(reported,reserved)),0) "
                "FROM model_usage WHERE day=?",
                (day,),
            ).fetchone()
            own = db.execute(
                "SELECT COUNT(*) FROM model_usage WHERE day=? AND user_hash=?", (day, user)
            ).fetchone()[0]
            recent = db.execute(
                "SELECT COUNT(*) FROM model_usage WHERE sent_at>?", (now - 60,)
            ).fetchone()[0]
            if (
                count >= self.settings.daily_requests
                or own >= self.settings.user_daily_requests
                or tokens + amount > self.settings.daily_token_budget
            ):
                raise AgentError("MODEL_QUOTA_EXCEEDED", 429, "本地每日模型调用额度已用完")
            if recent >= self.settings.requests_per_minute:
                raise AgentError("MODEL_RATE_LIMITED", 429, "模型调用过快，请稍后再试")
            identifier = str(uuid.uuid4())
            db.execute(
                "INSERT INTO model_usage(id,day,user_hash,sent_at,reserved) VALUES(?,?,?,?,?)",
                (identifier, day, user, now, amount),
            )
            return identifier

    def settle(self, identifier: str, reported: int | None):
        if reported is not None and (type(reported) is not int or not 0 <= reported < 2**63):
            raise ValueError("Invalid usage amount")
        with self.connection() as db:
            db.execute(
                "UPDATE model_usage SET reported=?,state=? WHERE id=? AND state='RESERVED'",
                (reported, "REPORTED" if reported is not None else "UNKNOWN", identifier),
            )

    def summary(self, now: float | None = None) -> dict:
        now = time.time() if now is None else now
        day = datetime.fromtimestamp(now, UTC).date().isoformat()
        with self.connection() as db:
            requests, accounted, unknown = db.execute(
                "SELECT COUNT(*),COALESCE(SUM(COALESCE(reported,reserved)),0),"
                "COALESCE(SUM(state != 'REPORTED'),0) FROM model_usage WHERE day=?",
                (day,),
            ).fetchone()
        return {
            "dayUtc": day,
            "requests": requests,
            "accountedTokens": accounted,
            "unknownUsageRequests": unknown,
            "billing": "school quota; no currency estimate",
        }
