import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock
from unittest.mock import patch

from fastapi.testclient import TestClient
from pydantic import ValidationError
from pydantic import SecretStr

from agent.internal_auth import SessionAuthorizationError, VoiceConnectionRegistry
from agent.main import app


class StubSession:
    instances = []
    fail_on_wait = None

    def __init__(
        self,
        websocket,
        settings,
        *,
        session_id=None,
        business_client=None,
        session_capability=None,
    ):
        self.websocket = websocket
        self.settings = settings
        self.session_id = session_id or "websocket-test"
        self.business_client = business_client
        self.session_capability = session_capability
        self.started = False
        self.closed = False
        self.interrupts = 0
        self.audio = []
        self._never = asyncio.Event()
        type(self).instances.append(self)

    async def start(self):
        self.started = True

    async def wait_failure(self):
        if type(self).fail_on_wait is not None:
            raise type(self).fail_on_wait
        await self._never.wait()

    async def receive_audio(self, audio):
        self.audio.append(audio)

    async def interrupt(self):
        self.interrupts += 1

    async def close(self):
        self.closed = True

    async def report_server_error(self, message):
        await self.websocket.send_json(
            {
                "type": "error",
                "session_id": self.session_id,
                "turn_id": None,
                "timestamp": "test",
                "source": "server",
                "code": "internal_error",
                "message": message,
            }
        )


class WebSocketRouteTests(unittest.TestCase):
    def setUp(self):
        StubSession.instances = []
        StubSession.fail_on_wait = None
        self.voice_session_patch = patch("agent.main.VoiceSession", StubSession)
        self.settings_patch = patch(
            "agent.main.get_settings",
            return_value=SimpleNamespace(
                agent_python_hmac_secret=SecretStr("python-secret"),
                agent_java_internal_url="http://java.internal",
                agent_allowed_origins="http://localhost:5173,http://127.0.0.1:5173",
            ),
        )
        self.client_patch = patch("agent.main.JavaInternalClient")
        self.registry_patch = patch(
            "agent.main.voice_connections", VoiceConnectionRegistry()
        )
        self.voice_session_patch.start()
        self.settings_mock = self.settings_patch.start()
        self.java_client_class = self.client_patch.start()
        self.java_client = self.java_client_class.return_value
        self.java_client.redeem = AsyncMock(
            return_value={"sessionCapability": "capability"}
        )
        self.java_client.validate = AsyncMock(return_value={"valid": True})
        self.java_client.close = AsyncMock(return_value=None)
        self.registry_patch.start()
        self.addCleanup(self.voice_session_patch.stop)
        self.addCleanup(self.settings_patch.stop)
        self.addCleanup(self.client_patch.stop)
        self.addCleanup(self.registry_patch.stop)
        self.client = TestClient(app)

    def authenticate(self, websocket):
        websocket.send_json({"type": "auth", "ticket": "one-time-ticket"})

    def connect_voice(self):
        return self.client.websocket_connect(
            "/ws/voice", headers={"Origin": "http://localhost:5173"}
        )

    def test_normal_stop_closes_session_once(self):
        with self.connect_voice() as websocket:
            self.authenticate(websocket)
            websocket.send_json({"type": "interrupt"})
            websocket.send_json({"type": "session.stop"})
        session = StubSession.instances[-1]
        self.assertTrue(session.started)
        self.assertIs(session.business_client, self.java_client)
        self.assertEqual(session.session_capability, "capability")
        self.assertEqual(session.interrupts, 1)
        self.assertTrue(session.closed)

    def test_abnormal_client_disconnect_still_closes_resources(self):
        with self.connect_voice() as websocket:
            self.authenticate(websocket)
            websocket.close(code=1011)
        self.assertTrue(StubSession.instances[-1].closed)

    def test_background_failure_is_reported_and_closes(self):
        StubSession.fail_on_wait = RuntimeError("background failed")
        with self.connect_voice() as websocket:
            self.authenticate(websocket)
            event = websocket.receive_json()
            self.assertEqual(event["type"], "error")
            self.assertEqual(event["code"], "internal_error")
            self.assertEqual(event["message"], "Voice WebSocket 执行失败")
            self.assertNotIn("background failed", event["message"])
            self.assertIn("timestamp", event)
        self.assertTrue(StubSession.instances[-1].closed)

    def test_missing_required_configuration_returns_actionable_error(self):
        self.settings_mock.side_effect = ValidationError.from_exception_data(
            "Settings",
            [{"type": "missing", "loc": ("DEEPSEEK_API_KEY",), "input": {}}],
        )
        with self.connect_voice() as websocket:
            event = websocket.receive_json()
            self.assertEqual(event["type"], "error")
            self.assertEqual(event["code"], "configuration_error")
            self.assertEqual(event["source"], "configuration")
            self.assertFalse(event["recoverable"])
            self.assertIn("DEEPSEEK_API_KEY", event["message"])
            self.assertIn(".env", event["message"])
        self.assertEqual(StubSession.instances, [])

    def test_audio_before_auth_is_rejected_without_starting_session(self):
        with self.connect_voice() as websocket:
            websocket.send_bytes(b"untrusted-audio")
            message = websocket.receive()
            self.assertEqual(message["type"], "websocket.close")
            self.assertEqual(message["code"], 4401)
        self.assertEqual(StubSession.instances, [])
        self.java_client.redeem.assert_not_awaited()

    def test_missing_origin_is_rejected(self):
        with self.assertRaises(Exception):
            with self.client.websocket_connect("/ws/voice"):
                self.fail("WebSocket without browser Origin must be rejected")
        self.assertEqual(StubSession.instances, [])

    def test_invalid_ticket_does_not_start_session(self):
        self.java_client.redeem.side_effect = SessionAuthorizationError("invalid")
        with self.connect_voice() as websocket:
            self.authenticate(websocket)
            message = websocket.receive()
            self.assertEqual(message["type"], "websocket.close")
            self.assertEqual(message["code"], 4401)
        self.assertEqual(StubSession.instances, [])


if __name__ == "__main__":
    unittest.main()
