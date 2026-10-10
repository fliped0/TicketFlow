import json
import uuid
from datetime import UTC, datetime
from pathlib import Path

import httpx
from jsonschema import Draft202012Validator, FormatChecker

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

    async def trade(self, row, token):
        """Only a persisted, explicitly approved confirmation reaches this method."""
        suffix = {"cancel": "cancel", "refund": "refunds"}[row["operation"]]
        path = f"/api/v1/orders/{row['order_id']}/{suffix}"
        try:
            async with self.http.stream(
                "POST",
                path,
                json={},
                headers={"Authorization": f"Bearer {token}", "Idempotency-Key": row["key"]},
            ) as response:
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > self.settings.max_response_bytes:
                        raise ValueError("oversize")
                status = response.status_code
            body = json.loads(raw)
            if status == 200:
                validator = Draft202012Validator(
                    {"$ref": "#/components/schemas/TradeResponse", **CONTRACT},
                    format_checker=FormatChecker(),
                )
                if not validator.is_valid(body):
                    raise ValueError("contract")
                data = body["data"]
                expected = {"CANCELLED", "CLOSED"} if suffix == "cancel" else {"REFUNDED"}
                snapshot = json.loads(row["snapshot"])
                if (
                    data["orderId"] != row["order_id"]
                    or data["amountFen"] != snapshot["amountFen"]
                    or data["operationStatus"] not in expected
                    or (suffix == "refunds" and (not data["refundId"] or not data["paymentId"]))
                ):
                    raise ValueError("transaction identity")
                return "SUCCEEDED", {
                    "code": "OK",
                    "data": data,
                    "replayed": body["replayed"],
                    "source": {"method": "POST", "path": path, "traceId": body["traceId"]},
                }
            known = {
                "ORDER_STATE_CONFLICT",
                "ORDER_EXPIRED",
                "REFUND_CLOSED",
                "REFUND_SIMULATED_FAILURE",
                "NOT_FOUND",
            }
            if (
                status in {404, 409, 422}
                and isinstance(body, dict)
                and body.get("code") in known
                and body.get("data") is None
                and type(body.get("replayed")) is bool
                and isinstance(body.get("traceId"), str)
            ):
                uuid.UUID(body["traceId"])
                return "REJECTED", {
                    "code": body["code"],
                    "message": "业务服务拒绝了本次操作",
                    "replayed": body["replayed"],
                    "source": {"method": "POST", "path": path, "traceId": body["traceId"]},
                }
            raise ValueError("indeterminate response")
        except (httpx.HTTPError, OSError, ValueError, TypeError, KeyError):
            return "UNKNOWN", {
                "code": "TRADE_RESULT_UNKNOWN",
                "message": "执行结果未确定，请检查原确认记录",
            }

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
