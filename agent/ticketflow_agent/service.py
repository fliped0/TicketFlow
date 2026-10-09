import copy
import json
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass

from .config import Settings
from .errors import AgentError
from .gateway import GatewayDecision, GatewayModel
from .model import Decision
from .tools import definitions, get_tool


@dataclass
class Session:
    user_id: str
    touched: float
    busy: bool = False


class Service:
    def __init__(self, settings: Settings, java, model):
        self.settings, self.java, self.model = settings, java, model
        self.sessions: dict[str, Session] = {}
        self.active = 0

    @contextmanager
    def capacity(self):
        # Accessed only on the application's event loop; no await before reservation.
        if self.active >= self.settings.max_concurrent:
            raise AgentError("AGENT_BUSY", 429, "查询服务繁忙，请稍后再试")
        self.active += 1
        try:
            yield
        finally:
            self.active -= 1

    def session(self, user_id: str, session_id: str | None):
        now = time.monotonic()
        for key, value in list(self.sessions.items()):
            if not value.busy and now - value.touched >= self.settings.session_ttl:
                del self.sessions[key]
        if session_id:
            state = self.sessions.get(session_id)
            if state is None or state.user_id != user_id:
                raise AgentError("SESSION_NOT_FOUND", 404, "会话不存在或已过期")
            if state.busy:
                raise AgentError("SESSION_BUSY", 409, "该会话正在处理请求")
        else:
            if len(self.sessions) >= self.settings.max_sessions:
                raise AgentError("SESSION_CAPACITY", 429, "会话已达上限，请稍后再试")
            session_id = str(uuid.uuid4())
            state = self.sessions[session_id] = Session(user_id, now)
        state.busy = True
        state.touched = now
        return session_id, state

    async def chat(self, user_id, token, message, session_id):
        created = session_id is None
        session_id, state = self.session(user_id, session_id)
        results = []
        try:
            for _ in range(self.settings.max_model_calls):
                if len(json.dumps([message, results], ensure_ascii=False)) > 32000:
                    raise AgentError("CONTEXT_LIMIT", 422, "查询内容过多，请缩小查询范围")
                try:
                    if isinstance(self.model, GatewayModel):
                        decision = await self.model.decide_for(
                            user_id, message, copy.deepcopy(results), definitions()
                        )
                    else:
                        decision = await self.model.decide(
                            message, copy.deepcopy(results), definitions()
                        )
                except AgentError:
                    raise
                except Exception:
                    raise AgentError("MODEL_UNAVAILABLE", 502, "模型服务暂不可用") from None
                if (
                    not isinstance(decision, Decision)
                    or type(decision.finished) is not bool
                    or not isinstance(decision.calls, tuple)
                    or (decision.finished and decision.calls)
                ):
                    raise AgentError("MODEL_INVALID_OUTPUT", 502, "模型输出格式无效")
                if decision.finished:
                    cards = results
                    text = (
                        "查询结果见数据卡片；库存以查询时刻为准。"
                        if results
                        else "本轮没有取得业务数据，无法给出业务结论。"
                    )
                    if isinstance(decision, GatewayDecision):
                        cards = [results[i] for i in decision.indices]
                        if decision.note == "unsupported":
                            text = "当前仅支持活动和订单查询，无法完成这项请求。"
                        elif decision.note == "clarification":
                            labels = {
                                "eventId": "活动编号",
                                "sessionId": "场次编号",
                                "orderId": "订单编号",
                                "keyword": "活动关键词",
                                "city": "城市",
                                "category": "活动类别",
                            }
                            text = "请补充：" + "、".join(
                                labels[k] for k in decision.missing_fields
                            )
                    return {
                        "sessionId": session_id,
                        "modelMode": self.settings.model_mode,
                        "message": text,
                        "cards": cards,
                    }
                if not decision.calls:
                    raise AgentError("MODEL_INVALID_OUTPUT", 502, "模型未给出可执行查询")
                if len(results) + len(decision.calls) > self.settings.max_tool_calls:
                    raise AgentError("TOOL_BUDGET_EXCEEDED", 429, "本轮查询次数已达上限")
                # Validate the complete batch before sending any request.
                for call in decision.calls:
                    if (
                        not hasattr(call, "name")
                        or not isinstance(call.name, str)
                        or not hasattr(call, "arguments")
                        or not isinstance(call.arguments, dict)
                    ):
                        raise AgentError("MODEL_INVALID_OUTPUT", 502, "模型工具调用格式无效")
                    path, params = get_tool(call.name).bind(call.arguments)
                    if isinstance(self.model, GatewayModel) and any(
                        r["tool"] == call.name
                        and r["source"]["path"] == path
                        and r["source"]["params"] == params
                        for r in results
                    ):
                        raise AgentError(
                            "MODEL_REPEATED_QUERY",
                            502,
                            "模型重复了已完成的查询，请重试或使用直接查询入口",
                        )
                    if (
                        isinstance(self.model, GatewayModel)
                        and not self.settings.allow_private_model_data
                        and call.name in {"list_my_orders", "get_my_order"}
                    ):
                        raise AgentError(
                            "PRIVATE_MODEL_DATA_DISABLED", 403, "尚未允许订单查询与模型网关联动"
                        )
                for call in decision.calls:
                    # Recheck account even before public tools after a model wait.
                    if await self.java.identity(token) != user_id:
                        raise AgentError("UNAUTHENTICATED", 401, "认证身份发生变化，请重新登录")
                    results.append(
                        await self.java.query(get_tool(call.name), call.arguments, token)
                    )
            raise AgentError("MODEL_BUDGET_EXCEEDED", 429, "本轮模型调用次数已达上限")
        except BaseException:
            if created:
                self.sessions.pop(session_id, None)
            raise
        finally:
            state.busy = False
            state.touched = time.monotonic()

    def delete_session(self, user_id, session_id):
        session_id, _ = self.session(user_id, session_id)
        del self.sessions[session_id]
