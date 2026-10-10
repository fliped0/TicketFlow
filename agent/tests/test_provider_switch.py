import json
from dataclasses import replace

import httpx
import pytest
from test_gateway import completion, send

import setup_gateway
from ticketflow_agent.config import Settings

QWEN_URL = "https://maas.qianwenaiapi.com/compatible-mode/v1"
SCHOOL_URL = "http://aigw.dlut.edu.cn/v1"


def test_qwen_wire_path_model_and_thinking(backend, tmp_path):
    settings = Settings(
        model_mode="gateway",
        gateway_key="qwen-test-key",
        gateway_key_url=QWEN_URL,
        usage_path=str(tmp_path / "usage.sqlite3"),
    )
    requests = []

    def handler(request):
        requests.append(request)
        assert str(request.url) == QWEN_URL + "/chat/completions"
        assert request.headers["authorization"] == "Bearer qwen-test-key"
        body = json.loads(request.content)
        assert body["model"] == "qwen3.8-flash"
        assert body["enable_thinking"] is False and body["stream"] is False
        assert body["temperature"] == 0.1
        assert body["response_format"] == {"type": "json_object"}
        return httpx.Response(200, json=completion("search_events", {"city": "杭州"}))

    response = send(backend, settings, handler)
    assert response.status_code == 200 and len(requests) == 1
    assert response.json()["data"]["cards"][0]["tool"] == "search_events"


def test_prepare_switch_preserves_old_key_binding_and_quota(tmp_path, monkeypatch):
    monkeypatch.setattr(setup_gateway, "ROOT", tmp_path)
    target = tmp_path / "config/local/agent.json"
    target.parent.mkdir(parents=True)
    old = {
        "gateway_url": SCHOOL_URL,
        "gateway_model": "DeepSeek-V4-Flash-0731-W8A8",
        "gateway_key": "old-school-test-key",
        "allow_http_gateway": True,
        "daily_requests": 7,
    }
    target.write_text(json.dumps(old), "utf-8")
    setup_gateway.prepare_switch(QWEN_URL, "qwen3.8-flash")
    pending = json.loads(target.read_text("utf-8"))
    assert pending["gateway_key"] == old["gateway_key"]
    assert pending["gateway_key_url"] == SCHOOL_URL
    assert pending["gateway_url"] == QWEN_URL and pending["daily_requests"] == 7
    monkeypatch.setenv("TF_AGENT_CONFIG", str(target))
    monkeypatch.delenv("TF_AGENT_API_KEY", raising=False)
    monkeypatch.setenv("TF_AGENT_MODEL_MODE", "disabled")
    Settings.from_env()
    monkeypatch.setenv("TF_AGENT_MODEL_MODE", "gateway")
    with pytest.raises(ValueError, match="Gateway changed"):
        Settings.from_env()
    pending.update(gateway_key="new-qwen-test-key", gateway_key_url=QWEN_URL)
    setup_gateway.save_local(pending, replace=True)
    assert Settings.from_env().gateway_key == "new-qwen-test-key"


def test_legacy_key_binds_to_previous_url(tmp_path, monkeypatch):
    target = tmp_path / "agent.json"
    target.write_text(
        json.dumps(
            {
                "gateway_url": SCHOOL_URL,
                "gateway_key": "school-test-key",
                "allow_http_gateway": True,
            }
        ),
        "utf-8",
    )
    monkeypatch.setenv("TF_AGENT_CONFIG", str(target))
    monkeypatch.setenv("TF_AGENT_MODEL_MODE", "gateway")
    monkeypatch.delenv("TF_AGENT_API_KEY", raising=False)
    settings = Settings.from_env()
    assert settings.gateway_key_url == SCHOOL_URL
    with pytest.raises(ValueError, match="Gateway changed"):
        replace(settings, gateway_url=QWEN_URL, allow_http_gateway=False)


@pytest.mark.parametrize(
    "url",
    [
        "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
        "https://maas.qianwenaiapi.com/compatible-mode/v1?key=x",
        "http://maas.qianwenaiapi.com/compatible-mode/v1",
    ],
)
def test_prepare_invalid_endpoint_cannot_replace_config(tmp_path, monkeypatch, url):
    monkeypatch.setattr(setup_gateway, "ROOT", tmp_path)
    with pytest.raises(ValueError):
        setup_gateway.prepare_switch(url, "qwen3.8-flash")
    assert not (tmp_path / "config/local/agent.json").exists()
