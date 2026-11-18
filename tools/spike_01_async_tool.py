"""Spike 1 — async ("기다리지 않는") function calling on Gemini Live.

Question this answers, with measurements instead of assumptions:

  1. Does the server accept a NON_BLOCKING (async) function declaration at all, and
     which field carries that flag?  (`behavior` on the declaration vs `scheduling`,
     which the server only knows on the *response*.)
  2. When the model calls the tool, does it keep producing audio / close its turn
     while the result is still pending?  (i.e. can the app say "잠깐만 확인해볼게~")
  3. How long after we finally send `toolResponse` does the spoken answer start, and
     can the response be delivered *after* `turnComplete`?

Run (from the repo root, repeatable):

    ../_venv/bin/python tools/spike_01_async_tool.py
    ../_venv/bin/python tools/spike_01_async_tool.py --delays 5,15 --sched INTERRUPT
    ../_venv/bin/python tools/spike_01_async_tool.py --skip-decl-matrix --skip-enum

Writes tools/spike_01_async_tool.json next to this file.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import pathlib
import re
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import websockets  # noqa: E402
from gemini_live import LiveSession, decode_audio_from, pcm_to_wav, summarize  # noqa: E402

MODEL = "gemini-3.8-live"
QUESTION = "내 서버 디스크 용량 알려줘"
VOICE = "Kore"
INSTR = (
    "너는 한국어 음성 비서야. 반말로 아주 짧게 말해. "
    "서버 상태나 디스크 용량처럼 네가 직접 알 수 없는 정보는 절대 추측하지 말고 "
    "반드시 ask_hermes 도구를 불러서 확인해. "
    "도구를 부를 때는 먼저 '잠깐만 확인해볼게~'라고 말하고 나서 도구를 불러. "
    "도구 결과가 아직 안 왔으면 기다리는 동안 짧게 계속 말해도 돼."
)
EXTRA = {"inputAudioTranscription": {}, "outputAudioTranscription": {}}
TOOL_RESULT = "서버 디스크는 512GB 중 380GB 사용 중이고, 남은 용량은 132GB야. 사용률 74%."
CHATTY = (
    INSTR + " 도구 결과가 올 때까지 절대 멈추지 말고 '확인 중이야, 조금만 기다려줘' 같은 말을 계속 이어가."
)

MAX_RETRIES = 5
RETRY_SLEEP = 20.0
QUOTA_HINTS = ("429", "quota", "resource_exhausted", "exhausted", "rate limit", "too many")


# --------------------------------------------------------------------------- helpers


def tool_decl(**kwargs: Any) -> List[Dict[str, Any]]:
    """One ask_hermes declaration, with optional behavior/scheduling spliced in."""
    fn: Dict[str, Any] = {
        "name": "ask_hermes",
        "description": (
            "로컬 Hermes 에이전트에게 서버 상태, 디스크 용량, 로그 등 네가 모르는 정보를 물어본다. "
            "추측하지 말고 항상 이 도구를 사용해."
        ),
        "parameters": {
            "type": "OBJECT",
            "properties": {
                "query": {"type": "STRING", "description": "한국어로 된 질문"}
            },
            "required": ["query"],
        },
    }
    fn.update(kwargs)
    return [{"functionDeclarations": [fn]}]


def is_quota(code: Optional[int], text: str) -> bool:
    low = (text or "").lower()
    if code == 1011 and any(h in low for h in QUOTA_HINTS):
        return True
    return any(h in low for h in ("429", "resource_exhausted", "quota exceeded"))


def close_info(exc: BaseException) -> Tuple[Optional[int], str]:
    """(code, reason) from a websockets close, without ever leaking the URL/key."""
    rcvd = getattr(exc, "rcvd", None)
    code = getattr(rcvd, "code", None)
    reason = getattr(rcvd, "reason", "") or ""
    if not reason:
        reason = str(exc)
    return code, reason


def clean_close(text: str) -> Tuple[Optional[int], str]:
    """Pull (code, reason) out of the helper's `repr(ConnectionClosed)` string."""
    m = re.search(r'Close\(code=(\d+), reason="(.*?)"\)', text or "")
    if m:
        return int(m.group(1)), m.group(2)
    return None, text or ""


