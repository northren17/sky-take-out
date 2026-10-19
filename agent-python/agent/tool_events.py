"""LangGraph tool lifecycle adaptation and safe result classification.

The installed LangGraph 1.2.x ``tools`` stream reports normal start/end/error
callbacks, but cancellation and an outer middleware timeout can end after only a
start event. This adapter therefore observes the same ``ToolCallRequest`` handler
boundary used by LangGraph. It emits one start and one terminal event per actual
handler invocation, including parallel calls.
"""

from __future__ import annotations

import asyncio
import inspect
import logging
import re
import time
from collections.abc import Awaitable, Callable
from typing import Any
from uuid import uuid4

from langchain_core.messages import ToolMessage

from agent.events import (
    ToolBusinessStatus,
    ToolExecutionStatus,
    ToolLifecycleEvent,
    redact_sensitive_text,
    safe_tool_args,
)


logger = logging.getLogger("voice_agent.tool_events")

LANGGRAPH_ADAPTER_VERSION = "langgraph-1.2-tools-boundary-v1"
TICKET_REASON_SUMMARY = "售后问题已记录"
TICKET_TOOLS = frozenset({"ticket_create", "ticket_lookup", "ticket_urge"})
ToolEventReporter = Callable[[ToolLifecycleEvent], Awaitable[None] | None]
_SAFE_ERROR_CODE_RE = re.compile(r"^[a-z0-9_.-]{1,64}$")


def _safe_error_code(value: Any) -> str:
    normalized = str(value or "").strip().lower()
    return normalized if _SAFE_ERROR_CODE_RE.fullmatch(normalized) else "execution_error"


def _artifact_from(response: Any) -> dict[str, Any]:
    artifact = getattr(response, "artifact", None)
    return artifact if isinstance(artifact, dict) else {}


def _business_status(artifact: dict[str, Any]) -> ToolBusinessStatus:
    raw = str(artifact.get("business_status") or "").lower()
    aliases = {
        "hit": ToolBusinessStatus.HIT,
        "found": ToolBusinessStatus.HIT,
        "no_match": ToolBusinessStatus.NO_MATCH,
        "not_found": ToolBusinessStatus.NO_MATCH,
        "empty": ToolBusinessStatus.NO_MATCH,
        "invalid_arguments": ToolBusinessStatus.INVALID_ARGUMENTS,
        "completed": ToolBusinessStatus.COMPLETED,
    }
    if raw in aliases:
        return aliases[raw]

    code = str(artifact.get("code") or "").upper()
    if "NOT_FOUND" in code or code in {"NO_TICKETS", "NO_RESULTS", "POLICY_REJECTED"}:
        return ToolBusinessStatus.NO_MATCH
    if code in {"INVALID_ARGUMENT", "AMBIGUOUS_QUERY"}:
        return ToolBusinessStatus.INVALID_ARGUMENTS
    if artifact.get("success") is True:
        return ToolBusinessStatus.HIT if artifact.get("data") else ToolBusinessStatus.COMPLETED
    return ToolBusinessStatus.UNKNOWN


def _execution_failed(artifact: dict[str, Any]) -> bool:
    if str(artifact.get("execution_status") or "").lower() == "failed":
        return True
    code = str(artifact.get("code") or "").upper()
    return code in {
        "STORAGE_READ_FAILED",
        "STORAGE_WRITE_FAILED",
        "EXTERNAL_SERVICE_FAILED",
        "POLICY_SEARCH_FAILED",
    }


def _result_summary(artifact: dict[str, Any]) -> dict[str, Any]:
    summary: dict[str, Any] = {}
    code = artifact.get("code")
    if isinstance(code, str) and code:
        summary["code"] = redact_sensitive_text(code, limit=64)
    data = artifact.get("data")
    if isinstance(data, dict):
        for key in ("count", "result_count", "citation_count", "changed", "debounced"):
            value = data.get(key)
            if isinstance(value, (bool, int)):
                summary[key] = value
    citations = artifact.get("citations")
    if isinstance(citations, list):
        summary["citation_count"] = len(citations)
    return summary


def _pick(source: dict[str, Any], fields: tuple[str, ...]) -> dict[str, Any]:
    return {field: source.get(field) for field in fields if field in source}


