import asyncio
import unittest

from agent.events import (
    AUDIO_FRAME_MAGIC,
    ToolBusinessStatus,
    ToolExecutionStatus,
    ToolLifecycleEvent,
    TtsEvent,
    TurnState,
)
from agent.session import (
    OutgoingBackpressureError,
    ProviderConnectionError,
    SessionLimits,
    VoiceSession,
)
from tests.fakes import FakeAsr, FakeTts, FakeWebSocket, ScriptedAgent, wait_until


def compact_limits(**overrides):
    values = {
        "connect_timeout": 0.5,
        "llm_timeout": 0.5,
        "tts_operation_timeout": 0.2,
        "tts_response_timeout": 0.5,
        "close_timeout": 0.2,
        "send_queue_size": 16,
        "send_queue_timeout": 0.2,
        "final_dedupe_seconds": 0.2,
    }
    values.update(overrides)
    return SessionLimits(**values)


class VoiceSessionTests(unittest.IsolatedAsyncioTestCase):
    async def make_session(self, plans, *, limits=None, websocket=None):
        websocket = websocket or FakeWebSocket()
        asr = FakeAsr()
        tts = FakeTts()
        agent = ScriptedAgent(plans)
        session = VoiceSession(
            websocket,
            asr=asr,
            tts=tts,
            agent=agent,
            limits=limits or compact_limits(),
        )
        await session.start()
        await wait_until(lambda: len(websocket.sent_json) >= 2)
        self.addAsyncCleanup(session.close)
        return session, websocket, asr, tts, agent

    async def test_two_turns_cancel_old_and_complete_new(self):
        release_old = asyncio.Event()
        session, websocket, asr, tts, agent = await self.make_session(
            [["old", release_old], ["new"]]
        )
        await asr.emit(asr.transcript("first", final=True))
        await wait_until(lambda: tts.appended == [(1, "old")])

        await asr.emit(asr.transcript("second", final=False))
        await wait_until(lambda: session.active_turn.turn_id == 2)
        await asr.emit(asr.transcript("second", final=True))
        await wait_until(lambda: tts.committed == [2])
        self.assertEqual(agent.cancelled_calls, ["first"])
        self.assertIn(1, tts.cancelled)

        await tts.emit(TtsEvent("started", 2, "response-2"))
        await tts.emit(TtsEvent("audio", 2, "response-2", b"\x01\x02"))
        await tts.emit(TtsEvent("audio_finished", 2, "response-2"))
        await tts.emit(TtsEvent("finished", 2, "response-2"))
        await wait_until(lambda: session.active_turn.state == TurnState.COMPLETED)
        await wait_until(lambda: len(websocket.sent_bytes) == 1)
        self.assertEqual(websocket.sent_bytes[0][:4], AUDIO_FRAME_MAGIC)
        self.assertEqual(int.from_bytes(websocket.sent_bytes[0][4:8], "big"), 2)
        self.assertEqual(websocket.sent_bytes[0][8:], b"\x01\x02")

        events = websocket.sent_json
        self.assertTrue(all({"session_id", "turn_id", "timestamp"} <= event.keys() for event in events))
        self.assertEqual(
            len([event for event in events if event["type"] == "tts.finished"]),
            1,
        )

    async def test_duplicate_final_does_not_create_duplicate_agent(self):
        session, _, asr, tts, agent = await self.make_session([["answer"]])
        event = asr.transcript("same", final=True)
        await asr.emit(event)
        await asr.emit(event)
        await wait_until(lambda: tts.committed == [1])
        self.assertEqual(agent.calls, ["same"])
        self.assertEqual(session.active_turn.turn_id, 1)

    async def test_same_text_with_new_partial_is_a_real_new_turn(self):
        session, _, asr, tts, agent = await self.make_session([["one"], ["two"]])
        await asr.emit(asr.transcript("repeat", final=True))
        await wait_until(lambda: tts.committed == [1])
        await asr.emit(asr.transcript("repeat", final=False))
        await asr.emit(asr.transcript("repeat", final=True))
        await wait_until(lambda: tts.committed == [1, 2])
        self.assertEqual(agent.calls, ["repeat", "repeat"])

    async def test_interrupt_during_thinking_converges_to_cancelled(self):
        release = asyncio.Event()
        session, websocket, asr, _, agent = await self.make_session([[release, "late"]])
        await asr.emit(asr.transcript("question", final=True))
        await wait_until(lambda: agent.calls == ["question"])
        await session.interrupt()
        self.assertEqual(session.active_turn.state, TurnState.CANCELLED)
        release.set()
        await asyncio.sleep(0)
        self.assertFalse(any(event["type"] == "agent.delta" for event in websocket.sent_json))

    async def test_interrupt_during_collection_drops_late_final(self):
        session, _, asr, tts, agent = await self.make_session([["new answer"]])
        await asr.emit(asr.transcript("cancelled utterance", final=False))
        await wait_until(lambda: session.active_turn is not None)

        old_generation = asr.input_generation
        await session.interrupt()
        self.assertEqual(asr.restart_calls, 1)
        await asr.emit(asr.transcript(
            "cancelled utterance", final=True, generation=old_generation,
        ))
        await asyncio.sleep(0.01)
        self.assertEqual(agent.calls, [])
        self.assertEqual(session.active_turn.state, TurnState.CANCELLED)

        await asr.emit(asr.transcript("new utterance", final=False))
        await asr.emit(asr.transcript("new utterance", final=True))
        await wait_until(lambda: tts.committed == [2])
        self.assertEqual(agent.calls, ["new utterance"])

    async def test_unexpected_provider_stream_end_wakes_route_for_cleanup(self):
        session, _, asr, _, _ = await self.make_session([["unused"]])
        await asr.emit(None)
        failure = await asyncio.wait_for(session.wait_failure(), 1)
        self.assertIsInstance(failure, ProviderConnectionError)
        self.assertIn("voice-asr-consumer", str(failure))

        await session.close()
        session, _, _, tts, _ = await self.make_session([["unused"]])
        await tts.emit(None)
        failure = await asyncio.wait_for(session.wait_failure(), 1)
        self.assertIsInstance(failure, ProviderConnectionError)
        self.assertIn("voice-tts-consumer", str(failure))

    async def test_late_old_tts_events_are_filtered_after_interrupt(self):
        session, websocket, asr, tts, _ = await self.make_session([["answer"]])
        await asr.emit(asr.transcript("question", final=True))
        await wait_until(lambda: tts.committed == [1])
        await session.interrupt()
        baseline = len(websocket.sent_bytes)
        await tts.emit(TtsEvent("audio", 1, "old", b"old"))
        await tts.emit(TtsEvent("finished", 1, "old"))
        await asyncio.sleep(0.01)
        self.assertEqual(len(websocket.sent_bytes), baseline)
        self.assertFalse(any(event["type"] == "tts.finished" for event in websocket.sent_json))

    async def test_new_speech_clears_audio_after_generation_completed(self):
        session, websocket, asr, tts, _ = await self.make_session([["one"], ["two"]])
        await asr.emit(asr.transcript("first", final=True))
        await wait_until(lambda: tts.committed == [1])
        await tts.emit(TtsEvent("finished", 1, "response-1"))
        await wait_until(lambda: session.active_turn.state == TurnState.COMPLETED)
        baseline = len(
            [event for event in websocket.sent_json if event["type"] == "playback.clear"]
        )
        await asr.emit(asr.transcript("second", final=False))
        await wait_until(lambda: session.active_turn.turn_id == 2)
        await wait_until(
            lambda: len(
                [
                    event
                    for event in websocket.sent_json
                    if event["type"] == "playback.clear"
                ]
            )
            == baseline + 1
        )
        clears = [
            event for event in websocket.sent_json if event["type"] == "playback.clear"
        ]
        self.assertEqual(len(clears), baseline + 1)
        self.assertEqual(clears[-1]["turn_id"], 1)

    async def test_llm_and_tts_response_timeouts_recover_to_listening(self):
        never = asyncio.Event()
        limits = compact_limits(llm_timeout=0.02, tts_response_timeout=0.02)
        session, websocket, asr, _, _ = await self.make_session([[never]], limits=limits)
        await asr.emit(asr.transcript("slow llm", final=True))
        await wait_until(
            lambda: any(event.get("source") == "llm" for event in websocket.sent_json)
        )
        self.assertEqual(session.active_turn.state, TurnState.TIMED_OUT)

        await session.close()
        session, websocket, asr, tts, _ = await self.make_session([["text"]], limits=limits)
        await asr.emit(asr.transcript("slow tts", final=True))
        await wait_until(lambda: tts.committed == [1])
        await wait_until(
            lambda: any(event.get("source") == "tts" for event in websocket.sent_json)
        )
        self.assertEqual(session.active_turn.state, TurnState.TIMED_OUT)

    async def test_slow_client_triggers_bounded_queue_failure(self):
        websocket = FakeWebSocket(blocked=True)
        limits = compact_limits(send_queue_size=1, send_queue_timeout=0.02)
        asr, tts, agent = FakeAsr(), FakeTts(), ScriptedAgent([["unused"]])
        session = VoiceSession(
            websocket,
            asr=asr,
            tts=tts,
            agent=agent,
            limits=limits,
        )
        await session.start()
        with self.assertRaises(OutgoingBackpressureError):
            await session._send(session._event("test.control"))
        failure = await session.wait_failure()
        self.assertIsInstance(failure, OutgoingBackpressureError)
        websocket.unblock()
        await session.close()

    async def test_two_sessions_do_not_share_state_or_audio(self):
        first = await self.make_session([["one"]])
        second = await self.make_session([["two"]])
        session1, ws1, asr1, tts1, _ = first
        session2, ws2, asr2, tts2, _ = second
        await asr1.emit(asr1.transcript("first", final=True))
        await asr2.emit(asr2.transcript("second", final=True))
        await wait_until(lambda: tts1.committed == [1] and tts2.committed == [1])
        await tts1.emit(TtsEvent("audio", 1, "r1", b"one"))
        await tts2.emit(TtsEvent("audio", 1, "r2", b"two"))
        await wait_until(lambda: ws1.sent_bytes and ws2.sent_bytes)
        self.assertEqual(ws1.sent_bytes[0][8:], b"one")
        self.assertEqual(ws2.sent_bytes[0][8:], b"two")
        self.assertNotEqual(session1.session_id, session2.session_id)

    async def test_close_is_idempotent_and_releases_providers(self):
        session, _, _, tts, _ = await self.make_session([["unused"]])
        asr = session._asr
        await session.close()
        await session.close()
        self.assertTrue(asr.closed)
        self.assertTrue(tts.closed)

    async def test_session_wide_provider_error_wakes_route_for_cleanup(self):
        session, websocket, asr, _, _ = await self.make_session([["unused"]])
        await asr.emit(asr.error("connection lost"))
        failure = await asyncio.wait_for(session.wait_failure(), 1)
        self.assertIsInstance(failure, ProviderConnectionError)
        await wait_until(
            lambda: any(event.get("source") == "asr" for event in websocket.sent_json)
        )

    async def test_tool_events_and_citations_reach_the_same_turn(self):
        class ObservedAgent:
            supports_tool_events = True

            async def stream(self, text, *, on_tool_event):
                common = {
                    "call_id": "call-1",
                    "tool_call_id": "tool-1",
                    "tool_name": "policy_search",
                    "safe_args": {"query": {"provided": True, "length": 4}},
                }
                await on_tool_event(
                    ToolLifecycleEvent(
                        event_type="tool.called",
                        status=ToolExecutionStatus.RUNNING,
                        **common,
                    )
                )
                await on_tool_event(
                    ToolLifecycleEvent(
                        event_type="tool.finished",
                        status=ToolExecutionStatus.COMPLETED,
                        business_status=ToolBusinessStatus.HIT,
                        duration_ms=3,
                        citations=(
                            {
                                "citation_id": "citation-1",
                                "policy_id": "RETURN_001",
                                "title": "七天无理由退货",
                            },
                        ),
                        **common,
                    )
                )
                yield "answer"

        websocket, asr, tts = FakeWebSocket(), FakeAsr(), FakeTts()
        session = VoiceSession(
            websocket,
            asr=asr,
            tts=tts,
            agent=ObservedAgent(),
            limits=compact_limits(),
        )
        await session.start()
        self.addAsyncCleanup(session.close)
        await asr.emit(asr.transcript("退货政策", final=True))
        await wait_until(lambda: tts.committed == [1])
        await wait_until(
            lambda: any(
                event["type"] == "agent.finished" for event in websocket.sent_json
            )
        )
        types = [event["type"] for event in websocket.sent_json]
        self.assertLess(types.index("tool.called"), types.index("tool.finished"))
        self.assertLess(types.index("tool.finished"), types.index("rag.citations"))
        finished = next(event for event in websocket.sent_json if event["type"] == "tool.finished")
        self.assertEqual(finished["call_id"], "call-1")
        self.assertEqual(finished["turn_id"], 1)
        agent_finished = next(event for event in websocket.sent_json if event["type"] == "agent.finished")
        self.assertEqual(agent_finished["citations"][0]["citation_id"], "citation-1")


if __name__ == "__main__":
    unittest.main()
