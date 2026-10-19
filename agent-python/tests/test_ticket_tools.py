import asyncio
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import patch

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult

from agent.llm_agent import DeepSeekVoiceAgent
from agent.tools import customer_service
from agent.tools.customer_service import ticket_create
from agent.tools.ticket_service import (
    MAX_LOOKUP_LIMIT,
    TicketService,
    normalize_ticket,
    ticket_lookup,
    ticket_urge,
)
from tests.test_llm_agent import FakeToolCallingModel


BASE_TIME = datetime(2026, 10, 14, 1, 0, tzinfo=UTC)


def legacy_ticket(
    ticket_id="TKT-LEGACY",
    customer_id="CUST001",
    created_at="2026-10-01T10:00:00+08:00",
    **overrides,
):
    record = {
        "ticket_id": ticket_id,
        "customer_id": customer_id,
        "order_id": "ORD202610010001",
        "reason": "退款异常",
        "description": "旧工单",
        "status": "待人工处理",
        "created_at": created_at,
        "source": "voice_agent",
    }
    record.update(overrides)
    return record


class TicketServiceTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.tickets_file = Path(self.temporary_directory.name) / "tickets.json"
        self.tickets_file.write_text("[]\n", encoding="utf-8")
        self.file_patch = patch.object(customer_service, "TICKETS_FILE", self.tickets_file)
        self.file_patch.start()
        self.addCleanup(self.file_patch.stop)
        self.service = TicketService(self.tickets_file)

    def write(self, records):
        self.tickets_file.write_text(
            json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    def read(self):
        return json.loads(self.tickets_file.read_text(encoding="utf-8"))

    def create(self, operation_id="create-1", **overrides):
        arguments = {
            "customer_id": "CUST001",
            "order_id": "ORD202610010001",
            "reason": "退款异常",
            "description": "退款没有到账",
            "operation_id": operation_id,
            "now": BASE_TIME,
        }
        arguments.update(overrides)
        return self.service.create(**arguments)

    def test_create_valid_ticket_has_v2_schema_and_history(self):
        result = self.create()
        self.assertTrue(result["success"])
        record = self.read()[0]
        self.assertEqual(record["schema_version"], 2)
        self.assertEqual(record["priority"], "普通")
        self.assertEqual(record["urge_count"], 0)
        self.assertEqual(record["created_at"], "2026-10-14T01:00:00Z")
        self.assertEqual(record["updated_at"], record["created_at"])
        self.assertEqual(record["history"][0]["operation_id"], "create-1")
        self.assertEqual(record["history"][0]["action"], "created")

    def test_create_rejects_missing_customer_wrong_order_and_empty_description(self):
        missing = self.create(customer_id="CUST404")
        wrong_order = self.create(
            operation_id="create-2",
            customer_id="CUST002",
            order_id="ORD202610010001",
        )
        empty = self.create(operation_id="create-3", reason=" ", description=" ")
        self.assertEqual(missing["code"], "CUSTOMER_NOT_FOUND")
        self.assertEqual(wrong_order["code"], "ORDER_CUSTOMER_MISMATCH")
        self.assertEqual(empty["code"], "INVALID_ARGUMENT")
        self.assertEqual(self.read(), [])

    def test_create_idempotency_replays_and_conflicting_payload_is_rejected(self):
        first = self.create()
        replay = self.create()
        conflict = self.create(description="另一个问题")
        self.assertEqual(len(self.read()), 1)
        self.assertEqual(first["data"]["ticket_id"], replay["data"]["ticket_id"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(conflict["code"], "IDEMPOTENCY_CONFLICT")

    def test_old_schema_defaults_are_in_memory_only(self):
        original = legacy_ticket()
        self.write([original])
        before = self.tickets_file.read_bytes()
        result = self.service.lookup(ticket_id="TKT-LEGACY")
        ticket = result["data"]["ticket"]
        self.assertEqual(ticket["priority"], "普通")
        self.assertEqual(ticket["urge_count"], 0)
        self.assertEqual(ticket["history"], [])
        self.assertEqual(ticket["updated_at"], "2026-10-01T02:00:00Z")
        self.assertEqual(self.tickets_file.read_bytes(), before)

    def test_invalid_status_is_readable_but_not_writable(self):
        self.write([legacy_ticket(status="神秘状态")])
        lookup = self.service.lookup(ticket_id="TKT-LEGACY")
        urge = self.service.urge(
            ticket_id="TKT-LEGACY", operation_id="urge-invalid", now=BASE_TIME
        )
        self.assertTrue(lookup["success"])
        self.assertTrue(
            any(
                "未知状态" in warning
                for warning in lookup["data"]["ticket"]["data_warnings"]
            )
        )
        self.assertEqual(urge["code"], "DATA_INVALID")
        self.assertEqual(self.read()[0]["status"], "神秘状态")

    def test_lookup_by_id_and_customer_is_sorted_limited_and_read_only(self):
        records = [
            legacy_ticket("TKT-OLD", created_at="2026-10-01T00:00:00Z"),
            legacy_ticket("TKT-NEW", created_at="2026-10-03T00:00:00Z"),
            legacy_ticket("TKT-MID", created_at="2026-10-02T00:00:00Z"),
        ]
        self.write(records)
        before = self.tickets_file.read_bytes()
        exact = self.service.lookup(ticket_id="TKT-MID")
        recent = self.service.lookup(customer_id="CUST001", limit=2)
        capped = self.service.lookup(customer_id="CUST001", limit=999)
        self.assertEqual(exact["data"]["ticket"]["ticket_id"], "TKT-MID")
        self.assertEqual(
            [item["ticket_id"] for item in recent["data"]["tickets"]],
            ["TKT-NEW", "TKT-MID"],
        )
        self.assertEqual(capped["data"]["limit"], MAX_LOOKUP_LIMIT)
        self.assertEqual(self.tickets_file.read_bytes(), before)

    def test_lookup_rejects_ambiguous_or_unknown_queries(self):
        ambiguous = self.service.lookup(ticket_id="T1", customer_id="CUST001")
        missing_ticket = self.service.lookup(ticket_id="T404")
        missing_customer = self.service.lookup(customer_id="CUST404")
        self.assertEqual(ambiguous["code"], "AMBIGUOUS_QUERY")
        self.assertEqual(missing_ticket["code"], "TICKET_NOT_FOUND")
        self.assertEqual(missing_customer["code"], "CUSTOMER_NOT_FOUND")

    def test_urge_upgrades_priority_once_and_appends_one_history(self):
        created = self.create()
        ticket_id = created["data"]["ticket_id"]
        result = self.service.urge(
            ticket_id=ticket_id,
            reason="用户等待较久",
            operation_id="urge-1",
            now=BASE_TIME + timedelta(minutes=2),
        )
        record = self.read()[0]
        self.assertEqual(result["data"]["priority_before"], "普通")
        self.assertEqual(result["data"]["priority_after"], "高")
        self.assertEqual(record["urge_count"], 1)
        self.assertEqual(len(record["history"]), 2)
        self.assertEqual(record["history"][-1]["operation_id"], "urge-1")

    def test_urge_same_operation_replays_without_modification(self):
        ticket_id = self.create()["data"]["ticket_id"]
        first = self.service.urge(
            ticket_id=ticket_id, operation_id="urge-1", now=BASE_TIME + timedelta(minutes=2)
        )
        after_first = self.tickets_file.read_bytes()
        replay = self.service.urge(
            ticket_id=ticket_id, operation_id="urge-1", now=BASE_TIME + timedelta(minutes=4)
        )
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(first["data"], replay["data"])
        self.assertEqual(self.tickets_file.read_bytes(), after_first)

    def test_urge_different_operation_inside_window_is_debounced_and_persisted(self):
        ticket_id = self.create()["data"]["ticket_id"]
        self.service.urge(
            ticket_id=ticket_id, operation_id="urge-1", now=BASE_TIME + timedelta(minutes=2)
        )
        result = self.service.urge(
            ticket_id=ticket_id,
            operation_id="urge-2",
            now=BASE_TIME + timedelta(minutes=2, seconds=30),
        )
        record = self.read()[0]
        self.assertEqual(result["code"], "URGE_DEBOUNCED")
        self.assertFalse(result["idempotent_replay"])
        self.assertEqual(record["urge_count"], 1)
        self.assertEqual(len(record["history"]), 2)
        self.assertEqual(len(record["operations"]), 3)
        replay = self.service.urge(
            ticket_id=ticket_id, operation_id="urge-2", now=BASE_TIME + timedelta(minutes=3)
        )
        self.assertTrue(replay["idempotent_replay"])

    def test_urge_after_window_and_at_max_priority(self):
        record = normalize_ticket(legacy_ticket(priority="紧急"))
        record.pop("_validation_errors")
        self.write([record])
        result = self.service.urge(
            ticket_id="TKT-LEGACY", operation_id="urge-max", now=BASE_TIME
        )
        stored = self.read()[0]
        self.assertEqual(result["data"]["priority_after"], "紧急")
        self.assertEqual(stored["urge_count"], 1)
        self.assertEqual(len(stored["history"]), 1)

    def test_new_operation_after_window_is_effective(self):
        ticket_id = self.create()["data"]["ticket_id"]
        self.service.urge(
            ticket_id=ticket_id, operation_id="urge-1", now=BASE_TIME + timedelta(minutes=2)
        )
        result = self.service.urge(
            ticket_id=ticket_id,
            operation_id="urge-2",
            now=BASE_TIME + timedelta(minutes=3, seconds=1),
        )
        record = self.read()[0]
        self.assertEqual(result["code"], "TICKET_URGED")
        self.assertEqual(result["data"]["priority_after"], "紧急")
        self.assertEqual(record["urge_count"], 2)
        self.assertEqual(len(record["history"]), 3)

    def test_urge_operation_id_conflict_is_rejected(self):
        ticket_id = self.create()["data"]["ticket_id"]
        self.service.urge(
            ticket_id=ticket_id,
            reason="第一次",
            operation_id="urge-conflict",
            now=BASE_TIME + timedelta(minutes=2),
        )
        conflict = self.service.urge(
            ticket_id=ticket_id,
            reason="不同参数",
            operation_id="urge-conflict",
            now=BASE_TIME + timedelta(minutes=4),
        )
        self.assertEqual(conflict["code"], "IDEMPOTENCY_CONFLICT")
        self.assertEqual(self.read()[0]["urge_count"], 1)

    def test_closed_or_missing_ticket_cannot_be_urged(self):
        self.write([legacy_ticket(status="已关闭")])
        before = self.tickets_file.read_bytes()
        closed = self.service.urge(
            ticket_id="TKT-LEGACY", operation_id="urge-closed", now=BASE_TIME
        )
        missing = self.service.urge(
            ticket_id="TKT-404", operation_id="urge-missing", now=BASE_TIME
        )
        self.assertEqual(closed["code"], "TICKET_NOT_OPEN")
        self.assertEqual(missing["code"], "TICKET_NOT_FOUND")
        self.assertEqual(self.tickets_file.read_bytes(), before)

    def test_corrupt_json_is_not_treated_as_empty_or_overwritten(self):
        corrupt = b'{"broken":\n'
        self.tickets_file.write_bytes(corrupt)
        with self.assertRaises(RuntimeError):
            self.service.create(
                customer_id="CUST001",
                reason="投诉",
                operation_id="corrupt-create",
                now=BASE_TIME,
            )
        self.assertEqual(self.tickets_file.read_bytes(), corrupt)

    def test_replace_failure_keeps_original_and_cleans_temp_file(self):
        original = self.tickets_file.read_bytes()
        with patch("agent.tools.customer_service.os.replace", side_effect=OSError("disk")):
            with self.assertRaises(RuntimeError):
                self.create()
        self.assertEqual(self.tickets_file.read_bytes(), original)
        self.assertEqual(list(self.tickets_file.parent.glob(".tickets.*.tmp")), [])

    def test_failed_commit_can_retry_same_operation_without_partial_record(self):
        with patch.object(
            customer_service, "_write_records", side_effect=RuntimeError("disk")
        ):
            with self.assertRaises(RuntimeError):
                self.create()
        self.assertEqual(self.read(), [])
        successful = self.create()
        replay = self.create()
        self.assertTrue(successful["success"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(len(self.read()), 1)

    def test_concurrent_creates_do_not_lose_updates(self):
        def create(index):
            return TicketService(self.tickets_file).create(
                customer_id="CUST001",
                reason=f"问题 {index}",
                operation_id=f"parallel-{index}",
                now=BASE_TIME + timedelta(seconds=index),
            )

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(create, range(20)))
        self.assertTrue(all(item["success"] for item in results))
        records = self.read()
        self.assertEqual(len(records), 20)
        self.assertEqual(len({item["ticket_id"] for item in records}), 20)

    def test_concurrent_same_operation_commits_once(self):
        def create(_):
            return TicketService(self.tickets_file).create(
                customer_id="CUST001",
                reason="相同请求",
                operation_id="parallel-same",
                now=BASE_TIME,
            )

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(create, range(12)))
        self.assertEqual(len(self.read()), 1)
        self.assertEqual(sum(not item["idempotent_replay"] for item in results), 1)
        self.assertEqual(sum(item["idempotent_replay"] for item in results), 11)

    def test_writing_legacy_target_upgrades_only_that_record(self):
        untouched = legacy_ticket("TKT-OTHER", customer_id="CUST002", order_id=None)
        target = legacy_ticket()
        self.write([target, untouched])
        self.service.urge(
            ticket_id="TKT-LEGACY", operation_id="legacy-urge", now=BASE_TIME
        )
        records = self.read()
        self.assertEqual(records[0]["schema_version"], 2)
        self.assertIn("history", records[0])
        self.assertNotIn("schema_version", records[1])
        self.assertNotIn("history", records[1])


class TicketToolContractTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.tickets_file = Path(self.temporary_directory.name) / "tickets.json"
        self.tickets_file.write_text("[]\n", encoding="utf-8")
        self.file_patch = patch.object(customer_service, "TICKETS_FILE", self.tickets_file)
        self.file_patch.start()
        self.addCleanup(self.file_patch.stop)

    async def test_tools_expose_text_and_structured_artifact(self):
        create_message = await ticket_create.ainvoke(
            {
                "type": "tool_call",
                "name": "ticket_create",
                "id": "create-call",
                "args": {"customer_id": "CUST001", "reason": "投诉"},
            }
        )
        self.assertIsInstance(create_message, ToolMessage)
        self.assertEqual(create_message.artifact["code"], "TICKET_CREATED")
        ticket_id = create_message.artifact["data"]["ticket_id"]
        lookup_message = ticket_lookup.invoke(
            {
                "type": "tool_call",
                "name": "ticket_lookup",
                "id": "lookup-call",
                "args": {"ticket_id": ticket_id},
            }
        )
        urge_message = await ticket_urge.ainvoke(
            {
                "type": "tool_call",
                "name": "ticket_urge",
                "id": "urge-call",
                "args": {"ticket_id": ticket_id},
            }
        )
        self.assertEqual(lookup_message.artifact["code"], "TICKET_FOUND")
        self.assertEqual(urge_message.artifact["code"], "TICKET_URGED")
        self.assertIn("工单", lookup_message.content)

    async def test_tool_failure_uses_business_code_without_internal_exception(self):
        message = await ticket_urge.ainvoke(
            {
                "type": "tool_call",
                "name": "ticket_urge",
                "id": "urge-call",
                "args": {"ticket_id": "TKT404"},
            }
        )
        self.assertEqual(message.artifact["code"], "TICKET_NOT_FOUND")
        self.assertNotIn("Traceback", message.content)

    async def test_create_write_failure_returns_structured_failure(self):
        with self.assertLogs("voice_agent.customer_tools", level="ERROR"):
            with patch.object(
                customer_service, "_write_records", side_effect=RuntimeError("disk")
            ):
                message = await ticket_create.ainvoke(
                    {
                        "type": "tool_call",
                        "name": "ticket_create",
                        "id": "create-failed",
                        "args": {"customer_id": "CUST001", "reason": "投诉"},
                    }
                )
        self.assertFalse(message.artifact["success"])
        self.assertEqual(message.artifact["code"], "STORAGE_WRITE_FAILED")
        self.assertEqual(
            json.loads(self.tickets_file.read_text(encoding="utf-8")), []
        )


class LifecycleFakeModel(BaseChatModel):
    """Choose the next ticket tool from user text and prior structured results."""

    requested_tools: list[str] = None

    def __init__(self):
        super().__init__()
        self.requested_tools = []

    @property
    def _llm_type(self):
        return "ticket-lifecycle-fake"

    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        self.requested_tools = [item.name for item in tools]
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise AssertionError("tests use the async model path")

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        last = messages[-1]
        if isinstance(last, ToolMessage):
            artifact = last.artifact
            if artifact["success"]:
                return ChatResult(
                    generations=[ChatGeneration(message=AIMessage(content=artifact["message"]))]
                )
            return ChatResult(
                generations=[ChatGeneration(message=AIMessage(content=f"操作失败：{artifact['message']}"))]
            )
        user_text = last.content
        ticket_id = None
        for message in reversed(messages):
            if isinstance(message, ToolMessage) and message.artifact:
                data = message.artifact.get("data") or {}
                ticket_id = data.get("ticket_id")
                if ticket_id is None and isinstance(data.get("ticket"), dict):
                    ticket_id = data["ticket"].get("ticket_id")
                if ticket_id:
                    break
        if "创建" in user_text:
            tool_call = {"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "退款异常", "description": "退款未到账"}, "id": "create-lifecycle"}
        elif "催办" in user_text:
            tool_call = {"name": "ticket_urge", "args": {"ticket_id": ticket_id}, "id": "urge-lifecycle"}
        else:
            tool_call = {"name": "ticket_lookup", "args": {"ticket_id": ticket_id}, "id": f"lookup-{len(messages)}"}
        return ChatResult(
            generations=[ChatGeneration(message=AIMessage(content="", tool_calls=[tool_call]))]
        )


class TicketAgentIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.tickets_file = Path(self.temporary_directory.name) / "tickets.json"
        self.tickets_file.write_text("[]\n", encoding="utf-8")
        self.file_patch = patch.object(customer_service, "TICKETS_FILE", self.tickets_file)
        self.file_patch.start()

    async def asyncTearDown(self):
        self.file_patch.stop()
        self.temporary_directory.cleanup()

    async def collect(self, agent, text):
        return "".join([chunk async for chunk in agent.stream(text)])

    async def test_agent_discovers_and_executes_all_ticket_tools(self):
        with patch("agent.tools.ticket_service.uuid4") as fake_uuid:
            fake_uuid.return_value.hex = "ABCDEF1234567890"
            model = FakeToolCallingModel(
                [
                    AIMessage(content="", tool_calls=[{"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "投诉"}, "id": "c1"}]),
                    AIMessage(content="已创建。"),
                    AIMessage(content="", tool_calls=[{"name": "ticket_lookup", "args": {"ticket_id": "TKT20261014"}, "id": "q1"}]),
                    AIMessage(content="未找到。"),
                    AIMessage(content="", tool_calls=[{"name": "ticket_urge", "args": {"ticket_id": "TKT404"}, "id": "u1"}]),
                    AIMessage(content="催办失败。"),
                ]
            )
            agent = DeepSeekVoiceAgent(model=model, tools=[ticket_create, ticket_lookup, ticket_urge])
            self.assertEqual(await self.collect(agent, "创建工单"), "已创建。")
            self.assertEqual(await self.collect(agent, "查询工单"), "未找到。")
            self.assertEqual(await self.collect(agent, "催办工单"), "催办失败。")
        tool_names = [
            message.name
            for message in agent.history
            if isinstance(message, ToolMessage)
        ]
        self.assertEqual(tool_names, ["ticket_create", "ticket_lookup", "ticket_urge"])
        self.assertIn("未找到工单", model.seen_messages[-1][-1].content)

    async def test_natural_language_create_lookup_urge_lookup_lifecycle(self):
        model = LifecycleFakeModel()
        agent = DeepSeekVoiceAgent(
            model=model, tools=[ticket_create, ticket_lookup, ticket_urge]
        )
        created = await self.collect(agent, "请为 CUST001 创建退款异常工单")
        ticket_id = json.loads(self.tickets_file.read_text(encoding="utf-8"))[0]["ticket_id"]
        looked_up = await self.collect(agent, "查询刚才工单的当前状态")
        urged = await self.collect(agent, "请催办刚才的工单")
        looked_up_again = await self.collect(agent, "再次查询工单状态")
        self.assertIn(ticket_id, created)
        self.assertIn("处理状态：待人工处理", looked_up)
        self.assertIn("优先级从普通调整为高", urged)
        self.assertIn("优先级：高", looked_up_again)
        self.assertEqual(
            model.requested_tools,
            ["ticket_create", "ticket_lookup", "ticket_urge"],
        )

    async def test_duplicate_model_calls_share_operation_id_and_create_once(self):
        call = {"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "投诉"}}
        model = FakeToolCallingModel(
            [
                AIMessage(content="", tool_calls=[{**call, "id": "c1"}, {**call, "id": "c2"}]),
                AIMessage(content="已创建。"),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[ticket_create])
        await self.collect(agent, "请创建投诉工单")
        records = json.loads(self.tickets_file.read_text(encoding="utf-8"))
        self.assertEqual(len(records), 1)
        tool_messages = [item for item in agent.history if isinstance(item, ToolMessage)]
        self.assertEqual(len(tool_messages), 2)
        self.assertEqual(
            {item.artifact["operation_id"] for item in tool_messages},
            {records[0]["history"][0]["operation_id"]},
        )
        self.assertEqual(
            sorted(item.artifact["idempotent_replay"] for item in tool_messages),
            [False, True],
        )

    async def test_completed_same_text_is_a_new_business_operation(self):
        call = {"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "投诉"}}
        model = FakeToolCallingModel(
            [
                AIMessage(content="", tool_calls=[{**call, "id": "c1"}]),
                AIMessage(content="已创建。"),
                AIMessage(content="", tool_calls=[{**call, "id": "c2"}]),
                AIMessage(content="已确认。"),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[ticket_create])
        await self.collect(agent, "请创建投诉工单")
        await self.collect(agent, "请创建投诉工单")
        records = json.loads(self.tickets_file.read_text(encoding="utf-8"))
        self.assertEqual(len(records), 2)
        tool_messages = [item for item in agent.history if isinstance(item, ToolMessage)]
        self.assertFalse(tool_messages[-1].artifact["idempotent_replay"])
        self.assertNotEqual(
            tool_messages[-2].artifact["operation_id"],
            tool_messages[-1].artifact["operation_id"],
        )

    async def test_cancel_after_write_then_same_text_reuses_operation(self):
        entered = asyncio.Event()
        release = asyncio.Event()
        call = {"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "投诉"}}
        model = FakeToolCallingModel(
            [
                AIMessage(content="", tool_calls=[{**call, "id": "c1"}]),
                (entered, release, AIMessage(content="不会提交")),
                AIMessage(content="", tool_calls=[{**call, "id": "c2"}]),
                AIMessage(content="已确认。"),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[ticket_create])
        task = asyncio.create_task(self.collect(agent, "请创建投诉工单"))
        await asyncio.wait_for(entered.wait(), 2)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(agent.history, ())
        self.assertEqual(len(json.loads(self.tickets_file.read_text(encoding="utf-8"))), 1)

        await self.collect(agent, "请创建投诉工单")
        records = json.loads(self.tickets_file.read_text(encoding="utf-8"))
        self.assertEqual(len(records), 1)
        tool_message = next(
            item for item in reversed(agent.history) if isinstance(item, ToolMessage)
        )
        self.assertTrue(tool_message.artifact["idempotent_replay"])

    async def test_same_logical_operation_with_changed_args_conflicts(self):
        model = FakeToolCallingModel(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "投诉一"}, "id": "c1"},
                        {"name": "ticket_create", "args": {"customer_id": "CUST001", "reason": "投诉二"}, "id": "c2"},
                    ],
                ),
                AIMessage(content="已处理。"),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[ticket_create])
        await self.collect(agent, "创建一张投诉工单")
        records = json.loads(self.tickets_file.read_text(encoding="utf-8"))
        messages = [item for item in agent.history if isinstance(item, ToolMessage)]
        self.assertEqual(len(records), 1)
        self.assertEqual(
            {item.artifact["code"] for item in messages},
            {"TICKET_CREATED", "IDEMPOTENCY_CONFLICT"},
        )


if __name__ == "__main__":
    unittest.main()
