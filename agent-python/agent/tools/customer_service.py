"""供 LangChain Agent 调用的本地电商客服工具。

本模块不访问真实电商平台，只读取项目根目录 ``data`` 下的 mock JSON 数据，
并把新建售后工单追加到 ``data/tickets.json``。普通查询工具返回适合 Agent 继续
组织回答的中文文本；工单写工具还附带结构化 artifact。文件和参数错误会转换成
可读的业务结果，不会把内部堆栈暴露给实时语音链路。
"""

from __future__ import annotations

import contextlib
import json
import logging
import os
import tempfile
from pathlib import Path
from threading import Lock
from typing import Any

from langchain_core.tools import tool


logger = logging.getLogger("voice_agent.customer_tools")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = PROJECT_ROOT / "data"
CUSTOMERS_FILE = DATA_DIR / "customers.json"
ORDERS_FILE = DATA_DIR / "orders.json"
LOGISTICS_FILE = DATA_DIR / "logistics.json"
TICKETS_FILE = DATA_DIR / "tickets.json"

# 同一进程内可能有多个语音会话同时创建工单，写文件时串行化避免互相覆盖。
_TICKETS_WRITE_LOCK = Lock()


def _observed_response(
    message: str,
    code: str,
    business_status: str,
    *,
    execution_status: str = "completed",
    data: dict[str, Any] | None = None,
) -> tuple[str, dict[str, Any]]:
    return message, {
        "success": execution_status == "completed",
        "execution_status": execution_status,
        "business_status": business_status,
        "code": code,
        "message": message,
        "data": data,
    }


def _clean(value: Any) -> str:
    """把工具参数或 JSON 字段规范化为去除首尾空白的字符串。"""

    return str(value or "").strip()


def _load_records(path: Path) -> list[dict[str, Any]]:
    """读取一个 JSON 数组，并校验数组元素都是对象。"""

    try:
        with path.open("r", encoding="utf-8") as file:
            records = json.load(file)
    except FileNotFoundError as exc:
        raise RuntimeError(f"缺少本地数据文件：{path.name}") from exc
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"本地数据文件格式错误：{path.name}") from exc
    except OSError as exc:
        raise RuntimeError(f"读取本地数据文件失败：{path.name}") from exc

    if not isinstance(records, list) or not all(
        isinstance(record, dict) for record in records
    ):
        raise RuntimeError(f"本地数据文件必须是对象数组：{path.name}")
    return records


def _find_by_value(
    records: list[dict[str, Any]], field: str, value: str
) -> dict[str, Any] | None:
    """按一个字段精确查找记录。"""

    normalized = _clean(value)
    return next(
        (
            record
            for record in records
            if _clean(record.get(field)) == normalized
        ),
        None,
    )


def _mask_phone(phone: Any) -> str:
    """只保留手机号首三位和末四位，避免工具结果泄露完整手机号。"""

    normalized = _clean(phone)
    if len(normalized) <= 7:
        return normalized
    return f"{normalized[:3]}****{normalized[-4:]}"


def _identifier_log_summary(value: Any) -> str:
    """Return a non-reversible summary suitable for operational logs."""

    normalized = _clean(value)
    return f"provided={bool(normalized)} length={len(normalized)}"


def _format_items(items: Any) -> str:
    """把订单商品数组格式化为简短的中文列表。"""

    if not isinstance(items, list) or not items:
        return "暂无商品明细"

    formatted: list[str] = []
    for item in items:
        if not isinstance(item, dict):
            continue
        name = _clean(item.get("name")) or "未命名商品"
        quantity = item.get("quantity", 1)
        formatted.append(f"{name} x{quantity}")
    return "、".join(formatted) if formatted else "暂无商品明细"


def _format_order(order: dict[str, Any]) -> str:
    """把订单对象转换为 Agent 易读的纯文本。"""

    after_sales = order.get("after_sales")
    if not isinstance(after_sales, dict):
        after_sales = {}

    eligible = "支持" if after_sales.get("eligible") else "不支持"
    deadline = _clean(after_sales.get("return_deadline")) or "无"
    refund_status = _clean(after_sales.get("refund_status")) or "暂无记录"
    amount = order.get("total_amount", "未知")

    return "\n".join(
        [
            f"订单号：{_clean(order.get('order_id'))}",
            f"客户ID：{_clean(order.get('customer_id'))}",
            f"订单状态：{_clean(order.get('status')) or '未知'}",
            f"下单时间：{_clean(order.get('placed_at')) or '未知'}",
            f"支付状态：{_clean(order.get('payment_status')) or '未知'}",
            f"商品：{_format_items(order.get('items'))}",
            f"订单金额：{amount}元",
            f"售后资格：{eligible}七天无理由退货",
            f"退货截止：{deadline}",
            f"退款状态：{refund_status}",
        ]
    )


