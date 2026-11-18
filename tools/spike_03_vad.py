#!/usr/bin/env python3
"""Spike 3 — Gemini Live automatic VAD (말 끝 감지): defaults, sensitivity fields, delays.

Answers three questions with measurements, not docs:
  1. What does the DEFAULT (no realtimeInputConfig) VAD do, and how long after the
     last speech sample does the server decide the user stopped talking?
  2. Which automaticActivityDetection fields does the server actually accept?
     (each field probed in isolation -> exact error string for rejects)
  3. Do END_SENSITIVITY_HIGH+300ms vs END_SENSITIVITY_LOW+1500ms actually change
     the measured end-of-speech delay?

Plus: does manual mode (automaticActivityDetection.disabled) work, and does
activityEnd trigger a response immediately?

Run:  "/Users/kanyevelvet/.hermes/hermes-work/_venv/bin/python" \
        "/Users/kanyevelvet/.hermes/hermes-work/hermes native/tools/spike_03_vad.py"

Timing method
-------------
Speech is streamed at real time (30 ms chunks). As soon as the last speech sample
is sent we keep streaming DIGITAL SILENCE at the same pace, so end-of-speech
detection has real silence to chew on and needs no wall-clock guesswork.

The primary metric is the server's OWN VAD verdict: Gemini Live pushes
`voiceActivity` events (`ACTIVITY_START` / `ACTIVITY_END`) carrying an
`audioOffset` into the stream. So:

    end_of_speech   = monotonic() when the last speech chunk went out
    end_detect_ms   = monotonic() of voiceActivity:ACTIVITY_END  - end_of_speech
    detect_audio_ms = (ACTIVITY_END.audioOffset - ACTIVITY_START.audioOffset)
                      - speech_ms        # pacing-independent: silence it counted
    turn_complete   = serverContent.turnComplete   (adds model TTFB + generation)

If a config yields no voiceActivity events, we fall back to
`first_model_audio - manual_mode_TTFB` and flag it in the table.
"""

from __future__ import annotations

import asyncio
import json
import pathlib
import struct
import sys
import time
import wave
from typing import Any, Dict, List, Optional, Tuple

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import websockets  # noqa: E402

from gemini_live import LiveSession  # noqa: E402

MODEL = sys.argv[1] if len(sys.argv) > 1 else "gemini-3.8-live"
OUT_JSON = pathlib.Path(__file__).resolve().parent / "spike_03_vad.json"
WAV = pathlib.Path("/tmp/vad.wav")

RATE = 16000
CHUNK_MS = 30
BYTES_PER_MS = RATE * 2 // 1000  # 32 B/ms (16-bit mono)

INSTR = (
    "너는 한국어 음성 비서야. 사용자가 말을 마치면 무조건 '네'라고만 한 번 대답해. "
    "그 외의 말은 절대 하지 마."
)

RETRY_STATS = {"retries": 0, "events": []}


# ---------------------------------------------------------------- audio input
def load_pcm(path: pathlib.Path) -> bytes:
    with wave.open(str(path), "rb") as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, "need mono 16-bit"
        assert w.getframerate() == RATE, f"need {RATE} Hz, got {w.getframerate()}"
        return w.readframes(w.getnframes())


def trim_trailing_silence(pcm: bytes, threshold: int = 500, win_ms: int = 20) -> bytes:
    """Drop trailing near-silent frames so `end of speech` == last real sample."""
    win = RATE * win_ms // 1000 * 2
    end = len(pcm) - (len(pcm) % win)
    last_loud = 0
    for off in range(0, end, win):
        block = pcm[off:off + win]
        peak = max(abs(v) for v in struct.unpack(f"<{len(block)//2}h", block))
        if peak > threshold:
            last_loud = off + win
    return pcm[:last_loud] if last_loud else pcm


SPEECH = trim_trailing_silence(load_pcm(WAV))
SILENCE_CHUNK = b"\x00" * (BYTES_PER_MS * CHUNK_MS)
SPEECH_CHUNKS = [SPEECH[i:i + BYTES_PER_MS * CHUNK_MS]
                 for i in range(0, len(SPEECH), BYTES_PER_MS * CHUNK_MS)]
