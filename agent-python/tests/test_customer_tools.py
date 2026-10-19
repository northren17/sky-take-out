"""本地电商客服工具的无外部网络单元测试。

测试直接调用 LangChain Tool 的 ``invoke`` 接口，覆盖订单、物流、客户查询和
工单创建。工单测试使用临时文件，避免测试运行后污染项目内的 mock 工单数据。
"""

import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.tools.customer_service import (
    customer_lookup,
    logistics_lookup,
    order_lookup,
    ticket_create,
)


class CustomerServiceToolTests(unittest.IsolatedAsyncioTestCase):
    """验证本地客服工具的正常结果和可读错误。"""

    def test_order_lookup_existing_order(self):
        """存在的订单应返回状态、商品和售后基础字段。"""

        result = order_lookup.invoke({"order_id": "ORD202610010001"})

        self.assertIn("订单号：ORD202610010001", result)
        self.assertIn("订单状态：已发货", result)
        self.assertIn("无线降噪耳机", result)
        self.assertIn("退款状态：无退款申请", result)

    def test_order_lookup_missing_order(self):
        """不存在的订单不能被编造，应明确返回未找到。"""

        result = order_lookup.invoke({"order_id": "ORD-NOT-FOUND"})

        self.assertEqual(result, "未找到订单：ORD-NOT-FOUND")

    def test_logistics_lookup(self):
        """物流查询应包含状态、运单号和物流节点。"""

        result = logistics_lookup.invoke({"order_id": "ORD202609250003"})

        self.assertIn("物流状态：已签收", result)
        self.assertIn("运单号：ZTO9876543210", result)
        self.assertIn("本人已签收", result)

    def test_customer_lookup_by_phone_and_customer_id(self):
        """客户查询同时支持手机号和客户 ID，并对手机号脱敏。"""

        by_phone = customer_lookup.invoke({"identifier": "13800000001"})
        by_id = customer_lookup.invoke({"identifier": "CUST002"})

        self.assertIn("客户ID：CUST001", by_phone)
        self.assertIn("手机号：138****0001", by_phone)
        self.assertNotIn("13800000001", by_phone)
        self.assertIn("姓名：王芳", by_id)
        self.assertIn("关联订单：ORD202609250003", by_id)

    async def test_ticket_create_returns_ticket_id_and_persists(self):
        """工单创建应返回工单号，并将记录追加到本地 JSON 文件。"""

        with tempfile.TemporaryDirectory() as temporary_directory:
            tickets_file = Path(temporary_directory) / "tickets.json"
            tickets_file.write_text("[]\n", encoding="utf-8")

            with patch(
                "agent.tools.customer_service.TICKETS_FILE", tickets_file
            ):
                result = await ticket_create.ainvoke(
                    {
                        "customer_id": "CUST001",
                        "order_id": "ORD202610010001",
                        "reason": "退款异常",
                        "description": "用户反馈退款一直没有到账",
                    }
                )

            match = re.search(r"工单号：(?P<ticket_id>TKT[0-9A-Z]+)", result)
            self.assertIsNotNone(match)
            self.assertIn("处理状态：待人工处理", result)

            records = json.loads(tickets_file.read_text(encoding="utf-8"))
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["customer_id"], "CUST001")
            self.assertEqual(records[0]["order_id"], "ORD202610010001")
            self.assertEqual(records[0]["reason"], "退款异常")

    async def test_missing_arguments_return_readable_errors(self):
        """缺参应返回中文错误，不应抛出 LangChain 参数校验异常。"""

        self.assertEqual(
            order_lookup.invoke({}),
            "订单查询失败：order_id 不能为空",
        )
        self.assertEqual(
            logistics_lookup.invoke({"order_id": " "}),
            "物流查询失败：order_id 不能为空",
        )
        self.assertEqual(
            customer_lookup.invoke({}),
            "客户查询失败：identifier 不能为空",
        )
        self.assertEqual(
            await ticket_create.ainvoke({}),
            "工单创建失败：customer_id 不能为空",
        )

    def test_tool_exception_returns_readable_error(self):
        """本地数据读取异常应转换为可读结果，并记录日志而不崩溃。"""

        with patch(
            "agent.tools.customer_service._load_records",
            side_effect=RuntimeError("模拟数据损坏"),
        ):
            with self.assertLogs(
                "voice_agent.customer_tools", level="ERROR"
            ) as logs:
                result = order_lookup.invoke({"order_id": "ORD202610010001"})

        self.assertEqual(result, "订单查询失败：本地订单数据暂时不可用。")
        self.assertNotIn("ORD202610010001", logs.output[0])
        self.assertIn("order_id_provided=True length=15", logs.output[0])


if __name__ == "__main__":
    unittest.main()
