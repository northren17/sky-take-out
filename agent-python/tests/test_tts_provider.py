import asyncio
import base64
import unittest

from agent.events import TtsEvent
from unittest.mock import patch

from agent.providers.aliyun_tts import AliyunRealtimeTts, _Callback


class TtsCallbackTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.queue = asyncio.Queue()
        self.callback = _Callback(asyncio.get_running_loop(), self.queue)

    async def drain(self):
        await asyncio.sleep(0)
        items = []
        while not self.queue.empty():
            items.append(self.queue.get_nowait())
        return items

    async def test_audio_done_does_not_finish_response(self):
        self.callback.begin_turn(7)
        self.callback.on_event(
            {"type": "response.created", "response": {"id": "response-7"}}
        )
        self.callback.on_event(
            {"type": "response.audio.done", "response_id": "response-7"}
        )
        items = await self.drain()
        self.assertEqual([item.type for item in items], ["started", "audio_finished"])

        self.callback.on_event(
            {"type": "response.done", "response": {"id": "response-7"}}
        )
        self.callback.on_event(
            {"type": "response.done", "response": {"id": "response-7"}}
        )
        items = await self.drain()
        self.assertEqual([item.type for item in items], ["finished"])

    async def test_response_done_without_audio_done_still_finishes(self):
        self.callback.begin_turn(1)
        self.callback.on_event(
            {"type": "response.created", "response": {"id": "response-1"}}
        )
        self.callback.on_event(
            {"type": "response.done", "response_id": "response-1"}
        )
        items = await self.drain()
        self.assertEqual([item.type for item in items], ["started", "finished"])

    async def test_cancelled_response_cannot_be_assigned_to_new_turn(self):
        self.callback.begin_turn(1)
        self.callback.cancel_turn(1)
        self.callback.begin_turn(2)
        self.callback.on_event(
            {"type": "response.created", "response": {"id": "old"}}
        )
        self.callback.on_event(
            {"type": "response.created", "response": {"id": "new"}}
        )
        self.callback.on_event(
            {
                "type": "response.audio.delta",
                "response_id": "old",
                "delta": base64.b64encode(b"old").decode(),
            }
        )
        self.callback.on_event(
            {
                "type": "response.audio.delta",
                "response_id": "new",
                "delta": base64.b64encode(b"new").decode(),
            }
        )
        items = await self.drain()
        self.assertEqual(
            [(item.type, item.turn_id) for item in items],
            [("started", 2), ("audio", 2)],
        )
        self.assertIsInstance(items[-1], TtsEvent)
        self.assertEqual(items[-1].audio, b"new")

    async def test_unidentified_audio_is_dropped(self):
        self.callback.begin_turn(3)
        self.callback.on_event(
            {
                "type": "response.audio.delta",
                "delta": base64.b64encode(b"unknown").decode(),
            }
        )
        self.assertEqual(await self.drain(), [])

    async def test_session_finished_terminates_event_stream_without_response_id(self):
        self.callback.on_event({"type": "session.finished"})
        self.assertEqual(await self.drain(), [None])

    async def test_cancel_retires_connection_before_next_turn(self):
        clients = []

        class FakeSdkClient:
            def __init__(self, *, callback, **kwargs):
                self.callback = callback
                self.closed = False
                clients.append(self)

            def connect(self):
                pass

            def update_session(self, **kwargs):
                pass

            def cancel_response(self):
                pass

            def clear_appended_text(self):
                pass

            def close(self):
                self.closed = True
                self.callback.on_close(1000, "retired")

            def append_text(self, text):
                pass

        with patch(
            "agent.providers.aliyun_tts.QwenTtsRealtime", FakeSdkClient
        ):
            provider = AliyunRealtimeTts(
                api_key="fake",
                workspace_id=None,
                model="fake-model",
                voice="fake-voice",
            )
            provider._connected = True
            old_callback = provider._callback
            await provider.cancel(1)
            self.assertTrue(clients[0].closed)
            self.assertIsNot(provider._callback, old_callback)
            self.assertFalse(provider._connected)
            self.assertTrue(provider._queue.empty())

            await provider.append_text(2, "new")
            self.assertTrue(provider._connected)
            self.assertEqual(len(clients), 2)


if __name__ == "__main__":
    unittest.main()
