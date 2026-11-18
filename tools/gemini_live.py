"""Minimal Gemini Live (BidiGenerateContent) helper for the Hermes voice spikes.

Reads the API key from ~/.config/hermes-next/gemini.key only — never from the
repository, never from the command line.

Usage from another script:

    from gemini_live import LiveSession, load_key

    async with LiveSession(model="gemini-3.8-live") as s:
        await s.setup(voice="Kore", instructions="...")
        await s.say("안녕")
        async for ev in s.events(timeout=10):
            ...
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import pathlib
import time
from typing import Any, AsyncIterator, Dict, List, Optional

import websockets

KEY_PATH = pathlib.Path.home() / ".config" / "hermes-next" / "gemini.key"
HOST = "generativelanguage.googleapis.com"
API_VERSION = "v1beta"
DEFAULT_MODEL = "gemini-3.8-live"


def load_key() -> str:
    key = KEY_PATH.read_text().strip()
    if not key:
        raise SystemExit(f"empty key file: {KEY_PATH}")
    return key


def ws_url(api_version: str = API_VERSION) -> str:
    return (
        f"wss://{HOST}/ws/google.ai.generativelanguage.{api_version}"
        f".GenerativeService.BidiGenerateContent?key={load_key()}"
    )


class LiveSession:
    """One live connection. Every received message is kept in `log` with a timestamp."""

    def __init__(self, model: str = DEFAULT_MODEL, api_version: str = API_VERSION):
        self.model = model
        self.api_version = api_version
        self.ws: Optional[websockets.WebSocketClientProtocol] = None
        self.log: List[Dict[str, Any]] = []
        self.opened_at: Optional[float] = None
        self.setup_complete_at: Optional[float] = None
        self.audio_bytes = 0
        self.first_audio_at: Optional[float] = None
        self.raw_audio = bytearray()
        self.closed_reason: Optional[str] = None

    async def __aenter__(self) -> "LiveSession":
        self.opened_at = time.monotonic()
        # No client-side message-size cap: the iOS lesson from the plan is that a 1 MB
        # cap silently kills long answers. websockets defaults are already generous,
        # we raise them explicitly and record the values.
        self.ws = await websockets.connect(
            ws_url(self.api_version),
            max_size=None,
            ping_interval=20,
            ping_timeout=20,
            open_timeout=30,
        )
        return self

    async def __aexit__(self, *exc) -> None:
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:  # noqa: BLE001 - closing must never mask the test result
                pass

    # ---- sending -------------------------------------------------------

    async def send(self, payload: Dict[str, Any]) -> None:
        assert self.ws is not None
        await self.ws.send(json.dumps(payload))

    async def setup(
        self,
        *,
        voice: str = "Kore",
        instructions: Optional[str] = None,
        language_code: str = "ko-KR",
        tools: Optional[List[Dict[str, Any]]] = None,
        extra: Optional[Dict[str, Any]] = None,
        response_modalities: tuple = ("AUDIO",),
        wait: bool = True,
        timeout: float = 15.0,
    ) -> Dict[str, Any]:
        setup: Dict[str, Any] = {
            "model": f"models/{self.model}",
            "generationConfig": {
                "responseModalities": list(response_modalities),
                "speechConfig": {
                    "languageCode": language_code,
                    "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": voice}},
                },
            },
        }
        if instructions:
            setup["systemInstruction"] = {"parts": [{"text": instructions}]}
        if tools:
            setup["tools"] = tools
        if extra:
            setup.update(extra)
        await self.send({"setup": setup})
        if not wait:
            return {}
        return await self.wait_for({"setupComplete"}, timeout=timeout)

    async def say(self, text: str, turn_complete: bool = True) -> None:
        await self.send(
            {
                "clientContent": {
                    "turns": [{"role": "user", "parts": [{"text": text}]}],
                    "turnComplete": turn_complete,
                }
            }
        )

    async def send_audio(self, pcm16: bytes) -> None:
        await self.send(
            {
                "realtimeInput": {
                    "audio": {
                        "mimeType": "audio/pcm;rate=16000",
                        "data": base64.b64encode(pcm16).decode(),
                    }
                }
            }
        )

    async def send_activity_start(self) -> None:
        await self.send({"realtimeInput": {"activityStart": {}}})

    async def send_activity_end(self) -> None:
        await self.send({"realtimeInput": {"activityEnd": {}}})

    async def send_tool_response(self, responses: List[Dict[str, Any]]) -> None:
        await self.send({"toolResponse": {"functionResponses": responses}})

    # ---- receiving -----------------------------------------------------

    def _record(self, msg: Dict[str, Any]) -> None:
        now = time.monotonic()
        kind = next((k for k in msg.keys()), "unknown")
        entry = {"t": now - (self.opened_at or now), "kind": kind, "msg": msg}
        self.log.append(entry)
        if "serverContent" in msg:
            sc = msg["serverContent"]
            for part in (sc.get("modelTurn") or {}).get("parts", []):
                blob = (part.get("inlineData") or {}).get("data")
                if blob:
                    raw = base64.b64decode(blob)
                    self.audio_bytes += len(raw)
                    self.raw_audio.extend(raw)
                    if self.first_audio_at is None:
                        self.first_audio_at = now
        elif "setupComplete" in msg:
            self.setup_complete_at = now

    async def recv(self, timeout: float = 10.0) -> Dict[str, Any]:
        assert self.ws is not None
        raw = await asyncio.wait_for(self.ws.recv(), timeout=timeout)
        msg = json.loads(raw)
        self._record(msg)
        return msg

    async def wait_for(self, keys, timeout: float = 15.0) -> Dict[str, Any]:
        """Wait for a message containing any of `keys`; returns it."""
        wanted = set(keys)
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"no message with {sorted(wanted)} within {timeout}s")
            msg = await self.recv(timeout=remaining)
            if wanted & set(msg.keys()):
                return msg

    async def events(self, timeout: float = 30.0, until_turn_complete: bool = False) -> AsyncIterator[Dict[str, Any]]:
        """Yield messages until the socket closes or `timeout` passes."""
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            try:
                msg = await self.recv(timeout=remaining)
            except (asyncio.TimeoutError, websockets.ConnectionClosed) as exc:
                self.closed_reason = repr(exc)
                return
            yield msg
            if until_turn_complete and "serverContent" in msg:
                if msg["serverContent"].get("turnComplete"):
                    return

    def kinds(self) -> List[str]:
        return [e["kind"] for e in self.log]


def pcm_to_wav(pcm: bytes, path: str, rate: int = 24000) -> str:
    import wave

    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return path


def decode_audio_from(log: List[Dict[str, Any]]) -> bytes:
    out = bytearray()
    for entry in log:
        sc = entry["msg"].get("serverContent") or {}
        for part in (sc.get("modelTurn") or {}).get("parts", []):
            blob = (part.get("inlineData") or {}).get("data")
            if blob:
                out.extend(base64.b64decode(blob))
    return bytes(out)


def summarize(msg: Dict[str, Any]) -> str:
    """One-line, log-safe rendering of a server message."""
    if "serverContent" in msg:
        sc = msg["serverContent"]
        bits = []
        parts = (sc.get("modelTurn") or {}).get("parts", [])
        audio = sum(len((p.get("inlineData") or {}).get("data", "")) for p in parts)
        texts = [p["text"] for p in parts if p.get("text")]
        if audio:
            bits.append(f"audio(base64)~{audio}B*3/4" )
        for t in texts:
            bits.append(f"text={t[:60]!r}")
        for flag in ("turnComplete", "interrupted", "generationComplete", "turnCompleteReason"):
            if flag in sc:
                bits.append(f"{flag}={sc[flag]}")
        if "inputTranscription" in sc:
            bits.append(f"in={sc['inputTranscription'].get('text','')[:40]!r}")
        if "outputTranscription" in sc:
            bits.append(f"out={sc['outputTranscription'].get('text','')[:40]!r}")
        return "serverContent(" + ", ".join(bits) + ")"
    return json.dumps({k: v for k, v in msg.items() if k != "data"}, ensure_ascii=False)[:400]
