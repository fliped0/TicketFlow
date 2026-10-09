from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest

from ticketflow_agent.config import Settings
from ticketflow_agent.errors import AgentError
from ticketflow_agent.usage import UsageLedger

NOW = 1791504000.0


@pytest.fixture
def ledger(tmp_path):
    return UsageLedger(Settings(usage_path=str(tmp_path / "ledger.sqlite3")))


def test_persistent_unknown_accounting_and_idempotent_settlement(ledger):
    first = ledger.reserve("1", 9024, NOW)
    second = ledger.reserve("1", 9024, NOW)
    ledger.settle(first, 120)
    ledger.settle(first, 0)
    ledger.settle(second, None)
    restored = UsageLedger(ledger.settings)
    summary = restored.summary(NOW)
    assert summary["requests"] == 2
    assert summary["accountedTokens"] == 9144
    assert summary["unknownUsageRequests"] == 1
    with restored.connection() as db:
        assert db.execute("SELECT user_hash FROM model_usage").fetchone()[0] != "1"


def test_quota_atomic_across_concurrent_clients(ledger):
    configured = replace(ledger.settings, daily_requests=2, user_daily_requests=2)

    def attempt(i):
        try:
            UsageLedger(configured).reserve(str(i), 100, NOW)
            return True
        except AgentError:
            return False

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(attempt, range(8)))
    assert sum(outcomes) == 2
    assert ledger.summary(NOW)["requests"] == 2


def test_user_quota_does_not_block_other_user(ledger):
    limited = UsageLedger(replace(ledger.settings, user_daily_requests=1))
    limited.reserve("1", 100, NOW)
    with pytest.raises(AgentError, match="MODEL_QUOTA_EXCEEDED"):
        limited.reserve("1", 100, NOW)
    limited.reserve("2", 100, NOW)


def test_request_rate_persists_across_restart(ledger):
    limited = UsageLedger(replace(ledger.settings, requests_per_minute=1))
    limited.reserve("1", 100, NOW)
    limited = UsageLedger(limited.settings)
    with pytest.raises(AgentError, match="MODEL_RATE_LIMITED"):
        limited.reserve("1", 100, NOW + 59)
    limited.reserve("1", 100, NOW + 61)


def test_token_reservation_limit_and_next_day(ledger):
    limited = UsageLedger(replace(ledger.settings, daily_token_budget=200))
    limited.reserve("1", 200, NOW)
    with pytest.raises(AgentError, match="MODEL_QUOTA_EXCEEDED"):
        limited.reserve("1", 1, NOW + 61)
    limited.reserve("1", 200, NOW + 86400)


def test_usage_ledger_refuses_corruption(tmp_path):
    path = tmp_path / "corrupt.sqlite3"
    path.write_bytes(b"not a database")
    import sqlite3

    with pytest.raises(sqlite3.DatabaseError):
        UsageLedger(Settings(usage_path=str(path)))
    assert path.read_bytes() == b"not a database"
