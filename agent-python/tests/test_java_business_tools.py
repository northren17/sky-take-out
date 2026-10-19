import unittest
from decimal import Decimal

from agent.internal_auth import InternalAgentError, SessionAuthorizationError
from agent.tools.java_business import create_java_business_tools


class FakeJavaClient:
    def __init__(self):
        self.orders_response = []
        self.coupons_response = {
            "total": 0,
            "page": 1,
            "pageSize": 20,
            "coupons": [],
        }
        self.calls = []
        self.error = None

    async def recent_orders(self, session_id, capability):
        self.calls.append(("orders", session_id, capability))
        if self.error:
            raise self.error
        return self.orders_response

    async def coupons(
        self, session_id, capability, *, page, page_size, status
    ):
        self.calls.append(
            ("coupons", session_id, capability, page, page_size, status)
        )
        if self.error:
            raise self.error
        return self.coupons_response


class JavaBusinessToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_order_tool_uses_bound_session_and_exactly_filters_order_number(self):
        client = FakeJavaClient()
        client.orders_response = [
            {
                "orderNumber": "S-100",
                "status": 5,
                "createdAt": "2026-10-17T10:00:00",
                "amount": Decimal("12.30"),
                "phone": "should-not-be-present",
                "items": [{"name": "noodles", "quantity": 2, "image": "private"}],
            },
            {
                "orderNumber": "S-200",
                "status": 1,
                "createdAt": "2026-10-16T10:00:00",
                "amount": Decimal("21.00"),
                "items": [],
            },
        ]
        order_tool, _ = create_java_business_tools(client, "session-1", "cap-1")

        content, artifact = await order_tool.coroutine(order_number="S-100")

        self.assertIn("订单号：S-100", content)
        self.assertNotIn("S-200", content)
        self.assertEqual(artifact["data"]["orders"][0]["amount"], "12.30")
        self.assertNotIn("phone", artifact["data"]["orders"][0])
        self.assertNotIn("user_id", order_tool.args_schema.model_fields)
        self.assertEqual(client.calls, [("orders", "session-1", "cap-1")])

    async def test_coupon_tool_returns_only_sanitized_rule_and_status(self):
        client = FakeJavaClient()
        client.coupons_response = {
            "total": 1,
            "page": 1,
            "pageSize": 20,
            "coupons": [
                {
                    "name": "午餐券",
                    "couponType": 1,
                    "thresholdAmount": Decimal("30.00"),
                    "discountAmount": Decimal("8.00"),
                    "discountRate": Decimal("1.00"),
                    "maxDiscountAmount": Decimal("0.00"),
                    "scopeType": 1,
                    "status": 0,
                    "validStartTime": "2026-10-01T00:00:00",
                    "validEndTime": "2026-10-31T23:59:59",
                    "userId": 999,
                    "lockedOrderId": 456,
                }
            ],
        }
        _, coupon_tool = create_java_business_tools(client, "session-2", "cap-2")

        content, artifact = await coupon_tool.coroutine(page=1, page_size=20, status=0)

        self.assertIn("满30.00元减8.00元", content)
        self.assertEqual(artifact["data"]["coupons"][0]["status"], "未使用")
        self.assertNotIn("userId", artifact["data"]["coupons"][0])
        self.assertNotIn("lockedOrderId", artifact["data"]["coupons"][0])
        self.assertEqual(client.calls, [("coupons", "session-2", "cap-2", 1, 20, 0)])

    async def test_tool_reports_auth_and_service_failures_without_success_fallback(self):
        client = FakeJavaClient()
        order_tool, coupon_tool = create_java_business_tools(client, "session-3", "cap-3")

        client.error = SessionAuthorizationError("expired")
        _, expired = await order_tool.coroutine(order_number="")
        self.assertFalse(expired["success"])
        self.assertEqual(expired["code"], "SESSION_AUTHORIZATION_EXPIRED")

        client.error = InternalAgentError("unavailable")
        _, unavailable = await coupon_tool.coroutine(page=1, page_size=20, status=None)
        self.assertFalse(unavailable["success"])
        self.assertEqual(unavailable["code"], "COUPON_SERVICE_UNAVAILABLE")


if __name__ == "__main__":
    unittest.main()
