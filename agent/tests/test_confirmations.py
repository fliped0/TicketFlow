import asyncio
import copy
import json
import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import httpx
import pytest
from conftest import Backend, example
from fastapi.testclient import TestClient

from ticketflow_agent.app import create_app
from ticketflow_agent.config import Settings
from ticketflow_agent.confirmations import ConfirmationStore
from ticketflow_agent.errors import AgentError
from ticketflow_agent.java import CONTRACT
from ticketflow_agent.model import Call, Decision


class TradeBackend(Backend):
    def __init__(self, status="PENDING"):
        super().__init__()
        self.status = status
        self.amount = 4500
        self.starts = "2035-01-01T12:00:00Z"
        self.posts = []
        self.effects = 0
        self.receipts = {}
        self.failure = None

    def __call__(self, request):
        if request.method == "GET":
            if request.url.path == "/api/v1/orders/1":
                self.requests.append(request)
                if self.disabled or request.headers.get("authorization") != "Bearer alice":
                    return httpx.Response(404)
                body = example(CONTRACT["components"]["schemas"]["OrderResponse"])
                body["data"].update(
                    status=self.status, amountFen=self.amount, unitPriceFen=self.amount
                )
                body["data"]["snapshot"].update(
                    startsAt=self.starts, amountFen=self.amount, unitPriceFen=self.amount
                )
                return httpx.Response(200, json=body)
            return super().__call__(request)
        assert request.method == "POST"
        assert request.url.path in {"/api/v1/orders/1/cancel", "/api/v1/orders/1/refunds"}
        assert request.headers["authorization"] == "Bearer alice"
        assert json.loads(request.content) == {}
        self.posts.append(request)
        if self.failure == "reject":
            return httpx.Response(
                409,
                json={
                    "code": "ORDER_STATE_CONFLICT",
                    "data": None,
                    "replayed": False,
                    "traceId": "00000000-0000-4000-8000-000000000001",
                },
            )
        key = request.headers["idempotency-key"]
        replayed = key in self.receipts
        if not replayed:
            state = "CANCELLED" if request.url.path.endswith("cancel") else "REFUNDED"
            self.status = state
            self.effects += 1
            body = example(CONTRACT["components"]["schemas"]["TradeResponse"])
            body["data"].update(
                orderId="1",
                amountFen=self.amount,
                operationStatus=state,
                currentOrderStatus=state,
                paymentId="11",
                refundId="12",
            )
            self.receipts[key] = body
        body = copy.deepcopy(self.receipts[key])
        body["replayed"] = replayed
        if self.failure == "lost" and not replayed:
            raise httpx.ReadTimeout("redacted", request=request)
        if self.failure == "server" and not replayed:
            return httpx.Response(500)
        if self.failure == "wrong-order":
            body["data"]["orderId"] = "2"
        if self.failure == "wrong-amount":
            body["data"]["amountFen"] = 99
        if self.failure == "invalid":
            return httpx.Response(200, json={"message": "退款成功"})
        if self.failure == "auth":
            return httpx.Response(401)
        return httpx.Response(200, json=body)


@pytest.fixture
def context(tmp_path):
    settings = Settings(model_mode="demo", confirmation_path=str(tmp_path / "c/confirm.sqlite3"))
    backend = TradeBackend()
    with TestClient(create_app(settings, transport=httpx.MockTransport(backend))) as client:
        client.headers["Authorization"] = "Bearer alice"
        yield client, backend, settings


def prepare(client, operation="cancel"):
    response = client.post("/agent/v1/confirmations", json={"operation": operation, "orderId": "1"})
    assert response.status_code == 200, response.text
    return response.json()["data"]


def execute(client, confirmation, *, recover=False):
    return client.post(
        f"/agent/v1/confirmations/{confirmation['confirmationId']}/"
        + ("recover" if recover else "execute"),
        json={} if recover else {"approved": True},
    )


def update_record(settings, **values):
    with sqlite3.connect(settings.confirmation_path) as db:
        for name, value in values.items():
            assert name in {"expires", "lease", "state"}
            db.execute(f"UPDATE confirmations SET {name}=?", (value,))