def parse_frames(entries: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Reduce a slice of the session log to audio/text/turn facts."""
    audio_bytes = 0
    text = ""
    turn_complete = False
    generation_complete = False
    interrupted = False
    for e in entries:
        sc = e["msg"].get("serverContent") or {}
        for part in (sc.get("modelTurn") or {}).get("parts", []):
            blob = (part.get("inlineData") or {}).get("data")
            if blob:
                audio_bytes += len(base64.b64decode(blob))
            if part.get("text"):
                text += part["text"]
        ot = sc.get("outputTranscription") or {}
        if ot.get("text"):
            text += ot["text"]
        turn_complete = turn_complete or bool(sc.get("turnComplete"))
        generation_complete = generation_complete or bool(sc.get("generationComplete"))
        interrupted = interrupted or bool(sc.get("interrupted"))
    return {
        "audio_bytes": audio_bytes,
        "text": text.strip(),
        "turn_complete": turn_complete,
        "generation_complete": generation_complete,
        "interrupted": interrupted,
    }


async def recv_until(
    s: LiveSession,
    *,
    deadline_s: float,
    stop_on: Tuple[str, ...] = (),
    stop_on_turn_complete: bool = False,
) -> Tuple[List[Dict[str, Any]], Optional[str], Optional[float]]:
    """Receive for `deadline_s`; stop early on a named key or turnComplete.

    Returns (new log entries, stop reason, monotonic time the stop condition hit).
    """
    before = len(s.log)
    deadline = time.monotonic() + deadline_s
    stopped: Optional[str] = None
    stopped_at: Optional[float] = None
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            msg = await s.recv(timeout=remaining)
        except asyncio.TimeoutError:
            break
        except websockets.ConnectionClosed as exc:
            s.closed_reason = repr(exc)
            break
        if not msg:
            continue
        hit = next((k for k in stop_on if k in msg), None)
        if hit:
            stopped, stopped_at = hit, time.monotonic()
            break
        if stop_on_turn_complete and (msg.get("serverContent") or {}).get("turnComplete"):
            stopped, stopped_at = "turnComplete", time.monotonic()
            break
    return s.log[before:], stopped, stopped_at


# --------------------------------------------------------------------------- probe: declaration


async def _attempt_declaration(tools: Optional[List[Dict[str, Any]]]) -> Dict[str, Any]:
    t0 = time.monotonic()
    try:
        async with LiveSession(MODEL) as s:
            try:
                await s.setup(
                    voice=VOICE, instructions=INSTR, tools=tools, extra=EXTRA, timeout=15.0
                )
                return {
                    "accepted": True,
                    "setup_ms": round((time.monotonic() - t0) * 1000),
                    "code": None,
                    "error": "",
                }
            except (asyncio.TimeoutError, websockets.ConnectionClosed) as exc:
                code, reason = close_info(exc)
                return {
                    "accepted": False,
                    "code": code,
                    "error": reason,
                    "error_truncated_at_123b": len(reason.encode()) >= 120,
                }
    except Exception as exc:  # noqa: BLE001 - connect-stage failures (HTTP 429 etc.)
        code, reason = close_info(exc)
        body = getattr(getattr(exc, "response", None), "body", "") or ""
        return {
            "accepted": False,
            "code": code or getattr(getattr(exc, "response", None), "status_code", None),
            "error": (reason + " " + str(body))[:400],
            "connect_failed": True,
        }


async def probe_declaration(label: str, tools: Optional[List[Dict[str, Any]]]) -> Dict[str, Any]:
    retries = 0
    while True:
        res = await _attempt_declaration(tools)
        if res["accepted"] is False and is_quota(res.get("code"), res.get("error", "")):
            if retries < MAX_RETRIES:
                retries += 1
                print(f"    ! quota/rate limit ({res.get('code')}); sleep {RETRY_SLEEP:.0f}s, "
                      f"retry {retries}/{MAX_RETRIES}")
                await asyncio.sleep(RETRY_SLEEP)
                continue
        res.update(label=label, retries=retries)
        return res


def decl_matrix() -> List[Tuple[str, Optional[List[Dict[str, Any]]]]]:
    return [
        ("no tools (baseline)", None),
        ('behavior="BLOCKING"', tool_decl(behavior="BLOCKING")),
        ('behavior="NON_BLOCKING"', tool_decl(behavior="NON_BLOCKING")),
        ('behavior="ASYNC" (invalid value)', tool_decl(behavior="ASYNC")),
        ('scheduling="INTERRUPT" on declaration', tool_decl(scheduling="INTERRUPT")),
        ('scheduling="SILENT" on declaration', tool_decl(scheduling="SILENT")),
        ('scheduling="WHEN_IDLE" on declaration', tool_decl(scheduling="WHEN_IDLE")),
    ]


# --------------------------------------------------------------------------- probe: timing


def _abs(s: LiveSession, entry: Dict[str, Any]) -> float:
    """Absolute monotonic time of a log entry (`t` is relative to socket open)."""
    return (s.opened_at or 0.0) + entry["t"]


def _first_audio_entry(entries: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    for e in entries:
        sc = e["msg"].get("serverContent") or {}
        for part in (sc.get("modelTurn") or {}).get("parts", []):
            if (part.get("inlineData") or {}).get("data"):
                return e
    return None


async def _attempt_timing(
    delay_s: float,
    sched: Optional[str],
    behavior: str = "NON_BLOCKING",
    instructions: str = INSTR,
    label: Optional[str] = None,
    interject: bool = False,
) -> Dict[str, Any]:
    run: Dict[str, Any] = {
        "delay_s": delay_s,
        "scheduling": sched,
        "behavior": label or behavior,
        "toolcall_ms": None,
        "first_audio_ms": None,
        "audio_while_waiting_bytes": 0,
        "turn_complete_before_response": None,
        "turn_complete_ms": None,
        "resp_to_first_audio_ms": None,
        "filler_text": "",
        "result_text": "",
        "error": "",
        "events": [],
    }
    async with LiveSession(MODEL) as s:
        t_setup = time.monotonic()
        try:
            await s.setup(
                voice=VOICE,
                instructions=instructions,
                tools=tool_decl(behavior=behavior),
                extra=EXTRA,
                timeout=15.0,
            )
        except (asyncio.TimeoutError, websockets.ConnectionClosed) as exc:
            code, reason = close_info(exc)
            run.update(error=f"setup failed code={code}: {reason[:300]}", code=code)
            return run
        run["setup_ms"] = round((time.monotonic() - t_setup) * 1000)

        # --- phase 1: ask, wait for toolCall (filler audio usually comes first) --
        t_ask = time.monotonic()
        await s.say(QUESTION)
        phase1, stopped, _ = await recv_until(
            s, deadline_s=20.0, stop_on=("toolCall", "toolCallCancellation")
        )
        call_entry = next((e for e in phase1 if "toolCall" in e["msg"]), None)
        if call_entry is None:
            run["error"] = f"no toolCall within 20s (stopped={stopped}); kinds={s.kinds()[-8:]}"
            run["events"] = [summarize(e["msg"]) for e in s.log]
            return run
        t_call = _abs(s, call_entry)
        fcall = (call_entry["msg"].get("toolCall") or {}).get("functionCalls", [{}])[0]
        run["toolcall"] = {"name": fcall.get("name"), "args": fcall.get("args"),
                           "id": fcall.get("id")}
        run["toolcall_ms"] = round((t_call - t_ask) * 1000)

        before_call = [e for e in phase1 if _abs(s, e) < t_call]
        after_call = [e for e in phase1 if _abs(s, e) >= t_call]
        facts_before = parse_frames(before_call)
        run["filler_text"] = facts_before["text"]
        run["audio_before_toolcall_bytes"] = facts_before["audio_bytes"]
        fa = _first_audio_entry(phase1)
        run["first_audio_ms"] = round((_abs(s, fa) - t_ask) * 1000) if fa else None
        run["toolcall_to_first_audio_ms"] = round((_abs(s, fa) - t_call) * 1000) if fa else None

        # --- phase 2: hold the result for `delay_s`, listening the whole time ---
        # Deliberate: we do NOT stop at turnComplete. The point is to see what the
        # model does between the call and the result, and to prove the result can
        # be delivered later (even after the turn already ended).
        remain = max(0.0, (t_call + delay_s) - time.monotonic())
        if interject:
            half = remain / 2
            ph_a, _, _ = await recv_until(s, deadline_s=half)
            t_say = time.monotonic()
            await s.say("아직 확인 중이야? 조금만 더 기다려줘.")
            run["interject_at_ms"] = round((t_say - t_ask) * 1000)
            ph_b, _, _ = await recv_until(
                s, deadline_s=max(0.0, (t_call + delay_s) - time.monotonic())
            )
            phase2 = ph_a + ph_b
            run["audio_after_interject_bytes"] = parse_frames(ph_b)["audio_bytes"]
            run["text_after_interject"] = parse_frames(ph_b)["text"]
        else:
            phase2, _, _ = await recv_until(s, deadline_s=remain)
        facts2 = parse_frames(phase2)
        fa2 = _first_audio_entry(phase2)
        run["first_audio_after_call_ms"] = (
            round((_abs(s, fa2) - t_call) * 1000) if fa2 else None
        )

        tc = next(
            (e for e in phase1 + phase2
             if (e["msg"].get("serverContent") or {}).get("turnComplete")),
            None,
        )
        run["turn_complete_ms"] = round((_abs(s, tc) - t_ask) * 1000) if tc else None
        # audio received between the toolCall and our toolResponse: the whole point
        # of non-blocking is that this can be > 0.
        run["audio_while_waiting_bytes"] = (
            parse_frames(after_call)["audio_bytes"] + facts2["audio_bytes"]
        )
        run["waiting_text"] = (parse_frames(after_call)["text"] + facts2["text"]).strip()
        run["toolcall_cancelled"] = any("toolCallCancellation" in e["msg"] for e in phase2)

        # --- phase 3: deliver the result --------------------------------------
        resp: Dict[str, Any] = {
            "id": fcall.get("id"),
            "name": fcall.get("name") or "ask_hermes",
            "response": {"result": TOOL_RESULT},
        }
        if sched:
            resp["scheduling"] = sched
        t_resp = time.monotonic()
        run["respond_at_ms"] = round((t_resp - t_ask) * 1000)
        run["turn_complete_before_response"] = bool(tc) and (_abs(s, tc) < t_resp)
        run["audio_before_response_bytes"] = (
            run["audio_before_toolcall_bytes"] + facts2["audio_bytes"] + parse_frames(after_call)["audio_bytes"]
        )
        await s.send_tool_response([resp])
        phase3, stopped3, _ = await recv_until(
            s, deadline_s=25.0, stop_on=("error",), stop_on_turn_complete=True
        )
        facts3 = parse_frames(phase3)
        run["result_text"] = facts3["text"]
        run["result_audio_bytes"] = facts3["audio_bytes"]
        run["interrupted_after_response"] = facts3["interrupted"]
        run["closed"] = clean_close(s.closed_reason or "")[1] or None
        fa3 = _first_audio_entry(phase3)
        run["resp_to_first_audio_ms"] = (
            round((_abs(s, fa3) - t_resp) * 1000) if fa3 else None
        )
        err = next((e["msg"]["error"] for e in phase3 if "error" in e["msg"]), None)
        if err:
            run["error"] = json.dumps(err, ensure_ascii=False)[:400]
        elif s.closed_reason and not facts3["audio_bytes"]:
            run["error"] = f"closed before answer: {s.closed_reason[:300]}"

        # If an interjection preceded the result and nothing was spoken, does a nudge
        # still get the answer (i.e. was the result queued, or simply dropped)?
        if interject and not facts3["audio_bytes"]:
            t_n = time.monotonic()
            await s.say("결과 나왔어?")
            ph_n, _, _ = await recv_until(s, deadline_s=12.0, stop_on_turn_complete=True)
            fn = parse_frames(ph_n)
            run["nudge_audio_bytes"] = fn["audio_bytes"]
            run["nudge_text"] = fn["text"]
            fan = _first_audio_entry(ph_n)
            run["nudge_to_audio_ms"] = round((_abs(s, fan) - t_n) * 1000) if fan else None

        run["events"] = [summarize(e["msg"]) for e in s.log]
        audio = decode_audio_from(s.log)
        if audio:
            run["wav"] = pcm_to_wav(
                audio, str(HERE / f"spike_01_delay{int(delay_s)}s_{sched}.wav")
            )
        return run


async def probe_timing(
    delay_s: float,
    sched: Optional[str],
    behavior: str = "NON_BLOCKING",
    instructions: str = INSTR,
    label: Optional[str] = None,
    interject: bool = False,
) -> Dict[str, Any]:
    retries = 0
    while True:
        res = await _attempt_timing(delay_s, sched, behavior, instructions, label, interject)
        if is_quota(res.get("code"), res.get("error", "")) and retries < MAX_RETRIES:
            retries += 1
            print(f"    ! quota/rate limit; sleep {RETRY_SLEEP:.0f}s, "
                  f"retry {retries}/{MAX_RETRIES}")
            await asyncio.sleep(RETRY_SLEEP)
            continue
        res["retries"] = retries
        return res


async def probe_response_enum() -> Dict[str, Any]:
    """One short flow to capture the error string for an invalid response `scheduling`."""
    run: Dict[str, Any] = {"label": 'scheduling="BOGUS" on response', "accepted": True, "code": None, "error": ""}
    async with LiveSession(MODEL) as s:
        try:
            await s.setup(voice=VOICE, instructions=INSTR,
                          tools=tool_decl(behavior="NON_BLOCKING"), extra=EXTRA, timeout=15.0)
        except (asyncio.TimeoutError, websockets.ConnectionClosed) as exc:
            code, reason = close_info(exc)
            run.update(accepted=False, code=code, error=f"setup failed: {reason[:300]}")
            return run
        await s.say(QUESTION)
        phase, _, _ = await recv_until(s, deadline_s=20.0, stop_on=("toolCall",))
        call = next((e["msg"] for e in phase if "toolCall" in e["msg"]), None)
        if not call:
            run.update(accepted=False, error="no toolCall; cannot probe enum")
            return run
        fcall = (call["toolCall"] or {}).get("functionCalls", [{}])[0]
        try:
            await s.send_tool_response([{
                "id": fcall.get("id"), "name": fcall.get("name") or "ask_hermes",
                "response": {"result": TOOL_RESULT}, "scheduling": "BOGUS",
            }])
            phase3, _, _ = await recv_until(s, deadline_s=8.0, stop_on=("error",))
            err = next((e["msg"]["error"] for e in phase3 if "error" in e["msg"]), None)
            if err:
                run.update(accepted=False, error=json.dumps(err, ensure_ascii=False)[:400])
            elif s.closed_reason:
                code, reason = clean_close(s.closed_reason)
                run.update(accepted=False, code=code, error=reason[:400])
            else:
                run.update(accepted=True, error="(server ignored the invalid value)")
        except websockets.ConnectionClosed as exc:
            code, reason = close_info(exc)
            run.update(accepted=False, code=code, error=reason)
        if s.closed_reason:
            code, reason = clean_close(s.closed_reason)
            run["closed_reason"] = reason[:400]
    return run


# --------------------------------------------------------------------------- reporting


def md(rows: List[List[str]], header: List[str]) -> str:
    out = ["| " + " | ".join(header) + " |",
           "|" + "|".join("---" for _ in header) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c).replace("|", "\\|") for c in r) + " |")
    return "\n".join(out)


def cell(v: Any, suffix: str = "") -> str:
    return "—" if v is None else f"{v}{suffix}"


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemini-3.8-live")
    ap.add_argument("--delays", default="5,15,5", help="comma separated result delays (s)")
    ap.add_argument("--sched", default="INTERRUPT,WHEN_IDLE,SILENT",
                    help="one response scheduling value per delay (INTERRUPT|WHEN_IDLE|SILENT)")
    ap.add_argument("--skip-decl-matrix", action="store_true")
    ap.add_argument("--skip-enum", action="store_true")
    ap.add_argument("--skip-control", action="store_true",
                    help="skip the BLOCKING control run (the contrast case)")
    ap.add_argument("--skip-chatty", action="store_true",
                    help="skip the run where the model is told to keep talking while waiting")
    ap.add_argument("--skip-interject", action="store_true",
                    help="skip the run where the app sends a turn during the pending call")
    ap.add_argument("--json-name", default="spike_01_async_tool.json",
                    help="output json filename (written next to this script)")
    ap.add_argument("--only-interject", action="store_true",
                    help="run just the interjection case (repeatability check)")
    args = ap.parse_args()

    global MODEL
    MODEL = args.model
    if args.only_interject:
        args.skip_decl_matrix = args.skip_enum = True
        args.skip_chatty = args.skip_control = True
        delays = []
    else:
        delays = [float(x) for x in args.delays.split(",") if x.strip()]
    scheds = [x.strip() for x in args.sched.split(",") if x.strip()]

    results: Dict[str, Any] = {
        "spike": "01_async_tool",
        "model": MODEL,
        "question": QUESTION,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "declaration_matrix": [],
        "response_enum": None,
        "timing": [],
        "chatty_while_waiting": None,
        "interject_while_waiting": None,
        "control_blocking": None,
        "retries_total": 0,
        "close_reason_limit_note": (
            "WebSocket close reasons are capped at 123 bytes by the protocol, so server "
            "errors that name a field are truncated mid-sentence; that truncation is the "
            "server's, not ours."
        ),
    }

    print(f"# Spike 01 — async function calling ({MODEL})\n")
    print(f"question: {QUESTION!r}\n")

    if not args.skip_decl_matrix:
        print("## 1. setup acceptance matrix\n")
        for label, tools in decl_matrix():
            r = await probe_declaration(label, tools)
            results["declaration_matrix"].append(r)
            results["retries_total"] += r.get("retries", 0)
            print(f"  {label:<40} accepted={r['accepted']!s:<5} "
                  f"code={r.get('code')} {r.get('error','')[:90]!r}")
            await asyncio.sleep(1.5)

    if not args.skip_enum:
        print("\n## 2. response-level scheduling enum\n")
        enum = await probe_response_enum()
        results["response_enum"] = enum
        print(f"  {enum['label']:<40} accepted={enum['accepted']!s:<5} "
              f"code={enum.get('code')} {enum.get('error','')[:90]!r}")
        await asyncio.sleep(1.5)

    print("\n## 3. timing runs\n")
    for i, delay in enumerate(delays):
        sched = scheds[0] if i == 0 else (scheds[i] if i < len(scheds) else scheds[0])
        print(f"  run delay={delay:g}s scheduling={sched} ...")
        r = await probe_timing(delay, sched)
        results["timing"].append(r)
        results["retries_total"] += r.get("retries", 0)
        print(f"    toolCall={cell(r['toolcall_ms'],'ms')} "
              f"firstAudio={cell(r['first_audio_ms'],'ms')} "
              f"audioWhileWaiting={r.get('audio_while_waiting_bytes', 0)}B "
              f"turnCompleteBeforeResp={r['turn_complete_before_response']} "
              f"respToAudio={cell(r['resp_to_first_audio_ms'],'ms')} "
              f"err={r.get('error','')[:60]!r}")
        await asyncio.sleep(2.0)

    if not args.skip_chatty:
        print("  chatty run delay=5s (model told to keep talking while waiting) ...")
        ch = await probe_timing(5.0, "INTERRUPT", instructions=CHATTY,
                                label="NON_BLOCKING+chatty")
        results["chatty_while_waiting"] = ch
        results["retries_total"] += ch.get("retries", 0)
        print(f"    audioWhileWaiting={ch.get('audio_while_waiting_bytes', 0)}B "
              f"turnCompleteBeforeResp={ch['turn_complete_before_response']} "
              f"respToAudio={cell(ch['resp_to_first_audio_ms'],'ms')} "
              f"said={ (ch.get('waiting_text') or '')[:60]!r}")
        await asyncio.sleep(2.0)

    if not args.skip_interject:
        print("  interject run delay=6s (app speaks a turn while the call is pending) ...")
        ij = await probe_timing(6.0, scheds[0], label="NON_BLOCKING+interject",
                                interject=True)
        results["interject_while_waiting"] = ij
        results["retries_total"] += ij.get("retries", 0)
        print(f"    audioAfterInterject={ij.get('audio_after_interject_bytes', 0)}B "
              f"toolcallCancelled={ij.get('toolcall_cancelled')} "
              f"respToAudio={cell(ij['resp_to_first_audio_ms'],'ms')} "
              f"nudgeToAudio={cell(ij.get('nudge_to_audio_ms'),'ms')} "
              f"said={ (ij.get('text_after_interject') or '')[:60]!r} "
              f"nudged={ (ij.get('nudge_text') or '')[:60]!r}")
        await asyncio.sleep(2.0)

    if not args.skip_control:
        print("  control run delay=5s behavior=BLOCKING ...")
        c = await probe_timing(5.0, "INTERRUPT", behavior="BLOCKING")
        results["control_blocking"] = c
        results["retries_total"] += c.get("retries", 0)
        print(f"    toolCall={cell(c['toolcall_ms'],'ms')} "
              f"firstAudio={cell(c['first_audio_ms'],'ms')} "
              f"turnCompleteBeforeResp={c['turn_complete_before_response']} "
              f"respToAudio={cell(c['resp_to_first_audio_ms'],'ms')} "
              f"err={c.get('error','')[:60]!r}")
        await asyncio.sleep(2.0)

    # ---- markdown ------------------------------------------------------
    print("\n\n## Declaration / field acceptance\n")
    print(md(
        [[r["label"], "yes" if r["accepted"] else "NO", cell(r.get("code")),
          (r.get("error") or "").replace("\n", " ")[:220] or "—"]
         for r in results["declaration_matrix"]],
        ["variant", "accepted", "close code", "raw server error"],
    ))

    if results["response_enum"]:
        e = results["response_enum"]
        print("\n## Response-level `scheduling` (on toolResponse.functionResponses[])\n")
        print(md(
            [[e["label"], "yes" if e["accepted"] else "NO", cell(e.get("code")),
              (e.get("error") or "").replace("\n", " ")[:220] or "—"]],
            ["variant", "accepted", "close code", "raw server error"],
        ))

    print("\n## Measurements\n")
    allruns = list(results["timing"])
    for key in ("chatty_while_waiting", "interject_while_waiting", "control_blocking"):
        if results.get(key):
            allruns.append(results[key])
    print(md(
        [[f"{r['delay_s']:g}s", r.get("behavior", "NON_BLOCKING"), str(r.get("scheduling")),
          cell(r["toolcall_ms"]), cell(r["first_audio_ms"]),
          cell(r.get("toolcall_to_first_audio_ms")),
          f"{r.get('audio_while_waiting_bytes', 0)}",
          ("yes @" + cell(r.get("turn_complete_ms"), "ms")) if r["turn_complete_before_response"] else "no",
          cell(r.get("respond_at_ms")),
          cell(r["resp_to_first_audio_ms"]),
          (r.get("result_text") or "")[:30] or "—"]
         for r in allruns],
        ["delay", "behavior", "scheduling", "user→toolCall ms", "user→1st audio ms",
         "toolCall→1st audio ms", "audio while waiting B", "turnComplete before resp?",
         "respond at ms", "resp→1st audio ms", "result spoken"],
    ))
    print("\n- negative `toolCall→1st audio` = the model was already speaking when the call "
          "arrived.\n- `audio while waiting B` = audio bytes received between the toolCall and "
          "our toolResponse (0 = the model went quiet and handed control back).\n"
          "- `respond at ms` is our deliberate hold, measured from the user turn.")

    print("\n## What the model actually said\n")
    for r in allruns:
        print(f"- {r['delay_s']:g}s/{r.get('behavior')}/{r.get('scheduling')}: "
              f"filler={ (r.get('filler_text') or '')[:70]!r} "
              f"while_waiting={ (r.get('waiting_text') or '')[:70]!r} "
              f"after_interject={ (r.get('text_after_interject') or '')[:70]!r} "
              f"nudged={ (r.get('nudge_text') or '')[:70]!r} "
              f"result={ (r.get('result_text') or '')[:90]!r}")

    errs = [r.get("error") for r in allruns if r.get("error")]
    if errs:
        print("\n## Errors (verbatim)\n")
        for e in errs:
            print(f"- {e}")

    out = HERE / args.json_name
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(f"\njson: {out}")
    print(f"total retries due to rate limits: {results['retries_total']}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
