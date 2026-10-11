import copy
import json

import httpx
import pytest
from test_gateway import completion, send

from ticketflow_agent.config import Settings


def routed(queries=None, note="queries", missing=None):
    return completion(
        "route_request",
        {
            "queries": queries
            if queries is not None
            else [{"name": "search_events", "arguments": {"city": "杭州"}}],
            "note": note,
            "missing_fields": missing or [],
        },
    )


@pytest.fixture
def settings(tmp_path):
    return Settings(
        model_mode="gateway",
        gateway_key="test-route-key",
        usage_path=str(tmp_path / "usage.sqlite3"),
    )


@pytest.mark.parametrize(
    "fault",
    [
        "text_only",
        "wrong_function",
        "two_calls",
        "truncated",
        "empty_id",
        "duplicate_json",
        "unknown_tool",
        "forged_identity",
        "wrong_id_type",
        "empty_queries",
        "mixed_rejection",
        "queries_with_missing",
        "unneeded_missing",
        "missing_clarification",
        "extra_root",
        "excess_calls",
        "bool_page",
    ],
)
def test_forced_route_rejects_invalid_complete_batch_before_java(backend, settings, fault):
    body = routed()
    choice = body["choices"][0]
    call = choice["message"]["tool_calls"][0]
    args = json.loads(call["function"]["arguments"])
    if fault == "text_only":
        choice.update(finish_reason="stop", message={"content": json.dumps(args)})
    elif fault == "wrong_function":
        call["function"]["name"] = "execute"
    elif fault == "two_calls":
        choice["message"]["tool_calls"].append(copy.deepcopy(call))
    elif fault == "truncated":
        choice["finish_reason"] = "length"
    elif fault == "empty_id":
        call["id"] = ""
    elif fault == "duplicate_json":
        call["function"]["arguments"] = '{"note":"queries","note":"unsupported"}'
    else:
        if fault == "unknown_tool":
            args["queries"].append({"name": "execute", "arguments": {}})
        elif fault == "forged_identity":
            args["queries"] = [
                {"name": "prepare_cancel", "arguments": {"orderId": "1", "userId": "2"}}
            ]
        elif fault == "wrong_id_type":
            args["queries"] = [{"name": "prepare_refund", "arguments": {"orderId": 1}}]
        elif fault == "empty_queries":
            args["queries"] = []
        elif fault == "mixed_rejection":
            args["note"] = "unsupported"
        elif fault == "queries_with_missing":
            args["missing_fields"] = ["orderId"]
        elif fault == "unneeded_missing":
            args.update(queries=[], note="unsupported", missing_fields=["orderId"])
        elif fault == "missing_clarification":
            args.update(queries=[], note="clarification")
        elif fault == "extra_root":
            args["approved"] = True
        elif fault == "excess_calls":
            args["queries"] *= 7
        elif fault == "bool_page":
            args["queries"][0]["arguments"]["page"] = True
        call["function"]["arguments"] = json.dumps(args)
    response = send(backend, settings, lambda _: httpx.Response(200, json=body))
    assert response.status_code in {422, 502}
    assert all(r.url.path == "/api/v1/users/me" for r in backend.requests)


@pytest.mark.parametrize("note,missing", [("unsupported", []), ("clarification", ["orderId"])])
def test_forced_route_fixed_finish_states(backend, settings, note, missing):
    body = routed([], note, missing)
    body["choices"][0]["finish_reason"] = "stop"
    response = send(backend, settings, lambda _: httpx.Response(200, json=body))
    assert response.status_code == 200 and response.json()["data"]["cards"] == []
    assert all(r.url.path == "/api/v1/users/me" for r in backend.requests)


def test_forced_route_mixed_queries_remain_one_model_request(backend, settings):
    count = []

    def respond(request):
        count.append(request)
        return httpx.Response(
            200,
            json=routed(
                [
                    {"name": "get_event", "arguments": {"eventId": "1"}},
                    {"name": "search_rules", "arguments": {"query": "退款规则"}},
                ]
            ),
        )

    response = send(backend, settings, respond, "查询活动1和退款规则")
    assert response.status_code == 200
    assert {c["tool"] for c in response.json()["data"]["cards"]} == {"get_event", "search_rules"}
    assert len(count) == 1
