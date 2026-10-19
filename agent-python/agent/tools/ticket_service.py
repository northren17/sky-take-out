"""Ticket lifecycle rules and LangChain tool adapters for local JSON data."""

from __future__ import annotations

import contextvars
import copy
import hashlib
import json
import logging
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from langchain_core.tools import tool

from agent.config import TICKET_URGE_DEBOUNCE_SECONDS


logger = logging.getLogger("voice_agent.ticket_tools")

SCHEMA_VERSION = 2
DEFAULT_STATUS = "待人工处理"
DEFAULT_PRIORITY = "普通"
OPEN_STATUSES = frozenset({"待人工处理", "处理中"})
CLOSED_STATUSES = frozenset({"已解决", "已关闭"})
VALID_STATUSES = OPEN_STATUSES | CLOSED_STATUSES
PRIORITY_LEVELS = ("低", "普通", "高", "紧急")
DEFAULT_LOOKUP_LIMIT = 5
MAX_LOOKUP_LIMIT = 20
URGE_DEBOUNCE_SECONDS = TICKET_URGE_DEBOUNCE_SECONDS

_operation_id_context: contextvars.ContextVar[str | None] = contextvars.ContextVar(
    "ticket_operation_id", default=None
)


def set_operation_id(operation_id: str):
    """Set the trusted operation id for the current Agent tool execution."""

    return _operation_id_context.set(operation_id)


def reset_operation_id(token) -> None:
    _operation_id_context.reset(token)


def _operation_id() -> str:
    return _operation_id_context.get() or str(uuid4())


def _clean(value: Any) -> str:
    return str(value or "").strip()


