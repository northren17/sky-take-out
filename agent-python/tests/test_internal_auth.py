import json
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from fastapi.testclient import TestClient
from pydantic import SecretStr
from agent.internal_auth import (
    InternalAgentError,
    ReplayGuard,
    VoiceConnectionRegistry,
    sign_internal_request,
    verify_internal_request,
)
from agent.main import app


class InternalAuthenticationTests(unittest.IsolatedAsyncioTestCase):
    async def test_hmac_matches_shared_java_vector(self):
        self.assertEqual(
            sign_internal_request(
                "shared-secret",
                "POST",
                "/x",
                "1700000000",
                "abcdefghijklmnopqrstuvwx",
                b'{"a":1}',
            ),
            "324774277285106267e115a86e76ce77c9fd6cad05127d12d2221943a128c127",
        )

    async def test_signed_request_is_accepted_only_once(self):
        guard = ReplayGuard()
        body = b'{"sessionIds":["a"]}'
        timestamp = str(int(time.time()))
        nonce = "a" * 24
        secret = "shared-secret-for-auth-test-000000"
        signature = sign_internal_request(
            secret, "POST", "/internal/revoke", timestamp, nonce, body
        )
        arguments = {
            "secret": secret,
            "method": "POST",
            "target": "/internal/revoke",
            "body": body,
            "timestamp": timestamp,
            "nonce": nonce,
            "signature": signature,
            "replay_guard": guard,
        }

        await verify_internal_request(**arguments)
        with self.assertRaises(InternalAgentError):
            await verify_internal_request(**arguments)

    async def test_expired_or_tampered_request_is_rejected(self):
        guard = ReplayGuard()
        old_timestamp = str(int(time.time()) - 61)
        with self.assertRaises(InternalAgentError):
            await verify_internal_request(
                secret="shared-secret-for-auth-test-000000",
                method="POST",
                target="/internal/revoke",
                body=b"",
                timestamp=old_timestamp,
                nonce="b" * 24,
                signature="0" * 64,
                replay_guard=guard,
            )

        timestamp = str(int(time.time()))
        with self.assertRaises(InternalAgentError):
            await verify_internal_request(
                secret="shared-secret-for-auth-test-000000",
                method="POST",
                target="/internal/revoke",
                body=b"changed",
                timestamp=timestamp,
                nonce="c" * 24,
                signature="0" * 64,
                replay_guard=guard,
            )

    async def test_weak_shared_secret_fails_closed(self):
        with self.assertRaises(InternalAgentError):
            await verify_internal_request(
                secret="short",
                method="POST",
                target="/internal/revoke",
                body=b"",
                timestamp=str(int(time.time())),
                nonce="f" * 24,
                signature="0" * 64,
                replay_guard=ReplayGuard(),
            )

    async def test_pending_connection_revoked_before_activation_never_starts(self):
        class FakeWebSocket:
            def __init__(self):
                self.closed = False

            async def close(self, **_kwargs):
                self.closed = True

        class FakeSession:
            def __init__(self):
                self.started = False
                self.closed = False

            async def start(self):
                self.started = True

            async def close(self):
                self.closed = True

        registry = VoiceConnectionRegistry()
        websocket = FakeWebSocket()
        connection = registry.register_pending("session-1", websocket)
        await registry.revoke(["session-1"])
        session = FakeSession()

        activated = await registry.activate(
            "session-1", connection, session, "capability"
        )

        self.assertFalse(activated)
        self.assertFalse(session.started)
        self.assertTrue(websocket.closed)

    def test_revocation_endpoint_requires_java_signature_and_rejects_replay(self):
        secret = "java-to-python-test-secret-0000000000"
        body = json.dumps(
            {"sessionIds": ["unknown-session"]}, separators=(",", ":")
        ).encode()
        timestamp = str(int(time.time()))
        nonce = "d" * 32
        signature = sign_internal_request(
            secret,
            "POST",
            "/internal/agent/v1/voice-sessions/revoke",
            timestamp,
            nonce,
            body,
        )
        settings = SimpleNamespace(agent_java_hmac_secret=SecretStr(secret))
        with (
            patch("agent.main.get_settings", return_value=settings),
            patch("agent.main.internal_replay_guard", ReplayGuard()),
            patch("agent.main.voice_connections", VoiceConnectionRegistry()),
            TestClient(app) as client,
        ):
            path = "/internal/agent/v1/voice-sessions/revoke"
            rejected = client.post(path, content=body)
            self.assertEqual(rejected.status_code, 401)

            headers = {
                "Content-Type": "application/json",
                "X-Agent-Timestamp": timestamp,
                "X-Agent-Nonce": nonce,
                "X-Agent-Signature": signature,
            }
            accepted = client.post(path, content=body, headers=headers)
            self.assertEqual(accepted.status_code, 200)
            self.assertEqual(accepted.json(), {"closedSessions": 0})

            replayed = client.post(path, content=body, headers=headers)
            self.assertEqual(replayed.status_code, 401)


if __name__ == "__main__":
    unittest.main()
