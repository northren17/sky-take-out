import asyncio
import unittest
from collections import deque

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import tool

from agent.llm_agent import (
    DeepSeekVoiceAgent,
    IncompleteToolHistoryError,
    ToolExecutionTimeout,
    validate_tool_history,
)


class FakeToolCallingModel(BaseChatModel):
    """A deterministic fake model with optional per-call gates."""

    responses: deque = None
    seen_messages: list = None

    def __init__(self, responses):
        super().__init__()
        self.responses = deque(responses)
        self.seen_messages = []

    @property
    def _llm_type(self):
        return "phase1-fake-tool-model"

    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise AssertionError("tests use the async model path")

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        self.seen_messages.append(list(messages))
        response = self.responses.popleft()
        if isinstance(response, tuple):
            entered, release, response = response
            entered.set()
            await release.wait()
        return ChatResult(generations=[ChatGeneration(message=response)])


class AgentHistoryTests(unittest.IsolatedAsyncioTestCase):
    def test_default_tool_fallback_is_disabled_without_session_scoped_tools(self):
        with self.assertRaisesRegex(ValueError, "session-bound tools"):
            DeepSeekVoiceAgent(model=object())

    async def collect(self, agent, text):
        return "".join([chunk async for chunk in agent.stream(text)])

    async def test_successful_tool_round_is_committed_as_complete_sequence(self):
        @tool
        async def fake_tool(value: str) -> str:
            """Return a deterministic value."""
            return f"result:{value}"

        model = FakeToolCallingModel(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {"name": "fake_tool", "args": {"value": "x"}, "id": "call-1"}
                    ],
                ),
                AIMessage(content="complete"),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[fake_tool])
        self.assertEqual(await self.collect(agent, "question"), "complete")
        self.assertEqual(
            [type(message).__name__ for message in agent.history],
            ["HumanMessage", "AIMessage", "ToolMessage", "AIMessage"],
        )
        validate_tool_history(agent.history)

    async def test_cancel_during_tool_discards_entire_turn(self):
        started = asyncio.Event()

        @tool
        async def fake_slow_tool(value: str) -> str:
            """Block so the test can cancel at the tool boundary."""
            started.set()
            await asyncio.sleep(30)
            return value

        model = FakeToolCallingModel(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "fake_slow_tool",
                            "args": {"value": "old"},
                            "id": "call-old",
                        }
                    ],
                ),
                AIMessage(content="new answer"),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[fake_slow_tool])
        task = asyncio.create_task(self.collect(agent, "old question"))
        await asyncio.wait_for(started.wait(), 1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(agent.history, ())

        self.assertEqual(await self.collect(agent, "new question"), "new answer")
        self.assertEqual(
            [message.content for message in model.seen_messages[-1] if isinstance(message, HumanMessage)],
            ["new question"],
        )
        validate_tool_history(agent.history)

    async def test_cancel_after_tool_result_before_next_model_discards_turn(self):
        second_model_started = asyncio.Event()
        release_second_model = asyncio.Event()

        @tool
        async def fake_fast_tool(value: str) -> str:
            """Complete immediately to reach the post-tool model boundary."""
            return value

        model = FakeToolCallingModel(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "fake_fast_tool",
                            "args": {"value": "done"},
                            "id": "call-done",
                        }
                    ],
                ),
                (
                    second_model_started,
                    release_second_model,
                    AIMessage(content="should not commit"),
                ),
            ]
        )
        agent = DeepSeekVoiceAgent(model=model, tools=[fake_fast_tool])
        task = asyncio.create_task(self.collect(agent, "cancel after tool"))
        await asyncio.wait_for(second_model_started.wait(), 1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(agent.history, ())

    async def test_tool_timeout_raises_classified_error_without_history(self):
        @tool
        async def fake_timeout_tool(value: str) -> str:
            """Exceed the configured timeout."""
            await asyncio.sleep(30)
            return value

        model = FakeToolCallingModel(
            [
                AIMessage(
                    content="",
                    tool_calls=[
                        {
                            "name": "fake_timeout_tool",
                            "args": {"value": "x"},
                            "id": "call-timeout",
                        }
                    ],
                )
            ]
        )
        agent = DeepSeekVoiceAgent(
            model=model,
            tools=[fake_timeout_tool],
            tool_timeout_seconds=0.02,
        )
        with self.assertRaises(ToolExecutionTimeout):
            await self.collect(agent, "timeout")
        self.assertEqual(agent.history, ())

    def test_history_validator_rejects_missing_tool_result(self):
        messages = [
            HumanMessage(content="question"),
            AIMessage(
                content="",
                tool_calls=[{"name": "fake", "args": {}, "id": "missing"}],
            ),
        ]
        with self.assertRaises(IncompleteToolHistoryError):
            validate_tool_history(messages)

        validate_tool_history(
            [
                *messages,
                ToolMessage(content="cancelled", tool_call_id="missing"),
                AIMessage(content="done"),
            ]
        )


if __name__ == "__main__":
    unittest.main()
