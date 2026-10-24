import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.tools import tool

from agent.events import (
    ToolExecutionStatus,
    ToolLifecycleEvent,
    redact_sensitive_text,
    safe_tool_args,
    tool_wire_event,
)
from agent.llm_agent import QwenVoiceAgent, ToolExecutionTimeout
from agent.tool_events import LangGraphToolLifecycleAdapter, _result_data
from agent.tools.ticket_service import TicketService, ticket_lookup
from tests.test_llm_agent import FakeToolCallingModel


class ToolLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def collect(self, agent, events, text="question"):
        return "".join(
            [chunk async for chunk in agent.stream(text, on_tool_event=events.append)]
        )

    async def test_success_has_one_ordered_terminal_and_business_outcome(self):
        @tool(response_format="content_and_artifact")
        async def lookup(identifier: str):
            """Return a structured no-match result."""
            return "not found", {
                "success": True,
                "execution_status": "completed",
                "business_status": "no_match",
                "code": "ITEM_NOT_FOUND",
            }

        model = FakeToolCallingModel(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {"name": "lookup", "args": {"identifier": "13812345678"}, "id": "tc-1"}
                    ],
                ),
                AIMessage(content="done"),
            ]
        )
        events = []
        result = await self.collect(QwenVoiceAgent(model=model, tools=[lookup]), events)
        self.assertEqual(result, "done")
        self.assertEqual([event.event_type for event in events], ["tool.called", "tool.finished"])
        self.assertEqual(events[0].call_id, events[1].call_id)
        self.assertEqual(events[1].business_status, "no_match")
        self.assertNotIn("13812345678", str(events))

    async def test_agent_order_and_coupon_results_are_projected_before_ui_events(self):
        order_data = _result_data(
            "order_lookup",
            {
                "data": {
                    "orders": [
                        {
                            "order_number": "S-100",
                            "status": "已完成",
                            "created_at": "2026-10-17T10:00:00",
                            "amount": "12.30",
                            "phone": "13800000000",
                            "address": "private address",
                            "items": [
                                {
                                    "name": "noodles",
                                    "quantity": 1,
                                    "image": "private-image",
                                }
                            ],
                        }
                    ]
                }
            },
        )
        coupon_data = _result_data(
            "coupon_lookup",
            {
                "data": {
                    "total": 1,
                    "page": 1,
                    "page_size": 20,
                    "coupons": [
                        {
                            "name": "lunch",
                            "type": "满减券",
                            "rule": "满30减8",
                            "status": "未使用",
                            "valid_end_time": "2026-10-31",
                            "user_id": 999,
                            "locked_order_id": 123,
                        }
                    ],
                }
            },
        )

        serialized = json.dumps(
            {"orders": order_data, "coupons": coupon_data}, ensure_ascii=False
        )
        self.assertIn("S-100", serialized)
        self.assertIn("lunch", serialized)
        self.assertNotIn("13800000000", serialized)
        self.assertNotIn("private address", serialized)
        self.assertNotIn("private-image", serialized)
        self.assertNotIn("user_id", serialized)
        self.assertNotIn("locked_order_id", serialized)

    async def test_exception_timeout_and_cancel_each_have_one_terminal(self):
        async def run_case(kind):
            entered = asyncio.Event()

            @tool
            async def probe(value: str):
                """Exercise a terminal lifecycle."""
                entered.set()
                if kind == "error":
                    raise RuntimeError("secret token=abcdef123456")
                await asyncio.sleep(30)
                return value

            model = FakeToolCallingModel(
                [
                    AIMessage(
                        content="",
                        tool_calls=[{"name": "probe", "args": {"value": "x"}, "id": kind}],
                    )
                ]
            )
            events = []
            agent = QwenVoiceAgent(
                model=model,
                tools=[probe],
                tool_timeout_seconds=0.02 if kind == "timeout" else 1,
            )
            task = asyncio.create_task(self.collect(agent, events))
            await asyncio.wait_for(entered.wait(), 1)
            if kind == "cancel":
                task.cancel()
            with self.assertRaises(
                asyncio.CancelledError if kind == "cancel" else (
                    ToolExecutionTimeout if kind == "timeout" else RuntimeError
                )
            ):
                await task
            terminals = [event for event in events if event.event_type != "tool.called"]
            self.assertEqual(len(terminals), 1)
            return terminals[0]

        failed = await run_case("error")
        timed_out = await run_case("timeout")
        cancelled = await run_case("cancel")
        self.assertEqual(failed.event_type, "tool.failed")
        self.assertNotIn("abcdef123456", str(failed.error))
        self.assertEqual(timed_out.error["code"], "timeout")
        self.assertEqual(cancelled.event_type, "tool.cancelled")

    async def test_parallel_calls_have_distinct_call_ids(self):
        active = 0
        peak = 0

        @tool
        async def parallel_probe(value: str):
            """Run concurrently."""
            nonlocal active, peak
            active += 1
            peak = max(peak, active)
            await asyncio.sleep(0.01)
            active -= 1
            return value

        calls = [
            {"name": "parallel_probe", "args": {"value": str(index)}, "id": f"p-{index}"}
            for index in range(2)
        ]
        model = FakeToolCallingModel(
            [AIMessage(content="", tool_calls=calls), AIMessage(content="done")]
        )
        events = []
        await self.collect(QwenVoiceAgent(model=model, tools=[parallel_probe]), events)
        called = [event for event in events if event.event_type == "tool.called"]
        self.assertEqual(peak, 2)
        self.assertEqual(len({event.call_id for event in called}), 2)
        for event in called:
            self.assertEqual(
                len([item for item in events if item.call_id == event.call_id and item.event_type != "tool.called"]),
                1,
            )

    async def test_reporter_failure_does_not_repeat_tool(self):
        executions = 0

        @tool
        async def once(value: str):
            """Count executions."""
            nonlocal executions
            executions += 1
            return value

        async def broken_reporter(event):
            raise RuntimeError("observability unavailable")

        model = FakeToolCallingModel(
            [
                AIMessage(content="", tool_calls=[{"name": "once", "args": {"value": "x"}, "id": "once"}]),
                AIMessage(content="done"),
            ]
        )
        result = "".join(
            [
                chunk
                async for chunk in QwenVoiceAgent(model=model, tools=[once]).stream(
                    "question", on_tool_event=broken_reporter
                )
            ]
        )
        self.assertEqual(result, "done")
        self.assertEqual(executions, 1)

    def test_redaction_covers_free_text_and_argument_summaries(self):
        text = redact_sensitive_text(
            "电话13812345678 token=abcdef123456789 地址：北京市朝阳区测试路1号"
        )
        self.assertNotIn("13812345678", text)
        self.assertNotIn("abcdef123456789", text)
        self.assertNotIn("北京市朝阳区测试路1号", text)
        summary = safe_tool_args(
            "ticket_create",
            {"customer_id": "13812345678", "description": "联系地址和电话"},
        )
        self.assertNotIn("13812345678", str(summary))
        self.assertNotIn("联系地址", str(summary))

    async def test_duplicate_or_missing_provider_ids_still_track_each_execution(self):
        adapter_events = []
        from agent.tool_events import LangGraphToolLifecycleAdapter

        adapter = LangGraphToolLifecycleAdapter(adapter_events.append)
        calls = [
            {"name": "lookup", "args": {}, "id": "duplicate"},
            {"name": "lookup", "args": {}, "id": "duplicate"},
            {"name": "lookup", "args": {}},
        ]
        for call in calls:
            await adapter.called(call)
            await adapter.finished(call, "ok")
        starts = [event for event in adapter_events if event.event_type == "tool.called"]
        terminals = [event for event in adapter_events if event.event_type != "tool.called"]
        self.assertEqual(len(starts), 3)
        self.assertEqual(len(terminals), 3)
        self.assertEqual(len({event.call_id for event in starts}), 3)

    async def test_langgraph_error_tool_message_is_a_technical_failure(self):
        from agent.tool_events import LangGraphToolLifecycleAdapter

        events = []
        adapter = LangGraphToolLifecycleAdapter(events.append)
        call = {"name": "lookup", "args": {}, "id": "tool-error"}
        await adapter.called(call)
        await adapter.finished(
            call,
            ToolMessage(
                content="地址：北京市朝阳区测试路1号",
                tool_call_id="tool-error",
                status="error",
            ),
        )
        self.assertEqual(events[-1].event_type, "tool.failed")
        self.assertEqual(events[-1].error["code"], "tool_message_error")
        self.assertNotIn("北京市朝阳区测试路1号", str(events[-1].error))

    def test_wire_schema_has_consistent_correlation_fields(self):
        event = ToolLifecycleEvent(
            event_type="tool.called",
            call_id="call-1",
            tool_call_id="provider-1",
            tool_name="order_lookup",
            status=ToolExecutionStatus.RUNNING,
            safe_args={"order_id": {"provided": True, "suffix": "0001", "length": 8}},
        )
        wire = tool_wire_event(event, session_id="session-1", turn_id=2, seq=9)
        self.assertEqual(wire["type"], wire["event_type"])
        self.assertEqual(wire["call_id"], "call-1")
        self.assertEqual(wire["seq"], 9)
        self.assertIsNone(wire["duration_ms"])
        self.assertIn("event_id", wire)
        self.assertIn("timestamp", wire)

    async def test_customer_result_data_is_projected_and_sensitive_fields_are_excluded(self):
        events = []
        adapter = LangGraphToolLifecycleAdapter(events.append)
        tool_call = {"id": "provider-1", "name": "customer_lookup", "args": {"identifier": "13800000001"}}
        await adapter.called(tool_call)
        response = ToolMessage(
            content="客户结果",
            tool_call_id="provider-1",
            artifact={
                "success": True,
                "business_status": "hit",
                "code": "CUSTOMER_FOUND",
                "data": {
                    "customer": {
                        "customer_id": "CUST001",
                        "name": "测试客户",
                        "masked_phone": "138****0001",
                        "phone": "13800000001",
                        "default_address": "不应发送的地址",
                        "member_level": "金卡",
                    }
                },
            },
        )
        await adapter.finished(tool_call, response)
        terminal = events[-1]
        self.assertEqual(terminal.result_data["customer"]["masked_phone"], "138****0001")
        self.assertNotIn("phone", terminal.result_data["customer"])
        self.assertNotIn("default_address", terminal.result_data["customer"])

    async def _ticket_wire(self, tool_name, data, *, failure=False):
        events = []
        adapter = LangGraphToolLifecycleAdapter(events.append)
        tool_call = {"id": "provider-ticket", "name": tool_name, "args": {"reason": "13800000001"}}
        await adapter.called(tool_call)
        response = ToolMessage(
            content="工单结果",
            tool_call_id="provider-ticket",
            artifact={
                "success": not failure,
                "execution_status": "failed" if failure else "completed",
                "business_status": "hit",
                "code": "STORAGE_READ_FAILED" if failure else "TICKET_FOUND",
                "message": "存储异常：麻烦寄到测试市测试路123号 13800000001",
                "data": data,
            },
        )
        await adapter.finished(tool_call, response)
        return json.loads(json.dumps(tool_wire_event(
            events[-1], session_id="session-1", turn_id=2, seq=9
        ), ensure_ascii=False))

    async def test_ticket_free_text_is_excluded_from_serialized_wire_payload(self):
        reasons = (
            "地址：测试市测试路123号",
            "麻烦寄到测试市测试路123号",
            "请联系13800000001",
            "邮箱buyer@example.com",
            "商品破损需要售后",
            "麻烦寄到测试市测试路123号，联系13800000001，邮箱buyer@example.com",
        )
        allowed = {
            "ticket_id", "customer_id", "order_id", "status", "priority",
            "created_at", "updated_at", "urge_count", "last_urged_at", "reason",
        }
        for reason in reasons:
            with self.subTest(reason=reason):
                ticket = {
                    "ticket_id": "T1", "customer_id": "CUST001", "order_id": "ORD001",
                    "status": "处理中", "priority": "普通", "created_at": "2026-10-14T00:00:00Z",
                    "updated_at": "2026-10-14T01:00:00Z", "urge_count": 1,
                    "last_urged_at": "2026-10-14T01:00:00Z",
                    "reason": reason, "description": reason,
                    "history": [{"description": reason, "before": {"reason": reason}}],
                    "unexpected": {"nested": reason},
                }
                for tool_name, data in (
                    ("ticket_create", ticket),
                    ("ticket_lookup", {"ticket": ticket}),
                    ("ticket_lookup", {"tickets": [ticket]}),
                ):
                    wire = await self._ticket_wire(tool_name, data)
                    projected = wire["result_data"]["tickets"][0]
                    self.assertEqual(set(projected), allowed)
                    self.assertEqual(projected["ticket_id"], "T1")
                    self.assertEqual(projected["status"], "处理中")
                    self.assertEqual(projected["reason"], "售后问题已记录")
                    serialized = json.dumps(wire, ensure_ascii=False)
                    self.assertNotIn(reason, serialized)
                    self.assertNotIn("测试市测试路123号", serialized)
                    self.assertNotIn("13800000001", serialized)
                    self.assertNotIn("buyer@example.com", serialized)

    async def test_ticket_nested_payload_and_failure_exclude_unlabelled_address(self):
        sensitive = "麻烦寄到测试市测试路123号，联系13800000001"
        ticket = {
            "ticket_id": "T1", "status": "处理中", "reason": sensitive,
            "history": [{"description": sensitive}],
        }
        wire = await self._ticket_wire("ticket_lookup", {"tickets": [ticket]})

        def values(value):
            if isinstance(value, dict):
                for item in value.values():
                    yield from values(item)
            elif isinstance(value, list):
                for item in value:
                    yield from values(item)
            elif isinstance(value, str):
                yield value

        self.assertTrue(all("测试市测试路123号" not in item and "13800000001" not in item
                            for item in values(wire)))
        failed = await self._ticket_wire("ticket_lookup", {"ticket": ticket}, failure=True)
        self.assertEqual(failed["type"], "tool.failed")
        self.assertEqual(failed["error"]["message"], "工单工具执行失败")
        self.assertNotIn(sensitive, json.dumps(failed, ensure_ascii=False))

    async def test_real_ticket_lookup_to_wire_excludes_stored_free_text(self):
        sensitive = "麻烦寄到测试市测试路123号，联系13800000001"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tickets.json"
            path.write_text(json.dumps([{
                "ticket_id": "TKT-TEST", "customer_id": "CUST001",
                "reason": sensitive, "description": sensitive,
                "status": "待人工处理", "created_at": "2026-10-14T00:00:00Z",
                "history": [{"description": sensitive}],
            }], ensure_ascii=False), encoding="utf-8")
            with patch("agent.tools.ticket_service._service", return_value=TicketService(path)):
                response = ticket_lookup.invoke({
                    "type": "tool_call", "name": "ticket_lookup",
                    "id": "lookup-call", "args": {"ticket_id": "TKT-TEST"},
                })
            self.assertNotIn(sensitive, response.content)
            events = []
            adapter = LangGraphToolLifecycleAdapter(events.append)
            call = {"id": "lookup-call", "name": "ticket_lookup", "args": {"ticket_id": "TKT-TEST"}}
            await adapter.called(call)
            await adapter.finished(call, response)
            wire = json.loads(json.dumps(tool_wire_event(
                events[-1], session_id="session-1", turn_id=2, seq=9
            ), ensure_ascii=False))
            self.assertEqual(wire["result_data"]["tickets"][0]["reason"], "售后问题已记录")
            self.assertNotIn(sensitive, json.dumps(wire, ensure_ascii=False))

    async def test_ticket_lookup_exception_log_excludes_free_text(self):
        sensitive = "麻烦寄到测试市测试路123号，联系13800000001"
        with patch("agent.tools.ticket_service._service", side_effect=RuntimeError(sensitive)):
            with self.assertLogs("voice_agent.ticket_tools", level="ERROR") as captured:
                response = ticket_lookup.invoke({
                    "type": "tool_call", "name": "ticket_lookup",
                    "id": "lookup-call", "args": {"ticket_id": "TKT-TEST"},
                })
        self.assertNotIn(sensitive, " ".join(captured.output))
        events = []
        adapter = LangGraphToolLifecycleAdapter(events.append)
        call = {"id": "lookup-call", "name": "ticket_lookup", "args": {"ticket_id": "TKT-TEST"}}
        await adapter.called(call)
        await adapter.finished(call, response)
        wire = json.loads(json.dumps(tool_wire_event(
            events[-1], session_id="session-1", turn_id=2, seq=9
        ), ensure_ascii=False))
        self.assertEqual(wire["type"], "tool.failed")
        self.assertNotIn(sensitive, json.dumps(wire, ensure_ascii=False))


if __name__ == "__main__":
    unittest.main()