def _safe_order(source: Any) -> dict[str, Any] | None:
    if not isinstance(source, dict):
        return None
    order = _pick(
        source,
        ("order_id", "customer_id", "status", "placed_at", "payment_status", "total_amount"),
    )
    items = source.get("items")
    if isinstance(items, list):
        order["items"] = [
            _pick(item, ("name", "quantity", "unit_price"))
            for item in items[:20]
            if isinstance(item, dict)
        ]
    after_sales = source.get("after_sales")
    if isinstance(after_sales, dict):
        order["after_sales"] = _pick(
            after_sales, ("eligible", "return_deadline", "refund_status")
        )
    return order or None


def _safe_ticket(source: Any) -> dict[str, Any] | None:
    if not isinstance(source, dict):
        return None
    ticket = _pick(
        source,
        (
            "ticket_id", "customer_id", "order_id", "status", "priority",
            "created_at", "updated_at", "urge_count", "last_urged_at",
        ),
    )
    if "reason" in source:
        ticket["reason"] = TICKET_REASON_SUMMARY
    return ticket or None


def _result_data(tool_name: str, artifact: dict[str, Any]) -> dict[str, Any] | None:
    """Project tool artifacts onto the fields explicitly allowed on the UI wire."""

    data = artifact.get("data")
    if not isinstance(data, dict):
        return None
    if tool_name == "customer_lookup":
        customer = data.get("customer")
        if not isinstance(customer, dict):
            return None
        safe_customer = _pick(
            customer,
            (
                "customer_id", "name", "masked_phone", "member_level", "registered_at",
                "order_ids", "after_sales_summary",
            ),
        )
        return {"customer": safe_customer} if safe_customer else None
    if tool_name == "order_lookup":
        orders = data.get("orders")
        if isinstance(orders, list):
            safe_orders = []
            for source in orders[:10]:
                if not isinstance(source, dict):
                    continue
                order = _pick(
                    source, ("order_number", "status", "created_at", "amount")
                )
                items = source.get("items")
                if isinstance(items, list):
                    order["items"] = [
                        _pick(item, ("name", "quantity"))
                        for item in items[:30]
                        if isinstance(item, dict)
                    ]
                if order:
                    safe_orders.append(order)
            return {"orders": safe_orders} if safe_orders else None
        order = _safe_order(data.get("order"))
        return {"order": order} if order else None
    if tool_name == "coupon_lookup":
        coupons = data.get("coupons")
        if not isinstance(coupons, list):
            return None
        safe_coupons = [
            _pick(
                coupon,
                (
                    "name", "type", "rule", "scope_type", "status",
                    "valid_start_time", "valid_end_time",
                ),
            )
            for coupon in coupons[:50]
            if isinstance(coupon, dict)
        ]
        return {
            "total": data.get("total") if isinstance(data.get("total"), int) else None,
            "page": data.get("page") if isinstance(data.get("page"), int) else None,
            "page_size": data.get("page_size")
            if isinstance(data.get("page_size"), int) else None,
            "coupons": safe_coupons,
        }
    if tool_name in TICKET_TOOLS:
        sources: list[Any]
        if isinstance(data.get("tickets"), list):
            sources = data["tickets"]
        elif isinstance(data.get("ticket"), dict):
            sources = [data["ticket"]]
        elif data.get("ticket_id"):
            sources = [data]
        elif tool_name == "ticket_lookup" and isinstance(data.get("tickets"), list):
            sources = data["tickets"]
        else:
            sources = []
        tickets = [ticket for item in sources if (ticket := _safe_ticket(item))]
        return {"tickets": tickets} if tickets or "tickets" in data else None
    return None


