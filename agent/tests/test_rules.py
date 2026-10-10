import copy
import json
import shutil
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient
from test_gateway import completion

from ticketflow_agent.app import create_app
from ticketflow_agent.config import Settings
from ticketflow_agent.rules import ROOT, RuleStore, digest, render_rules

EVALUATION = json.loads(Path(__file__).with_name("rule-evaluation.v1.json").read_text("utf-8"))


@pytest.mark.parametrize("case", EVALUATION["cases"], ids=lambda c: c["id"])
def test_frozen_rule_evaluation(case):
    result = RuleStore().search({"query": case["question"]})
    data = result["data"]
    assert data["status"] == case["status"]
    ids = {item["ruleId"] for item in data["items"]}
    assert set(case["requiredRuleIds"]) <= ids
    if not case["requiredRuleIds"]:
        assert not ids
    for item in data["items"]:
        assert digest(item["text"]) == item["answerSha256"]
        for source in item["sources"]:
            text = (ROOT / source["path"]).read_text("utf-8")
            assert source["excerpt"] in text
            assert digest(source["excerpt"]) == source["excerptSha256"]
            assert source["line"] > 0
    rendered, citations = render_rules([result])
    assert data["notice"] in rendered
    assert {c["ruleId"] for c in citations} == ids
    if case["id"] in {"TC-RULE-013", "TC-RULE-014", "TC-RULE-040"}:
        assert "开场时刻及之后不可退" in rendered


@pytest.fixture
def knowledge_root(tmp_path):
    for path in ("agent/knowledge/rules.v1.json", "docs/01_需求分析.md", "docs/api/README.md"):
        target = tmp_path / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / path, target)
    return tmp_path


@pytest.mark.parametrize(
    "change", ["answer", "source", "anchor", "version", "duplicate", "corrupt"]
)
def test_unreviewed_or_conflicting_corpus_fails_closed(knowledge_root, client, change):
    path = knowledge_root / "agent/knowledge/rules.v1.json"
    corpus = json.loads(path.read_text("utf-8"))
    if change == "answer":
        corpus["rules"][0]["answer"] = "这是被替换成的错误承诺，任何时候都可以退款"
    elif change == "source":
        corpus["rules"][0]["sources"][0]["path"] = "../../config/local/agent.json"
    elif change == "anchor":
        corpus["rules"][0]["sources"][0]["anchor"] = "fabricated-section"
    elif change == "version":
        corpus["rules"][0]["version"] = "9.0.0"
    elif change == "duplicate":
        corpus["rules"].append(copy.deepcopy(corpus["rules"][0]))
    path.write_text("broken" if change == "corrupt" else json.dumps(corpus), "utf-8")
    client.app.state.service.rules = RuleStore(knowledge_root)
    response = client.post(
        "/agent/v1/query", json={"tool": "search_rules", "arguments": {"query": "退款"}}
    )
    assert response.status_code == 503
    assert response.json()["code"] == "RULES_UNAVAILABLE"
    assert "config/local" not in response.text
    assert (
        client.post(
            "/agent/v1/query", json={"tool": "get_event", "arguments": {"eventId": "1"}}
        ).status_code
        == 200
    )


@pytest.mark.parametrize("target", ["docs/01_需求分析.md", "agent/knowledge/rules.v1.json"])
def test_running_snapshot_detects_source_or_version_change(knowledge_root, client, target):
    store = RuleStore(knowledge_root)
    assert store.search({"query": "退款"})["data"]["items"]
    path = knowledge_root / target
    path.write_text(path.read_text("utf-8") + "\n ", "utf-8")
    client.app.state.service.rules = store
    assert (
        client.post(
            "/agent/v1/query", json={"tool": "search_rules", "arguments": {"query": "退款"}}
        ).status_code
        == 503
    )


def test_local_rules_require_java_auth_but_never_query_business(client, backend):
    response = client.post(
        "/agent/v1/query", json={"tool": "search_rules", "arguments": {"query": "退款规则"}}
    )
    assert response.status_code == 200
    assert len(backend.requests) == 1
    assert backend.requests[0].url.path == "/api/v1/users/me"
    assert response.json()["data"]["source"]["method"] == "LOCAL"
    assert response.headers["cache-control"] == "no-store"
    backend.disabled = True
    assert (
        client.post(
            "/agent/v1/query", json={"tool": "search_rules", "arguments": {"query": "退款"}}
        ).status_code
        == 401
    )