@pytest.mark.parametrize("operation", ["cancel", "refund"])
def test_explicit_approval_duplicate_click_and_durable_receipt(context, operation):
    client, backend, settings = context
    backend.status = "PENDING" if operation == "cancel" else "PAID"
    confirmation = prepare(client, operation)
    assert confirmation["state"] == "PENDING" and not backend.posts
    assert "key" not in confirmation and "user_hash" not in confirmation
    assert confirmation["simulation"] is True
    assert prepare(client, operation)["confirmationId"] == confirmation["confirmationId"]
    first = execute(client, confirmation).json()["data"]
    assert first["state"] == "SUCCEEDED" and backend.effects == len(backend.posts) == 1
    assert execute(client, confirmation).json()["data"]["result"] == first["result"]
    assert len(backend.posts) == 1
    store = ConfirmationStore(settings)
    assert store.get(confirmation["confirmationId"], "1")["state"] == "SUCCEEDED"
    assert b"alice" not in open(settings.confirmation_path, "rb").read()


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"approved": False},
        {"approved": 1},
        {"approved": "true"},
        {"approved": True, "orderId": "2"},
        {"approved": True, "key": "attacker-key"},
    ],
)
def test_missing_or_forged_confirmation_never_writes(context, body):
    client, backend, _ = context
    confirmation = prepare(client)
    response = client.post(
        f"/agent/v1/confirmations/{confirmation['confirmationId']}/execute", json=body
    )
    assert response.status_code == 422 and not backend.posts


@pytest.mark.parametrize(
    "method,suffix,body",
    [
        ("GET", "", None),
        ("DELETE", "", None),
        ("POST", "/execute", {"approved": True}),
        ("POST", "/recover", {}),
    ],
)
def test_cross_user_confirmation_unavailable(context, method, suffix, body):
    client, backend, _ = context
    confirmation = prepare(client)
    response = client.request(
        method,
        f"/agent/v1/confirmations/{confirmation['confirmationId']}" + suffix,
        json=body,
        headers={"Authorization": "Bearer bob"},
    )
    assert response.status_code == 404 and not backend.posts


def test_expiry_revoke_and_recovery_cannot_create_an_unapproved_write(context):
    client, backend, settings = context
    confirmation = prepare(client)
    assert execute(client, confirmation, recover=True).status_code == 409
    update_record(settings, expires=time.time() - 1)
    assert execute(client, confirmation).json()["data"]["state"] == "EXPIRED"
    another = prepare(client)
    assert client.delete(f"/agent/v1/confirmations/{another['confirmationId']}").status_code == 200
    assert execute(client, another).json()["data"]["state"] == "CANCELLED"
    assert not backend.posts


@pytest.mark.parametrize("starts", ["not-a-date", "2035-01-01T12:00:00"])
def test_invalid_refund_time_fails_before_record_or_write(context, starts):
    client, backend, _ = context
    backend.status = "PAID"
    backend.starts = starts
    response = client.post("/agent/v1/confirmations", json={"operation": "refund", "orderId": "1"})
    assert response.status_code == 502 and response.json()["code"] == "JAVA_INVALID_RESPONSE"
    assert not backend.posts


def test_corrupt_confirmation_store_is_not_rebuilt(context):
    from pathlib import Path

    client, backend, settings = context
    confirmation = prepare(client)
    Path(settings.confirmation_path).write_bytes(b"corrupt-confirmation-record")
    response = execute(client, confirmation)
    assert response.status_code == 503 and not backend.posts
    assert Path(settings.confirmation_path).read_bytes() == b"corrupt-confirmation-record"


def test_storage_failure_after_java_commit_preserves_original_key(context, monkeypatch):
    client, backend, _ = context
    confirmation = prepare(client)
    store = client.app.state.service.confirmations
    original = store.finish
    row = store.get(confirmation["confirmationId"], "1")

    def fail(row, state, result=None):
        raise AgentError("CONFIRMATIONS_UNAVAILABLE", 503, "test storage unavailable")

    monkeypatch.setattr(store, "finish", fail)
    assert execute(client, confirmation).status_code == 503
    assert backend.effects == 1
    assert store.get(confirmation["confirmationId"], "1")["state"] == "EXECUTING"
    monkeypatch.setattr(store, "finish", original)
    update_record(store.settings, lease=time.time() - 1, expires=time.time() - 1)
    result = execute(client, confirmation, recover=True).json()["data"]
    assert result["state"] == "SUCCEEDED" and result["result"]["replayed"]
    assert backend.effects == 1
    assert {r.headers["idempotency-key"] for r in backend.posts} == {row["key"]}


def test_unknown_execution_cannot_be_replaced_with_new_key(context):
    client, backend, _ = context
    confirmation = prepare(client)
    backend.failure = "lost"
    assert execute(client, confirmation).json()["data"]["state"] == "UNKNOWN"
    backend.status = "PENDING"  # Inconclusive/stale read does not authorize a new key.
    response = client.post("/agent/v1/confirmations", json={"operation": "cancel", "orderId": "1"})
    assert response.status_code == 409 and response.json()["code"] == "CONFIRMATION_UNRESOLVED"
    assert len(backend.posts) == 1