class LangGraphToolLifecycleAdapter:
    """Emit an idempotent application lifecycle around LangGraph tool handlers."""

    def __init__(self, reporter: ToolEventReporter | None) -> None:
        self._reporter = reporter
        self._calls: dict[str, dict[str, Any]] = {}

    @staticmethod
    def _state_key(tool_call: dict[str, Any]) -> str:
        # One middleware request is one execution. Object identity keeps two
        # malformed provider calls with the same tool_call id independent.
        return f"request:{id(tool_call)}"

    async def _emit(self, event: ToolLifecycleEvent) -> None:
        if self._reporter is None:
            return
        try:
            result = self._reporter(event)
            if inspect.isawaitable(result):
                await result
        except Exception as exc:
            # Observability must never retry or suppress the business tool call.
            logger.error("Tool lifecycle reporter failed: error_type=%s", type(exc).__name__)

    async def called(self, tool_call: dict[str, Any]) -> str:
        state_key = self._state_key(tool_call)
        current = self._calls.get(state_key)
        if current is not None:
            return str(current["call_id"])
        tool_call_id = str(tool_call.get("id") or uuid4())
        tool_name = str(tool_call.get("name") or "unknown")
        call_id = str(uuid4())
        state = {
            "call_id": call_id,
            "tool_call_id": tool_call_id,
            "tool_name": tool_name,
            "safe_args": safe_tool_args(tool_name, tool_call.get("args")),
            "started_at": time.monotonic(),
            "terminal": False,
        }
        self._calls[state_key] = state
        await self._emit(
            ToolLifecycleEvent(
                event_type="tool.called",
                call_id=call_id,
                tool_call_id=tool_call_id,
                tool_name=tool_name,
                status=ToolExecutionStatus.RUNNING,
                safe_args=state["safe_args"],
            )
        )
        return call_id

    async def finished(self, tool_call: dict[str, Any], response: Any) -> None:
        artifact = _artifact_from(response)
        if isinstance(response, ToolMessage) and response.status == "error":
            await self.failed(
                tool_call,
                code="tool_message_error",
                message="工具执行返回错误",
                result_summary=_result_summary(artifact),
            )
            return
        if _execution_failed(artifact):
            await self.failed(
                tool_call,
                code=str(artifact.get("code") or "tool_reported_failure").lower(),
                message=(
                    "工单工具执行失败"
                    if tool_call.get("name") in TICKET_TOOLS
                    else str(artifact.get("message") or "工具执行失败")
                ),
                result_summary=_result_summary(artifact),
            )
            return
        await self._terminal(
            tool_call,
            event_type="tool.finished",
            status=ToolExecutionStatus.COMPLETED,
            business_status=_business_status(artifact),
            result_summary=_result_summary(artifact),
            result_data=_result_data(str(tool_call.get("name") or "unknown"), artifact),
            citations=artifact.get("citations") if isinstance(artifact.get("citations"), list) else [],
        )

    async def failed(
        self,
        tool_call: dict[str, Any],
        *,
        code: str,
        message: str,
        result_summary: dict[str, Any] | None = None,
    ) -> None:
        await self._terminal(
            tool_call,
            event_type="tool.failed",
            status=ToolExecutionStatus.FAILED,
            error={
                "code": _safe_error_code(code),
                "message": redact_sensitive_text(message),
            },
            result_summary=result_summary,
        )

    async def cancelled(self, tool_call: dict[str, Any], *, reason: str) -> None:
        await self._terminal(
            tool_call,
            event_type="tool.cancelled",
            status=ToolExecutionStatus.CANCELLED,
            error={"code": reason, "message": "工具调用已取消"},
        )

    async def _terminal(
        self,
        tool_call: dict[str, Any],
        *,
        event_type: str,
        status: ToolExecutionStatus,
        business_status: ToolBusinessStatus = ToolBusinessStatus.UNKNOWN,
        result_summary: dict[str, Any] | None = None,
        result_data: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        citations: list[dict[str, Any]] | None = None,
    ) -> None:
        state_key = self._state_key(tool_call)
        state = self._calls.get(state_key)
        if state is None:
            await self.called(tool_call)
            state = self._calls[state_key]
        if state["terminal"]:
            return
        state["terminal"] = True
        duration_ms = max(0, round((time.monotonic() - state["started_at"]) * 1000))
        await self._emit(
            ToolLifecycleEvent(
                event_type=event_type,  # type: ignore[arg-type]
                call_id=state["call_id"],
                tool_call_id=state["tool_call_id"],
                tool_name=state["tool_name"],
                status=status,
                safe_args=state["safe_args"],
                duration_ms=duration_ms,
                business_status=business_status,
                result_summary=result_summary,
                result_data=result_data,
                error=error,
                citations=tuple(citations or []),
            )
        )
