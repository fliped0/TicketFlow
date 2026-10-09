import json
from datetime import UTC, datetime
from pathlib import Path

import httpx
from jsonschema import Draft202012Validator

from .config import Settings
from .errors import AgentError
from .tools import Tool

CONTRACT = json.loads(Path(__file__).with_name("java_contract.json").read_text(encoding="utf-8"))
VALIDATORS = {
    name: Draft202012Validator({"$ref": f"#/components/schemas/{name}", **CONTRACT})
    for name in (
        "UserResponse",
        "EventPageResponse",
        "EventResponse",
        "SessionPageResponse",
        "TierPageResponse",
        "OrderPageResponse",
        "OrderResponse",
    )
}


def project(value, schema):
    """Only explicitly declared fields cross the Java/assistant boundary."""
    if "$ref" in schema:
        return project(value, CONTRACT["components"]["schemas"][schema["$ref"].split("/")[-1]])
    if isinstance(value, dict):
        return {
            k: project(v, schema["properties"][k])
            for k, v in value.items()
            if k in schema.get("properties", {}) and k not in {"payment", "refund", "version"}
        }
    if isinstance(value, list):
        return [project(item, schema["items"]) for item in value]
    return value


class JavaClient:
    def __init__(self, settings: Settings, transport=None):
        self.settings = settings
        self.http = httpx.AsyncClient(
            base_url=settings.java_url,
            timeout=settings.java_timeout,
            follow_redirects=False,
            trust_env=False,
            transport=transport,
            limits=httpx.Limits(
                max_connections=settings.max_concurrent, max_keepalive_connections=8
            ),
        )

    async def close(self):
        await self.http.aclose()

    async def read(self, path: str, params: dict, token: str, schema: str) -> dict:
        try:
            async with self.http.stream(
                "GET", path, params=params, headers={"Authorization": f"Bearer {token}"}
            ) as response:
                errors = {
                    401: ("UNAUTHENTICATED", 401, "登录已失效，请重新登录"),
                    403: ("FORBIDDEN", 403, "无权执行此查询"),
                    404: ("NOT_FOUND", 404, "未找到可访问的记录"),
                    429: ("JAVA_RATE_LIMITED", 429, "业务服务繁忙，请稍后再试"),
                }
                if response.status_code in errors:
                    raise AgentError(*errors[response.status_code])
                if response.status_code != 200:
                    raise AgentError("JAVA_UNAVAILABLE", 502, "业务服务暂不可用")
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > self.settings.max_response_bytes:
                        raise AgentError("JAVA_RESPONSE_TOO_LARGE", 502, "业务响应超出处理上限")
            body = json.loads(raw)
            if not VALIDATORS[schema].is_valid(body) or body.get("code") != "OK":
                raise ValueError("invalid business envelope")
            return body
        except (httpx.HTTPError, OSError):
            raise AgentError("JAVA_UNAVAILABLE", 502, "业务服务连接失败，请稍后再试") from None
        except (ValueError, TypeError):
            raise AgentError("JAVA_INVALID_RESPONSE", 502, "业务响应格式不符合接口契约") from None

    async def identity(self, token: str) -> str:
        body = await self.read("/api/v1/users/me", {}, token, "UserResponse")
        return body["data"]["userId"]

    async def query(self, tool: Tool, arguments: dict, token: str) -> dict:
        path, params = tool.bind(arguments)
        body = await self.read(path, params, token, tool.schema)
        data_schema = CONTRACT["components"]["schemas"][tool.schema]["properties"]["data"]
        data = project(body["data"], data_schema)
        if "items" in data and (
            len(data["items"]) > params.get("size", 20)
            or data["page"] != params["page"]
            or data["size"] != params["size"]
        ):
            raise AgentError("JAVA_INVALID_RESPONSE", 502, "业务分页响应不符合请求")
        return {
            "tool": tool.name,
            "data": data,
            "source": {
                "method": "GET",
                "path": path,
                "params": params,
                "queriedAt": datetime.now(UTC).isoformat(),
                "traceId": body["traceId"],
            },
        }