@pytest.mark.parametrize("operation", ["cancel", "refund"])
def test_definitive_business_rejection_is_terminal_and_not_retried(context, operation):
    client, backend, _ = context
    backend.status = "PENDING" if operation == "cancel" else "PAID"
    confirmation = prepare(client, operation)
    backend.failure = "reject"
    first = execute(client, confirmation).json()["data"]
    assert first["state"] == "REJECTED" and backend.effects == 0
    assert first["result"]["code"] == "ORDER_STATE_CONFLICT"
    assert execute(client, confirmation, recover=True).json()["data"] == first
    assert len(backend.posts) == 1


def test_mixed_batch_with_unknown_execute_tool_never_prepares(tmp_path):
    backend = TradeBackend()

    class Model:
        async def decide(self, message, results, tools):
            return Decision((Call("prepare_cancel", {"orderId": "1"}), Call("execute", {})))

    settings = Settings(confirmation_path=str(tmp_path / "confirm.sqlite3"))
    with TestClient(
        create_app(settings, model=Model(), transport=httpx.MockTransport(backend))
    ) as client:
        response = client.post(
            "/agent/v1/chat",
            headers={"Authorization": "Bearer alice"},
            json={"message": "取消订单1"},
        )
        assert response.status_code == 422
        assert client.app.state.service._confirmations is None and not backend.posts


@pytest.mark.parametrize("answer, posts", [("", 0), ("确认", 0), ("yes", 0), ("确认执行", 1)])
def test_cli_requires_separate_exact_confirmation(monkeypatch, answer, posts):
    from demo import show_confirmations

    monkeypatch.setattr("builtins.input", lambda _: answer)
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(200, json={"code": "OK"})

    with httpx.Client(
        base_url="http://127.0.0.1", transport=httpx.MockTransport(respond)
    ) as client:
        show_confirmations(
            client,
            {
                "data": {
                    "confirmations": [
                        {
                            "state": "PENDING",
                            "impact": "test",
                            "orderId": "1",
                            "amountFen": 0,
                            "expiresAt": "2035-01-01",
                            "confirmationId": "test",
                        }
                    ]
                }
            },
        )
    assert len(requests) == posts
    if posts:
        assert json.loads(requests[0].content) == {"approved": True}


@pytest.mark.parametrize("change", ["amount", "status", "starts"])
def test_changed_snapshot_invalidates_approval_without_write(context, change):
    client, backend, _ = context
    confirmation = prepare(client)
    if change == "amount":
        backend.amount += 1
    elif change == "status":
        backend.status = "PAID"
    else:
        backend.starts = "2035-02-01T12:00:00Z"
    result = execute(client, confirmation).json()["data"]
    assert result["state"] == "REJECTED"
    assert result["result"]["code"] == "CONFIRMATION_SNAPSHOT_CHANGED" and not backend.posts


@pytest.mark.parametrize("failure", ["lost", "server"])
@pytest.mark.parametrize("operation", ["cancel", "refund"])
def test_unknown_commit_replay_survives_restart_and_expired_confirmation(
    context, failure, operation
):
    client, backend, settings = context
    backend.status = "PENDING" if operation == "cancel" else "PAID"
    backend.failure = failure
    confirmation = prepare(client, operation)
    assert execute(client, confirmation).json()["data"]["state"] == "UNKNOWN"
    assert execute(client, confirmation).json()["data"]["state"] == "UNKNOWN"
    assert len(backend.posts) == backend.effects == 1
    assert (
        client.post(
            "/agent/v1/confirmations", json={"operation": operation, "orderId": "1"}
        ).status_code
        == 409
    )
    update_record(settings, expires=time.time() - 10)
    with TestClient(create_app(settings, transport=httpx.MockTransport(backend))) as restarted:
        restarted.headers["Authorization"] = "Bearer alice"
        result = execute(restarted, confirmation, recover=True).json()["data"]
    assert result["state"] == "SUCCEEDED" and result["result"]["replayed"]
    assert len(backend.posts) == 2 and backend.effects == 1
    assert (
        backend.posts[0].headers["idempotency-key"] == backend.posts[1].headers["idempotency-key"]
    )