def _public_order(order: dict[str, Any]) -> dict[str, Any]:
    """Return the order fields approved for the structured UI event."""

    result = {
        key: order.get(key)
        for key in (
            "order_id", "customer_id", "status", "placed_at", "payment_status", "total_amount"
        )
    }
    result["items"] = [
        {key: item.get(key) for key in ("name", "quantity", "unit_price")}
        for item in order.get("items", [])[:20]
        if isinstance(item, dict)
    ]
    after_sales = order.get("after_sales")
    if isinstance(after_sales, dict):
        result["after_sales"] = {
            key: after_sales.get(key)
            for key in ("eligible", "return_deadline", "refund_status")
        }
    return result


def _public_customer(customer: dict[str, Any]) -> dict[str, Any]:
    """Exclude addresses and expose only a masked contact value to the UI."""

    return {
        "customer_id": _clean(customer.get("customer_id")),
        "name": _clean(customer.get("name")),
        "masked_phone": _mask_phone(customer.get("phone")),
        "member_level": _clean(customer.get("member_level")),
        "registered_at": _clean(customer.get("registered_at")),
        "order_ids": [
            _clean(order_id) for order_id in customer.get("order_ids", []) if _clean(order_id)
        ],
        "after_sales_summary": _clean(customer.get("after_sales_summary")),
    }


