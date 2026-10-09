import json
from pathlib import Path

import httpx
import pytest
from conftest import example
from fastapi.testclient import TestClient

from ticketflow_agent.app import create_app
from ticketflow_agent.config import Settings
from ticketflow_agent.java import CONTRACT


@pytest.mark.parametrize(
    "tool,args,path",
    [
        ("search_events", {"city": "杭州", "keyword": "%_"}, "/api/v1/events"),
        ("get_event", {"eventId": "1"}, "/api/v1/events/1"),
        ("list_sessions", {"eventId": "1"}, "/api/v1/events/1/sessions"),
        ("list_tiers", {"sessionId": "1"}, "/api/v1/sessions/1/tiers"),
        ("list_my_orders", {"status": "PAID"}, "/api/v1/orders"),
        ("get_my_order", {"orderId": "1"}, "/api/v1/orders/1"),
    ],
)
def test_six_tools_against_java_contract(client, backend, tool, args, path):
    response = client.post("/agent/v1/query", json={"tool": tool, "arguments": args})
    assert response.status_code == 200, response.text
    result = response.json()["data"]
    assert result["source"]["path"] == path
    assert result["source"]["queriedAt"]
    assert result["source"]["traceId"]
    assert len(backend.requests) == 2
    assert backend.requests[0].url.path == "/api/v1/users/me"
    assert all(r.headers["authorization"] == "Bearer alice" for r in backend.requests)
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-trace-id"] == response.json()["traceId"]


@pytest.mark.parametrize(
    "args",
    [
        {"orderId": "2", "userId": "2"},
        {"orderId": 1},
        {"orderId": "../admin/orders"},
        {"orderId": "https://evil.test"},
        {"orderId": "0"},
        {"orderId": "-1"},
        {"orderId": "9223372036854775808"},
        {"orderId": "1", "Authorization": "bob"},
    ],
)
def test_bad_ids_and_identity_injection(client, backend, args):
    response = client.post("/agent/v1/query", json={"tool": "get_my_order", "arguments": args})
    assert response.status_code == 422
    assert len(backend.requests) == 1


@pytest.mark.parametrize(
    "args",
    [{"size": 21}, {"page": 0}, {"page": True}, {"size": "10"}, {"price": 1}, {"userId": "2"}],
)
def test_page_filter_limits(client, backend, args):
    assert (
        client.post(
            "/agent/v1/query", json={"tool": "search_events", "arguments": args}
        ).status_code
        == 422
    )
    assert len(backend.requests) == 1


@pytest.mark.parametrize("tool", ["cancel", "refund", "payments", "admin_orders", "http_get"])
def test_no_write_or_arbitrary_tools(client, backend, tool):
    assert client.post("/agent/v1/query", json={"tool": tool}).status_code == 422
    assert len(backend.requests) == 1


def test_private_order_is_not_disclosed(client):
    r = client.post("/agent/v1/query", json={"tool": "get_my_order", "arguments": {"orderId": "2"}})
    assert r.status_code == 404
    assert r.json()["code"] == "NOT_FOUND"
    assert "他人" not in r.text


@pytest.mark.parametrize("authorization", ["", "Basic alice", "Bearer expired"])
def test_missing_invalid_and_expired_auth(client, backend, authorization):
    r = client.post(
        "/agent/v1/query", headers={"Authorization": authorization}, json={"tool": "list_my_orders"}
    )
    assert r.status_code == 401
    assert all(x.url.path == "/api/v1/users/me" for x in backend.requests)


def test_disabled_user(client, backend):
    backend.disabled = True
    assert client.get("/agent/v1/tools").status_code == 401


@pytest.mark.parametrize(
    "status,expected",
    [(301, 502), (302, 502), (401, 401), (403, 403), (404, 404), (429, 429), (500, 502)],
)
def test_dependency_status_and_no_redirect(client, backend, status, expected):
    backend.override = lambda _: httpx.Response(status, headers={"Location": "https://evil.test"})
    r = client.post("/agent/v1/query", json={"tool": "list_my_orders"})
    assert r.status_code == expected
    assert len(backend.requests) == 2
    assert "evil" not in r.text


