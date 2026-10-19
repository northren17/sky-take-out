"""Deterministic in-memory providers for Phase 1 tests."""

from __future__ import annotations

import asyncio
from collections import deque

from agent.events import ProviderError, TranscriptEvent


class FakeWebSocket:
    def __init__(self, *, blocked: bool = False) -> None:
        self.sent_json: list[dict] = []
        self.sent_bytes: list[bytes] = []
        self._gate = asyncio.Event()
        if not blocked:
            self._gate.set()

    def unblock(self) -> None:
        self._gate.set()

    async def send_json(self, item: dict) -> None:
        await self._gate.wait()
        self.sent_json.append(item)

    async def send_bytes(self, item: bytes) -> None:
        await self._gate.wait()
        self.sent_bytes.append(item)


class FakeAsr:
    def __init__(self) -> None:
        self.queue: asyncio.Queue = asyncio.Queue()
        self.input_generation = 1
        self.restart_calls = 0
        self.started = False
        self.closed = False
        self.audio: list[bytes] = []

    async def start(self) -> None:
        self.started = True

    async def send_audio(self, chunk: bytes) -> None:
        self.audio.append(chunk)

    async def restart(self) -> int:
        self.input_generation += 1
        self.restart_calls += 1
        return self.input_generation

    def transcript(
        self, text: str, *, final: bool, generation: int | None = None
    ) -> TranscriptEvent:
        return TranscriptEvent(
            text, final,
            input_generation=self.input_generation if generation is None else generation,
        )

    def error(self, message: str, *, generation: int | None = None) -> ProviderError:
        return ProviderError(
            "asr", message,
            input_generation=self.input_generation if generation is None else generation,
        )

    async def emit(self, event) -> None:
        await self.queue.put(event)

    async def events(self):
        while True:
            event = await self.queue.get()
            if event is None:
                return
            yield event

    async def close(self) -> None:
        self.closed = True
        await self.queue.put(None)


class FakeTts:
    def __init__(self) -> None:
        self.queue: asyncio.Queue = asyncio.Queue()
        self.connected = False
        self.closed = False
        self.appended: list[tuple[int, str]] = []
        self.committed: list[int] = []
        self.cancelled: list[int] = []
        self.block_append = False

    async def connect(self) -> None:
        self.connected = True

    async def append_text(self, turn_id: int, text: str) -> None:
        if self.block_append:
            await asyncio.sleep(30)
        self.appended.append((turn_id, text))

    async def commit(self, turn_id: int) -> None:
        self.committed.append(turn_id)

    async def cancel(self, turn_id: int) -> None:
        self.cancelled.append(turn_id)

    async def emit(self, event) -> None:
        await self.queue.put(event)

    async def events(self):
        while True:
            event = await self.queue.get()
            if event is None:
                return
            yield event

    async def close(self) -> None:
        self.closed = True
        await self.queue.put(None)


class ScriptedAgent:
    """Each plan is a sequence of text chunks or awaitable Events."""

    def __init__(self, plans) -> None:
        self.plans = deque(plans)
        self.calls: list[str] = []
        self.cancelled_calls: list[str] = []

    async def stream(self, text: str):
        self.calls.append(text)
        plan = self.plans.popleft()
        try:
            for item in plan:
                if isinstance(item, asyncio.Event):
                    await item.wait()
                elif isinstance(item, BaseException):
                    raise item
                else:
                    yield item
        except asyncio.CancelledError:
            self.cancelled_calls.append(text)
            raise


async def wait_until(predicate, timeout: float = 1.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0)
