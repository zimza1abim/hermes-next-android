"""Spike 0 — connection smoke test: setup, first audio, transcription, latency.

Run:  ../_venv/bin/python tools/spike_00_smoke.py [model]
"""

from __future__ import annotations

import asyncio
import sys
import time

from gemini_live import LiveSession, decode_audio_from, pcm_to_wav, summarize

MODEL = sys.argv[1] if len(sys.argv) > 1 else "gemini-3.8-live"


async def main() -> None:
    async with LiveSession(MODEL) as s:
        t0 = time.monotonic()
        await s.setup(
            voice="Kore",
            instructions="너는 다정한 한국어 비서야. 반말로 짧게 대답해.",
            extra={"inputAudioTranscription": {}, "outputAudioTranscription": {}},
        )
        t_setup = time.monotonic() - t0
        print(f"setupComplete in {t_setup*1000:.0f} ms")

        t_ask = time.monotonic()
        await s.say("한 문장으로 인사해줘.")
        async for msg in s.events(timeout=20, until_turn_complete=True):
            print(f"  +{(time.monotonic()-t_ask)*1000:6.0f} ms  {summarize(msg)}")
            if msg.get("serverContent", {}).get("turnComplete"):
                break
        first = (s.first_audio_at - t_ask) * 1000 if s.first_audio_at else -1
        print(f"first audio after ask: {first:.0f} ms, bytes={s.audio_bytes}")
        audio = decode_audio_from(s.log)
        out = "/tmp/spike0.wav"
        pcm_to_wav(audio, out)
        print(f"wav: {out} ({len(audio)/2/24000:.2f}s)")
        print("kinds:", s.kinds())


asyncio.run(main())