SPEECH_MS = len(SPEECH) / 2 / RATE * 1000


# ---------------------------------------------------------------- retry layer
def is_quota(exc: BaseException) -> bool:
    txt = f"{type(exc).__name__} {exc}".upper()
    return any(k in txt for k in ("429", "RESOURCE_EXHAUSTED", "QUOTA", "RATE LIMIT"))


async def with_retry(fn, label: str, attempts: int = 5):
    last: Optional[BaseException] = None
    for i in range(attempts):
        try:
            return await fn()
        except BaseException as exc:  # noqa: BLE001
            if isinstance(exc, (KeyboardInterrupt, SystemExit)):
                raise
            last = exc
            if not is_quota(exc):
                raise
            RETRY_STATS["retries"] += 1
            RETRY_STATS["events"].append({"label": label, "attempt": i + 1, "err": str(exc)[:200]})
            print(f"    [429] {label}: {str(exc)[:120]} -> sleep 20s (retry {i+1}/{attempts})")
            await asyncio.sleep(20)
    raise RuntimeError(f"{label}: gave up after {attempts} attempts: {last}")


# ---------------------------------------------------------------- field probes
def aad_cfg(**kw) -> Dict[str, Any]:
    return {"realtimeInputConfig": {"automaticActivityDetection": kw}}


PROBES: List[Tuple[str, Dict[str, Any]]] = [
    ("startOfSpeechSensitivity=START_SENSITIVITY_HIGH",
     aad_cfg(startOfSpeechSensitivity="START_SENSITIVITY_HIGH")),
    ("startOfSpeechSensitivity=START_SENSITIVITY_LOW",
     aad_cfg(startOfSpeechSensitivity="START_SENSITIVITY_LOW")),
    ("endOfSpeechSensitivity=END_SENSITIVITY_HIGH",
     aad_cfg(endOfSpeechSensitivity="END_SENSITIVITY_HIGH")),
    ("endOfSpeechSensitivity=END_SENSITIVITY_LOW",
     aad_cfg(endOfSpeechSensitivity="END_SENSITIVITY_LOW")),
    ("prefixPaddingMs=20", aad_cfg(prefixPaddingMs=20)),
    ("silenceDurationMs=300", aad_cfg(silenceDurationMs=300)),
    ("silenceDurationMs=500", aad_cfg(silenceDurationMs=500)),
    ("silenceDurationMs=1500", aad_cfg(silenceDurationMs=1500)),
    ("silenceDurationMs=2500", aad_cfg(silenceDurationMs=2500)),
    ("prefixPaddingMs=-1 (negative value: range check)", aad_cfg(prefixPaddingMs=-1)),
    ("endOfSpeechSensitivity=END_SENSITIVITY_MEDIUM (bogus enum, expect reject)",
     aad_cfg(endOfSpeechSensitivity="END_SENSITIVITY_MEDIUM")),
    ("automaticActivityDetection.disabled=true", aad_cfg(disabled=True)),
    ("all accepted fields together",
     {"realtimeInputConfig": {"automaticActivityDetection": {
         "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
         "endOfSpeechSensitivity": "END_SENSITIVITY_HIGH",
         "prefixPaddingMs": 20,
         "silenceDurationMs": 500}}}),
]


async def probe_one(label: str, cfg: Dict[str, Any]) -> Dict[str, Any]:
    """Send setup with one config and report accepted / rejected + exact error."""
    s = LiveSession(MODEL)
    out = {"label": label, "cfg": cfg, "status": "?", "error": ""}
    try:
        await s.__aenter__()
        await s.setup(voice="Kore", instructions=INSTR, extra=cfg, wait=False)
        deadline = time.monotonic() + 12
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                out.update(status="timeout", error="no reply in 12s")
                break
            try:
                msg = await s.recv(timeout=remaining)
            except asyncio.TimeoutError:
                out.update(status="timeout", error="no reply in 12s")
                break
            except websockets.ConnectionClosed as exc:
                out.update(status="closed",
                           error=f"code={exc.code} reason={exc.reason!r}")
                break
            if "setupComplete" in msg:
                out.update(status="accepted")
                break
            if "error" in msg:
                out.update(status="rejected",
                           error=json.dumps(msg["error"], ensure_ascii=False))
                break
    except Exception as exc:  # noqa: BLE001
        out.update(status="exception", error=f"{type(exc).__name__}: {exc}"[:300])
    finally:
        await s.__aexit__()
    return out


