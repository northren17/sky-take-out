"""Authenticated Java service client and in-process voice-session revocation."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import re
import secrets
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

from fastapi import WebSocket, WebSocketDisconnect

from agent.session import VoiceSession


ALLOWED_CLOCK_SKEW_SECONDS = 60
NONCE_TTL_SECONDS = 120
HMAC_NONCE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{22,128}$")
MIN_SECRET_BYTES = 32


class InternalAgentError(RuntimeError):
    def __init__(self, message: str, *, status_code: int = 503) -> None:
        super().__init__(message)
        self.status_code = status_code


class SessionAuthorizationError(RuntimeError):
    pass


def _canonical(method: str, target: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return f"{method}\n{target}\n{timestamp}\n{nonce}\n{body_hash}".encode("utf-8")


def sign_internal_request(
    secret: str, method: str, target: str, timestamp: str, nonce: str, body: bytes
) -> str:
    return hmac.new(
        secret.encode("utf-8"),
        _canonical(method, target, timestamp, nonce, body),
        hashlib.sha256,
    ).hexdigest()


class ReplayGuard:
    """Per-process replay cache; safe for the single-instance Agent deployment."""

    def __init__(self) -> None:
        self._seen: dict[str, float] = {}
        self._lock = asyncio.Lock()

    async def accept(self, nonce: str) -> bool:
        now = time.monotonic()
        async with self._lock:
            expired = [key for key, deadline in self._seen.items() if deadline <= now]
            for key in expired:
                del self._seen[key]
            if nonce in self._seen:
                return False
            self._seen[nonce] = now + NONCE_TTL_SECONDS
            return True


async def verify_internal_request(
    *,
    secret: str,
    method: str,
    target: str,
    body: bytes,
    timestamp: str | None,
    nonce: str | None,
    signature: str | None,
    replay_guard: ReplayGuard,
) -> None:
    if len(secret.encode("utf-8")) < MIN_SECRET_BYTES:
        raise InternalAgentError("Internal service authentication is not configured")
    if (
        timestamp is None
        or not timestamp.isdigit()
        or len(timestamp) != 10
        or nonce is None
        or not HMAC_NONCE_PATTERN.fullmatch(nonce)
        or signature is None
    ):
        raise InternalAgentError("Invalid internal service authentication", status_code=401)
    if abs(int(time.time()) - int(timestamp)) > ALLOWED_CLOCK_SKEW_SECONDS:
        raise InternalAgentError("Expired internal service authentication", status_code=401)

    expected = sign_internal_request(secret, method, target, timestamp, nonce, body)
    if not hmac.compare_digest(expected, signature):
        raise InternalAgentError("Invalid internal service authentication", status_code=401)
    if not await replay_guard.accept(nonce):
        raise InternalAgentError("Replayed internal service request", status_code=401)


class JavaInternalClient:
    def __init__(self, base_url: str, secret: str, *, timeout_seconds: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._secret = secret
        self._timeout = timeout_seconds

    async def redeem(self, ticket: str, session_id: str) -> dict[str, Any]:
        return await self._request(
            "POST",
            "/internal/agent/v1/voice-sessions/redeem",
            {"ticket": ticket, "sessionId": session_id},
        )

    async def validate(self, session_id: str, capability: str) -> dict[str, Any]:
        return await self._request(
            "POST",
            f"/internal/agent/v1/voice-sessions/{session_id}/validate",
            {"sessionCapability": capability},
        )

    async def close(self, session_id: str, capability: str) -> None:
        await self._request(
            "POST",
            f"/internal/agent/v1/voice-sessions/{session_id}/close",
            {"sessionCapability": capability},
        )

    async def recent_orders(
        self, session_id: str, capability: str
    ) -> list[dict[str, Any]]:
        result = await self._request(
            "POST",
            f"/internal/agent/v1/voice-sessions/{session_id}/orders",
            {"sessionCapability": capability},
        )
        if not isinstance(result, list) or not all(
            isinstance(item, dict) for item in result
        ):
            raise InternalAgentError("Java returned an invalid order response")
        return result

    async def coupons(
        self,
        session_id: str,
        capability: str,
        *,
        page: int = 1,
        page_size: int = 20,
        status: int | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "sessionCapability": capability,
            "page": page,
            "pageSize": page_size,
        }
        if status is not None:
            payload["status"] = status
        result = await self._request(
            "POST",
            f"/internal/agent/v1/voice-sessions/{session_id}/coupons",
            payload,
        )
        if not isinstance(result, dict) or not isinstance(result.get("coupons"), list):
            raise InternalAgentError("Java returned an invalid coupon response")
        return result

    async def recommendations(
        self, session_id: str, capability: str
    ) -> dict[str, Any]:
        result = await self._request(
            "POST",
            f"/internal/agent/v1/voice-sessions/{session_id}/recommendations",
            {"sessionCapability": capability},
        )
        if (
            not isinstance(result, dict)
            or not isinstance(result.get("items"), list)
            or not isinstance(result.get("tastes"), list)
        ):
            raise InternalAgentError("Java returned an invalid recommendation response")
        return result

    async def _request(
        self, method: str, path: str, payload: dict[str, Any]
    ) -> Any:
        if not self._base_url or len(self._secret.encode("utf-8")) < MIN_SECRET_BYTES:
            raise InternalAgentError("Java internal service is not configured")
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        timestamp = str(int(time.time()))
        nonce = secrets.token_urlsafe(24)
        signature = sign_internal_request(
            self._secret, method, path, timestamp, nonce, body
        )
        request = urllib.request.Request(
            self._base_url + path,
            data=body,
            method=method,
            headers={
                "Content-Type": "application/json",
                "X-Agent-Timestamp": timestamp,
                "X-Agent-Nonce": nonce,
                "X-Agent-Signature": signature,
            },
        )
        try:
            return await asyncio.to_thread(self._send, request)
        except urllib.error.HTTPError as exc:
            status = exc.code
            if status in {401, 403, 409, 410}:
                raise SessionAuthorizationError(
                    "语音会话认证已失效，请重新登录并发起会话"
                ) from None
            raise InternalAgentError(
                "Java 业务服务暂时不可用", status_code=status
            ) from None
        except (TimeoutError, OSError, urllib.error.URLError):
            raise InternalAgentError("Java 业务服务暂时不可用") from None

    def _send(self, request: urllib.request.Request) -> Any:
        with urllib.request.urlopen(request, timeout=self._timeout) as response:
            raw = response.read(65_537)
            if len(raw) > 65_536:
                raise InternalAgentError("Java internal response exceeds size limit")
            try:
                payload = json.loads(raw or b"{}", parse_float=Decimal)
            except json.JSONDecodeError:
                raise InternalAgentError("Java returned an invalid internal response") from None
            if not isinstance(payload, (dict, list)):
                raise InternalAgentError("Java returned an invalid internal response")
            return payload


@dataclass
class ActiveVoiceConnection:
    websocket: WebSocket
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    session: VoiceSession | None = None
    capability: str | None = None
    revoked: bool = False


class VoiceConnectionRegistry:
    def __init__(self) -> None:
        self._connections: dict[str, ActiveVoiceConnection] = {}

    def register_pending(self, session_id: str, websocket: WebSocket) -> ActiveVoiceConnection:
        if session_id in self._connections:
            raise SessionAuthorizationError("语音会话编号冲突")
        connection = ActiveVoiceConnection(websocket=websocket)
        self._connections[session_id] = connection
        return connection

    async def activate(
        self,
        session_id: str,
        connection: ActiveVoiceConnection,
        session: VoiceSession,
        capability: str,
    ) -> bool:
        async with connection.lock:
            if connection.revoked:
                return False
            connection.session = session
            connection.capability = capability
            await session.start()
            return not connection.revoked

    async def revoke(self, session_ids: list[str]) -> int:
        closed = 0
        for session_id in session_ids:
            connection = self._connections.get(session_id)
            if connection is None:
                continue
            async with connection.lock:
                connection.revoked = True
                if connection.session is not None:
                    await connection.session.close()
                try:
                    await connection.websocket.close(code=4001, reason="Session revoked")
                except (RuntimeError, WebSocketDisconnect):
                    pass
                self._connections.pop(session_id, None)
                closed += 1
        return closed

    async def remove(
        self, session_id: str, connection: ActiveVoiceConnection
    ) -> None:
        async with connection.lock:
            if connection.session is not None:
                await connection.session.close()
            if self._connections.get(session_id) is connection:
                self._connections.pop(session_id, None)
