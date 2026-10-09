import asyncio
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import Field

from .config import Settings
from .errors import AgentError
from .java import JavaClient
from .model import DemoModel, DisabledModel
from .service import Service
from .tools import StrictInput, definitions, get_tool

log = logging.getLogger("ticketflow.agent")


class Query(StrictInput):
    tool: str = Field(min_length=1, max_length=64)
    arguments: dict = Field(default_factory=dict)


class Chat(StrictInput):
    message: str = Field(min_length=1, max_length=2000)
    sessionId: str | None = Field(default=None, pattern=r"^[0-9a-f-]{36}$")


def bearer(authorization: str | None):
    if not authorization or not re.fullmatch(r"Bearer [A-Za-z0-9._~-]{1,8192}", authorization):
        raise AgentError("UNAUTHENTICATED", 401, "请提供有效的登录令牌")
    return authorization[7:]


def create_app(settings=None, *, transport=None, model=None):
    settings = settings or Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        logging.getLogger("httpx").setLevel(logging.WARNING)
        logging.getLogger("httpcore").setLevel(logging.WARNING)
        log.setLevel(logging.INFO)
        if not log.handlers:
            handler = logging.StreamHandler()
            handler.setFormatter(logging.Formatter("%(asctime)s %(name)s %(message)s"))
            log.addHandler(handler)
        java = JavaClient(settings, transport)
        selected = model or (DemoModel() if settings.model_mode == "demo" else DisabledModel())
        app.state.service = Service(settings, java, selected)
        try:
            yield
        finally:
            await java.close()

    app = FastAPI(
        title="TicketFlow Agent",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )

    @app.middleware("http")
    async def boundary(request: Request, call_next):
        request.state.trace_id = str(uuid.uuid4())
        started = time.monotonic()
        try:
            async with asyncio.timeout(settings.java_timeout):
                body = bytearray()
                async for chunk in request.stream():
                    body.extend(chunk)
                    if len(body) > 16384:
                        return JSONResponse(
                            status_code=413,
                            content={
                                "code": "REQUEST_TOO_LARGE",
                                "message": "请求内容过大",
                                "traceId": request.state.trace_id,
                            },
                            headers={
                                "X-Trace-Id": request.state.trace_id,
                                "Cache-Control": "no-store",
                            },
                        )
                request._body = bytes(body)
        except TimeoutError:
            return JSONResponse(
                status_code=408,
                content={
                    "code": "REQUEST_TIMEOUT",
                    "message": "读取请求超时",
                    "traceId": request.state.trace_id,
                },
                headers={"X-Trace-Id": request.state.trace_id, "Cache-Control": "no-store"},
            )
        # Never include headers, query strings, body or raw dependency errors in logs.
        response = await call_next(request)
        response.headers["X-Trace-Id"] = request.state.trace_id
        response.headers["Cache-Control"] = "no-store"
        log.info(
            "request trace=%s status=%s elapsed_ms=%d",
            request.state.trace_id,
            response.status_code,
            int((time.monotonic() - started) * 1000),
        )
        return response

    @app.exception_handler(AgentError)
    async def agent_error(request, error):
        return JSONResponse(
            status_code=error.status,
            content={
                "code": error.code,
                "message": error.message,
                "traceId": request.state.trace_id,
            },
        )

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return await agent_error(request, AgentError("INVALID_REQUEST", 422, "请求参数无效"))

    async def run(request, authorization, action):
        token = bearer(authorization)
        svc = request.app.state.service
        with svc.capacity():
            try:
                async with asyncio.timeout(settings.turn_timeout):
                    user_id = await svc.java.identity(token)
                    data = await action(svc, user_id, token)
                    return {"code": "OK", "data": data, "traceId": request.state.trace_id}
            except TimeoutError:
                raise AgentError("TURN_TIMEOUT", 504, "本轮查询超时，请稍后再试") from None

    Auth = Annotated[str | None, Header()]

    @app.get("/health")
    async def health():
        return {"status": "UP", "modelMode": settings.model_mode, "liveModelEnabled": False}

    @app.get("/agent/v1/tools")
    async def tools(request: Request, authorization: Auth = None):
        async def action(svc, user_id, token):
            return definitions()

        return await run(request, authorization, action)

    @app.post("/agent/v1/query")
    async def query(body: Query, request: Request, authorization: Auth = None):
        async def action(svc, user_id, token):
            return await svc.java.query(get_tool(body.tool), body.arguments, token)

        return await run(request, authorization, action)

    @app.post("/agent/v1/chat")
    async def chat(body: Chat, request: Request, authorization: Auth = None):
        async def action(svc, user_id, token):
            return await svc.chat(user_id, token, body.message, body.sessionId)

        return await run(request, authorization, action)

    @app.delete("/agent/v1/sessions/{session_id}")
    async def delete(session_id: str, request: Request, authorization: Auth = None):
        async def action(svc, user_id, token):
            svc.delete_session(user_id, session_id)
            return {"deleted": True}

        return await run(request, authorization, action)

    return app
