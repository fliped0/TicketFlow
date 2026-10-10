import json
import math
import os
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Settings:
    java_url: str = "http://127.0.0.1:8080"
    model_mode: str = "disabled"
    java_timeout: float = 5.0
    turn_timeout: float = 45.0
    max_model_calls: int = 4
    max_tool_calls: int = 6
    max_sessions: int = 256
    session_ttl: float = 1800.0
    max_concurrent: int = 8
    max_response_bytes: int = 262144
    gateway_url: str = "https://maas.qianwenaiapi.com/compatible-mode/v1"
    gateway_model: str = "qwen3.8-flash"
    gateway_key: str = field(default="", repr=False)
    gateway_key_url: str = field(default="", repr=False)
    allow_http_gateway: bool = False
    allow_private_model_data: bool = False
    model_timeout: float = 30.0
    max_input_bytes: int = 8000
    max_output_tokens: int = 1024
    daily_requests: int = 20
    user_daily_requests: int = 10
    requests_per_minute: int = 10
    daily_token_budget: int = 200000
    usage_path: str = field(default=".runtime/model-usage.sqlite3", repr=False)

    def __post_init__(self):
        for flag in (self.allow_http_gateway, self.allow_private_model_data):
            if type(flag) is not bool:
                raise ValueError("Gateway consent flags must be booleans")
        url = urlsplit(self.java_url)
        if (
            url.scheme not in {"http", "https"}
            or not url.hostname
            or url.username
            or url.password
            or url.query
            or url.fragment
            or url.path not in {"", "/"}
        ):
            raise ValueError("Java URL must be an origin without credentials, path or query")
        if url.scheme == "http" and url.hostname not in {"127.0.0.1", "localhost", "::1"}:
            raise ValueError("Non-loopback Java origins require HTTPS")
        if self.model_mode not in {"disabled", "demo", "gateway"}:
            raise ValueError("Unknown model mode")
        if self.model_mode == "gateway" or self.gateway_key_url:
            target = urlsplit(self.gateway_url)
            if (
                target.scheme not in {"http", "https"}
                or not target.hostname
                or target.username
                or target.password
                or target.query
                or target.fragment
                or target.path.rstrip("/") not in {"/v1", "/compatible-mode/v1"}
            ):
                raise ValueError(
                    "Gateway URL requires /v1 or /compatible-mode/v1 without credentials"
                )
            if target.scheme == "http" and not (
                self.allow_http_gateway
                and target.hostname == "aigw.dlut.edu.cn"
                and target.port in {None, 80}
            ):
                raise ValueError("HTTP is only allowed for the explicitly enabled DLUT gateway")
        if self.model_mode == "gateway":
            if self.gateway_key_url and (
                self.gateway_key_url.rstrip("/") != self.gateway_url.rstrip("/")
            ):
                raise ValueError(
                    "Gateway changed; configure its API key locally before enabling it"
                )
            if (
                not isinstance(self.gateway_key, str)
                or not self.gateway_key
                or len(self.gateway_key) > 8192
                or not self.gateway_key.isascii()
                or any(c.isspace() for c in self.gateway_key)
            ):
                raise ValueError("Configure a fresh gateway API key locally")
            if not self.gateway_model or len(self.gateway_model) > 128:
                raise ValueError("Invalid gateway model name")
            if not self.usage_path or self.usage_path == ":memory:":
                raise ValueError("Live model usage requires a persistent local ledger")
        for name in (
            "java_timeout",
            "turn_timeout",
            "max_model_calls",
            "max_tool_calls",
            "max_sessions",
            "session_ttl",
            "max_concurrent",
            "max_response_bytes",
            "model_timeout",
            "max_input_bytes",
            "max_output_tokens",
            "daily_requests",
            "user_daily_requests",
            "requests_per_minute",
            "daily_token_budget",
        ):
            value = getattr(self, name)
            is_timeout = name in {"java_timeout", "turn_timeout", "session_ttl", "model_timeout"}
            if (
                (type(value) not in {int, float} if is_timeout else type(value) is not int)
                or not math.isfinite(value)
                or value <= 0
            ):
                raise ValueError(f"{name} must be positive")
        if self.requests_per_minute > 20:
            raise ValueError("Local gateway request limit must not exceed 20 RPM")

    @classmethod
    def from_env(cls):
        root = Path(__file__).resolve().parents[2]
        config_path = Path(os.getenv("TF_AGENT_CONFIG", root / "config/local/agent.json"))
        values = {}
        if config_path.exists():
            values = json.loads(config_path.read_text("utf-8"))
            allowed = {
                "gateway_url",
                "gateway_model",
                "gateway_key",
                "gateway_key_url",
                "allow_http_gateway",
                "allow_private_model_data",
                "daily_requests",
                "user_daily_requests",
                "requests_per_minute",
                "daily_token_budget",
            }
            if not isinstance(values, dict) or values.keys() - allowed:
                raise ValueError("Unexpected fields in local Agent configuration")
            if values.get("gateway_key") and not values.get("gateway_key_url"):
                # Legacy credentials belonged to the previously explicit endpoint.
                values["gateway_key_url"] = values.get("gateway_url", "http://aigw.dlut.edu.cn/v1")
        values["usage_path"] = str(root / "agent/.runtime/model-usage.sqlite3")
        values["java_url"] = os.getenv("TF_AGENT_JAVA_URL", "http://127.0.0.1:8080")
        values["model_mode"] = os.getenv("TF_AGENT_MODEL_MODE", "disabled")
        if os.getenv("TF_AGENT_API_KEY"):
            values["gateway_key"] = os.environ["TF_AGENT_API_KEY"]
            values["gateway_key_url"] = values.get("gateway_url", cls.gateway_url)
        return cls(**values)
