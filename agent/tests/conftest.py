import copy

import httpx
import pytest
from fastapi.testclient import TestClient

from ticketflow_agent.app import create_app
from ticketflow_agent.config import Settings
from ticketflow_agent.java import CONTRACT


@pytest.fixture(autouse=True)
def isolated_configuration(monkeypatch, tmp_path):
    monkeypatch.setenv("TF_AGENT_CONFIG", str(tmp_path / "absent-config.json"))
    for name in ("TF_AGENT_MODEL_MODE", "TF_AGENT_API_KEY", "TF_AGENT_JAVA_URL"):
        monkeypatch.delenv(name, raising=False)


def example(schema):
    if "$ref" in schema:
        return example(CONTRACT["components"]["schemas"][schema["$ref"].split("/")[-1]])
    if "const" in schema:
        return schema["const"]
    if "enum" in schema:
        return schema["enum"][0]
    if "oneOf" in schema:
        return None
    kind = schema.get("type")
    if kind == "object":
        return {key: example(schema["properties"][key]) for key in schema["required"]}
    if kind == "array":
        return [example(schema["items"])]
    if kind == "integer":
        return max(schema.get("minimum", 0), 1)
    if kind == "boolean":
        return False
    if schema.get("format") == "uuid":
        return "11111111-1111-4111-8111-111111111111"
    if schema.get("format") == "date-time":
        return "2026-12-01T12:00:00Z"
    return "1" if "pattern" in schema else "测试活动"


class Backend:
    def __init__(self):
        self.requests = []
        self.override = None
        self.disabled = False

    def __call__(self, request):
        self.requests.append(request)
        assert request.method == "GET", "Agent must never write to Java"
        token = request.headers.get("authorization")
        if self.disabled or token not in {"Bearer alice", "Bearer bob"}:
            return httpx.Response(401)
        if request.url.path == "/api/v1/users/me":
            body = example(CONTRACT["components"]["schemas"]["UserResponse"])
            body["code"] = "OK"
            body["data"]["userId"] = "1" if token == "Bearer alice" else "2"
            return httpx.Response(200, json=body)
        if self.override:
            return self.override(request)
        path = request.url.path
        if path == "/api/v1/orders/2" and token == "Bearer alice":
            return httpx.Response(404)
        name = {
            "/api/v1/events": "EventPageResponse",
            "/api/v1/events/1": "EventResponse",
            "/api/v1/events/1/sessions": "SessionPageResponse",
            "/api/v1/sessions/1/tiers": "TierPageResponse",
            "/api/v1/orders": "OrderPageResponse",
            "/api/v1/orders/1": "OrderResponse",
        }.get(path)
        assert name, path
        body = copy.deepcopy(example(CONTRACT["components"]["schemas"][name]))
        if "items" in body["data"]:
            body["data"]["page"] = int(request.url.params.get("page", 1))
            body["data"]["size"] = int(request.url.params.get("size", 10))
        return httpx.Response(200, json=body)


@pytest.fixture
def backend():
    return Backend()


@pytest.fixture
def client(backend):
    with TestClient(
        create_app(Settings(model_mode="demo"), transport=httpx.MockTransport(backend))
    ) as client:
        client.headers["Authorization"] = "Bearer alice"
        yield client
