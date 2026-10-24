"""Per-WebSocket real-time voice orchestration with turn isolation."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from dataclasses import dataclass
from typing import Any
from uuid import uuid4

from fastapi import WebSocket

from agent.config import (
    ASR_FINAL_DEDUPE_SECONDS,
    ASR_MODEL,
    ASR_SAMPLE_RATE,
    LLM_TURN_TIMEOUT_SECONDS,
    PROVIDER_CLOSE_TIMEOUT_SECONDS,
    PROVIDER_CONNECT_TIMEOUT_SECONDS,
    SEND_QUEUE_MAX_ITEMS,
    SEND_QUEUE_PUT_TIMEOUT_SECONDS,
    TTS_MODEL,
    TTS_OPERATION_TIMEOUT_SECONDS,
    TTS_RESPONSE_TIMEOUT_SECONDS,
    TTS_VOICE,
    Settings,
)
from agent.events import (
    AudioFrame,
    ProviderError,
    TranscriptEvent,
    TtsEvent,
    TurnContext,
    TurnState,
    ToolLifecycleEvent,
    encode_audio_frame,
    redact_sensitive_text,
    tool_wire_event,
    wire_event,
)
from agent.llm_agent import QwenVoiceAgent, ToolExecutionTimeout
from agent.providers.aliyun_tts import AliyunRealtimeTts
from agent.providers.fun_asr import FunAsrStream


logger = logging.getLogger("voice_agent.session")

class OutgoingBackpressureError(RuntimeError):
    """The client could not drain the bounded send queue in time."""


class TtsOperationTimeout(TimeoutError):
    """A TTS append/commit/cancel control operation exceeded its limit."""


class ProviderConnectionError(RuntimeError):
    """A session-wide ASR/TTS connection is no longer usable."""


MAX_ASR_RECOVERY_ATTEMPTS = 3


@dataclass(frozen=True, slots=True)
class SessionLimits:
    connect_timeout: float = PROVIDER_CONNECT_TIMEOUT_SECONDS
    llm_timeout: float = LLM_TURN_TIMEOUT_SECONDS
    tts_operation_timeout: float = TTS_OPERATION_TIMEOUT_SECONDS
    tts_response_timeout: float = TTS_RESPONSE_TIMEOUT_SECONDS
    close_timeout: float = PROVIDER_CLOSE_TIMEOUT_SECONDS
    send_queue_size: int = SEND_QUEUE_MAX_ITEMS
    send_queue_timeout: float = SEND_QUEUE_PUT_TIMEOUT_SECONDS
    final_dedupe_seconds: float = ASR_FINAL_DEDUPE_SECONDS


OutgoingItem = dict[str, Any] | AudioFrame | None


class VoiceSession:
    """Own all mutable state for one browser connection."""

    def __init__(
        self,
        websocket: WebSocket,
        settings: Settings | None = None,
        *,
        asr: Any | None = None,
        tts: Any | None = None,
        agent: Any | None = None,
        session_id: str | None = None,
        business_client: Any | None = None,
        session_capability: str | None = None,
        limits: SessionLimits | None = None,
    ) -> None:
        self._websocket = websocket
        self._limits = limits or SessionLimits()
        self.session_id = session_id or str(uuid4())

        if asr is None or tts is None or agent is None:
            if settings is None:
                raise ValueError("settings are required unless all providers are injected")
            dashscope_key = settings.dashscope_api_key.get_secret_value()
            asr = asr or FunAsrStream(
                api_key=dashscope_key,
                workspace_id=settings.dashscope_workspace_id,
                model=ASR_MODEL,
                sample_rate=ASR_SAMPLE_RATE,
            )
            tts = tts or AliyunRealtimeTts(
                api_key=dashscope_key,
                workspace_id=settings.dashscope_workspace_id,
                model=TTS_MODEL,
                voice=TTS_VOICE,
            )
            if agent is None:
                if business_client is None or not session_capability:
                    raise ValueError(
                        "Java session authorization is required for business tools"
                    )
                from agent.tools import policy_search, web_search
                from agent.tools.java_business import create_java_business_tools

                agent = QwenVoiceAgent(
                    api_key=dashscope_key,
                    tools=[
                        *create_java_business_tools(
                            business_client, self.session_id, session_capability
                        ),
                        policy_search,
                        web_search,
                    ],
                )
        self._asr = asr
        self._tts = tts
        self._agent = agent

        self._outgoing: asyncio.Queue[OutgoingItem] = asyncio.Queue(
            maxsize=self._limits.send_queue_size
        )
        self._tasks: set[asyncio.Task[Any]] = set()
        self._send_task: asyncio.Task[Any] | None = None
        self._turn_task: asyncio.Task[Any] | None = None
        self._tts_timeout_task: asyncio.Task[Any] | None = None
        self._failure: asyncio.Future[BaseException] = (
            asyncio.get_running_loop().create_future()
        )
        self._turn_lock = asyncio.Lock()
        self._asr_event_lock = asyncio.Lock()
        self._send_lock = asyncio.Lock()
        self._active_turn: TurnContext | None = None
        self._input_turn_id: int | None = None
        self._accepted_asr_generation: int | None = None
        self._next_turn_id = 0
        self._next_event_seq = 0
        self._last_final_text = ""
        self._last_final_at = float("-inf")
        self._asr_recovery_attempts = 0
        self._closed = False

    @property
    def active_turn(self) -> TurnContext | None:
        return self._active_turn

    def _event(
        self, event_type: str, turn_id: int | None = None, **payload: Any
    ) -> dict[str, Any]:
        self._next_event_seq += 1
        return wire_event(
            event_type,
            session_id=self.session_id,
            turn_id=turn_id,
            seq=self._next_event_seq,
            **payload,
        )

    async def _tool_event(self, turn_id: int, event: ToolLifecycleEvent) -> None:
        """Forward a safe lifecycle event if it still belongs to this turn."""

        turn = self._active_turn
        if turn is None or turn.turn_id != turn_id or self._closed:
            return
        self._next_event_seq += 1
        await self._send(
            tool_wire_event(
                event,
                session_id=self.session_id,
                turn_id=turn_id,
                seq=self._next_event_seq,
            )
        )
        if not event.citations:
            return
        if turn.citations is None:
            turn.citations = []
        known = {item.get("citation_id") for item in turn.citations}
        added = [item for item in event.citations if item.get("citation_id") not in known]
        if not added:
            return
        turn.citations.extend(added)
        await self._send(
            self._event(
                "rag.citations",
                turn_id,
                call_id=event.call_id,
                citations=added,
                citation_role="retrieved_evidence",
            )
        )

    def _track(
        self, coroutine, *, name: str, fail_if_completed: bool = False
    ) -> asyncio.Task[Any]:
        task = asyncio.create_task(coroutine, name=name)
        self._tasks.add(task)

        def finished(done: asyncio.Task[Any]) -> None:
            self._tasks.discard(done)
            if done.cancelled():
                return
            exception = done.exception()
            if exception is not None and not self._failure.done():
                self._failure.set_result(exception)
            elif fail_if_completed and not self._closed and not self._failure.done():
                self._failure.set_result(
                    ProviderConnectionError(f"{name} ended unexpectedly")
                )

        task.add_done_callback(finished)
        return task

    async def wait_failure(self) -> BaseException:
        """Wait until a background producer or sender fails."""

        return await asyncio.shield(self._failure)

    async def start(self) -> None:
        self._send_task = self._track(self._send_loop(), name="voice-send")
        await self._send(
            self._event(
                "session.ready",
                input_audio={"encoding": "pcm_s16le", "sample_rate": 16000},
                output_audio={
                    "encoding": "pcm_s16le",
                    "sample_rate": 24000,
                    "binary_header": {
                        "magic": "VA1",
                        "bytes": 8,
                        "turn_id": "uint32_be",
                    },
                },
            )
        )
        async with asyncio.timeout(self._limits.connect_timeout):
            await asyncio.gather(self._asr.start(), self._tts.connect())
        self._accepted_asr_generation = self._asr.input_generation
        self._track(
            self._consume_asr(),
            name="voice-asr-consumer",
            fail_if_completed=True,
        )
        self._track(
            self._consume_tts(),
            name="voice-tts-consumer",
            fail_if_completed=True,
        )
        await self._send(self._event("state.changed", state="listening"))

    async def receive_audio(self, chunk: bytes) -> None:
        async with self._asr_event_lock:
            if chunk and not self._closed and self._accepted_asr_generation is not None:
                await self._asr.send_audio(chunk)

    async def _cancel_active(
        self, *, send_events: bool, send_listening: bool
    ) -> int | None:
        async with self._turn_lock:
            turn = self._active_turn
            if turn is None:
                return None
            if turn.state in {
                TurnState.CANCELLED,
                TurnState.COMPLETED,
                TurnState.FAILED,
                TurnState.TIMED_OUT,
            }:
                terminal_turn_id = turn.turn_id
            else:
                terminal_turn_id = None
            if terminal_turn_id is not None:
                task = None
                timeout_task = None
                needs_tts_cancel = False
            else:
                needs_tts_cancel = bool(turn.response_text) or turn.state in {
                    TurnState.TTS_PENDING,
                    TurnState.SPEAKING,
                }
                turn.state = TurnState.CANCELLED
                self._input_turn_id = None
                task = self._turn_task
                self._turn_task = None
                timeout_task = self._tts_timeout_task
                self._tts_timeout_task = None

        if terminal_turn_id is not None:
            if send_events and not self._closed:
                await self._send(self._event("playback.clear", terminal_turn_id))
                if send_listening:
                    await self._send(
                        self._event(
                            "state.changed", terminal_turn_id, state="listening"
                        )
                    )
            return terminal_turn_id

        for pending in (task, timeout_task):
            if pending and not pending.done() and pending is not asyncio.current_task():
                pending.cancel()
        if task and not task.done() and task is not asyncio.current_task():
            with contextlib.suppress(asyncio.CancelledError):
                await task
        if timeout_task and not timeout_task.done() and timeout_task is not asyncio.current_task():
            with contextlib.suppress(asyncio.CancelledError):
                await timeout_task

        if needs_tts_cancel:
            try:
                async with asyncio.timeout(self._limits.tts_operation_timeout):
                    await self._tts.cancel(turn.turn_id)
            except TimeoutError:
                logger.warning("TTS cancel timed out for turn %s", turn.turn_id)
            except Exception as exc:
                logger.error(
                    "TTS cancel failed for turn %s: error_type=%s",
                    turn.turn_id,
                    type(exc).__name__,
                )

        if send_events and not self._closed:
            await self._send(self._event("playback.clear", turn.turn_id))
            await self._send(
                self._event(
                    "turn.cancelled",
                    turn.turn_id,
                    state=TurnState.CANCELLED,
                )
            )
            if send_listening:
                await self._send(
                    self._event("state.changed", turn.turn_id, state="listening")
                )
        return turn.turn_id

    async def interrupt(self) -> None:
        async with self._asr_event_lock:
            if self._closed:
                return
            self._accepted_asr_generation = None
            turn_id = await self._cancel_active(send_events=True, send_listening=False)
            if turn_id is None:
                await self._send(self._event("playback.clear"))
            self._last_final_text = ""
            self._last_final_at = float("-inf")
            old_generation = self._asr.input_generation
            new_generation = await self._asr.restart()
            if (
                not isinstance(new_generation, int)
                or new_generation <= old_generation
                or new_generation != self._asr.input_generation
            ):
                raise ProviderConnectionError("ASR restart did not advance input generation")
            self._accepted_asr_generation = new_generation
            await self._send(self._event("state.changed", turn_id, state="listening"))

    async def finalize_input(self) -> bool:
        """Submit the latest recognized partial transcript if ASR has not finalized it."""

        async with self._asr_event_lock:
            turn = self._active_turn
            if (
                self._closed
                or turn is None
                or self._input_turn_id != turn.turn_id
                or turn.final_received
                or not turn.transcript.strip()
            ):
                return False
            await self._handle_asr_event(
                TranscriptEvent(
                    text=turn.transcript.strip(),
                    final=True,
                    input_generation=self._accepted_asr_generation,
                )
            )
            return True

    async def _new_input_turn(self) -> TurnContext:
        await self._cancel_active(send_events=True, send_listening=False)
        async with self._turn_lock:
            self._next_turn_id += 1
            turn = TurnContext(self._next_turn_id, TurnState.COLLECTING)
            self._active_turn = turn
            self._input_turn_id = turn.turn_id
            return turn

    async def _consume_asr(self) -> None:
        async for event in self._asr.events():
            async with self._asr_event_lock:
                if (
                    self._closed
                    or event.input_generation is None
                    or event.input_generation != self._accepted_asr_generation
                ):
                    continue
                await self._handle_asr_event(event)

    async def _handle_asr_event(self, event: TranscriptEvent | ProviderError) -> None:
        if isinstance(event, ProviderError):
            if await self._recover_asr_timeout(event):
                return
            await self._provider_error(event)
            return

        self._asr_recovery_attempts = 0
        turn = self._active_turn
        if not event.final:
            if self._input_turn_id is None:
                turn = await self._new_input_turn()
            else:
                turn = self._active_turn
            if turn is not None:
                turn.transcript = event.text
                await self._send(
                    self._event("stt.partial", turn.turn_id, text=event.text)
                )
            return

        now = asyncio.get_running_loop().time()
        if (
            self._input_turn_id is None
            and event.text == self._last_final_text
            and now - self._last_final_at <= self._limits.final_dedupe_seconds
        ):
            return
        if self._input_turn_id is None:
            turn = await self._new_input_turn()
        else:
            turn = self._active_turn
        if turn is None or turn.final_received:
            return

        turn.transcript = event.text
        turn.final_received = True
        turn.state = TurnState.AGENT_RUNNING
        self._input_turn_id = None
        self._last_final_text = event.text
        self._last_final_at = now
        await self._send(
            self._event("stt.final", turn.turn_id, text=event.text)
        )
        if not event.text.strip():
            response_text = "抱歉，这次没有听清。请靠近麦克风，再简短清晰地说一次。"
            turn.response_text = response_text
            turn.state = TurnState.COMPLETED
            await self._send(
                self._event("agent.delta", turn.turn_id, text=response_text)
            )
            await self._send(
                self._event("agent.finished", turn.turn_id, text=response_text)
            )
            await self._send(
                self._event("state.changed", turn.turn_id, state="listening")
            )
            return
        self._turn_task = self._track(
            self._run_turn(turn.turn_id, event.text),
            name=f"voice-turn-{turn.turn_id}",
        )

    async def _recover_asr_timeout(self, error: ProviderError) -> bool:
        if (
            error.source != "asr"
            or "timeout" not in error.message.lower()
            or self._asr_recovery_attempts >= MAX_ASR_RECOVERY_ATTEMPTS
        ):
            if (
                error.source == "asr"
                and "timeout" in error.message.lower()
                and self._asr_recovery_attempts >= MAX_ASR_RECOVERY_ATTEMPTS
            ):
                logger.error(
                    "ASR timeout recovery exhausted after %s attempts",
                    self._asr_recovery_attempts,
                )
            return False

        self._asr_recovery_attempts += 1
        self._accepted_asr_generation = None
        turn_id = await self._cancel_active(
            send_events=True, send_listening=False
        )
        self._input_turn_id = None
        self._last_final_text = ""
        self._last_final_at = float("-inf")
        try:
            old_generation = self._asr.input_generation
            new_generation = await self._asr.restart()
            if (
                not isinstance(new_generation, int)
                or new_generation <= old_generation
                or new_generation != self._asr.input_generation
            ):
                raise ProviderConnectionError(
                    "ASR recovery did not advance input generation"
                )
        except Exception as exc:
            logger.warning(
                "ASR timeout recovery failed: error_type=%s",
                type(exc).__name__,
            )
            return False

        self._accepted_asr_generation = new_generation
        await self._send(
            self._event(
                "error",
                turn_id,
                source="asr",
                code="provider_reconnected",
                message="语音识别连接超时，已重新连接，请再说一次。",
                recoverable=True,
            )
        )
        await self._send(
            self._event("state.changed", turn_id, state="listening")
        )
        logger.warning("ASR provider timed out; connection recovered")
        return True

    def _is_current(self, turn_id: int) -> bool:
        turn = self._active_turn
        return bool(
            turn
            and turn.turn_id == turn_id
            and turn.state
            not in {
                TurnState.CANCELLED,
                TurnState.FAILED,
                TurnState.TIMED_OUT,
            }
        )

    async def _tts_call(self, operation, *args) -> None:
        try:
            async with asyncio.timeout(self._limits.tts_operation_timeout):
                await operation(*args)
        except TimeoutError as exc:
            raise TtsOperationTimeout(
                f"TTS operation exceeded {self._limits.tts_operation_timeout:g}s"
            ) from exc

    async def _run_turn(self, turn_id: int, transcript: str) -> None:
        response_text = ""
        try:
            if not self._is_current(turn_id):
                return
            await self._send(self._event("agent.started", turn_id))
            await self._send(
                self._event("state.changed", turn_id, state="thinking")
            )
            async with asyncio.timeout(self._limits.llm_timeout):
                stream = (
                    self._agent.stream(
                        transcript,
                        on_tool_event=lambda event: self._tool_event(turn_id, event),
                    )
                    if getattr(self._agent, "supports_tool_events", False)
                    else self._agent.stream(transcript)
                )
                async for text in stream:
                    if not self._is_current(turn_id):
                        return
                    response_text += text
                    turn = self._active_turn
                    if turn is not None and turn.turn_id == turn_id:
                        turn.response_text = response_text
                    await self._send(
                        self._event("agent.delta", turn_id, text=text)
                    )
                    await self._tts_call(self._tts.append_text, turn_id, text)

            if not self._is_current(turn_id):
                return
            turn = self._active_turn
            if turn is not None:
                turn.response_text = response_text
            await self._send(
                self._event(
                    "agent.finished",
                    turn_id,
                    text=response_text,
                    citations=list(turn.citations or []) if turn is not None else [],
                    citation_role="retrieved_evidence",
                )
            )
            if not response_text:
                if turn is not None:
                    turn.state = TurnState.COMPLETED
                await self._send(
                    self._event("state.changed", turn_id, state="listening")
                )
                return

            await self._tts_call(self._tts.commit, turn_id)
            if turn is not None and turn.state != TurnState.SPEAKING:
                turn.state = TurnState.TTS_PENDING
            self._tts_timeout_task = self._track(
                self._watch_tts_response(turn_id),
                name=f"voice-tts-timeout-{turn_id}",
            )
        except asyncio.CancelledError:
            raise
        except ToolExecutionTimeout as exc:
            await self._fail_turn(turn_id, "tool", "timeout", str(exc), timed_out=True)
        except TtsOperationTimeout as exc:
            await self._fail_turn(turn_id, "tts", "timeout", str(exc), timed_out=True)
        except TimeoutError:
            await self._fail_turn(
                turn_id,
                "llm",
                "timeout",
                f"LLM turn exceeded {self._limits.llm_timeout:g}s",
                timed_out=True,
            )
        except OutgoingBackpressureError:
            raise
        except Exception as exc:
            logger.error(
                "Agent turn %s failed: error_type=%s status=%s",
                turn_id,
                type(exc).__name__,
                getattr(exc, "status_code", "n/a"),
            )
            await self._fail_turn(
                turn_id, "agent", "execution_error", "Agent 执行失败"
            )

    async def _watch_tts_response(self, turn_id: int) -> None:
        await asyncio.sleep(self._limits.tts_response_timeout)
        if self._is_current(turn_id):
            await self._fail_turn(
                turn_id,
                "tts",
                "timeout",
                f"TTS response exceeded {self._limits.tts_response_timeout:g}s",
                timed_out=True,
            )

    async def _consume_tts(self) -> None:
        async for event in self._tts.events():
            if isinstance(event, ProviderError):
                await self._provider_error(event)
                continue
            if not isinstance(event, TtsEvent) or not self._is_current(event.turn_id):
                continue
            turn = self._active_turn
            if event.type == "started":
                if turn is not None:
                    turn.state = TurnState.SPEAKING
                await self._send(
                    self._event(
                        "tts.started",
                        event.turn_id,
                        response_id=event.response_id,
                    )
                )
                await self._send(
                    self._event("state.changed", event.turn_id, state="speaking")
                )
            elif event.type == "audio" and event.audio:
                await self._send(AudioFrame(event.turn_id, event.audio))
            elif event.type == "audio_finished":
                await self._send(
                    self._event(
                        "tts.audio.done",
                        event.turn_id,
                        response_id=event.response_id,
                    )
                )
            elif event.type == "finished" and turn is not None and not turn.tts_finished:
                turn.tts_finished = True
                turn.state = TurnState.COMPLETED
                timeout_task = self._tts_timeout_task
                self._tts_timeout_task = None
                if timeout_task and timeout_task is not asyncio.current_task():
                    timeout_task.cancel()
                await self._send(
                    self._event(
                        "tts.finished",
                        event.turn_id,
                        response_id=event.response_id,
                    )
                )
                await self._send(
                    self._event("state.changed", event.turn_id, state="listening")
                )

    async def _fail_turn(
        self,
        turn_id: int,
        source: str,
        code: str,
        message: str,
        *,
        timed_out: bool = False,
    ) -> None:
        if not self._is_current(turn_id):
            return
        message = redact_sensitive_text(message)
        turn = self._active_turn
        needs_tts_cancel = bool(turn and turn.response_text)
        if turn is not None:
            turn.state = TurnState.TIMED_OUT if timed_out else TurnState.FAILED
        if needs_tts_cancel:
            with contextlib.suppress(Exception):
                await self._tts_call(self._tts.cancel, turn_id)
        await self._send(
            self._event(
                "error",
                turn_id,
                source=source,
                code=code,
                message=message,
                recoverable=True,
            )
        )
        await self._send(self._event("playback.clear", turn_id))
        await self._send(
            self._event("state.changed", turn_id, state="listening")
        )

    async def _provider_error(self, error: ProviderError) -> None:
        if error.turn_id is not None and not self._is_current(error.turn_id):
            return
        if error.turn_id is not None:
            await self._fail_turn(
                error.turn_id,
                error.source,
                error.code,
                error.message,
            )
            return
        await self._send(
            self._event(
                "error",
                error.turn_id,
                source=error.source,
                code=error.code,
                message=redact_sensitive_text(error.message),
                recoverable=False,
            )
        )
        await self._send(
            self._event("state.changed", error.turn_id, state="error")
        )
        raise ProviderConnectionError(f"{error.source}: {error.message}")

    async def _send(self, item: dict[str, Any] | AudioFrame) -> None:
        if self._closed:
            return
        try:
            async with asyncio.timeout(self._limits.send_queue_timeout):
                await self._outgoing.put(item)
        except TimeoutError as exc:
            error = OutgoingBackpressureError(
                f"client did not drain {self._limits.send_queue_size} queued messages "
                f"within {self._limits.send_queue_timeout:g}s"
            )
            if not self._failure.done():
                self._failure.set_result(error)
            raise error from exc

    async def _send_loop(self) -> None:
        while True:
            item = await self._outgoing.get()
            try:
                if item is None:
                    return
                async with self._send_lock:
                    if self._closed:
                        continue
                    if isinstance(item, AudioFrame):
                        turn = self._active_turn
                        if (
                            turn is None
                            or turn.turn_id != item.turn_id
                            or turn.state
                            in {
                                TurnState.CANCELLED,
                                TurnState.FAILED,
                                TurnState.TIMED_OUT,
                            }
                        ):
                            continue
                        await self._websocket.send_bytes(
                            encode_audio_frame(item.turn_id, item.audio)
                        )
                    else:
                        await self._websocket.send_json(item)
            finally:
                self._outgoing.task_done()

    async def report_server_error(self, message: str) -> None:
        """Serialize a route-level error through the normal bounded send path."""

        await self._send(
            self._event(
                "error",
                source="server",
                code="internal_error",
                message=redact_sensitive_text(message),
                recoverable=False,
            )
        )

    async def heartbeat(self) -> None:
        await self._send(self._event("heartbeat.ack"))

    async def close(self) -> None:
        async with self._asr_event_lock:
            if self._closed:
                return
            self._accepted_asr_generation = None
            self._closed = True
            await self._cancel_active(send_events=False, send_listening=False)

        current = asyncio.current_task()
        tasks = [task for task in self._tasks if task is not current]
        for task in tasks:
            task.cancel()
        for task in tasks:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        async with self._send_lock:
            while not self._outgoing.empty():
                with contextlib.suppress(asyncio.QueueEmpty):
                    self._outgoing.get_nowait()
                    self._outgoing.task_done()

        async def close_provider(provider, name: str) -> None:
            try:
                async with asyncio.timeout(self._limits.close_timeout):
                    await provider.close()
            except TimeoutError:
                logger.warning("%s close timed out", name)
            except Exception as exc:
                logger.error("%s close failed: error_type=%s", name, type(exc).__name__)

        await asyncio.gather(
            close_provider(self._asr, "ASR"),
            close_provider(self._tts, "TTS"),
        )
        if not self._failure.done():
            self._failure.cancel()