def _identifier_log_summary(value: Any) -> str:
    normalized = _clean(value)
    return f"provided={bool(normalized)} length={len(normalized)}"


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _timestamp(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _parse_timestamp(value: Any) -> datetime | None:
    text = _clean(value)
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _fingerprint(tool_name: str, arguments: dict[str, Any]) -> str:
    payload = json.dumps(
        {"tool": tool_name, "arguments": arguments},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _result(
    success: bool,
    code: str,
    message: str,
    *,
    data: Any = None,
    operation_id: str | None = None,
    idempotent_replay: bool = False,
) -> dict[str, Any]:
    return {
        "success": success,
        "code": code,
        "message": message,
        "data": data,
        "operation_id": operation_id,
        "idempotent_replay": idempotent_replay,
    }


def _public_ticket(ticket: dict[str, Any]) -> dict[str, Any]:
    return {
        key: copy.deepcopy(value)
        for key, value in ticket.items()
        if key not in {"operations", "_validation_errors"}
    }


def normalize_ticket(record: dict[str, Any]) -> dict[str, Any]:
    """Return a compatible in-memory view without mutating the stored record."""

    ticket = copy.deepcopy(record)
    errors: list[str] = []
    ticket_id = _clean(ticket.get("ticket_id"))
    customer_id = _clean(ticket.get("customer_id"))
    if not ticket_id:
        errors.append("ticket_id 缺失")
    if not customer_id:
        errors.append("customer_id 缺失")

    status = _clean(ticket.get("status")) or DEFAULT_STATUS
    priority = _clean(ticket.get("priority")) or DEFAULT_PRIORITY
    if status not in VALID_STATUSES:
        errors.append(f"未知状态：{status}")
    if priority not in PRIORITY_LEVELS:
        errors.append(f"未知优先级：{priority}")

    created = _parse_timestamp(ticket.get("created_at"))
    if created is None:
        errors.append("created_at 缺失或格式非法")
    updated = _parse_timestamp(ticket.get("updated_at")) or created

    urge_count = ticket.get("urge_count", 0)
    if not isinstance(urge_count, int) or isinstance(urge_count, bool) or urge_count < 0:
        errors.append("urge_count 必须是非负整数")
        urge_count = 0

    history = ticket.get("history", [])
    if not isinstance(history, list) or not all(isinstance(item, dict) for item in history):
        errors.append("history 必须是对象数组")
        history = []
    operations = ticket.get("operations", [])
    if not isinstance(operations, list) or not all(
        isinstance(item, dict) for item in operations
    ):
        errors.append("operations 必须是对象数组")
        operations = []

    ticket.update(
        {
            "schema_version": ticket.get("schema_version", 1),
            "ticket_id": ticket_id,
            "customer_id": customer_id,
            "order_id": _clean(ticket.get("order_id")) or None,
            "reason": _clean(ticket.get("reason")) or "其他售后",
            "description": _clean(ticket.get("description"))
            or _clean(ticket.get("reason"))
            or "历史工单未提供问题描述",
            "status": status,
            "priority": priority,
            "created_at": _timestamp(created) if created else _clean(ticket.get("created_at")),
            "updated_at": _timestamp(updated) if updated else _clean(ticket.get("updated_at")),
            "urge_count": urge_count,
            "last_urged_at": _clean(ticket.get("last_urged_at")) or None,
            "history": history,
            "operations": operations,
            "source": _clean(ticket.get("source")) or "legacy",
            "_validation_errors": errors,
        }
    )
    return ticket


class TicketService:
    """Single-process ticket operations over the existing JSON array store."""

    def __init__(self, tickets_file: Path | None = None) -> None:
        from agent.tools import customer_service as customer_tools

        self._customer_tools = customer_tools
        self.tickets_file = tickets_file or customer_tools.TICKETS_FILE
        self._lock = customer_tools._TICKETS_WRITE_LOCK

    def _load_raw(self) -> list[dict[str, Any]]:
        return self._customer_tools._load_records(self.tickets_file)

    def _write_raw(self, records: list[dict[str, Any]]) -> None:
        self._customer_tools._write_records(self.tickets_file, records)

    def _customer(self, identifier: str) -> dict[str, Any] | None:
        return self._customer_tools._resolve_customer(identifier)

    def _order(self, order_id: str) -> dict[str, Any] | None:
        return self._customer_tools._find_by_value(
            self._customer_tools._load_records(self._customer_tools.ORDERS_FILE),
            "order_id",
            order_id,
        )

    @staticmethod
    def _find_operation(
        tickets: list[dict[str, Any]], operation_id: str
    ) -> tuple[dict[str, Any], dict[str, Any]] | None:
        for raw in tickets:
            for operation in normalize_ticket(raw)["operations"]:
                if _clean(operation.get("operation_id")) == operation_id:
                    return raw, operation
        return None

    @staticmethod
    def _replay_or_conflict(
        tickets: list[dict[str, Any]], operation_id: str, fingerprint: str
    ) -> dict[str, Any] | None:
        found = TicketService._find_operation(tickets, operation_id)
        if found is None:
            return None
        _, operation = found
        if operation.get("fingerprint") != fingerprint:
            return _result(
                False,
                "IDEMPOTENCY_CONFLICT",
                "该操作标识已用于不同的工单请求，请重新发起操作。",
                operation_id=operation_id,
            )
        stored = copy.deepcopy(operation.get("result"))
        if not isinstance(stored, dict):
            return _result(
                False,
                "DATA_INVALID",
                "工单幂等记录不完整，无法安全重放该操作。",
                operation_id=operation_id,
            )
        stored["idempotent_replay"] = True
        return stored

    @staticmethod
    def _operation_record(
        operation_id: str,
        action: str,
        fingerprint: str,
        timestamp: str,
        result: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "operation_id": operation_id,
            "action": action,
            "fingerprint": fingerprint,
            "timestamp": timestamp,
            "result": copy.deepcopy(result),
        }

    def create(
        self,
        *,
        customer_id: str,
        order_id: str = "",
        reason: str = "",
        description: str = "",
        operation_id: str,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        customer_identifier = _clean(customer_id)
        normalized_order_id = _clean(order_id)
        normalized_reason = _clean(reason)
        normalized_description = _clean(description)
        if not customer_identifier:
            return _result(False, "INVALID_ARGUMENT", "工单创建失败：customer_id 不能为空", operation_id=operation_id)
        if not normalized_reason and not normalized_description:
            return _result(False, "INVALID_ARGUMENT", "工单创建失败：reason 或 description 至少填写一项", operation_id=operation_id)

        customer = self._customer(customer_identifier)
        if customer is None:
            return _result(False, "CUSTOMER_NOT_FOUND", f"工单创建失败：未找到客户 {customer_identifier}", operation_id=operation_id)
        canonical_customer_id = _clean(customer.get("customer_id"))
        if normalized_order_id:
            order = self._order(normalized_order_id)
            if order is None:
                return _result(False, "ORDER_NOT_FOUND", f"工单创建失败：未找到订单 {normalized_order_id}", operation_id=operation_id)
            if _clean(order.get("customer_id")) != canonical_customer_id:
                return _result(False, "ORDER_CUSTOMER_MISMATCH", "工单创建失败：订单不属于该客户", operation_id=operation_id)

        arguments = {
            "customer_id": canonical_customer_id,
            "order_id": normalized_order_id or None,
            "reason": normalized_reason or "其他售后",
            "description": normalized_description or normalized_reason,
        }
        fingerprint = _fingerprint("ticket_create", arguments)
        current = (now or _utc_now()).astimezone(UTC)
        timestamp = _timestamp(current)

        with self._lock:
            tickets = self._load_raw()
            replay = self._replay_or_conflict(tickets, operation_id, fingerprint)
            if replay is not None:
                return replay

            ticket_id = f"TKT{current:%Y%m%d%H%M%S}{uuid4().hex[:6].upper()}"
            while any(_clean(item.get("ticket_id")) == ticket_id for item in tickets):
                ticket_id = f"TKT{current:%Y%m%d%H%M%S}{uuid4().hex[:6].upper()}"
            history = {
                "history_id": operation_id,
                "operation_id": operation_id,
                "action": "created",
                "timestamp": timestamp,
                "operator": "agent",
                "description": "创建售后工单",
                "before": None,
                "after": {"status": DEFAULT_STATUS, "priority": DEFAULT_PRIORITY},
            }
            ticket = {
                "schema_version": SCHEMA_VERSION,
                "ticket_id": ticket_id,
                "customer_id": canonical_customer_id,
                "order_id": normalized_order_id or None,
                "reason": normalized_reason or "其他售后",
                "description": normalized_description or normalized_reason,
                "status": DEFAULT_STATUS,
                "priority": DEFAULT_PRIORITY,
                "created_at": timestamp,
                "updated_at": timestamp,
                "urge_count": 0,
                "last_urged_at": None,
                "history": [history],
                "operations": [],
                "source": "voice_agent",
            }
            result = _result(
                True,
                "TICKET_CREATED",
                f"工单已创建，工单号：{ticket_id}\n处理状态：{DEFAULT_STATUS}\n客服会根据工单内容继续处理。",
                data=_public_ticket(ticket),
                operation_id=operation_id,
            )
            ticket["operations"].append(
                self._operation_record(operation_id, "ticket_create", fingerprint, timestamp, result)
            )
            tickets.append(ticket)
            self._write_raw(tickets)
            return result

    def lookup(
        self, *, ticket_id: str = "", customer_id: str = "", limit: int = DEFAULT_LOOKUP_LIMIT
    ) -> dict[str, Any]:
        normalized_ticket_id = _clean(ticket_id)
        normalized_customer_id = _clean(customer_id)
        if normalized_ticket_id and normalized_customer_id:
            return _result(False, "AMBIGUOUS_QUERY", "工单查询失败：ticket_id 和 customer_id 不能同时提供")
        if not normalized_ticket_id and not normalized_customer_id:
            return _result(False, "INVALID_ARGUMENT", "工单查询失败：ticket_id 或 customer_id 至少提供一项")
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1:
            return _result(False, "INVALID_ARGUMENT", "工单查询失败：limit 必须是正整数")
        effective_limit = min(limit, MAX_LOOKUP_LIMIT)
        tickets = [normalize_ticket(item) for item in self._load_raw()]

        if normalized_ticket_id:
            matches = [item for item in tickets if item["ticket_id"] == normalized_ticket_id]
            if not matches:
                return _result(False, "TICKET_NOT_FOUND", f"未找到工单：{normalized_ticket_id}")
            if len(matches) > 1:
                return _result(False, "DATA_CONFLICT", f"工单数据冲突：{normalized_ticket_id} 存在重复记录")
            ticket = matches[0]
            if ticket["_validation_errors"]:
                ticket["data_warnings"] = list(ticket["_validation_errors"])
            public = _public_ticket(ticket)
            message = "\n".join(
                [
                    f"工单号：{ticket['ticket_id']}",
                    f"处理状态：{ticket['status']}",
                    f"优先级：{ticket['priority']}",
                    "问题描述：售后问题已记录",
                    f"催办次数：{ticket['urge_count']}",
                ]
            )
            return _result(True, "TICKET_FOUND", message, data={"ticket": public})

        customer = self._customer(normalized_customer_id)
        if customer is None:
            return _result(False, "CUSTOMER_NOT_FOUND", f"工单查询失败：未找到客户 {normalized_customer_id}")
        canonical_customer_id = _clean(customer.get("customer_id"))
        matches = [item for item in tickets if item["customer_id"] == canonical_customer_id]
        matches.sort(
            key=lambda item: _parse_timestamp(item.get("created_at")) or datetime.min.replace(tzinfo=UTC),
            reverse=True,
        )
        public = [_public_ticket(item) for item in matches[:effective_limit]]
        if not public:
            return _result(True, "NO_TICKETS", f"客户 {canonical_customer_id} 暂无工单。", data={"customer_id": canonical_customer_id, "tickets": [], "limit": effective_limit})
        summary = "、".join(f"{item['ticket_id']}（{item['status']}，{item['priority']}）" for item in public)
        return _result(True, "TICKETS_FOUND", f"客户 {canonical_customer_id} 最近的工单：{summary}", data={"customer_id": canonical_customer_id, "tickets": public, "limit": effective_limit})

    def urge(
        self,
        *,
        ticket_id: str,
        reason: str = "",
        operation_id: str,
        now: datetime | None = None,
    ) -> dict[str, Any]:
        normalized_ticket_id = _clean(ticket_id)
        normalized_reason = _clean(reason)
        if not normalized_ticket_id:
            return _result(False, "INVALID_ARGUMENT", "工单催办失败：ticket_id 不能为空", operation_id=operation_id)
        arguments = {"ticket_id": normalized_ticket_id, "reason": normalized_reason}
        fingerprint = _fingerprint("ticket_urge", arguments)
        current = (now or _utc_now()).astimezone(UTC)
        timestamp = _timestamp(current)

        with self._lock:
            tickets = self._load_raw()
            replay = self._replay_or_conflict(tickets, operation_id, fingerprint)
            if replay is not None:
                return replay
            indexes = [i for i, item in enumerate(tickets) if _clean(item.get("ticket_id")) == normalized_ticket_id]
            if not indexes:
                return _result(False, "TICKET_NOT_FOUND", f"工单催办失败：未找到工单 {normalized_ticket_id}", operation_id=operation_id)
            if len(indexes) > 1:
                return _result(False, "DATA_CONFLICT", f"工单催办失败：工单 {normalized_ticket_id} 存在重复记录", operation_id=operation_id)
            index = indexes[0]
            ticket = normalize_ticket(tickets[index])
            if ticket["_validation_errors"]:
                return _result(False, "DATA_INVALID", "工单催办失败：工单数据不完整或包含非法字段。", data={"ticket_id": normalized_ticket_id, "errors": ticket["_validation_errors"]}, operation_id=operation_id)
            if ticket["status"] not in OPEN_STATUSES:
                return _result(False, "TICKET_NOT_OPEN", f"工单当前状态为{ticket['status']}，不能催办。", data={"ticket_id": normalized_ticket_id, "status": ticket["status"], "changed": False}, operation_id=operation_id)

            last_urged = _parse_timestamp(ticket.get("last_urged_at"))
            within_window = last_urged is not None and (current - last_urged).total_seconds() < URGE_DEBOUNCE_SECONDS
            before_priority = ticket["priority"]
            if within_window:
                result = _result(
                    True,
                    "URGE_DEBOUNCED",
                    f"工单 {normalized_ticket_id} 刚刚已经催办，本次未重复升级。",
                    data={"ticket_id": normalized_ticket_id, "status": ticket["status"], "priority_before": before_priority, "priority_after": before_priority, "urge_count": ticket["urge_count"], "changed": False, "debounced": True},
                    operation_id=operation_id,
                )
                ticket["operations"].append(self._operation_record(operation_id, "ticket_urge", fingerprint, timestamp, result))
                ticket.pop("_validation_errors", None)
                tickets[index] = ticket
                self._write_raw(tickets)
                return result

            priority_index = PRIORITY_LEVELS.index(before_priority)
            after_priority = PRIORITY_LEVELS[min(priority_index + 1, len(PRIORITY_LEVELS) - 1)]
            ticket["priority"] = after_priority
            ticket["updated_at"] = timestamp
            ticket["last_urged_at"] = timestamp
            ticket["urge_count"] += 1
            description = f"催办工单，优先级从{before_priority}调整为{after_priority}"
            if before_priority == after_priority:
                description = f"催办工单，优先级已保持最高等级{after_priority}"
            ticket["history"].append(
                {
                    "history_id": operation_id,
                    "operation_id": operation_id,
                    "action": "urged",
                    "timestamp": timestamp,
                    "operator": "agent",
                    "description": description if not normalized_reason else f"{description}；原因：{normalized_reason}",
                    "before": {"status": ticket["status"], "priority": before_priority},
                    "after": {"status": ticket["status"], "priority": after_priority},
                }
            )
            result = _result(
                True,
                "TICKET_URGED",
                f"工单 {normalized_ticket_id} 已催办，优先级从{before_priority}调整为{after_priority}。",
                data={"ticket_id": normalized_ticket_id, "status": ticket["status"], "priority_before": before_priority, "priority_after": after_priority, "urge_count": ticket["urge_count"], "changed": True, "debounced": False},
                operation_id=operation_id,
            )
            ticket["operations"].append(self._operation_record(operation_id, "ticket_urge", fingerprint, timestamp, result))
            ticket["schema_version"] = SCHEMA_VERSION
            ticket.pop("_validation_errors", None)
            tickets[index] = ticket
            self._write_raw(tickets)
            return result


def _tool_response(result: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    return result["message"], result


def _service() -> TicketService:
    return TicketService()


@tool("ticket_lookup", parse_docstring=True, response_format="content_and_artifact")
def ticket_lookup(ticket_id: str = "", customer_id: str = "", limit: int = DEFAULT_LOOKUP_LIMIT) -> tuple[str, dict[str, Any]]:
    """按工单号精确查询，或按客户 ID 查询最近工单。

    ticket_id 和 customer_id 必须且只能提供一个。查询当前状态时必须调用本工具，
    不得根据历史对话猜测。limit 仅用于客户工单列表，最大为 20。

    Args:
        ticket_id: 完整工单号；与 customer_id 互斥。
        customer_id: 客户 ID；与 ticket_id 互斥。
        limit: 最近工单返回数量，范围 1 到 20。

    Returns:
        可朗读摘要，以及包含业务 code 和工单数据的结构化结果。
    """

    try:
        return _tool_response(_service().lookup(ticket_id=ticket_id, customer_id=customer_id, limit=limit))
    except Exception as exc:
        logger.error("工单查询发生存储错误：error_type=%s", type(exc).__name__)
        return _tool_response(_result(False, "STORAGE_READ_FAILED", "工单查询失败：本地工单数据暂时不可用。"))


@tool("ticket_urge", parse_docstring=True, response_format="content_and_artifact")
async def ticket_urge(ticket_id: str = "", reason: str = "") -> tuple[str, dict[str, Any]]:
    """催办一张已知工单，并按规则升级优先级和记录操作历史。

    只有获得完整 ticket_id 后才能调用。开放工单可以催办；关闭、已解决、
    不存在或数据异常的工单不会被修改。短时间重复请求不会重复升级。

    Args:
        ticket_id: 用户提供或 ticket_lookup 返回的完整工单号。
        reason: 用户本次催办的简短原因，可为空。

    Returns:
        可朗读结果，以及催办前后优先级、次数和幂等状态等结构化数据。
    """

    operation_id = _operation_id()
    try:
        return _tool_response(_service().urge(ticket_id=ticket_id, reason=reason, operation_id=operation_id))
    except Exception as exc:
        logger.error(
            "工单催办发生存储错误：ticket_id_%s error_type=%s",
            _identifier_log_summary(ticket_id),
            type(exc).__name__,
        )
        return _tool_response(_result(False, "STORAGE_WRITE_FAILED", "工单催办失败：本地工单数据暂时无法安全写入。", operation_id=operation_id))


__all__ = [
    "CLOSED_STATUSES",
    "DEFAULT_LOOKUP_LIMIT",
    "MAX_LOOKUP_LIMIT",
    "OPEN_STATUSES",
    "PRIORITY_LEVELS",
    "SCHEMA_VERSION",
    "TicketService",
    "URGE_DEBOUNCE_SECONDS",
    "normalize_ticket",
    "reset_operation_id",
    "set_operation_id",
    "ticket_lookup",
    "ticket_urge",
]
