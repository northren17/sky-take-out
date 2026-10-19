"""Provider-neutral events and the VoiceAgent WebSocket wire protocol."""

from __future__ import annotations

import struct
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any, Literal
from uuid import uuid4


AUDIO_FRAME_MAGIC = b"VA1\x00"
AUDIO_FRAME_HEADER_SIZE = 8
WIRE_SCHEMA_VERSION = 2

ToolEventType = Literal[
    "tool.called", "tool.finished", "tool.failed", "tool.cancelled"
]


class ToolExecutionStatus(StrEnum):
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class ToolBusinessStatus(StrEnum):
    UNKNOWN = "unknown"
    HIT = "hit"
    NO_MATCH = "no_match"
    INVALID_ARGUMENTS = "invalid_arguments"
    COMPLETED = "completed"


_PHONE_RE = re.compile(r"(?<!\d)(1\d{2})\d{4}(\d{4})(?!\d)")
_EMAIL_RE = re.compile(
    r"(?i)(?<![A-Z0-9._%+-])([A-Z0-9._%+-]{1,64})@"
    r"([A-Z0-9.-]+\.[A-Z]{2,})(?![A-Z0-9.-])"
)
_SECRET_RE = re.compile(
    r"(?i)(api[_ -]?key|token|authorization|bearer|secret)\s*[:=]?\s*"
    r"[^\s,;]{4,}"
)
_LONG_ID_RE = re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9_-]{18,}(?![A-Za-z0-9])")
_ADDRESS_RE = re.compile(
    r"(?i)(收货地址|联系地址|家庭住址|地址)\s*[:：=]?\s*[^,，;；。\n]{4,}"
)


def redact_sensitive_text(value: Any, *, limit: int = 160) -> str:
    """Return a bounded diagnostic string with common secrets and identifiers masked."""

    text = " ".join(str(value or "").split())
    text = _PHONE_RE.sub(r"\1****\2", text)
    text = _EMAIL_RE.sub(lambda match: f"{match.group(1)[:1]}***@{match.group(2)}", text)
    text = _SECRET_RE.sub(lambda match: f"{match.group(1)}=***", text)
    text = _LONG_ID_RE.sub("***", text)
    text = _ADDRESS_RE.sub(lambda match: f"{match.group(1)}=***", text)
    return text[:limit]


def _masked_identifier(value: Any) -> dict[str, Any]:
    normalized = str(value or "").strip()
    return {
        "provided": bool(normalized),
        "suffix": normalized[-4:] if normalized else "",
        "length": len(normalized),
    }


def safe_tool_args(tool_name: str, arguments: Any) -> dict[str, Any]:
    """Create a display-safe argument summary using a per-tool allowlist."""

    if not isinstance(arguments, dict):
        return {}
    identifiers = {
        "order_id", "order_number", "customer_id", "ticket_id", "identifier"
    }
    free_text = {"query", "reason", "description"}
    allowed = {
        "order_lookup": {"order_number"},
        "logistics_lookup": {"order_id"},
        "customer_lookup": {"identifier"},
        "ticket_create": {"customer_id", "order_id", "reason", "description"},
        "ticket_lookup": {"ticket_id", "customer_id", "limit"},
        "ticket_urge": {"ticket_id", "reason"},
        "policy_search": {"query"},
        "web_search": {"query"},
        "coupon_lookup": {"page", "page_size", "status"},
    }.get(tool_name, set())
    result: dict[str, Any] = {}
    for key in sorted(allowed):
        value = arguments.get(key)
        if key in identifiers:
            result[key] = _masked_identifier(value)
        elif key in free_text:
            normalized = " ".join(str(value or "").split())
            result[key] = {"provided": bool(normalized), "length": len(normalized)}
        elif key == "limit":
            result[key] = value if isinstance(value, int) else None
    return result


@dataclass(frozen=True, slots=True)
class ToolLifecycleEvent:
    """Provider-neutral lifecycle event emitted from the real tool boundary."""

    event_type: ToolEventType
    call_id: str
    tool_call_id: str
    tool_name: str
    status: ToolExecutionStatus
    safe_args: dict[str, Any]
    duration_ms: int | None = None
    business_status: ToolBusinessStatus = ToolBusinessStatus.UNKNOWN
    result_summary: dict[str, Any] | None = None
    result_data: dict[str, Any] | None = None
    error: dict[str, Any] | None = None
    citations: tuple[dict[str, Any], ...] = ()


class TurnState(StrEnum):
    """Lifecycle states for one user utterance and its response."""

    COLLECTING = "collecting"
    AGENT_RUNNING = "agent_running"
    TTS_PENDING = "tts_pending"
    SPEAKING = "speaking"
    COMPLETED = "completed"
    CANCELLED = "cancelled"
    FAILED = "failed"
    TIMED_OUT = "timed_out"


@dataclass(slots=True)
class TurnContext:
    turn_id: int
    state: TurnState
    transcript: str = ""
    final_received: bool = False
    response_text: str = ""
    tts_finished: bool = False
    citations: list[dict[str, Any]] | None = None


@dataclass(slots=True)
class TranscriptEvent:
    text: str
    final: bool
    input_generation: int | None = None


@dataclass(slots=True)
class ProviderError:
    source: Literal["asr", "tts"]
    message: str
    turn_id: int | None = None
    code: str = "provider_error"
    input_generation: int | None = None


@dataclass(slots=True)
class TtsEvent:
    """A TTS event correlated to a local turn and provider response."""

    type: Literal["started", "audio", "audio_finished", "finished"]
    turn_id: int
    response_id: str
    audio: bytes | None = None


@dataclass(slots=True)
class AudioFrame:
    """An internal PCM frame that remains tagged until WebSocket send time."""

    turn_id: int
    audio: bytes


def utc_timestamp() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds")


def wire_event(
    wire_type: str,
    *,
    session_id: str,
    turn_id: int | None,
    **payload: Any,
) -> dict[str, Any]:
    """Build a JSON event with mandatory correlation and time fields."""

    base = {
        "type": wire_type,
        "schema_version": WIRE_SCHEMA_VERSION,
        "event_id": str(uuid4()),
        "session_id": session_id,
        "turn_id": turn_id,
        "timestamp": utc_timestamp(),
    }
    base.update(payload)
    return base


def tool_wire_event(
    event: ToolLifecycleEvent,
    *,
    session_id: str,
    turn_id: int,
    seq: int | None = None,
) -> dict[str, Any]:
    """Serialize a tool event without exposing raw arguments or tool output."""

    return wire_event(
        event.event_type,
        session_id=session_id,
        turn_id=turn_id,
        event_type=event.event_type,
        call_id=event.call_id,
        tool_call_id=event.tool_call_id,
        tool_name=event.tool_name,
        status=event.status,
        duration_ms=event.duration_ms,
        safe_args=event.safe_args,
        business_status=event.business_status,
        result_summary=event.result_summary,
        result_data=event.result_data,
        error=event.error,
        citation_count=len(event.citations),
        seq=seq,
    )


def encode_audio_frame(turn_id: int, audio: bytes) -> bytes:
    """Prefix PCM with a versioned magic value and an unsigned turn id."""

    if turn_id < 0 or turn_id > 0xFFFFFFFF:
        raise ValueError("turn_id is outside the uint32 wire range")
    return AUDIO_FRAME_MAGIC + struct.pack(">I", turn_id) + audio
