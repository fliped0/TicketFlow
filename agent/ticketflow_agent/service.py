import copy
import json
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import UTC, datetime

from .config import Settings
from .confirmations import ConfirmationStore, card, fingerprint
from .errors import AgentError
from .gateway import GatewayDecision, GatewayModel
from .model import Decision
from .rules import RuleStore, render_rules
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
        self.rules = RuleStore()
        self._confirmations = None

    @property
    def confirmations(self):
        if self._confirmations is None:
            try:
                self._confirmations = ConfirmationStore(self.settings)
            except AgentError:
                raise
            except (OSError, TypeError, ValueError):
                raise AgentError(
                    "CONFIRMATIONS_UNAVAILABLE", 503, "确认仓储不可用，停止交易"
                ) from None
        return self._confirmations

    @staticmethod
    def check_operation(operation, snapshot):
        expected = "PENDING" if operation == "cancel" else "PAID"
        if snapshot["status"] != expected:
            raise AgentError("CONFIRMATION_ORDER_STATE", 409, "当前订单状态不允许准备此操作")
        if operation == "refund":
            try:
                starts = datetime.fromisoformat(
                    snapshot["snapshot"]["startsAt"].replace("Z", "+00:00")
                )
                if starts.tzinfo is None:
                    raise ValueError("Missing timezone")
            except (ValueError, TypeError, AttributeError):
                raise AgentError(
                    "JAVA_INVALID_RESPONSE", 502, "订单开场时间不符合接口契约"
                ) from None
            if datetime.now(UTC) >= starts:
                raise AgentError("CONFIRMATION_REFUND_CLOSED", 409, "已经到开场时间，不能准备退款")

    async def prepare(self, user, token, operation, order_id, session_id):
        order = await self.java.query(get_tool("get_my_order"), {"orderId": order_id}, token)
        self.check_operation(operation, order["data"])
        row = self.confirmations.prepare(user, session_id, operation, order["data"])
        return card(row)

    async def execute(self, user, token, identifier, *, recover=False):
        row, claimed = self.confirmations.claim(identifier, user, recover=recover)
        if not claimed:
            return card(row)
        sent = False
        try:
            if await self.java.identity(token) != user:
                raise AgentError("UNAUTHENTICATED", 401, "认证身份发生变化，请重新登录")
            if not recover:
                order = await self.java.query(
                    get_tool("get_my_order"), {"orderId": row["order_id"]}, token
                )
                if fingerprint(order["data"]) != row["digest"]:
                    self.confirmations.finish(
                        row, "REJECTED", {"code": "CONFIRMATION_SNAPSHOT_CHANGED"}
                    )
                    return card(self.confirmations.get(identifier, user))
                try:
                    self.check_operation(row["operation"], order["data"])
                except AgentError as error:
                    self.confirmations.finish(row, "REJECTED", {"code": error.code})
                    return card(self.confirmations.get(identifier, user))
            sent = True
            state, result = await self.java.trade(row, token)
            self.confirmations.finish(row, state, result)
            return card(self.confirmations.get(identifier, user))
        except BaseException:
            # Cancellation/crash after approval never creates a new key or claims failure.
            self.confirmations.finish(row, "UNKNOWN" if sent or recover else "PENDING")
            raise

    async def query(self, tool_name, arguments, token, session_id=None):
        tool = get_tool(tool_name)
        tool.bind(arguments)
        if tool.name == "search_rules":
            return self.rules.search(arguments)
        if tool.name in {"prepare_cancel", "prepare_refund"}:
            user = await self.java.identity(token)
            prepared = await self.prepare(
                user,
                token,
                tool.name.removeprefix("prepare_"),
                arguments["orderId"],
                session_id or str(uuid.uuid4()),
            )
            return {
                "tool": tool.name,
                "data": prepared,
                "source": {
                    "method": "LOCAL",
                    "path": "/agent/v1/confirmations",
                    "params": {"orderId": arguments["orderId"]},
                },
            }
        return await self.java.query(tool, arguments, token)

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
                            text = "当前仅支持业务查询和项目规则说明，无法完成这项请求。"
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
                    rule_text, citations = render_rules(cards)
                    if rule_text:
                        text = rule_text + (
                            "\n实时业务信息见查询卡片，规则说明不代替该时刻的查询。"
                            if any(c["tool"] != "search_rules" for c in cards)
                            else ""
                        )
                    response = {
                        "sessionId": session_id,
                        "modelMode": self.settings.model_mode,
                        "message": text,
                        "cards": cards,
                    }
                    if any(c["tool"] == "search_rules" for c in cards):
                        response["citations"] = citations
                    confirmations = [c["data"] for c in cards if c["tool"].startswith("prepare_")]
                    if confirmations:
                        response["confirmations"] = confirmations
                        response["message"] += (
                            "\n操作尚未执行，请核对确认卡并通过独立确认入口执行。"
                        )
                    return response
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
                    arguments = call.arguments
                    if call.name == "search_rules":
                        # A model cannot erase the unknown/private part of the user's question.
                        arguments = {**arguments, "query": message}
                    results.append(await self.query(call.name, arguments, token, session_id))
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
