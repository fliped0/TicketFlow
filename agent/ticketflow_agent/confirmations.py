"""Persistent explicit confirmations. The model can prepare, never approve or execute."""

import hashlib
import json
import os
import sqlite3
import subprocess
import time
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from .errors import AgentError

TERMINAL = {"SUCCEEDED", "REJECTED", "EXPIRED", "CANCELLED"}


def fingerprint(snapshot):
    return hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()


class ConfirmationStore:
    def __init__(self, settings):
        self.settings = settings
        self.path = Path(settings.confirmation_path)
        self.marker = self.path.with_suffix(".initialized")
        if not self.path.exists() and self.marker.exists():
            raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "确认仓储丢失，需先核查历史操作")
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if os.name == "nt":
            account = subprocess.check_output(["whoami"], text=True).strip()
            subprocess.run(
                [
                    "icacls",
                    str(self.path.parent),
                    "/inheritance:r",
                    "/grant:r",
                    account + ":(OI)(CI)(F)",
                    "SYSTEM:(OI)(CI)(F)",
                ],
                check=True,
                capture_output=True,
            )
        else:
            self.path.parent.chmod(0o700)
        new = not self.path.exists()
        with self.connection() as db:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if new and version == 0:
                db.execute("""CREATE TABLE confirmations (
                    id TEXT PRIMARY KEY, user_hash TEXT NOT NULL, session_id TEXT NOT NULL,
                    order_id TEXT NOT NULL, operation TEXT NOT NULL
                        CHECK(operation IN ('cancel','refund')),
                    snapshot TEXT NOT NULL, digest TEXT NOT NULL, key TEXT NOT NULL UNIQUE,
                    created REAL NOT NULL, expires REAL NOT NULL, state TEXT NOT NULL
                        CHECK(state IN ('PENDING','EXECUTING','UNKNOWN','SUCCEEDED',
                                        'REJECTED','EXPIRED','CANCELLED')),
                    lease REAL NOT NULL DEFAULT 0, attempt INTEGER NOT NULL DEFAULT 0,
                    result TEXT)""")
                db.execute("""CREATE UNIQUE INDEX confirmation_active
                    ON confirmations(user_hash,order_id)
                    WHERE state IN ('PENDING','EXECUTING','UNKNOWN')""")
                db.execute(
                    "CREATE TABLE confirmation_meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)"
                )
                db.executemany(
                    "INSERT INTO confirmation_meta VALUES (?,?)",
                    [("salt", uuid.uuid4().hex), ("java_origin", settings.java_url)],
                )
                db.execute("PRAGMA user_version=1")
            elif version != 1:
                raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "确认仓储版本不可用")
            self.salt = db.execute(
                "SELECT value FROM confirmation_meta WHERE name='salt'"
            ).fetchone()[0]
            origin = db.execute(
                "SELECT value FROM confirmation_meta WHERE name='java_origin'"
            ).fetchone()[0]
            if origin != settings.java_url:
                raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "确认仓储与业务服务不匹配")
        self.marker.touch(exist_ok=True)

    @contextmanager
    def connection(self):
        if self.marker.exists() and not self.path.exists():
            raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "确认仓储丢失，需先核查历史操作")
        db = None
        try:
            db = sqlite3.connect(self.path, timeout=3)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA synchronous=FULL")
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except sqlite3.Error:
            if db:
                db.rollback()
            raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "确认仓储不可用，停止交易") from None
        finally:
            if db:
                db.close()

    def user_hash(self, user):
        return hashlib.sha256((self.salt + user).encode()).hexdigest()

    def _get(self, db, identifier, user, now):
        row = db.execute(
            "SELECT * FROM confirmations WHERE id=? AND user_hash=?",
            (identifier, self.user_hash(user)),
        ).fetchone()
        if row is None:
            raise AgentError("CONFIRMATION_NOT_FOUND", 404, "未找到可访问的确认记录")
        if row["state"] == "EXECUTING" and row["lease"] <= now:
            db.execute("UPDATE confirmations SET state='UNKNOWN',lease=0 WHERE id=?", (identifier,))
        if row["state"] == "PENDING" and row["expires"] <= now:
            db.execute("UPDATE confirmations SET state='EXPIRED' WHERE id=?", (identifier,))
        return dict(db.execute("SELECT * FROM confirmations WHERE id=?", (identifier,)).fetchone())

    def get(self, identifier, user):
        with self.connection() as db:
            return self._get(db, identifier, user, time.time())

    def prepare(self, user, session, operation, snapshot):
        now = time.time()
        user_hash = self.user_hash(user)
        with self.connection() as db:
            db.execute(
                "UPDATE confirmations SET state='EXPIRED' WHERE user_hash=? "
                "AND state='PENDING' AND expires<=?",
                (user_hash, now),
            )
            previous = db.execute(
                "SELECT * FROM confirmations WHERE user_hash=? AND order_id=? "
                "AND state IN ('PENDING','EXECUTING','UNKNOWN')",
                (user_hash, snapshot["orderId"]),
            ).fetchone()
            if previous:
                if (
                    previous["state"] == "PENDING"
                    and previous["operation"] == operation
                    and previous["digest"] == fingerprint(snapshot)
                ):
                    return dict(previous)
                raise AgentError(
                    "CONFIRMATION_UNRESOLVED", 409, "该订单已有待确认或未决操作，请先处理"
                )
            identifier = str(uuid.uuid4())
            db.execute(
                """INSERT INTO confirmations
                (id,user_hash,session_id,order_id,operation,snapshot,digest,key,created,expires,state)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING')""",
                (
                    identifier,
                    user_hash,
                    session,
                    snapshot["orderId"],
                    operation,
                    json.dumps(snapshot, ensure_ascii=False),
                    fingerprint(snapshot),
                    "agent_" + uuid.uuid4().hex,
                    now,
                    now + self.settings.confirmation_ttl,
                ),
            )
            return dict(
                db.execute("SELECT * FROM confirmations WHERE id=?", (identifier,)).fetchone()
            )

    def claim(self, identifier, user, *, recover=False):
        now = time.time()
        with self.connection() as db:
            row = self._get(db, identifier, user, now)
            if row["state"] in TERMINAL or (row["state"] == "UNKNOWN" and not recover):
                return row, False
            if row["state"] == "EXECUTING":
                raise AgentError("CONFIRMATION_BUSY", 409, "操作正在执行，请稍后查询结果")
            if recover and row["state"] != "UNKNOWN":
                raise AgentError("CONFIRMATION_NOT_STARTED", 409, "尚未确认执行，不能恢复操作")
            if not recover and row["state"] != "PENDING":
                raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "确认状态不可用，停止交易")
            attempt = row["attempt"] + 1
            db.execute(
                "UPDATE confirmations SET state='EXECUTING',attempt=?,lease=? WHERE id=?",
                (attempt, now + self.settings.confirmation_lease, identifier),
            )
            row.update(
                state="EXECUTING", attempt=attempt, lease=now + self.settings.confirmation_lease
            )
            return row, True

    def finish(self, row, state, result=None):
        with self.connection() as db:
            db.execute(
                "UPDATE confirmations SET state=?,result=?,lease=0 "
                "WHERE id=? AND state='EXECUTING' AND attempt=?",
                (
                    state,
                    json.dumps(result, ensure_ascii=False) if result else None,
                    row["id"],
                    row["attempt"],
                ),
            )

    def cancel(self, identifier, user):
        with self.connection() as db:
            row = self._get(db, identifier, user, time.time())
            if row["state"] != "PENDING":
                raise AgentError("CONFIRMATION_NOT_PENDING", 409, "只能撤销尚未执行的确认")
            db.execute("UPDATE confirmations SET state='CANCELLED' WHERE id=?", (identifier,))
            row["state"] = "CANCELLED"
            return row


def card(row):
    snapshot = json.loads(row["snapshot"])
    return {
        "confirmationId": row["id"],
        "sessionId": row["session_id"],
        "orderId": row["order_id"],
        "operation": row["operation"],
        "state": row["state"],
        "expiresAt": datetime.fromtimestamp(row["expires"], UTC).isoformat(),
        "amountFen": snapshot["amountFen"] if row["operation"] == "refund" else 0,
        "snapshot": snapshot,
        "simulation": True,
        "impact": "取消待支付订单并释放占用"
        if row["operation"] == "cancel"
        else "本地模拟全额退款并归还库存",
        "requiresExplicitConfirmation": row["state"] == "PENDING",
        "result": json.loads(row["result"]) if row["result"] else None,
        "notice": (
            "结果尚未确定，请使用原确认记录检查结果，不要另建操作"
            if row["state"] in {"EXECUTING", "UNKNOWN"}
            else "只有独立确认入口可执行交易；普通聊天中的确认不会执行"
        ),
    }
