import os
from dataclasses import dataclass
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

    def __post_init__(self):
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
        if self.model_mode not in {"disabled", "demo"}:
            raise ValueError(
                "Only disabled/demo modes are available; live models are not configured"
            )
        for name in (
            "java_timeout",
            "turn_timeout",
            "max_model_calls",
            "max_tool_calls",
            "max_sessions",
            "session_ttl",
            "max_concurrent",
            "max_response_bytes",
        ):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be positive")

    @classmethod
    def from_env(cls):
        return cls(
            java_url=os.getenv("TF_AGENT_JAVA_URL", "http://127.0.0.1:8080"),
            model_mode=os.getenv("TF_AGENT_MODEL_MODE", "disabled"),
        )
