"""VoiceAgent 的 FastAPI 应用入口。

本模块只负责网络边界：创建应用、配置开发环境 CORS、暴露健康检查，并把每条
``/ws/voice`` 连接交给独立的 ``VoiceSession``。ASR、Agent、TTS 的业务时序
全部留在 Session 层，避免 WebSocket 路由演变成难以测试的巨型函数。

WebSocket 使用两种帧：浏览器发送和接收的 PCM 音频使用二进制帧；状态、转写、
回答文本及中断命令使用 JSON 文本帧。
"""

import json
import asyncio
import logging
from pathlib import Path
from time import monotonic
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ValidationError

from agent.config import get_settings
from agent.events import redact_sensitive_text, wire_event
from agent.internal_auth import (
    InternalAgentError,
    JavaInternalClient,
    ReplayGuard,
    SessionAuthorizationError,
    VoiceConnectionRegistry,
    verify_internal_request,
)
from agent.session import (
    OutgoingBackpressureError,
    ProviderConnectionError,
    VoiceSession,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
ERROR_LOG_PATH = PROJECT_ROOT / "logs" / "voice-agent-error.log"


def configure_logging() -> None:
    """配置控制台日志和本地错误日志。

    Uvicorn 可能已经为根 Logger 安装 Handler，因此这里不能只依赖
    ``basicConfig(filename=...)``。函数会显式检查目标 FileHandler，不存在时
    才添加，避免模块重复加载后同一错误被写入多次。

    错误文件固定为 ``logs/voice-agent-error.log``，只接收 ERROR 及以上级别；
    普通启动信息仍由控制台 Handler 处理。
    """

    logging.basicConfig(level=logging.INFO)
    ERROR_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    target = str(ERROR_LOG_PATH.resolve()).casefold()
    root_logger = logging.getLogger()
    # 防止开发环境重复导入模块时为同一文件添加多个 Handler。
    for handler in root_logger.handlers:
        if not isinstance(handler, logging.FileHandler):
            continue
        if str(Path(handler.baseFilename).resolve()).casefold() == target:
            return

    # 文件只记录错误，日常 INFO 日志仍输出到启动终端。
    error_handler = logging.FileHandler(ERROR_LOG_PATH, encoding="utf-8")
    error_handler.setLevel(logging.ERROR)
    error_handler.setFormatter(
        logging.Formatter(
            "%(asctime)s | %(levelname)s | %(name)s | %(message)s"
        )
    )
    root_logger.addHandler(error_handler)


configure_logging()


def configuration_error_message(error: ValidationError) -> str:
    missing = sorted({
        str(item["loc"][-1])
        for item in error.errors(include_url=False, include_input=False)
        if item.get("type") == "missing" and item.get("loc")
    })
    if missing:
        return (
            f"服务端配置不完整：缺少 {'、'.join(missing)}。"
            "请检查项目根目录 .env 并重启后端。"
        )
    return "服务端配置无效。请检查项目根目录 .env 并重启后端。"

app = FastAPI(title="Voice Agent API", version="0.1.0")
voice_connections = VoiceConnectionRegistry()
internal_replay_guard = ReplayGuard()
# 开发阶段只允许本机 Vite 页面跨域访问后端。
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/health")
async def health() -> dict[str, str]:
    """返回最小健康状态，供启动脚本或存活探针检查。

    当前检查只证明 FastAPI 进程能够响应，不主动连接三个外部模型服务，因而
    不代表 ASR、DeepSeek 和 TTS 都可用。
    """

    return {"status": "ok"}


@app.websocket("/ws/voice")
async def voice_socket(websocket: WebSocket) -> None:
    """Require a one-time Java ticket before creating providers or accepting audio."""
    origin = websocket.headers.get("origin")
    await websocket.accept()
    session: VoiceSession | None = None
    connection = None
    client: JavaInternalClient | None = None
    session_id: str | None = None
    capability: str | None = None
    authorization_task: asyncio.Task | None = None
    try:
        settings = get_settings()
        allowed_origins = {
            value.strip()
            for value in settings.agent_allowed_origins.split(",")
            if value.strip()
        }
        if not origin or origin not in allowed_origins:
            await websocket.close(code=4403, reason="Origin not allowed")
            return
        try:
            first_message = await asyncio.wait_for(websocket.receive(), timeout=5)
        except TimeoutError:
            await websocket.close(code=4401, reason="Authentication timed out")
            return
        if first_message.get("type") == "websocket.disconnect":
            return
        text = first_message.get("text")
        if not text or len(text) > 4096 or first_message.get("bytes") is not None:
            await websocket.close(code=4401, reason="Authentication required")
            return
        try:
            auth_message = json.loads(text)
        except json.JSONDecodeError:
            await websocket.close(code=4401, reason="Invalid authentication message")
            return
        if not isinstance(auth_message, dict):
            await websocket.close(code=4401, reason="Invalid authentication message")
            return
        ticket = auth_message.get("ticket")
        if auth_message.get("type") != "auth" or not isinstance(ticket, str) or not ticket:
            await websocket.close(code=4401, reason="Authentication required")
            return

        if not settings.agent_python_hmac_secret:
            raise InternalAgentError("Python-to-Java authentication is not configured")
        client = JavaInternalClient(
            settings.agent_java_internal_url,
            settings.agent_python_hmac_secret.get_secret_value(),
        )
        session_id = str(uuid4())
        connection = voice_connections.register_pending(session_id, websocket)
        grant = await client.redeem(ticket, session_id)
        capability = grant.get("sessionCapability")
        if not isinstance(capability, str) or not capability:
            raise SessionAuthorizationError("Invalid session grant")
        await client.validate(session_id, capability)

        session = VoiceSession(
            websocket,
            settings,
            session_id=session_id,
            business_client=client,
            session_capability=capability,
        )
        if not await voice_connections.activate(
            session_id, connection, session, capability
        ):
            return

        session_started_at = monotonic()
        last_user_activity_at = session_started_at
        last_heartbeat_at = session_started_at

        async def monitor_authorization() -> None:
            while True:
                await asyncio.sleep(20)
                now = monotonic()
                if now - session_started_at >= 30 * 60:
                    raise SessionAuthorizationError("Voice session reached its maximum lifetime")
                if now - last_user_activity_at >= 5 * 60:
                    raise SessionAuthorizationError("Voice session was idle for too long")
                if now - last_heartbeat_at >= 60:
                    raise SessionAuthorizationError("Voice session heartbeat was lost")
                await client.validate(session_id, capability)

        authorization_task = asyncio.create_task(
            monitor_authorization(), name=f"voice-auth-{session_id}"
        )
        while True:
            receive_task = asyncio.create_task(websocket.receive())
            failure_task = asyncio.create_task(session.wait_failure())
            done, pending = await asyncio.wait(
                {receive_task, failure_task, authorization_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                if task is not authorization_task:
                    task.cancel()
            for task in pending:
                if task is authorization_task:
                    continue
                try:
                    await task
                except asyncio.CancelledError:
                    pass
            if authorization_task in done:
                authorization_task.result()
            if failure_task in done:
                raise failure_task.result()
            message = receive_task.result()
            if message.get("type") == "websocket.disconnect":
                raise WebSocketDisconnect(code=message.get("code", 1000))
            now = monotonic()
            # 二进制帧始终视为麦克风 PCM 音频，直接交给实时 ASR。
            if audio := message.get("bytes"):
                last_user_activity_at = now
                await session.receive_audio(audio)
                continue
            text = message.get("text")
            if not text:
                continue
            # 文本帧仅承载中断、停止等控制协议。
            control = json.loads(text)
            if not isinstance(control, dict):
                continue
            if control.get("type") == "heartbeat":
                last_heartbeat_at = now
                await session.heartbeat()
            elif control.get("type") == "interrupt":
                last_user_activity_at = now
                await session.interrupt()
            elif control.get("type") == "session.stop":
                break
    except SessionAuthorizationError:
        try:
            await websocket.close(code=4401, reason="Session authorization expired")
        except RuntimeError:
            pass
    except InternalAgentError as exc:
        logging.error("Voice authorization unavailable: status=%s", exc.status_code)
        try:
            await websocket.close(code=1013, reason="Voice service temporarily unavailable")
        except RuntimeError:
            pass
    except WebSocketDisconnect as exc:
        if exc.code not in {1000, 1001}:
            logging.info("Voice WebSocket disconnected with code %s", exc.code)
    except ValidationError as exc:
        message = configuration_error_message(exc)
        logging.error("Voice WebSocket configuration invalid: %s", message)
        try:
            await websocket.send_json(
                wire_event(
                    "error",
                    session_id="uninitialized",
                    turn_id=None,
                    source="configuration",
                    code="configuration_error",
                    message=message,
                    recoverable=False,
                )
            )
        except (RuntimeError, WebSocketDisconnect):
            pass
    except Exception as exc:
        logging.error("Voice WebSocket failed: error_type=%s", type(exc).__name__)
        try:
            if session and not isinstance(
                exc, (OutgoingBackpressureError, ProviderConnectionError)
            ):
                await session.report_server_error("Voice WebSocket 执行失败")
            elif session is None:
                await websocket.send_json(
                    wire_event(
                        "error",
                        session_id="uninitialized",
                        turn_id=None,
                        source="server",
                        code="internal_error",
                        message=redact_sensitive_text(exc),
                    )
                )
        except (RuntimeError, WebSocketDisconnect, OutgoingBackpressureError):
            pass
    finally:
        if authorization_task:
            authorization_task.cancel()
            try:
                await authorization_task
            except asyncio.CancelledError:
                pass
        if session_id and connection:
            await voice_connections.remove(session_id, connection)
        if session_id and capability and client:
            try:
                await client.close(session_id, capability)
            except (InternalAgentError, SessionAuthorizationError):
                logging.info("Voice session close was already revoked or unavailable")


class RevokeSessionsRequest(BaseModel):
    sessionIds: list[str]


@app.post("/internal/agent/v1/voice-sessions/revoke")
async def revoke_voice_sessions(request: Request) -> dict[str, int]:
    settings = get_settings()
    raw_body = await request.body()
    target = request.url.path
    if request.url.query:
        target += "?" + request.url.query
    secret = (
        settings.agent_java_hmac_secret.get_secret_value()
        if settings.agent_java_hmac_secret
        else ""
    )
    try:
        await verify_internal_request(
            secret=secret,
            method=request.method,
            target=target,
            body=raw_body,
            timestamp=request.headers.get("X-Agent-Timestamp"),
            nonce=request.headers.get("X-Agent-Nonce"),
            signature=request.headers.get("X-Agent-Signature"),
            replay_guard=internal_replay_guard,
        )
        payload = RevokeSessionsRequest.model_validate_json(raw_body)
        if not payload.sessionIds or len(payload.sessionIds) > 100:
            raise InternalAgentError("Invalid session revoke request", status_code=400)
        closed = await voice_connections.revoke(payload.sessionIds)
        return {"closedSessions": closed}
    except InternalAgentError as exc:
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from None
