import re
from dataclasses import dataclass, field
from typing import Protocol

from .errors import AgentError


@dataclass(frozen=True)
class Call:
    name: str
    arguments: dict = field(default_factory=dict)


@dataclass(frozen=True)
class Decision:
    calls: tuple[Call, ...] = ()
    # No unverified prose is rendered as a business fact in the foundation batch.
    finished: bool = False


class Model(Protocol):
    async def decide(self, message: str, results: list[dict], tools: list[dict]) -> Decision: ...


class DisabledModel:
    async def decide(self, message, results, tools):
        raise AgentError("MODEL_NOT_CONFIGURED", 503, "尚未配置模型；可使用查询工具入口")


class DemoModel:
    """Explicit command demonstration, not a language model or accuracy evaluation."""

    async def decide(self, message, results, tools):
        if results:
            return Decision(finished=True)
        if message == "查活动":
            return Decision((Call("search_events"),))
        if message == "我的订单":
            return Decision((Call("list_my_orders"),))
        if message.startswith("规则 "):
            return Decision((Call("search_rules", {"query": message[3:]}),))
        match = re.fullmatch(r"(活动|场次|票档|订单) ([1-9][0-9]{0,18})", message)
        if match:
            name, key = {
                "活动": ("get_event", "eventId"),
                "场次": ("list_sessions", "eventId"),
                "票档": ("list_tiers", "sessionId"),
                "订单": ("get_my_order", "orderId"),
            }[match[1]]
            return Decision((Call(name, {key: match[2]}),))
        raise AgentError(
            "DEMO_COMMAND_REQUIRED",
            422,
            "演示模式仅支持：查活动、我的订单、活动 ID、场次 活动ID、"
            "票档 场次ID、订单 ID、规则 问题",
        )
