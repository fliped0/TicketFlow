from dataclasses import dataclass
from typing import Annotated, Literal

from pydantic import AfterValidator, BaseModel, ConfigDict, Field, ValidationError

from .errors import AgentError


def bigint(value: str) -> str:
    if int(value) > 9223372036854775807:
        raise ValueError("ID exceeds signed BIGINT")
    return value


Id = Annotated[str, Field(pattern=r"^[1-9][0-9]{0,18}$"), AfterValidator(bigint)]


class StrictInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Page(StrictInput):
    page: int = Field(default=1, ge=1, le=10000)
    size: int = Field(default=10, ge=1, le=20)


class EventSearch(Page):
    keyword: str | None = Field(default=None, max_length=100)
    city: str | None = Field(default=None, max_length=64)
    category: str | None = Field(default=None, max_length=32)


class EventId(StrictInput):
    eventId: Id


class Sessions(Page):
    eventId: Id


class Tiers(Page):
    sessionId: Id


class Orders(Page):
    status: Literal["PENDING", "PAID", "CANCELLED", "CLOSED", "REFUNDED"] | None = None


class OrderId(StrictInput):
    orderId: Id


class RuleSearch(StrictInput):
    query: str = Field(min_length=1, max_length=2000)
    limit: int = Field(default=3, ge=1, le=5)


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    inputs: type[StrictInput]
    path: str
    schema: str

    def bind(self, arguments: dict) -> tuple[str, dict]:
        try:
            values = self.inputs.model_validate(arguments).model_dump(exclude_none=True)
        except ValidationError:
            raise AgentError("INVALID_TOOL_ARGUMENTS", 422, "工具参数无效") from None
        path = self.path
        for key in tuple(values):
            if "{" + key + "}" in path:
                path = path.replace("{" + key + "}", values.pop(key))
        return path, values


TOOLS = {
    t.name: t
    for t in (
        Tool(
            "search_events",
            "查询上架活动；只返回指定页，不支持日期或价格筛选",
            EventSearch,
            "/api/v1/events",
            "EventPageResponse",
        ),
        Tool(
            "get_event",
            "按活动 ID 查询公开详情",
            EventId,
            "/api/v1/events/{eventId}",
            "EventResponse",
        ),
        Tool(
            "list_sessions",
            "查询活动的场次及开售时间",
            Sessions,
            "/api/v1/events/{eventId}/sessions",
            "SessionPageResponse",
        ),
        Tool(
            "list_tiers",
            "查询场次票档、整数分价格与查询时刻可售库存",
            Tiers,
            "/api/v1/sessions/{sessionId}/tiers",
            "TierPageResponse",
        ),
        Tool(
            "list_my_orders",
            "查询当前认证用户的订单",
            Orders,
            "/api/v1/orders",
            "OrderPageResponse",
        ),
        Tool(
            "get_my_order",
            "查询当前认证用户的一笔订单",
            OrderId,
            "/api/v1/orders/{orderId}",
            "OrderResponse",
        ),
        Tool(
            "search_rules",
            "检索本项目模拟票务规则及出处；不判断某笔订单，不执行交易，未知政策明确拒答",
            RuleSearch,
            "/knowledge/rules",
            "",
        ),
        Tool(
            "prepare_cancel",
            "准备本人待支付订单的取消确认卡；不会执行取消",
            OrderId,
            "/agent/confirmations/cancel/{orderId}",
            "",
        ),
        Tool(
            "prepare_refund",
            "准备本人已支付订单的模拟退款确认卡；不会执行退款",
            OrderId,
            "/agent/confirmations/refund/{orderId}",
            "",
        ),
    )
}


def get_tool(name: str) -> Tool:
    if name not in TOOLS:
        raise AgentError("UNKNOWN_TOOL", 422, "不支持该工具；仅开放查询、规则检索和准备确认卡")
    return TOOLS[name]


def definitions() -> list[dict]:
    return [
        {"name": t.name, "description": t.description, "parameters": t.inputs.model_json_schema()}
        for t in TOOLS.values()
    ]