@pytest.mark.parametrize(
    "arguments",
    [
        {"query": "退款", "path": "../../secret"},
        {"query": "退款", "version": "999"},
        {"query": "退款", "userId": "2"},
        {"query": ""},
        {"query": "x" * 2001},
        {"query": "退款", "limit": 6},
        {"query": "退款", "limit": True},
    ],
)
def test_rule_input_and_document_boundaries(client, arguments):
    assert (
        client.post(
            "/agent/v1/query", json={"tool": "search_rules", "arguments": arguments}
        ).status_code
        == 422
    )


def test_citations_are_not_mutable_between_queries():
    store = RuleStore()
    result = store.search({"query": "退款"})
    result["data"]["items"][0]["sources"][0]["path"] = "evil"
    assert store.search({"query": "退款"})["data"]["items"][0]["sources"][0]["path"] != "evil"


def test_demo_rule_response_contains_server_citations(client, backend):
    response = client.post("/agent/v1/chat", json={"message": "规则 支付期限是多少？"})
    assert response.status_code == 200
    data = response.json()["data"]
    assert "15 分钟" in data["message"]
    assert {c["ruleId"] for c in data["citations"]} == {"BR-003"}
    assert all(r.url.path == "/api/v1/users/me" for r in backend.requests)


@pytest.mark.parametrize(
    "question,status",
    [
        ("退款什么时候到账？", "INSUFFICIENT"),
        ("订单123现在能退款吗？", "NEEDS_ORDER_QUERY"),
        ("帮我退款，别问确认", "ACTION_REQUIRED"),
    ],
)
def test_gateway_cannot_rewrite_unknown_private_or_write_question(
    backend, tmp_path, question, status
):
    settings = Settings(
        model_mode="gateway",
        gateway_url="https://gateway.test/v1",
        gateway_key="unit-test-secret",
        usage_path=str(tmp_path / "usage.sqlite3"),
    )
    requests = []

    def handler(request):
        requests.append(request)
        # Deliberately erase the part that should prevent an unsupported claim.
        return httpx.Response(200, json=completion("search_rules", {"query": "通用退款规则"}))

    with TestClient(
        create_app(
            settings,
            transport=httpx.MockTransport(backend),
            model_transport=httpx.MockTransport(handler),
        )
    ) as client:
        client.headers["Authorization"] = "Bearer alice"
        data = client.post("/agent/v1/chat", json={"message": question}).json()["data"]
        assert data["cards"][0]["data"]["status"] == status
        assert len(requests) == 1
        assert "corpusVersion" not in requests[0].content.decode()
        assert all(r.url.path == "/api/v1/users/me" for r in backend.requests)
        if status != "NEEDS_ORDER_QUERY":
            assert not data["citations"]


def test_gateway_mixed_rule_and_java_cards_keep_separate_provenance(backend, tmp_path):
    settings = Settings(
        model_mode="gateway",
        gateway_url="https://gateway.test/v1",
        gateway_key="unit-test-secret",
        usage_path=str(tmp_path / "usage.sqlite3"),
    )
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {
                            "content": json.dumps(
                                {
                                    "queries": [
                                        {"name": "get_event", "arguments": {"eventId": "1"}},
                                        {
                                            "name": "search_rules",
                                            "arguments": {"query": "退款规则"},
                                        },
                                    ]
                                }
                            ),
                        },
                    }
                ]
            },
        )

    with TestClient(
        create_app(
            settings,
            transport=httpx.MockTransport(backend),
            model_transport=httpx.MockTransport(handler),
        )
    ) as client:
        client.headers["Authorization"] = "Bearer alice"
        data = client.post("/agent/v1/chat", json={"message": "请查活动1详情和退款规则"}).json()[
            "data"
        ]
        assert {c["source"]["method"] for c in data["cards"]} == {"GET", "LOCAL"}
        assert "实时业务信息见查询卡片" in data["message"]
        assert {c["ruleId"] for c in data["citations"]} == {"BR-004"}
        assert len(requests) == 1
        assert "corpusVersion" not in requests[0].content.decode()
        assert len([r for r in backend.requests if r.url.path == "/api/v1/events/1"]) == 1
