"""Spike 03b — pick the VAD setting the app ships with, by measuring it.

spike_03 measured the ends of the range. This run measures the two middle candidates that matter.
One trap is encoded here: end-of-speech detection only fires when SILENCE audio is actually streamed,
so the harness feeds ~1.6 s of digital silence after the sentence and timestamps the server's own
`voiceActivity ACTIVITY_END`.

Run (from tools/):
    ~/.hermes/hermes-work/_venv/bin/python spike_03_conclude.py
"""

from __future__ import annotations

import asyncio
import json
import time
import wave
from pathlib import Path

from gemini_live import LiveSession

HERE = Path(__file__).resolve().parent
MODEL = "gemini-3.8-live"
SILENCE_MS = 1600
CHUNK = 960  # 30 ms of 16 kHz mono 16-bit
WAV = "/tmp/spike3b.wav"


def load_pcm() -> bytes:
    with wave.open(WAV, "rb") as w:
        return w.readframes(w.getnframes())


async def measure(label: str, vad: dict, pcm: bytes) -> dict:
    silence = b"\x00\x00" * (16_000 * SILENCE_MS // 1000)
    async with LiveSession(MODEL) as s:
        await s.setup(
            voice="Kore",
            instructions="한국어로 짧게 대답해.",
            extra={
                "inputAudioTranscription": {},
                "outputAudioTranscription": {},
                "realtimeInputConfig": {"automaticActivityDetection": vad},
            },
        )
        for i in range(0, len(pcm), CHUNK):
            await s.send_audio(pcm[i:i + CHUNK])
            await asyncio.sleep(CHUNK / 2 / 16_000)
        speech_end = time.time()

        end_at = None
        first_audio = None
        for i in range(0, len(silence), CHUNK):
            await s.send_audio(silence[i:i + CHUNK])
            await asyncio.sleep(CHUNK / 2 / 16_000)
            while True:
                try:
                    raw = await asyncio.wait_for(s.ws.recv(), timeout=0.001)
                except asyncio.TimeoutError:
                    break
                msg = json.loads(raw)
                s._record(msg)
                activity = msg.get("voiceActivity") or {}
                if activity.get("type") == "ACTIVITY_END" and end_at is None:
                    end_at = time.time()
                content = msg.get("serverContent") or {}
                for part in (content.get("modelTurn") or {}).get("parts", []):
                    if (part.get("inlineData") or {}).get("data") and first_audio is None:
                        first_audio = time.time()
            if end_at and first_audio:
                break

        return {
            "label": label,
            "vad": vad,
            "end_detect_ms": None if end_at is None else round((end_at - speech_end) * 1000),
            "first_audio_ms": None if first_audio is None else round((first_audio - speech_end) * 1000),
        }


async def main() -> None:
    pcm = load_pcm()
    runs = [("HIGH+300", 300), ("HIGH+500", 500)]
    out = []
    for label, silence_ms in runs:
        result = await measure(label, {
            "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
            "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
            "silenceDurationMs": silence_ms,
            "prefixPaddingMs": 40,
        }, pcm)
        out.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)

    (HERE / "spike_03_conclude.json").write_text(json.dumps(out, ensure_ascii=False, indent=2))
    print("\n| 설정 | 말 끝 감지 | 첫 음성 |")
    print("|---|---|---|")
    for row in out:
        print(f"| {row['label']} | {row['end_detect_ms']} ms | {row['first_audio_ms']} ms |")


asyncio.run(main())
