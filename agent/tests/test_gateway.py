import asyncio
import json
from dataclasses import replace

import httpx
import pytest
from fastapi.testclient import TestClient

from ticketflow_agent.app import create_app
from ticketflow_agent.config import Settings
from ticketflow_agent.gateway import GatewayModel


def completion(name, args, *, usage=True):
    body = {
        "choices": [
            {
                "finish_reason": "tool_calls",
                "message": {
                    "role": "assistant",
                    "tool_calls": [
                        {
                            "id": "call-1",
                            "type": "function",
                            "function": {"name": name, "arguments": json.dumps(args)},
                        }
                    ],
                },
            }
        ]
    }
    if usage:
        body["usage"] = {"prompt_tokens": 100, "completion_tokens": 20}
    return body


@pytest.fixture
def gateway_settings(tmp_path):
    return Settings(
        model_mode="gateway",
        gateway_url="https://gateway.test/v1",
        gateway_key="unit-test-secret",
        usage_path=str(tmp_path / "usage.sqlite3"),
    )


def send(backend, settings, handler, message="帮我查一下杭州的活动"):
    with TestClient(
        create_app(
            settings,
            transport=httpx.MockTransport(backend),
            model_transport=httpx.MockTransport(handler),
        )
    ) as c:
        c.headers["Authorization"] = "Bearer alice"
        return c.post("/agent/v1/chat", json={"message": message})


def test_natural_query_wire_contract_sources_and_no_credentials(backend, gateway_settings):
    captured = []

    def handler(request):
        assert request.method == "POST"
        assert str(request.url) == "https://gateway.test/v1/chat/completions"
        assert request.headers["authorization"] == "Bearer unit-test-secret"
        body = json.loads(request.content)
        captured.append(body)
        assert body["model"] == gateway_settings.gateway_model
        assert "enable_thinking" not in body
        assert body["stream"] is False and body["max_tokens"] == 1024
        assert "alice" not in request.content.decode()
        names = [x["function"]["name"] for x in body["tools"]]
        assert "list_my_orders" not in names
        assert body["response_format"] == {"type": "json_object"}
        assert [m["role"] for m in body["messages"]] == ["system", "user"]
        return httpx.Response(200, json=completion("search_events", {"city": "杭州"}))

    r = send(backend, gateway_settings, handler)
    assert r.status_code == 200, r.text
    assert r.json()["data"]["cards"][0]["source"]["params"]["city"] == "杭州"
    assert len(captured) == 1


@pytest.mark.parametrize(
    "status,code",
    [
        (401, "GATEWAY_AUTH_FAILED"),
        (403, "GATEWAY_AUTH_FAILED"),
        (429, "GATEWAY_RATE_LIMITED"),
        (500, "GATEWAY_UNAVAILABLE"),
        (302, "GATEWAY_UNAVAILABLE"),
    ],
)
def test_gateway_status_redaction_no_retries(backend, gateway_settings, status, code):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(
            status, json={"error": "unit-test-secret"}, headers={"Location": "https://evil.test"}
        )

    r = send(backend, gateway_settings, handler)
    assert r.json()["code"] == code
    assert "unit-test-secret" not in r.text
    assert len(requests) == 1


def test_connection_failure_keeps_unknown_reservation(backend, gateway_settings):
    def handler(request):
        raise httpx.ReadTimeout("unit-test-secret", request=request)

    r = send(backend, gateway_settings, handler)
    assert r.json()["code"] == "GATEWAY_UNAVAILABLE"
    from ticketflow_agent.usage import UsageLedger

    summary = UsageLedger(gateway_settings).summary()
    assert summary["requests"] == summary["unknownUsageRequests"] == 1
    assert summary["accountedTokens"] == 9024


@pytest.mark.parametrize(
    "args",
    [
        {"note": "results", "result_indices": [0]},
        {"note": "results", "result_indices": []},
        {"note": "results", "result_indices": [True]},
        {"note": "results", "result_indices": [-1]},
        {"note": "unsupported", "result_indices": [0]},
        {"note": "clarification"},
        {"note": "clarification", "missing_fields": ["password"]},
        {"note": "unsupported", "question": "订单已退款"},
    ],
)
def test_no_fabricated_source_or_business_prose(backend, gateway_settings, args):
    r = send(
        backend, gateway_settings, lambda _: httpx.Response(200, json=completion("finish", args))
    )
    assert r.json()["code"] == "MODEL_INVALID_OUTPUT"


def test_structured_clarification_is_rendered_by_server(backend, gateway_settings):
    r = send(
        backend,
        gateway_settings,
        lambda _: httpx.Response(
            200, json=completion("finish", {"note": "clarification", "missing_fields": ["eventId"]})
        ),
    )
    assert r.status_code == 200
    assert r.json()["data"]["message"] == "请补充：活动编号"
    assert r.json()["data"]["cards"] == []


def test_unsupported_is_not_a_successful_transaction(backend, gateway_settings):
    r = send(
        backend,
        gateway_settings,
        lambda _: httpx.Response(200, json=completion("finish", {"note": "unsupported"})),
        "把票退了",
    )
    assert r.status_code == 200
    assert "仅支持" in r.json()["data"]["message"]
    assert all(r.url.path == "/api/v1/users/me" for r in backend.requests)


@pytest.mark.parametrize(
    "name,args",
    [
        ("refund", {"orderId": "1"}),
        ("get_event", {"eventId": "../admin"}),
        ("list_my_orders", {}),
        ("search_events", {"userId": "2"}),
    ],
)
def test_gateway_tool_permission_checks(backend, gateway_settings, name, args):
    r = send(backend, gateway_settings, lambda _: httpx.Response(200, json=completion(name, args)))
    assert r.status_code == 422
    assert len(backend.requests) == 1


