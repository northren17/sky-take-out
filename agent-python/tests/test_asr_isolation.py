import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from dashscope.common.error import InvalidParameter

from agent.events import TranscriptEvent
from agent.providers.fun_asr import FunAsrStream
from agent.session import SessionLimits, VoiceSession
from tests.fakes import FakeAsr, FakeTts, FakeWebSocket, ScriptedAgent, wait_until


def isolation_limits():
    return SessionLimits(
        connect_timeout=0.5,
        llm_timeout=1,
        tts_operation_timeout=0.2,
        tts_response_timeout=1,
        close_timeout=0.2,
        send_queue_size=32,
        send_queue_timeout=0.2,
        final_dedupe_seconds=0.04,
    )


class AsrIsolationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.asr = FakeAsr()
        self.tts = FakeTts()
        self.websocket = FakeWebSocket()
        self.agent = ScriptedAgent([["answer"] for _ in range(4)])
        self.session = VoiceSession(
            self.websocket,
            asr=self.asr,
            tts=self.tts,
            agent=self.agent,
            limits=isolation_limits(),
        )
        await self.session.start()
        self.addAsyncCleanup(self.session.close)

    async def cancel_first_input(self):
        old_generation = self.asr.input_generation
        await self.asr.emit(self.asr.transcript("old input", final=False))
        await wait_until(lambda: self.session.active_turn is not None)
        await self.session.interrupt()
        self.assertEqual(self.asr.restart_calls, 1)
        self.assertNotEqual(self.asr.input_generation, old_generation)
        return old_generation

    def transcript_events(self, event_type, turn_id):
        return [
            event["text"] for event in self.websocket.sent_json
            if event["type"] == event_type and event["turn_id"] == turn_id
        ]

    async def test_empty_final_transcript_returns_clear_result_without_calling_agent(self):
        await self.asr.emit(self.asr.transcript("", final=True))
        await wait_until(
            lambda: any(
                event["type"] == "agent.finished"
                for event in self.websocket.sent_json
            )
        )

        self.assertEqual(self.agent.calls, [])
        response = self.transcript_events("agent.delta", 1)
        self.assertEqual(
            response,
            ["抱歉，这次没有听清。请靠近麦克风，再简短清晰地说一次。"],
        )
        self.assertEqual(self.session.active_turn.state.value, "completed")

    async def test_finalize_input_submits_recognized_partial_when_asr_final_is_delayed(self):
        await self.asr.emit(self.asr.transcript("天气怎么样", final=False))
        await wait_until(lambda: self.session.active_turn is not None)

        self.assertTrue(await self.session.finalize_input())
        await wait_until(lambda: self.agent.calls == ["天气怎么样"])

        self.assertEqual(self.transcript_events("stt.final", 1), ["天气怎么样"])
        self.assertFalse(await self.session.finalize_input())

    async def test_old_partial_after_new_turn_cannot_change_transcript(self):
        old_generation = await self.cancel_first_input()
        await self.asr.emit(self.asr.transcript("new input", final=False))
        await wait_until(lambda: self.session.active_turn.turn_id == 2)
        await self.asr.emit(self.asr.transcript(
            "old late partial", final=False, generation=old_generation,
        ))
        await self.asr.emit(self.asr.transcript("new input", final=True))
        await wait_until(lambda: self.agent.calls == ["new input"])
        self.assertEqual(self.transcript_events("stt.partial", 2), ["new input"])
        self.assertEqual(self.transcript_events("stt.final", 2), ["new input"])

    async def test_old_final_is_rejected_after_former_dedupe_window(self):
        old_generation = await self.cancel_first_input()
        await self.asr.emit(self.asr.transcript("new input", final=False))
        await wait_until(lambda: self.session.active_turn.turn_id == 2)
        started = asyncio.get_running_loop().time()
        await asyncio.sleep(self.session._limits.final_dedupe_seconds + 0.02)
        self.assertGreater(
            asyncio.get_running_loop().time() - started,
            self.session._limits.final_dedupe_seconds,
        )
        await self.asr.emit(self.asr.transcript(
            "old late final", final=True, generation=old_generation,
        ))
        await self.asr.emit(self.asr.transcript("new input", final=True))
        await wait_until(lambda: self.agent.calls == ["new input"])
        self.assertEqual(self.transcript_events("stt.final", 2), ["new input"])

    async def test_old_final_interleaved_with_new_partial_keeps_new_final(self):
        old_generation = await self.cancel_first_input()
        await self.asr.emit(self.asr.transcript("new partial", final=False))
        await wait_until(lambda: self.session.active_turn.turn_id == 2)
        await self.asr.emit(self.asr.transcript(
            "old final", final=True, generation=old_generation,
        ))
        await self.asr.emit(self.asr.transcript("new final", final=True))
        await wait_until(lambda: self.agent.calls == ["new final"])
        self.assertEqual(self.transcript_events("stt.partial", 2), ["new partial"])
        self.assertEqual(self.transcript_events("stt.final", 2), ["new final"])

    async def test_new_connection_allows_multiple_partials_and_same_text_final(self):
        await self.asr.emit(self.asr.transcript("same", final=True))
        await wait_until(lambda: self.agent.calls == ["same"])
        await self.session.interrupt()
        for text in ("s", "sa", "same"):
            await self.asr.emit(self.asr.transcript(text, final=False))
        await self.asr.emit(self.asr.transcript("same", final=True))
        await wait_until(lambda: self.agent.calls == ["same", "same"])
        self.assertEqual(self.transcript_events("stt.partial", 2), ["s", "sa", "same"])
        self.assertEqual(self.transcript_events("stt.final", 2), ["same"])

    async def test_duplicate_out_of_order_and_unattributed_old_events_stay_invalid(self):
        old_generation = await self.cancel_first_input()
        await self.asr.emit(self.asr.transcript("new input", final=False))
        await wait_until(lambda: self.session.active_turn.turn_id == 2)
        for _ in range(2):
            await self.asr.emit(self.asr.transcript(
                "old partial", final=False, generation=old_generation,
            ))
            await self.asr.emit(self.asr.transcript(
                "old final", final=True, generation=old_generation,
            ))
        await self.asr.queue.put(TranscriptEvent("no origin", final=True))
        await self.asr.emit(self.asr.transcript("new input", final=True))
        await wait_until(lambda: self.agent.calls == ["new input"])
        await self.asr.emit(self.asr.transcript(
            "old partial", final=False, generation=old_generation,
        ))
        await self.asr.emit(self.asr.transcript(
            "old final", final=True, generation=old_generation,
        ))
        await self.asr.emit(self.asr.transcript("third input", final=False))
        await self.asr.emit(self.asr.transcript("third input", final=True))
        await wait_until(lambda: self.agent.calls == ["new input", "third input"])
        self.assertEqual(self.transcript_events("stt.partial", 2), ["new input"])
        self.assertEqual(self.transcript_events("stt.final", 2), ["new input"])