def _write_records(path: Path, records: list[dict[str, Any]]) -> None:
    """以同目录临时文件替换方式写入 JSON，避免留下半截工单文件。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: str | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.stem}.",
            suffix=".tmp",
            delete=False,
        ) as file:
            temporary_path = file.name
            json.dump(records, file, ensure_ascii=False, indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary_path, path)
        temporary_path = None
    except OSError as exc:
        raise RuntimeError(f"写入本地数据文件失败：{path.name}") from exc
    finally:
        if temporary_path:
            with contextlib.suppress(OSError):
                Path(temporary_path).unlink()


def _resolve_customer(identifier: str) -> dict[str, Any] | None:
    """按客户 ID 或手机号查找客户。"""

    customers = _load_records(CUSTOMERS_FILE)
    return next(
        (
            customer
            for customer in customers
            if _clean(customer.get("customer_id")) == identifier
            or _clean(customer.get("phone")) == identifier
        ),
        None,
    )


@tool("order_lookup", parse_docstring=True, response_format="content_and_artifact")
def order_lookup(order_id: str = "") -> tuple[str, dict[str, Any]]:
    """按订单号查询订单状态、商品、金额和基础售后信息。

    只查询本地 mock 数据，不会访问真实电商平台。订单号为空或查不到时，
    返回明确的中文提示，不能根据常识推测订单内容。

    Args:
        order_id: 用户提供的完整订单号。

    Returns:
        订单详情或可读的查询失败信息。
    """

    normalized_order_id = _clean(order_id)
    if not normalized_order_id:
        return _observed_response(
            "订单查询失败：order_id 不能为空", "INVALID_ARGUMENT", "invalid_arguments"
        )

    try:
        order = _find_by_value(
            _load_records(ORDERS_FILE), "order_id", normalized_order_id
        )
        if order is None:
            return _observed_response(
                f"未找到订单：{normalized_order_id}", "ORDER_NOT_FOUND", "no_match"
            )
        return _observed_response(
            _format_order(order),
            "ORDER_FOUND",
            "hit",
            data={"result_count": 1, "order": _public_order(order)},
        )
    except Exception as exc:
        logger.error(
            "订单查询失败：order_id_%s error_type=%s",
            _identifier_log_summary(normalized_order_id),
            type(exc).__name__,
        )
        return _observed_response(
            "订单查询失败：本地订单数据暂时不可用。",
            "STORAGE_READ_FAILED",
            "unknown",
            execution_status="failed",
        )


@tool("logistics_lookup", parse_docstring=True, response_format="content_and_artifact")
def logistics_lookup(order_id: str = "") -> tuple[str, dict[str, Any]]:
    """按订单号查询物流状态、快递单号和最近物流节点。

    只读取本地 mock 物流数据。订单号为空、订单不存在或没有物流记录时，
    返回明确的中文提示，不编造物流进度。

    Args:
        order_id: 用户提供的完整订单号。

    Returns:
        物流详情或可读的查询失败信息。
    """

    normalized_order_id = _clean(order_id)
    if not normalized_order_id:
        return _observed_response(
            "物流查询失败：order_id 不能为空", "INVALID_ARGUMENT", "invalid_arguments"
        )

    try:
        logistics = _find_by_value(
            _load_records(LOGISTICS_FILE), "order_id", normalized_order_id
        )
        if logistics is None:
            return _observed_response(
                f"未找到订单的物流信息：{normalized_order_id}",
                "LOGISTICS_NOT_FOUND",
                "no_match",
            )

        lines = [
            f"订单号：{normalized_order_id}",
            f"物流状态：{_clean(logistics.get('status')) or '未知'}",
            f"承运商：{_clean(logistics.get('carrier')) or '尚未分配'}",
            f"运单号：{_clean(logistics.get('tracking_number')) or '尚未生成'}",
            f"预计送达：{_clean(logistics.get('estimated_delivery')) or '暂无'}",
            f"最后更新：{_clean(logistics.get('last_updated')) or '未知'}",
        ]
        nodes = logistics.get("nodes")
        if isinstance(nodes, list):
            for node in nodes[-5:]:
                if not isinstance(node, dict):
                    continue
                node_time = _clean(node.get("time")) or "未知时间"
                location = _clean(node.get("location")) or "未知地点"
                description = _clean(node.get("description")) or "暂无说明"
                lines.append(f"物流节点：{node_time}，{location}，{description}")
        return _observed_response(
            "\n".join(lines), "LOGISTICS_FOUND", "hit", data={"result_count": 1}
        )
    except Exception as exc:
        logger.error(
            "物流查询失败：order_id_%s error_type=%s",
            _identifier_log_summary(normalized_order_id),
            type(exc).__name__,
        )
        return _observed_response(
            "物流查询失败：本地物流数据暂时不可用。",
            "STORAGE_READ_FAILED",
            "unknown",
            execution_status="failed",
        )


@tool("customer_lookup", parse_docstring=True, response_format="content_and_artifact")
def customer_lookup(identifier: str = "") -> tuple[str, dict[str, Any]]:
    """按手机号或客户 ID 查询客户摘要和关联订单。

    只读取本地 mock 客户数据。返回结果会对手机号做脱敏处理，不返回完整
    手机号或其他不必要的隐私信息。

    Args:
        identifier: 用户提供的客户 ID 或手机号。

    Returns:
        客户摘要或可读的查询失败信息。
    """

    normalized_identifier = _clean(identifier)
    if not normalized_identifier:
        return _observed_response(
            "客户查询失败：identifier 不能为空", "INVALID_ARGUMENT", "invalid_arguments"
        )

    try:
        customer = _resolve_customer(normalized_identifier)
        if customer is None:
            return _observed_response(
                "未找到匹配的客户。", "CUSTOMER_NOT_FOUND", "no_match"
            )

        order_ids = customer.get("order_ids")
        if isinstance(order_ids, list) and order_ids:
            related_orders = "、".join(_clean(order_id) for order_id in order_ids)
        else:
            related_orders = "暂无订单"
        message = "\n".join(
            [
                f"客户ID：{_clean(customer.get('customer_id'))}",
                f"姓名：{_clean(customer.get('name')) or '未登记'}",
                f"手机号：{_mask_phone(customer.get('phone')) or '未登记'}",
                f"会员等级：{_clean(customer.get('member_level')) or '未知'}",
                f"关联订单：{related_orders}",
                f"售后摘要：{_clean(customer.get('after_sales_summary')) or '暂无记录'}",
            ]
        )
        return _observed_response(
            message,
            "CUSTOMER_FOUND",
            "hit",
            data={"result_count": 1, "customer": _public_customer(customer)},
        )
    except Exception as exc:
        logger.error(
            "客户查询失败：identifier_%s error_type=%s",
            _identifier_log_summary(normalized_identifier),
            type(exc).__name__,
        )
        return _observed_response(
            "客户查询失败：本地客户数据暂时不可用。",
            "STORAGE_READ_FAILED",
            "unknown",
            execution_status="failed",
        )


@tool("ticket_create", parse_docstring=True, response_format="content_and_artifact")
async def ticket_create(
    customer_id: str = "",
    order_id: str = "",
    reason: str = "",
    description: str = "",
) -> tuple[str, dict[str, Any]]:
    """创建售后工单并写入本地 tickets.json。

    创建前会校验客户存在；如果提供订单号，还会校验订单存在且属于该客户。
    reason 或 description 至少需要提供一个。customer_id 也支持传手机号，工单
    中会统一保存为客户 ID。

    Args:
        customer_id: 客户 ID 或客户手机号。
        order_id: 关联订单号，可为空。
        reason: 售后原因，例如退款异常、商品破损或投诉。
        description: 问题补充描述，可为空。

    Returns:
        可朗读的创建结果，以及包含业务 code 和工单数据的结构化结果。
    """

    from agent.tools.ticket_service import TicketService, _operation_id, _result

    operation_id = _operation_id()
    try:
        result = TicketService(TICKETS_FILE).create(
            customer_id=customer_id,
            order_id=order_id,
            reason=reason,
            description=description,
            operation_id=operation_id,
        )
        return result["message"], result
    except Exception as exc:
        logger.error(
            "售后工单创建失败：customer_id_%s order_id_%s error_type=%s",
            _identifier_log_summary(customer_id),
            _identifier_log_summary(order_id),
            type(exc).__name__,
        )
        result = _result(
            False,
            "STORAGE_WRITE_FAILED",
            "工单创建失败：本地工单数据暂时无法安全写入。",
            operation_id=operation_id,
        )
        return result["message"], result


__all__ = [
    "customer_lookup",
    "logistics_lookup",
    "order_lookup",
    "ticket_create",
]
