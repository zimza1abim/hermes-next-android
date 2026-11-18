"""Spike 05 — can the speaking rate be set on the Live API, and where?

The plan's voice settings need a speed control. Two ways to get one:
  a) ask the server to speak slower/faster (best quality, no DSP);
  b) stretch the returned audio on the phone (works even if the API has no field).

This script answers (a): which field the setup accepts, and whether it actually changes the
duration of the same sentence.

Run (from tools/):
    ~/.hermes/hermes-work/_venv/bin/python spike_05_speed.py
"""

from __future__ import annotations

import asyncio
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gemini_live import LiveSession, decode_audio_from, pcm_to_wav, summarize  # noqa: E402

HERE = Path(__file__).resolve().parent
MODEL = "gemini-3.8-live"
SENTENCE = "오늘 하루도 정말 고생 많았어. 잠깐 쉬어 가자."

# Candidate shapes: the live setup may take a rate beside the voice, or inside generationConfig.
CANDIDATES = [
    ("speechConfig.speakingRate=1.5", {"speechConfig": {"speakingRate": 1.5}}),
    ("speechConfig.speakingRate=0.8", {"speechConfig": {"speakingRate": 0.8}}),
    ("speechConfig.speed=1.5", {"speechConfig": {"speed": 1.5}}),
    ("generationConfig.speakingRate=1.5", {"generationConfig": {"speakingRate": 1.5}}),
    ("generationConfig.speechConfig.speakingRate=1.5",
     {"generationConfig": {"speechConfig": {"speakingRate": 1.5}}}),
    ("speechConfig SAPI-style speaking_rate=1.5", {"speechConfig": {"speaking_rate": 1.5}}),
]


async def probe(label: str, extra: dict) -> dict:
    """Send a setup with `extra` merged in; record acceptance and the error text."""
    async with LiveSession(MODEL) as s:
        try:
            await s.setup(voice="Kore", instructions="한국어로 짧게 대답해.", extra=extra)
            return {"label": label, "accepted": True, "error": ""}
        except Exception as exc:  # noqa: BLE001
            return {"label": label, "accepted": False, "error": repr(exc)[:220]}


async def measure(rate: float | None) -> dict:
    """Same sentence at one rate; duration is the proof."""
    extra = {"outputAudioTranscription": {}}
    if rate is not None:
        extra["speechConfig"] = {"speakingRate": rate}
    async with LiveSession(MODEL) as s:
        await s.setup(voice="Kore", instructions="다음 문장을 그대로 읽어 줘.", extra=extra)
        await s.say(f"다음 문장만 그대로 읽어 줘: {SENTENCE}")
        async for _ in s.events(timeout=40, until_turn_complete=True):
            pass
        audio = decode_audio_from(s.log)
        seconds = len(audio) / 2 / 24_000
        text = ""
        for entry in s.log:
            content = entry["msg"].get("serverContent") or {}
            text += (content.get("outputTranscription") or {}).get("text", "")
        wav = HERE / f"spike05_speed_{'default' if rate is None else rate}.wav"
        if audio:
            pcm_to_wav(str(wav), str(wav)) if False else pcm_to_wav(audio, str(wav))
        return {
            "rate": rate,
            "seconds": round(seconds, 2),
            "bytes": len(audio),
            "spoken": text[:70],
            "wav": str(wav) if audio else None,
        }


async def main() -> None:
    print(f"# Spike 05 — speaking rate on {MODEL}\n")
    print("## 1. which setup field is accepted\n")
    probes = []
    for label, extra in CANDIDATES:
        result = await probe(label, extra)
        probes.append(result)
        mark = "OK    " if result["accepted"] else "REJECT"
        print(f"  {mark} {label}  {result['error'][:120]}")

    accepted = [p for p in probes if p["accepted"] and p["label"].endswith("=1.5")]
    print("\n## 2. does it change the duration of the same sentence?\n")
    runs = []
    if accepted:
        for rate in (0.8, 1.0, 1.5):
            try:
                result = await measure(rate)
            except Exception as exc:  # noqa: BLE001
                result = {"rate": rate, "error": repr(exc)[:160]}
            runs.append(result)
            print(f"  rate={rate}: {result}")
    baseline = await measure(None)
    runs.append(baseline)
    print(f"  default : {baseline}")

    plain = [r for r in runs if r.get("seconds")]
    if len(plain) >= 2:
        slow = min(plain, key=lambda r: r["rate"] or 1.0)
        fast = max(plain, key=lambda r: r["rate"] or 1.0)
        if slow["seconds"] and fast["seconds"]:
            print(f"\n  slowest {slow['rate']} -> {slow['seconds']}s, fastest {fast['rate']} -> {fast['seconds']}s "
                  f"({slow['seconds'] / fast['seconds']:.2f}x)")

    (HERE / "spike_05_speed.json").write_text(
        json.dumps({"probes": probes, "runs": runs}, ensure_ascii=False, indent=2))
    print("\njson -> tools/spike_05_speed.json")


asyncio.run(main())