# ---------------------------------------------------------------- timed runs
async def run_turn(label: str, extra: Dict[str, Any], manual: bool = False,
                   wait_cap_s: float = 12.0) -> Dict[str, Any]:
    """Stream real speech, then measure how long the server takes to end the turn."""
    res: Dict[str, Any] = {"label": label, "manual": manual, "extra": extra,
                           "speech_ms": round(SPEECH_MS), "ok": False}
    s = LiveSession(MODEL)
    try:
        await s.__aenter__()
        await s.setup(voice="Kore", instructions=INSTR,
                      extra={**extra, "inputAudioTranscription": {}})
        t_setup = s.setup_complete_at - s.opened_at
        res["setup_ms"] = round(t_setup * 1000)

        if manual:
            await s.send_activity_start()

        # rate-lock to absolute deadlines: sleep(n*30ms) alone drifts ~15% slow,
        # which would inflate every wall-clock delay measured after this point.
        t_audio0 = time.monotonic()
        for i, chunk in enumerate(SPEECH_CHUNKS):
            await s.send_audio(chunk)
            target = t_audio0 + (i + 1) * CHUNK_MS / 1000
            delay = target - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)
        t_end_speech = time.monotonic()
        res["pacing_drift_ms"] = round((t_end_speech - t_audio0) * 1000 - SPEECH_MS)

        stop = asyncio.Event()
        pump: Optional[asyncio.Task] = None
        t_marker: float = time.monotonic()
        if manual:
            t_marker = time.monotonic()
            await s.send_activity_end()
            mode = "activityEnd"
        else:
            async def pump_silence() -> None:
                t0 = time.monotonic()
                n = 0
                while not stop.is_set():
                    try:
                        await s.send_audio(SILENCE_CHUNK)
                    except Exception:  # noqa: BLE001
                        return
                    n += 1
                    target = t0 + n * CHUNK_MS / 1000
                    delay = target - time.monotonic()
                    if delay > 0:
                        await asyncio.sleep(delay)

            t_marker = time.monotonic()
            pump = asyncio.create_task(pump_silence())
            mode = "autoVAD+silence"

        res["mode"] = mode
        res["marker"] = "last_speech_sample" if not manual else "activityEnd_sent"

        marks: List[Dict[str, Any]] = []
        t_first_audio = None
        t_turn = None
        t_first_input_tx = None
        va: List[Dict[str, Any]] = []
        tx_parts: List[str] = []

        def has_audio(parts: List[Dict[str, Any]]) -> bool:
            for p in parts:
                if (p.get("inlineData") or {}).get("data"):
                    return True
            return False

        deadline = time.monotonic() + wait_cap_s
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                res["timeout"] = True
                break
            try:
                msg = await s.recv(timeout=remaining)
            except asyncio.TimeoutError:
                res["timeout"] = True
                break
            except websockets.ConnectionClosed as exc:
                res["closed"] = f"code={exc.code} reason={exc.reason!r}"
                break
            now = time.monotonic()
            ms = round((now - t_marker) * 1000)

            # authoritative VAD signal: server tells us when it thinks speech starts/ends
            if "voiceActivity" in msg:
                v = msg["voiceActivity"]
                va.append({"type": v.get("type"), "audioOffset": v.get("audioOffset"), "ms": ms})
                marks.append({"ev": f"voiceActivity:{v.get('type')}", "ms": ms,
                              "audioOffset": v.get("audioOffset")})

            sc = msg.get("serverContent") or {}
            if "inputTranscription" in sc:
                bit = sc["inputTranscription"].get("text", "")
                if bit:
                    tx_parts.append(bit)
                    if t_first_input_tx is None:
                        t_first_input_tx = now
            if has_audio((sc.get("modelTurn") or {}).get("parts", [])) and t_first_audio is None:
                t_first_audio = now
                marks.append({"ev": "first_model_audio", "ms": ms})
            if sc.get("interrupted"):
                marks.append({"ev": "interrupted", "ms": ms})
            if sc.get("turnComplete"):
                t_turn = now
                marks.append({"ev": "turnComplete", "ms": ms,
                              "reason": sc.get("turnCompleteReason")})
                break

        if not manual:
            stop.set()
            if pump is not None:
                pump.cancel()
                try:
                    await pump
                except (asyncio.CancelledError, Exception):  # noqa: BLE001
                    pass

        def offset_ms(s_val: Optional[str]) -> Optional[float]:
            if not s_val:
                return None
            try:
                return float(str(s_val).rstrip("s")) * 1000
            except ValueError:
                return None

        end_ev = next((v for v in va if v["type"] == "ACTIVITY_END"), None)
        start_ev = next((v for v in va if v["type"] == "ACTIVITY_START"), None)
        res["voiceActivity"] = va
        res["has_voiceActivity"] = bool(va)
        res["end_detect_ms"] = end_ev["ms"] if end_ev else None
        # pacing-independent: how much audio the SERVER counted as the utterance
        o0, o1 = offset_ms((start_ev or {}).get("audioOffset")), offset_ms((end_ev or {}).get("audioOffset"))
        res["utterance_audio_ms"] = round(o1 - o0) if (o0 is not None and o1 is not None) else None
        res["detect_audio_ms"] = (round((o1 - o0) - SPEECH_MS)
                                  if (o0 is not None and o1 is not None) else None)
        res["marks"] = marks
        res["first_audio_ms"] = round((t_first_audio - t_marker) * 1000) if t_first_audio else None
        res["turn_complete_ms"] = round((t_turn - t_marker) * 1000) if t_turn else None
        res["first_input_tx_ms"] = round((t_first_input_tx - t_marker) * 1000) if t_first_input_tx else None
        res["input_text"] = "".join(tx_parts)
        res["turn_reason"] = next((m.get("reason") for m in marks if m["ev"] == "turnComplete"), None)
        res["kinds"] = sorted(set(s.kinds()))
        res["ok"] = t_turn is not None
        print(f"    [{label}] detect={res['end_detect_ms']} ms  "
              f"silence_counted={res['detect_audio_ms']} ms  "
              f"first_audio={res['first_audio_ms']} ms  "
              f"turnComplete={res['turn_complete_ms']} ms  "
              f"pacing_drift={res.get('pacing_drift_ms')} ms")
        return res
    finally:
        await s.__aexit__()