def test_private_data_can_only_be_shared_when_enabled(backend, gateway_settings):
    settings = replace(gateway_settings, allow_private_model_data=True)
    captured = []

    def handler(request):
        body = json.loads(request.content)
        captured.append(body)
        assert "get_my_order" in [x["function"]["name"] for x in body["tools"]]
        assert all(m["role"] != "tool" for m in body["messages"])
        return httpx.Response(200, json=completion("get_my_order", {"orderId": "1"}))

    assert send(backend, settings, handler).status_code == 200
    assert len(captured) == 1


@pytest.mark.parametrize(
    "body",
    [
        {},
        {"choices": []},
        {"choices": [{"finish_reason": "length"}]},
        {"choices": [{"finish_reason": "stop", "message": {"content": "退款成功"}}]},
    ],
)
def test_gateway_malformed_output(backend, gateway_settings, body):
    assert (
        send(backend, gateway_settings, lambda _: httpx.Response(200, json=body)).json()["code"]
        == "MODEL_INVALID_OUTPUT"
    )


@pytest.mark.parametrize(
    "route,status",
    [
        ({"queries": [{"name": "search_events", "arguments": {"city": "杭州"}}]}, 200),
        ({"note": "clarification", "missing_fields": ["sessionId"]}, 200),
        ({"note": "unsupported"}, 200),
        ({"queries": [{"name": "refund", "arguments": {"orderId": "1"}}]}, 422),
        ({"queries": [{"name": "search_events", "arguments": {"userId": "2"}}]}, 422),
        ({"queries": []}, 502),
        ({"queries": [], "message": "退款成功"}, 502),
        ({"note": "results", "result_indices": [0]}, 502),
    ],
)
def test_json_routes_use_same_permission_and_fact_validation(
    backend, gateway_settings, route, status
):
    body = {
        "choices": [
            {
                "finish_reason": "stop",
                "message": {
                    "content": json.dumps(route),
                },
            }
        ],
        "usage": {"prompt_tokens": 100, "completion_tokens": 20},
    }
    response = send(backend, gateway_settings, lambda _: httpx.Response(200, json=body))
    assert response.status_code == status
    if route.get("note") == "clarification":
        assert response.json()["data"]["message"] == "请补充：场次编号"
    if status != 200 or "queries" not in route:
        assert all(r.url.path == "/api/v1/users/me" for r in backend.requests)


def test_duplicate_json_and_oversize_response(backend, gateway_settings):
    for raw in (b'{"choices":[],"choices":[]}', b"x" * 131073):
        assert (
            send(
                backend, gateway_settings, lambda _, raw=raw: httpx.Response(200, content=raw)
            ).json()["code"]
            == "MODEL_INVALID_OUTPUT"
        )


def test_cancelled_gateway_retains_usage(backend, gateway_settings):
    async def scenario():
        async def slow(request):
            await asyncio.sleep(5)

        model = GatewayModel(gateway_settings, transport=httpx.MockTransport(slow))
        try:
            from ticketflow_agent.tools import definitions

            with pytest.raises(TimeoutError):
                async with asyncio.timeout(0.02):
                    await model.decide_for("1", "hi", [], definitions())
            assert model.ledger.summary()["unknownUsageRequests"] == 1
        finally:
            await model.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "changes",
    [
        {"gateway_key": ""},
        {"gateway_url": "http://evil.test/v1", "allow_http_gateway": True},
        {"gateway_url": "http://aigw.dlut.edu.cn/v1"},
        {"gateway_url": "https://u:p@gateway.test/v1"},
        {"gateway_url": "https://gateway.test/v1/v1"},
        {"allow_private_model_data": "false"},
        {"daily_requests": True},
        {
            "requests_per_minute": 21,
            "gateway_url": "http://aigw.dlut.edu.cn/v1",
            "allow_http_gateway": True,
        },
        {"usage_path": ":memory:"},
        {"model_timeout": float("nan")},
    ],
)
def test_live_configuration_fail_closed(gateway_settings, changes):
    with pytest.raises(ValueError):
        replace(gateway_settings, **changes)


def test_key_not_in_settings_repr(gateway_settings):
    assert "unit-test-secret" not in repr(gateway_settings)


def test_completed_query_not_repeated_in_java(backend, gateway_settings):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=completion("search_events", {}))

    r = send(backend, gateway_settings, handler)
    assert r.status_code == 200
    assert len(requests) == 1
    assert len([x for x in backend.requests if x.url.path == "/api/v1/events"]) == 1


def test_input_size_rejected_before_reserving_or_sending(backend, gateway_settings):
    requests = []
    settings = replace(gateway_settings, max_input_bytes=10)

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=completion("finish", {"note": "unsupported"}))

    r = send(backend, settings, handler)
    assert r.json()["code"] == "MODEL_INPUT_LIMIT" and not requests
    from ticketflow_agent.usage import UsageLedger

    assert UsageLedger(settings).summary()["requests"] == 0


def test_quota_rejected_before_model_network(backend, gateway_settings):
    requests = []
    settings = replace(gateway_settings, daily_requests=1)

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json=completion("search_events", {}))

    assert send(backend, settings, handler).status_code == 200
    r = send(backend, settings, handler)
    assert r.json()["code"] == "MODEL_QUOTA_EXCEEDED" and len(requests) == 1