def test_network_failure_not_empty_orders(client, backend):
    def fail(request):
        raise httpx.ConnectError("secret exception", request=request)

    backend.override = fail
    r = client.post("/agent/v1/query", json={"tool": "list_my_orders"})
    assert r.status_code == 502
    assert "secret" not in r.text
    assert "data" not in r.json()


@pytest.mark.parametrize(
    "body", [{}, {"code": "OK", "data": {}}, {"code": "NO", "data": {"items": []}}]
)
def test_malformed_upstream(client, backend, body):
    backend.override = lambda _: httpx.Response(200, json=body)
    assert client.post("/agent/v1/query", json={"tool": "list_my_orders"}).status_code == 502


def test_amount_precision_and_field_minimization(client, backend):
    body = example(CONTRACT["components"]["schemas"]["TierPageResponse"])
    body["data"].update(page=1, size=10)
    body["data"]["items"][0].update(priceFen=12345, internalSecret="secret")
    backend.override = lambda _: httpx.Response(200, json=body)
    r = client.post("/agent/v1/query", json={"tool": "list_tiers", "arguments": {"sessionId": "1"}})
    assert r.status_code == 200
    assert r.json()["data"]["data"]["items"][0]["priceFen"] == 12345
    assert "secret" not in r.text


def test_response_bound(client, backend):
    backend.override = lambda _: httpx.Response(200, content=b"x" * 262145)
    assert (
        client.post("/agent/v1/query", json={"tool": "list_my_orders"}).json()["code"]
        == "JAVA_RESPONSE_TOO_LARGE"
    )


def test_request_bound_and_validation_redaction(client):
    assert client.post("/agent/v1/chat", json={"message": "x" * 17000}).status_code == 413
    r = client.post("/agent/v1/chat", json={"message": "hi", "token": "SUPER_SECRET"})
    assert r.status_code == 422
    assert "SUPER_SECRET" not in r.text


def test_model_disabled_but_query_available(backend):
    with TestClient(create_app(transport=httpx.MockTransport(backend))) as c:
        c.headers["Authorization"] = "Bearer alice"
        assert (
            c.post("/agent/v1/chat", json={"message": "我的订单"}).json()["code"]
            == "MODEL_NOT_CONFIGURED"
        )
        assert c.post("/agent/v1/query", json={"tool": "list_my_orders"}).status_code == 200


def test_contract_copy_matches_java_openapi():
    source = json.loads((Path(__file__).parents[2] / "docs/api/openapi.json").read_text("utf-8"))
    for key, value in CONTRACT["components"]["schemas"].items():
        assert source["components"]["schemas"][key] == value


@pytest.mark.parametrize(
    "url",
    [
        "http://evil.test",
        "https://x.test/api",
        "https://u:p@x.test",
        "file:///etc/passwd",
        "https://x.test?token=abc",
    ],
)
def test_unsafe_configuration(url):
    with pytest.raises(ValueError):
        Settings(java_url=url)


def test_empty_result_is_not_dependency_failure(client, backend):
    body = example(CONTRACT["components"]["schemas"]["EventPageResponse"])
    body["data"].update(items=[], total=0, size=10)
    backend.override = lambda _: httpx.Response(200, json=body)
    r = client.post("/agent/v1/query", json={"tool": "search_events"})
    assert r.status_code == 200
    assert r.json()["data"]["data"]["total"] == 0


def test_mismatched_page_is_rejected(client, backend):
    body = example(CONTRACT["components"]["schemas"]["EventPageResponse"])
    body["data"].update(page=2, size=10)
    backend.override = lambda _: httpx.Response(200, json=body)
    assert (
        client.post("/agent/v1/query", json={"tool": "search_events"}).json()["code"]
        == "JAVA_INVALID_RESPONSE"
    )


def test_unexpected_private_fields_fail_closed(client, backend):
    body = example(CONTRACT["components"]["schemas"]["OrderResponse"])
    body["data"]["userId"] = "999"
    backend.override = lambda _: httpx.Response(200, json=body)
    r = client.post("/agent/v1/query", json={"tool": "get_my_order", "arguments": {"orderId": "1"}})
    assert r.status_code == 502
    assert "999" not in r.text