# ---------------------------------------------------------------- main
async def main() -> int:
    print("# Spike 3 — Gemini Live automatic VAD (말 끝 감지)\n")
    print(f"model=`{MODEL}`  speech=`{WAV}`  speech_len={SPEECH_MS:.0f} ms "
          f"(trailing silence trimmed)  chunk={CHUNK_MS} ms  rate={RATE} Hz mono 16-bit\n")

    t_start = time.monotonic()
    report: Dict[str, Any] = {"model": MODEL, "speech_ms": round(SPEECH_MS),
                              "chunk_ms": CHUNK_MS, "rate": RATE}

    # --- 1. field probes -------------------------------------------------
    print("## 1. Field acceptance (each field sent alone in setup)\n")
    probes = []
    for label, cfg in PROBES:
        r = await with_retry(lambda c=cfg, l=label: probe_one(l, c), f"probe:{label}")
        probes.append(r)
        flag = {"accepted": "OK", "rejected": "REJECT", "closed": "REJECT"}.get(r["status"], r["status"].upper())
        err = f"  err={r['error']}" if r["error"] else ""
        print(f"  {flag:6s} {label}{err}")
    report["probes"] = probes

    # --- 2. default VAD, twice -------------------------------------------
    print("\n## 2. Baseline: no realtimeInputConfig (default VAD)\n")
    baseline = []
    for i in (1, 2):
        r = await with_retry(lambda i=i: run_turn(f"baseline_default_{i}", {}), f"baseline_{i}")
        baseline.append(r)
        print(f"  run{i}: first_audio={r['first_audio_ms']} ms  turnComplete={r['turn_complete_ms']} ms"
              f"  heard={r['input_text']!r}  reason={r['turn_reason']}")
    report["baseline"] = baseline

    # --- 3. sensitivity / silenceDuration comparison ---------------------
    print("\n## 3. Sensitivity + silenceDuration (does it actually change timing?)\n")
    variants = []

    r_hi = await with_retry(lambda: run_turn(
        "END_SENSITIVITY_HIGH_silence300",
        aad_cfg(startOfSpeechSensitivity="START_SENSITIVITY_HIGH",
                endOfSpeechSensitivity="END_SENSITIVITY_HIGH",
                prefixPaddingMs=20, silenceDurationMs=300)), "hi300")
    variants.append(r_hi)
    print(f"  HIGH/300 : first_audio={r_hi['first_audio_ms']} ms  turnComplete={r_hi['turn_complete_ms']} ms"
          f"  heard={r_hi['input_text']!r}")

    r_lo = await with_retry(lambda: run_turn(
        "END_SENSITIVITY_LOW_silence1500",
        aad_cfg(startOfSpeechSensitivity="START_SENSITIVITY_HIGH",
                endOfSpeechSensitivity="END_SENSITIVITY_LOW",
                prefixPaddingMs=20, silenceDurationMs=1500)), "lo1500")
    variants.append(r_lo)
    print(f"  LOW/1500 : first_audio={r_lo['first_audio_ms']} ms  turnComplete={r_lo['turn_complete_ms']} ms"
          f"  heard={r_lo['input_text']!r}")

    r_lo_mid = await with_retry(lambda: run_turn(
        "END_SENSITIVITY_LOW_silence500_default",
        aad_cfg(endOfSpeechSensitivity="END_SENSITIVITY_LOW",
                silenceDurationMs=500)), "lo500")
    variants.append(r_lo_mid)
    print(f"  LOW/500  : first_audio={r_lo_mid['first_audio_ms']} ms  turnComplete={r_lo_mid['turn_complete_ms']} ms"
          f"  heard={r_lo_mid['input_text']!r}")

    report["variants"] = variants

    # the "must never cut the user off" candidate, measured not assumed
    r_lo_max = await with_retry(lambda: run_turn(
        "END_SENSITIVITY_LOW_silence2500",
        aad_cfg(endOfSpeechSensitivity="END_SENSITIVITY_LOW",
                silenceDurationMs=2500)), "lo2500")
    variants.append(r_lo_max)
    print(f"  LOW/2500 : first_audio={r_lo_max['first_audio_ms']} ms  turnComplete={r_lo_max['turn_complete_ms']} ms"
          f"  heard={r_lo_max['input_text']!r}")

    # --- 4. manual mode --------------------------------------------------
    print("\n## 4. Manual mode: automaticActivityDetection.disabled + activityStart/End\n")
    r_man = await with_retry(lambda: run_turn(
        "manual_disabled", aad_cfg(disabled=True), manual=True), "manual")
    print(f"  accepted_and_responded={r_man['ok']}  first_audio={r_man['first_audio_ms']} ms"
          f"  turnComplete={r_man['turn_complete_ms']} ms  heard={r_man['input_text']!r}")
    report["manual"] = r_man

    # --- 5. derived numbers ----------------------------------------------
    manual_ttfb = r_man["first_audio_ms"]

    def primary(r: Dict[str, Any]) -> Optional[int]:
        """End-of-speech detection delay: AAD ACTIVITY_END wall clock if present,
        else (first model audio - manual-mode TTFB floor)."""
        if r.get("end_detect_ms") is not None:
            return r["end_detect_ms"]
        if r.get("first_audio_ms") is not None and manual_ttfb is not None:
            return r["first_audio_ms"] - manual_ttfb
        return None

    for r in baseline + variants + [r_man]:
        r["detect_ms"] = primary(r)

    bvals = [r["detect_ms"] for r in baseline if r["detect_ms"] is not None]
    baseline_avg = round(sum(bvals) / len(bvals)) if bvals else None
    diff = (r_lo["detect_ms"] or 0) - (r_hi["detect_ms"] or 0)
    report["derived"] = {
        "detect_metric": "voiceActivity ACTIVITY_END wall-clock ms after last speech sample",
        "manual_ttfb_ms": manual_ttfb,
        "baseline_detect_avg_ms": baseline_avg,
        "high300_detect_ms": r_hi["detect_ms"],
        "low1500_detect_ms": r_lo["detect_ms"],
        "low2500_detect_ms": r_lo_max["detect_ms"],
        "low_minus_high_ms": diff,
        "voiceActivity_events_seen": any(r.get("has_voiceActivity") for r in baseline + variants),
    }

    # --- 6. markdown table ----------------------------------------------
    def note_for(r: Dict[str, Any]) -> str:
        bits = []
        if r.get("input_text"):
            bits.append(f"heard {r['input_text']!r}")
        if r.get("detect_audio_ms") is not None:
            bits.append(f"silence it actually counted={r['detect_audio_ms']} ms "
                        f"(utterance_audio={r['utterance_audio_ms']} ms - speech {round(SPEECH_MS)} ms)")
        if r.get("turn_complete_ms") is not None:
            bits.append(f"turnComplete={r['turn_complete_ms']} ms")
        if r.get("first_audio_ms") is not None:
            bits.append(f"first_audio={r['first_audio_ms']} ms")
        elif r.get("turn_complete_ms") is not None:
            bits.append("turn returned no audio frames (model empty turn; VAD timing unaffected)")
        if r.get("timeout"):
            bits.append("TIMEOUT no turnComplete")
        if r.get("closed"):
            bits.append(r["closed"])
        if not r.get("has_voiceActivity"):
            bits.append("no voiceActivity events -> fell back to first_audio-TTFB")
        return "; ".join(bits) or "-"

    lines = [
        "| variant | fields accepted | end-detection delay ms | notes |",
        "| --- | --- | --- | --- |",
    ]
    for r in baseline:
        lines.append(f"| {r['label']} | none sent (defaults) | {r['detect_ms']} | "
                     f"{note_for(r)} |")
    for r in variants:
        cfg = r["extra"].get("realtimeInputConfig", {}).get("automaticActivityDetection", {})
        fields = ", ".join(f"{k}={v}" for k, v in cfg.items()) or "none"
        lines.append(f"| {r['label']} | {fields} | {r['detect_ms']} | {note_for(r)} |")
    lines.append(f"| manual_disabled | disabled=true + activityEnd | {r_man['detect_ms']} | "
                 f"{note_for(r_man)}; activityEnd -> response with NO silence wait |")
    table = "\n".join(lines)

    print("\n## Result table\n")
    print(table)
    print(f"\n  default end-detection avg: {baseline_avg} ms  (voiceActivity ACTIVITY_END"
          f"={'yes' if report['derived']['voiceActivity_events_seen'] else 'no'})")
    print(f"  HIGH/300 vs LOW/1500: {r_hi['detect_ms']} ms vs {r_lo['detect_ms']} ms "
          f"= {diff:+d} ms difference")
    print(f"  LOW/2500 (never-cut-off candidate): {r_lo_max['detect_ms']} ms")
    print(f"  manual activityEnd TTFB floor: {manual_ttfb} ms")
    print(f"\n  retries(429): {RETRY_STATS['retries']}   elapsed: {time.monotonic()-t_start:.1f}s")

    report["table_md"] = table
    report["retries"] = RETRY_STATS
    report["elapsed_s"] = round(time.monotonic() - t_start, 1)
    OUT_JSON.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nwrote {OUT_JSON}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