class ProviderReconnectionTests(unittest.IsolatedAsyncioTestCase):
    async def test_timeout_recovery_restarts_when_sdk_already_stopped_old_stream(self):
        instances = []

        class FakeRecognition:
            def __init__(self, **kwargs):
                self.callback = kwargs["callback"]
                self.started = False
                instances.append(self)

            def start(self):
                self.started = True

            def stop(self):
                raise InvalidParameter("Speech recognition has stopped.")

            def send_audio_frame(self, _chunk):
                pass

        with patch("agent.providers.fun_asr.Recognition", FakeRecognition):
            asr = FunAsrStream(
                api_key="fake", workspace_id=None, model="fake", sample_rate=16000,
            )
            await asr.start()
            generation = await asr.restart()

        self.assertEqual(generation, 2)
        self.assertEqual(len(instances), 2)
        self.assertTrue(instances[1].started)
        self.assertTrue(asr._started)

    async def test_close_accepts_recognition_already_stopped_by_provider(self):
        class StoppedRecognition:
            def __init__(self, **_kwargs):
                pass

            def start(self):
                pass

            def stop(self):
                raise InvalidParameter("Speech recognition has stopped.")

        with patch("agent.providers.fun_asr.Recognition", StoppedRecognition):
            asr = FunAsrStream(
                api_key="fake", workspace_id=None, model="fake", sample_rate=16000,
            )
            await asr.start()
            await asr.close()

        self.assertFalse(asr._started)

    async def test_old_sdk_callback_after_restart_cannot_reach_new_agent_turn(self):
        instances = []

        class FakeRecognition:
            def __init__(self, **kwargs):
                self.callback = kwargs["callback"]
                self.started = False
                self.stopped = False
                instances.append(self)

            def start(self):
                self.started = True

            def stop(self):
                self.stopped = True
                self.callback.on_complete()

            def send_audio_frame(self, chunk):
                pass

        class FakeResult:
            def __init__(self, text, final):
                self.sentence = {"text": text, "end_time": 0 if final else None}

            def get_sentence(self):
                return self.sentence

        with patch("agent.providers.fun_asr.Recognition", FakeRecognition):
            asr = FunAsrStream(
                api_key="fake", workspace_id=None, model="fake", sample_rate=16000,
            )
            websocket = FakeWebSocket()
            agent = ScriptedAgent([["answer"]])
            session = VoiceSession(
                websocket, asr=asr, tts=FakeTts(), agent=agent,
                limits=isolation_limits(),
            )
            await session.start()
            try:
                old_connection = instances[0]
                old_connection.callback.on_event(FakeResult("old input", False))
                await wait_until(lambda: session.active_turn is not None)
                await session.interrupt()
                self.assertTrue(old_connection.stopped)
                self.assertEqual(len(instances), 2)
                new_connection = instances[1]
                self.assertTrue(new_connection.started)
                new_connection.callback.on_event(FakeResult("new input", False))
                await wait_until(lambda: session.active_turn.turn_id == 2)
                old_connection.callback.on_event(FakeResult("old partial", False))
                old_connection.callback.on_event(FakeResult("old final", True))
                old_connection.callback.on_error(SimpleNamespace(message="late error"))
                old_connection.callback.on_complete()
                new_connection.callback.on_event(FakeResult("new input", True))
                await wait_until(lambda: agent.calls == ["new input"])
                self.assertFalse(session._failure.done())
                self.assertEqual([
                    event["text"] for event in websocket.sent_json
                    if event["type"] == "stt.final"
                ], ["new input"])
            finally:
                await session.close()


if __name__ == "__main__":
    unittest.main()
