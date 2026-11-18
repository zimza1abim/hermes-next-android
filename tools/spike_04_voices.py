#!/usr/bin/env python3
"""Spike 04 - enumerate Gemini Live prebuilt voices, measure pitch & quality.

One LiveSession per candidate voice. Fixed Korean input sentence. For each:
  * raw PCM bytes / duration / sha256 (exact sha is reported for the record, but
    this endpoint is nondeterministic at the sample level: two identical runs
    give different sha even with temperature=0, so sha cannot define identity)
  * LTAS (long-term average spectrum) fingerprint -> identity clusters.
    Measured gap on this endpoint: same voice ~0.04, different voice ~0.26.
  * median F0 (Hz) via pure-python normalized autocorrelation (no numpy)
  * outputAudioTranscription text -> is the output Korean / natural?

An unknown voice name silently falls back to a default voice; its LTAS will
cluster with whichever real voice the API actually used.

Run (from tools/):
    ../_venv/bin/python spike_04_voices.py
    ../_venv/bin/python spike_04_voices.py --voices Kore,Zephyr,NoSuchVoiceXYZ
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import re
import sys
import time
from array import array
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from gemini_live import LiveSession, decode_audio_from, pcm_to_wav  # noqa: E402

MODEL = "gemini-3.8-live"
RATE = 24_000
SENTENCE = "안녕하세요. 저는 하루를 함께 보내는 음성 비서예요. 오늘 기분은 어떠세요?"
INSTRUCTIONS = "너는 다정한 한국어 음성 비서야."
INVALID = "NoSuchVoiceXYZ"
LTAS_THRESHOLD = 0.15         # measured: same voice ~0.03-0.08, different ~0.40+
SPEECH_FRAC = 0.12            # voiced-frame energy threshold (relative to peak)
AUDIO_CAP_S = 22              # stop reading a runaway/repeating audio stream
MIN_SPEECH_FRAC = 0.15         # below this the clip is silence -> retry

VOICES = [
    "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede",
    "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba",
    "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
    "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
    "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
]

HANGUL = re.compile(r"[\uac00-\ud7a3]")


# --------------------------------------------------------------------------
# PCM analysis (pure python, no numpy)
# --------------------------------------------------------------------------
def _samples(pcm: bytes, downsample: int = 4):
    n = len(pcm) // 2
    a = array("h")
    a.frombytes(pcm[: n * 2])
    if downsample > 1:
        a = a[::downsample]
    return a, RATE // downsample


def estimate_f0(pcm: bytes):
    """Median F0 (Hz) over voiced frames via normalized autocorrelation."""
    if len(pcm) < 800:
        return None, 0
    x, sr = _samples(pcm, downsample=4)      # 6 kHz -> F0 70..300 Hz = lag 20..85
    if len(x) < 400:
        return None, 0
    frame = int(0.040 * sr)
    hop = int(0.020 * sr)
    min_lag, max_lag = int(sr / 300), int(sr / 70)

    frames = []
    for s in range(0, len(x) - frame, hop):
        seg = x[s : s + frame]
        m = sum(seg) / frame
        y = [v - m for v in seg]
        e = sum(v * v for v in y)
        frames.append((y, e))
    if not frames:
        return None, 0
    peak_e = max(e for _, e in frames)
    if peak_e <= 0:
        return None, 0

    f0s = []
    for y, e in frames:
        if e < 0.08 * peak_e:
            continue
        rvals = [0.0] * (max_lag + 2)
        best_val, best_lag = -1.0, min_lag
        for tau in range(min_lag, max_lag + 1):
            num = e1 = e2 = 0.0
            lim = frame - tau
            for i in range(lim):
                a, b = y[i], y[i + tau]
                num += a * b
                e1 += a * a
                e2 += b * b
            if e1 <= 0 or e2 <= 0:
                continue
            r = num / math.sqrt(e1 * e2)
            rvals[tau] = r
            if r > best_val:
                best_val, best_lag = r, tau
        if best_val < 0.45:
            continue
        strong = [
            t for t in range(min_lag + 1, max_lag)
            if rvals[t] >= 0.85 * best_val
            and rvals[t] >= rvals[t - 1]
            and rvals[t] >= rvals[t + 1]
        ]
        lag = strong[0] if strong else best_lag
        if min_lag < lag < max_lag:
            y1, y2, y3 = rvals[lag - 1], rvals[lag], rvals[lag + 1]
            den = y1 - 2 * y2 + y3
            if den != 0:
                lag += 0.5 * (y1 - y3) / den
        f0s.append(sr / lag)

    if not f0s:
        return None, 0
    f0s.sort()
    return round(f0s[len(f0s) // 2], 1), len(f0s)


# --- LTAS: text-independent timbre fingerprint ----------------------------
_NFFT = 128
_K = _NFFT // 2 + 1
_COS = [[math.cos(2 * math.pi * k * i / _NFFT) for i in range(_NFFT)] for k in range(_K)]
_SIN = [[math.sin(2 * math.pi * k * i / _NFFT) for i in range(_NFFT)] for k in range(_K)]
_WIN = [0.5 - 0.5 * math.cos(2 * math.pi * i / (_NFFT - 1)) for i in range(_NFFT)]


def speech_only(pcm: bytes, frac: float = SPEECH_FRAC, win_ms: int = 10) -> bytes:
    """Drop silent/repeated-gap frames; concatenate only voiced 10 ms chunks.

    The Live endpoint sometimes emits long silences and repeats the utterance.
    LTAS over voiced frames is invariant to how many times it repeats, so this
    canonicalization makes the timbre fingerprint stable across sessions.
    """
    a = array("h")
    a.frombytes(pcm[: len(pcm) // 2 * 2])
    w = RATE * win_ms // 1000
    chunks = [a[i : i + w] for i in range(0, len(a) - w, w)]
    if not chunks:
        return b""
    peak = max(sum(v * v for v in c) for c in chunks) or 1
    keep = [c for c in chunks if sum(v * v for v in c) > frac * peak]
    out = array("h")
    for c in keep:
        out.extend(c)
    return out.tobytes()


def ltas_vector(pcm: bytes):
    """Gain-normalized mean log-magnitude spectrum over voiced frames only."""
    a = array("h")
    a.frombytes(speech_only(pcm))
    x = a[::4]                                # 6 kHz
    if len(x) < _NFFT:
        return None
    hop = 64
    acc = [0.0] * _K
    count = 0
    for start in range(0, len(x) - _NFFT, hop):
        seg = [x[start + i] * _WIN[i] for i in range(_NFFT)]
        for k in range(_K):
            re = sum(seg[i] * _COS[k][i] for i in range(_NFFT))
            im = sum(seg[i] * _SIN[k][i] for i in range(_NFFT))
            acc[k] += math.log(math.sqrt(re * re + im * im) + 1e-6)
        count += 1
    if not count:
        return None
    v = [c / count for c in acc]
    mean = sum(v) / len(v)
    return [round(q - mean, 5) for q in v]


def ltas_dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)) / len(a))


def quality_note(text: str):
    if not text.strip():
        return "no transcription"
    flat = " ".join(text.split())
    ko = len(HANGUL.findall(flat))
    snippet = flat[:30]
    if ko >= 4:
        return f"KO-ok({ko} Hangul) '{snippet}'"
    return f"NO-HANGUL '{snippet}'"


# --------------------------------------------------------------------------
# One live session per voice
# --------------------------------------------------------------------------
def build_extra(name: str):
    # temperature=0 makes the spoken text deterministic across runs, which is
    # required for a meaningful timbre comparison (raw PCM still varies).
    return {
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "temperature": 0.0,
            "speechConfig": {
                "languageCode": "ko-KR",
                "voiceConfig": {"prebuiltVoiceConfig": {"voiceName": name}},
            },
        },
        "inputAudioTranscription": {},
        "outputAudioTranscription": {},
    }


async def run_voice(name: str):
    retries = 0
    last_err = ""
    for attempt in range(6):                 # initial + up to 5 retries
        try:
            async with LiveSession(MODEL) as s:
                await s.setup(
                    voice=name,
                    instructions=INSTRUCTIONS,
                    language_code="ko-KR",
                    extra=build_extra(name),
                )
                await s.say(SENTENCE)
                async for _msg in s.events(timeout=20, until_turn_complete=True):
                    # the endpoint can repeat the reply forever; cap the stream
                    if s.audio_bytes >= AUDIO_CAP_S * RATE * 2:
                        break
                pcm = decode_audio_from(s.log)
                texts = []
                for entry in s.log:
                    sc = entry["msg"].get("serverContent") or {}
                    ot = sc.get("outputTranscription") or {}
                    if ot.get("text"):
                        texts.append(ot["text"])
                    for part in (sc.get("modelTurn") or {}).get("parts", []):
                        if part.get("text"):
                            texts.append(part["text"])
                out_text = "".join(texts).strip()
                speech_frac = len(speech_only(pcm)) / len(pcm) if pcm else 0.0
                if not pcm or speech_frac < MIN_SPEECH_FRAC:
                    # intermittent silent responses are common on this endpoint
                    raise RuntimeError(
                        f"insufficient speech: {speech_frac:.2f} of {len(pcm)} bytes"
                    )
                wav = str(HERE / f"spike04_{name}.wav")
                pcm_to_wav(pcm, wav)
                f0, nvoiced = estimate_f0(pcm)
                return {
                    "voice": name,
                    "bytes": len(pcm),
                    "duration_s": round(len(pcm) / 2 / RATE, 3),
                    "sha256": hashlib.sha256(pcm).hexdigest(),
                    "f0_hz": f0,
                    "voiced_frames": nvoiced,
                    "speech_frac": round(speech_frac, 3),
                    "output_text": out_text,
                    "quality": quality_note(out_text),
                    "wav": wav,
                    "ltas": ltas_vector(pcm),
                    "retries": retries,
                    "attempts": attempt + 1,
                    "error": "",
                }
        except Exception as exc:             # noqa: BLE001 - spike must be resilient
            last_err = f"{type(exc).__name__}: {exc}"[:300]
            rate_limited = "429" in last_err or "resource_exhausted" in last_err.lower()
            if attempt >= 5:
                break
            delay = 20.0 if rate_limited else 5.0
            retries += 1
            print(
                f"    retry {retries}/5 for {name} after {delay:.0f}s "
                f"({'429' if rate_limited else 'err'}) {last_err[:120]}",
                file=sys.stderr, flush=True,
            )
            await asyncio.sleep(delay)
    return {
        "voice": name, "bytes": 0, "duration_s": 0.0, "sha256": "",
        "f0_hz": None, "voiced_frames": 0, "speech_frac": 0.0, "output_text": "",
        "quality": "FAILED", "wav": "", "ltas": None, "retries": retries,
        "attempts": 6, "error": last_err,
    }


def regroup(results):
    """Assign exact-sha groups, LTAS identity clusters and distinct flags."""
    # exact sha groups (informational - nondeterministic endpoint)
    exact = defaultdict(list)
    for r in results:
        if r["sha256"]:
            exact[r["sha256"]].append(r["voice"])
    sha_group = {}
    for gid, (sha, members) in enumerate(
        sorted(exact.items(), key=lambda kv: -len(kv[1])), 1
    ):
        for v in members:
            sha_group[v] = f"g{gid}({len(members)})"

    # identity clusters via LTAS timbre; F0 is reported but not used to split
    clusters = []
    for r in results:
        r["nearest"] = ""
        r["nearest_dist"] = None
        if not r.get("ltas"):
            r["cluster"] = f"FAILED({r['voice']})"
            r["cluster_members"] = [r["voice"]]
            continue
        best_d, best_name, best_c = 1e9, "", None
        for c in clusters:
            d = ltas_dist(r["ltas"], c["ltas"])
            if d < best_d:
                best_d, best_name, best_c = d, c["rep"], c
        r["nearest"] = best_name
        r["nearest_dist"] = None if best_c is None else round(best_d, 3)
        if best_d < LTAS_THRESHOLD:
            best_c["members"].append(r["voice"])
            r["cluster"] = best_c["rep"]
            r["cluster_members"] = list(best_c["members"])
        else:
            clusters.append({
                "rep": r["voice"], "ltas": r["ltas"], "f0": r["f0_hz"],
                "members": [r["voice"]],
            })
            r["cluster"] = r["voice"]
            r["cluster_members"] = [r["voice"]]

    for r in results:
        csize = len(r["cluster_members"])
        if not r.get("ltas"):
            r["distinct"] = "FAILED"
        elif r["cluster"] == r["voice"] and csize == 1:
            r["distinct"] = "YES"
        else:
            r["distinct"] = f"DUP->{r['cluster']}"
        r["sha_group"] = sha_group.get(r["voice"], "-")
    return results, exact, clusters


async def main_async(names, sleep_between):
    results = []
    total = len(names)
    for idx, name in enumerate(names, 1):
        print(f"[{idx:02d}/{total}] {name} ...", file=sys.stderr, flush=True)
        t0 = time.monotonic()
        r = await run_voice(name)
        r["wall_s"] = round(time.monotonic() - t0, 1)
        print(
            f"    -> {r['duration_s']}s f0={r['f0_hz']}Hz sha={r['sha256'][:10]} "
            f"retries={r['retries']} {r['quality'][:32]}",
            file=sys.stderr, flush=True,
        )
        results.append(r)
        if idx < total and sleep_between:
            await asyncio.sleep(sleep_between)

    return regroup(results)


def print_report(results, exact, clusters):
    print("\n# Spike 04 - Gemini Live prebuilt voice sweep")
    print(f"\nModel: `{MODEL}`  |  languageCode: `ko-KR`  |  temperature: `0`")
    print(f"Fixed input: `{SENTENCE}`")
    print("\nNote: this endpoint is nondeterministic at the PCM-sample level "
          "(repeat runs of one voice have different sha256). Identity is therefore "
          f"decided by LTAS timbre distance (<{LTAS_THRESHOLD}); exact sha groups are "
          "shown only for the record.\n")
    print("| voice | distinct? (sha dup group) | F0 Hz | duration | speech | Korean quality | wav path |")
    print("|---|---|---:|---:|---:|---|---|")
    for r in results:
        f0 = "n/a" if r["f0_hz"] is None else f"{r['f0_hz']:.1f}"
        print(
            f"| {r['voice']} | {r['distinct']} ({r['sha_group']}) | {f0} | "
            f"{r['duration_s']:.2f}s | {r.get('speech_frac', 0):.0%} | "
            f"{r['quality']} | `{r['wav']}` |"
        )

    print("\n## LTAS identity clusters (LTAS distance < %.2f)" % LTAS_THRESHOLD)
    for c in clusters:
        f0 = "n/a" if c["f0"] is None else f"{c['f0']:.1f}"
        tag = "  <-- fallback cluster" if len(c["members"]) > 1 else ""
        print(f"- {c['rep']} (F0 {f0} Hz): {', '.join(c['members'])}{tag}")

    print("\n## Exact sha256 groups (informational; endpoint is nondeterministic)")
    for sha, members in sorted(exact.items(), key=lambda kv: -len(kv[1])):
        print(f"- {sha[:16]} x{len(members)}: {', '.join(members)}")

    inv = next((r for r in results if r["voice"] == INVALID), None)
    if inv:
        print(f"\n## Invalid-name reference ({INVALID})")
        print(f"- {inv['distinct']} | cluster={inv['cluster_members']} | "
              f"nearest={inv['nearest']} (LTAS {inv['nearest_dist']}) | F0={inv['f0_hz']} Hz")

    real = [
        r for r in results
        if r["voice"] != INVALID and r["distinct"] == "YES" and r["f0_hz"]
    ]
    real.sort(key=lambda r: -r["f0_hz"])
    print("\n## Real distinct voices, F0 high -> low")
    for r in real:
        print(f"- {r['voice']:16s} {r['f0_hz']:6.1f} Hz  ({r['duration_s']:.2f}s)")

    poor = [r for r in results if r["quality"].startswith(("NO-HANGUL", "no transcription", "FAILED"))]
    print("\n## Poor / non-Korean output")
    print("- " + (", ".join(f"{r['voice']} ({r['quality']})" for r in poor) if poor else "none"))

    print(f"\nTotal retries across sweep: {sum(r['retries'] for r in results)}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--voices", default="", help="comma-separated subset (default: all 30 + invalid)")
    ap.add_argument("--sleep", type=float, default=3.0, help="seconds between sessions")
    ap.add_argument("--no-sleep", action="store_true")
    ap.add_argument("--report-only", action="store_true",
                    help="print the saved JSON as a report without any API calls")
    ap.add_argument(
        "--merge", action="store_true",
        help="reuse prior successful results from spike_04_voices.json for "
             "voices that fail in this run (for retrying silent clips)",
    )
    args = ap.parse_args()

    out_json = HERE / "spike_04_voices.json"
    if args.report_only:
        saved = json.loads(out_json.read_text())
        results, exact, clusters = regroup(saved["results"])
        print_report(results, exact, clusters)
        return

    prior = {}
    if args.merge and out_json.exists():
        try:
            prior = {r["voice"]: r for r in json.loads(out_json.read_text())["results"]}
        except Exception:
            prior = {}

    names = (
        [v.strip() for v in args.voices.split(",") if v.strip()]
        if args.voices.strip() else list(VOICES) + [INVALID]
    )
    sleep_between = 0.0 if args.no_sleep else args.sleep

    results, exact, clusters = asyncio.run(main_async(names, sleep_between))

    if prior:
        covered = set(names)
        recovered = []
        for i, r in enumerate(results):
            if not r.get("ltas") and r["voice"] in prior and prior[r["voice"]].get("ltas"):
                results[i] = dict(prior[r["voice"]])
                results[i]["recovered_from_prior"] = True
                recovered.append(r["voice"])
        results.extend(
            dict(v) for k, v in prior.items() if k not in covered and v.get("ltas")
        )
        order = {v: i for i, v in enumerate(list(VOICES) + [INVALID])}
        results.sort(key=lambda r: order.get(r["voice"], 999))
        results, exact, clusters = regroup(results)
        if recovered:
            print(f"\nRecovered from prior JSON: {', '.join(recovered)}")
    payload = {
        "model": MODEL,
        "sentence": SENTENCE,
        "language_code": "ko-KR",
        "temperature": 0.0,
        "ltas_threshold": LTAS_THRESHOLD,
        "timestamp_unix": time.time(),
        "voices_tested": names,
        "results": results,
        "exact_sha_groups": {sha: members for sha, members in exact.items()},
        "clusters": [
            {"representative": c["rep"], "f0_hz": c["f0"], "members": c["members"]}
            for c in clusters
        ],
    }
    out_json = HERE / "spike_04_voices.json"
    out_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    print_report(results, exact, clusters)
    print(f"\nJSON written to {out_json}", file=sys.stderr, flush=True)


if __name__ == "__main__":
    main()