@pytest.mark.parametrize("failure", ["wrong-order", "wrong-amount", "invalid", "auth"])
def test_untrusted_success_or_auth_loss_preserves_unknown(context, failure):
    client, backend, _ = context
    backend.failure = failure
    result = execute(client, prepare(client)).json()["data"]
    assert result["state"] == "UNKNOWN" and "退款成功" not in json.dumps(result)


def test_durable_executing_lease_can_be_recovered_after_restart(context):
    client, backend, settings = context
    confirmation = prepare(client)
    store = client.app.state.service.confirmations
    row, claimed = store.claim(confirmation["confirmationId"], "1")
    assert claimed
    assert execute(client, confirmation).status_code == 409
    update_record(settings, lease=time.time() - 1, expires=time.time() - 1)
    client.app.state.service._confirmations = ConfirmationStore(settings)
    result = execute(client, confirmation, recover=True).json()["data"]
    assert result["state"] == "SUCCEEDED" and backend.effects == 1
    assert backend.posts[0].headers["idempotency-key"] == row["key"]


def test_atomic_claim_and_late_response_fencing(context):
    client, _, settings = context
    confirmation = prepare(client)
    store = client.app.state.service.confirmations

    def claim(_):
        try:
            return store.claim(confirmation["confirmationId"], "1")[1]
        except AgentError as error:
            assert error.code == "CONFIRMATION_BUSY"
            return False

    with ThreadPoolExecutor(max_workers=6) as pool:
        assert sum(pool.map(claim, range(6))) == 1
    old = store.get(confirmation["confirmationId"], "1")
    update_record(settings, lease=time.time() - 1)
    current, _ = store.claim(confirmation["confirmationId"], "1", recover=True)
    store.finish(current, "SUCCEEDED", {"code": "OK"})
    store.finish(old, "UNKNOWN", {"code": "late-error"})
    assert store.get(confirmation["confirmationId"], "1")["state"] == "SUCCEEDED"


def test_storage_loss_and_business_origin_change_fail_closed(context):
    client, backend, settings = context
    confirmation = prepare(client)
    with pytest.raises(AgentError, match="CONFIRMATIONS_UNAVAILABLE"):
        ConfirmationStore(replace(settings, java_url="http://localhost:8088"))
    from pathlib import Path

    Path(settings.confirmation_path).unlink()
    assert execute(client, confirmation).status_code == 503 and not backend.posts
    with pytest.raises(AgentError):
        ConfirmationStore(settings)
    assert not Path(settings.confirmation_path).exists()


def test_model_only_prepares_and_plain_chat_confirmation_never_executes(tmp_path):
    backend = TradeBackend()

    class Model:
        async def decide(self, message, results, tools):
            if results:
                return Decision(finished=True)
            if message == "确认":
                return Decision(finished=True)
            return Decision((Call("prepare_cancel", {"orderId": "1"}),))

    settings = Settings(confirmation_path=str(tmp_path / "confirm.sqlite3"))
    with TestClient(
        create_app(settings, model=Model(), transport=httpx.MockTransport(backend))
    ) as client:
        client.headers["Authorization"] = "Bearer alice"
        result = client.post("/agent/v1/chat", json={"message": "取消订单1"}).json()["data"]
        assert result["confirmations"][0]["state"] == "PENDING"
        client.post("/agent/v1/chat", json={"message": "确认", "sessionId": result["sessionId"]})
        assert not backend.posts


def test_pre_write_account_disabled_stops_execution(context):
    client, backend, _ = context
    confirmation = prepare(client)
    backend.disabled = True
    assert execute(client, confirmation).status_code == 401 and not backend.posts


def test_local_write_cancellation_keeps_original_key(context):
    client, backend, settings = context
    confirmation = prepare(client)
    row, _ = client.app.state.service.confirmations.claim(confirmation["confirmationId"], "1")

    async def cancelled():
        async def transport(request):
            await asyncio.sleep(5)

        service = client.app.state.service
        original = service.java.http
        service.java.http = httpx.AsyncClient(transport=httpx.MockTransport(transport))
        update_record(settings, state="UNKNOWN")
        try:
            async with asyncio.timeout(0.01):
                await service.execute("1", "alice", confirmation["confirmationId"], recover=True)
        finally:
            await service.java.http.aclose()
            service.java.http = original

    with pytest.raises(TimeoutError):
        asyncio.run(cancelled())
    assert (
        client.app.state.service.confirmations.get(confirmation["confirmationId"], "1")["key"]
        == row["key"]
    )
    assert (
        client.app.state.service.confirmations.get(confirmation["confirmationId"], "1")["state"]
        == "UNKNOWN"
    )
    assert not backend.posts
