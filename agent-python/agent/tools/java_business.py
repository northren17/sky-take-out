"""Session-bound read-only order and coupon tools backed by the Java service."""

from __future__ import annotations

from decimal import Decimal
from typing import Any

from langchain_core.tools import tool

from agent.internal_auth import (
    InternalAgentError,
    JavaInternalClient,
    SessionAuthorizationError,
)


ORDER_STATUSES = {
    1: "待付款",
    2: "待接单",
    3: "已接单",
    4: "配送中",
    5: "已完成",
    6: "已取消",
}
COUPON_TYPES = {1: "满减券", 2: "折扣券", 3: "无门槛券"}
COUPON_STATUSES = {0: "未使用", 1: "已锁定", 2: "已使用", 3: "已过期"}


def _public_number(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, Decimal):
        return format(value, "f")
    return str(value)


def _tool_result(
    message: str,
    *,
    success: bool,
    status: str,
    code: str,
    data: dict[str, Any] | None = None,
) -> tuple[str, dict[str, Any]]:
    return message, {
        "success": success,
        "execution_status": "completed" if success else "failed",
        "business_status": status,
        "code": code,
        "message": message,
        "data": data or {},
    }


def create_java_business_tools(
    client: JavaInternalClient, session_id: str, capability: str
) -> list[Any]:
    """Create tools whose only identity source is the redeemed Java capability."""

    @tool("order_lookup", response_format="content_and_artifact")
    async def order_lookup(order_number: str = "") -> tuple[str, dict[str, Any]]:
        """查询当前登录用户最近90天内最多10笔订单。

        可提供完整订单号精确筛选；不提供时返回最近订单摘要。只能查询当前语音
        会话所属用户，不能查询其他人的订单。

        Args:
            order_number: 可选的完整订单号，不要猜测或补造。
        """
        normalized_number = order_number.strip()
        try:
            orders = await client.recent_orders(session_id, capability)
        except SessionAuthorizationError:
            return _tool_result(
                "语音会话权限已失效，请重新登录后再查询订单。",
                success=False,
                status="authorization_expired",
                code="SESSION_AUTHORIZATION_EXPIRED",
            )
        except InternalAgentError:
            return _tool_result(
                "订单服务暂时不可用，请稍后重试。",
                success=False,
                status="service_unavailable",
                code="ORDER_SERVICE_UNAVAILABLE",
            )

        if normalized_number:
            orders = [
                order for order in orders
                if str(order.get("orderNumber", "")) == normalized_number
            ]
        if not orders:
            message = (
                "最近90天内没有查到这个订单号。"
                if normalized_number
                else "最近90天内没有查到订单。"
            )
            return _tool_result(
                message,
                success=True,
                status="no_match",
                code="ORDER_NOT_FOUND",
                data={"orders": []},
            )

        summaries = []
        lines = []
        for order in orders:
            status = ORDER_STATUSES.get(order.get("status"), "状态未知")
            created_at = str(order.get("createdAt") or "时间未知")
            amount = _public_number(order.get("amount")) or "金额未知"
            items = [
                f"{item.get('name', '未命名商品')} x{item.get('quantity', 0)}"
                for item in order.get("items", [])
                if isinstance(item, dict)
            ]
            summaries.append(
                {
                    "order_number": order.get("orderNumber"),
                    "status": status,
                    "created_at": created_at,
                    "amount": amount,
                    "items": items,
                }
            )
            lines.append(
                f"订单号：{order.get('orderNumber')}；状态：{status}；"
                f"下单时间：{created_at}；金额：{amount}元；"
                f"商品：{'、'.join(items) if items else '暂无商品摘要'}"
            )
        return _tool_result(
            "\n".join(lines),
            success=True,
            status="hit",
            code="ORDERS_FOUND",
            data={"orders": summaries},
        )

    @tool("coupon_lookup", response_format="content_and_artifact")
    async def coupon_lookup(
        page: int = 1, page_size: int = 20, status: int | None = None
    ) -> tuple[str, dict[str, Any]]:
        """分页查询当前登录用户自己的优惠券及规则、有效期和状态。

        Args:
            page: 页码，从1开始。
            page_size: 每页数量，最大50。
            status: 可选状态，0未使用、1已锁定、2已使用、3已过期。
        """
        try:
            result = await client.coupons(
                session_id,
                capability,
                page=page,
                page_size=page_size,
                status=status,
            )
        except SessionAuthorizationError:
            return _tool_result(
                "语音会话权限已失效，请重新登录后再查询优惠券。",
                success=False,
                status="authorization_expired",
                code="SESSION_AUTHORIZATION_EXPIRED",
            )
        except InternalAgentError as exc:
            if exc.status_code == 400:
                return _tool_result(
                    "优惠券查询条件无效，请调整状态或分页后重试。",
                    success=False,
                    status="invalid_arguments",
                    code="INVALID_COUPON_QUERY",
                )
            return _tool_result(
                "优惠券服务暂时不可用，请稍后重试。",
                success=False,
                status="service_unavailable",
                code="COUPON_SERVICE_UNAVAILABLE",
            )

        coupons = []
        lines = []
        for coupon in result["coupons"]:
            if not isinstance(coupon, dict):
                continue
            coupon_type = COUPON_TYPES.get(coupon.get("couponType"), "优惠券")
            coupon_status = COUPON_STATUSES.get(coupon.get("status"), "状态未知")
            threshold = _public_number(coupon.get("thresholdAmount"))
            discount_amount = _public_number(coupon.get("discountAmount"))
            discount_rate = _public_number(coupon.get("discountRate"))
            max_discount = _public_number(coupon.get("maxDiscountAmount"))
            if coupon.get("couponType") == 2:
                rule = f"{discount_rate or '未知'}折"
                if max_discount is not None:
                    rule += f"，最高优惠{max_discount}元"
            elif coupon.get("couponType") == 3:
                rule = f"优惠{discount_amount or '未知'}元"
            else:
                rule = (
                    f"满{threshold or '未知'}元减{discount_amount or '未知'}元"
                )
            valid_start = str(coupon.get("validStartTime") or "未知")
            valid_end = str(coupon.get("validEndTime") or "未知")
            summary = {
                "name": coupon.get("name"),
                "type": coupon_type,
                "rule": rule,
                "scope_type": coupon.get("scopeType"),
                "status": coupon_status,
                "valid_start_time": valid_start,
                "valid_end_time": valid_end,
            }
            coupons.append(summary)
            lines.append(
                f"{coupon.get('name', '未命名优惠券')}：{rule}；"
                f"状态：{coupon_status}；有效期：{valid_start}至{valid_end}"
            )
        if not lines:
            lines.append("当前页没有符合条件的优惠券。")
        return _tool_result(
            f"共查询到{result.get('total', 0)}张优惠券。\n" + "\n".join(lines),
            success=True,
            status="hit" if coupons else "no_match",
            code="COUPONS_FOUND" if coupons else "COUPONS_NOT_FOUND",
            data={
                "total": result.get("total", 0),
                "page": result.get("page", page),
                "page_size": result.get("pageSize", page_size),
                "coupons": coupons,
            },
        )

    return [order_lookup, coupon_lookup]
