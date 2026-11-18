"""Spike 2 — Gemini Live: session lifetime, resumption handles, goAway, context compression.

Everything printed as a MEASUREMENT was observed on the wire by this script.
Doc claims, if any, are labelled "unverified" and are never mixed into the table.

Run (from any cwd; ~3m20s with the default 120s goAway watch):
    "~/venv/bin/python" \
        "<repo>/tools/spike_02_session.py" [--goaway-seconds N]

Outputs tools/spike_02_session.json next to this file.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import pathlib
import sys
import time
from typing import Any, Awaitable, Callable, Dict, List, Optional

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from gemini_live import LiveSession  # noqa: E402

MODEL = "gemini-3.8-live"
OUT_JSON = HERE / "spike_02_session.json"
INSTR = "너는 한국어 비서야. 반말로 아주 짧게, 한 문장으로만 답해. 확인 질문은 하지 마."

QUOTA_MARKERS = ("429", "resource_exhausted", "quota", "rate limit")
RETRY_SLEEP = 20.0
MAX_RETRIES = 5

# Transcription on everywhere so every claim about "what the model said" is text, not a guess.
BASE_EXTRA = {"inputAudioTranscription": {}, "outputAudioTranscription": {}}

retries: List[Dict[str, Any]] = []


def is_quota(err: str) -> bool:
    low = (err or "").lower()
    return any(m in low for m in QUOTA_MARKERS)


def short(s: Optional[str], n: int = 120) -> str:
    if not s:
        return ""
    s = " ".join(str(s).split())
    return s if len(s) <= n else s[: n - 1] + "…"


# --------------------------------------------------------------------------- sending/reading


async def turn(s: LiveSession, text: str, timeout: float = 25.0) -> Dict[str, Any]:
    """One client turn; returns transcriptions and whether turnComplete arrived."""
    t0 = time.monotonic()
    audio_before = s.audio_bytes
    out: List[str] = []
    inp: List[str] = []
    complete = False
    empty = 0
    await s.say(text)
    async for msg in s.events(timeout=timeout):
        if not msg:
            # The server sends bare "{}" frames interleaved with real content.
            empty += 1
            continue
        sc = msg.get("serverContent") or {}
        if "outputTranscription" in sc:
            out.append((sc["outputTranscription"] or {}).get("text", ""))
        if "inputTranscription" in sc:
            inp.append((sc["inputTranscription"] or {}).get("text", ""))
        if sc.get("turnComplete"):
            complete = True
            break
    return {
        "asked": text,
        "in": "".join(inp).strip(),
        "out": "".join(out).strip(),
        "turn_complete": complete,
        "ms": round((time.monotonic() - t0) * 1000),
        "audio_bytes": s.audio_bytes - audio_before,
        "empty_frames": empty,
    }


def updates_from(log: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Every sessionResumptionUpdate seen, in order, with the fields it actually carried."""
    rows = []
    for e in log:
        upd = e["msg"].get("sessionResumptionUpdate")
        if upd is None:
            continue
        rows.append(
            {
                "t": round(e["t"], 3),
                "newHandle": upd.get("newHandle"),
                "handle_len": len(upd.get("newHandle") or ""),
                "resumable": upd.get("resumable"),
                "fields": sorted(upd.keys()),
            }
        )
    return rows


def latest_handle(rows: List[Dict[str, Any]]) -> Optional[str]:
    for r in reversed(rows):
        if r.get("resumable") and r.get("newHandle"):
            return r["newHandle"]
    return None


# --------------------------------------------------------------------------- one connection


async def _one(
    extra: Dict[str, Any],
    body: Optional[Callable[[LiveSession], Awaitable[Any]]] = None,
    drain: float = 2.0,
) -> Dict[str, Any]:
    """Open, setup with `extra`, optionally run `body`, and report what the server did."""
    res: Dict[str, Any] = {
        "accepted": False,
        "setup_ms": None,
        "error": None,
        "close_code": None,
        "close_reason": None,
        "error_json": None,
        "first_msg_kind": None,
        "first_msg": None,
        "drained_kinds": [],
        "resumption_updates": [],
        "post": None,
    }
    async with LiveSession(MODEL) as s:
        try:
            t0 = time.monotonic()
            await s.setup(
                voice="Kore",
                instructions=INSTR,
                extra={**BASE_EXTRA, **extra},
                timeout=20.0,
            )
            res["accepted"] = True
            res["setup_ms"] = round((time.monotonic() - t0) * 1000)
            if drain:
                deadline = time.monotonic() + drain
                while True:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        break
                    try:
                        msg = await s.recv(timeout=remaining)
                    except asyncio.TimeoutError:
                        break
                    except Exception as exc:  # noqa: BLE001 - socket death is a result
                        res["close_code"] = getattr(s.ws, "close_code", None)
                        res["close_reason"] = getattr(s.ws, "close_reason", None) or repr(exc)
                        break
                    kind = next(iter(msg), "{}")
                    res["drained_kinds"].append(kind)
                    if res["first_msg_kind"] is None:
                        res["first_msg_kind"] = kind
                        res["first_msg"] = short(json.dumps(msg, ensure_ascii=False), 300)
            if body is not None:
                res["post"] = await body(s)
            res["resumption_updates"] = updates_from(s.log)
            res["close_code"] = getattr(s.ws, "close_code", None) or res["close_code"]
            res["close_reason"] = getattr(s.ws, "close_reason", None) or res["close_reason"]
        except Exception as exc:  # noqa: BLE001 - rejection is the measurement
            res["error"] = f"{type(exc).__name__}: {exc}"
            for e in s.log:
                if "error" in e["msg"]:
                    res["error_json"] = e["msg"]["error"]
            res["close_code"] = getattr(s.ws, "close_code", None)
            res["close_reason"] = getattr(s.ws, "close_reason", None)
            res["resumption_updates"] = updates_from(s.log)
    return res


async def probe(
    extra: Dict[str, Any],
    body: Optional[Callable[[LiveSession], Awaitable[Any]]] = None,
    drain: float = 2.0,
    label: str = "",
) -> Dict[str, Any]:
    """_one() with free-tier 429 handling: sleep 20s, up to 5 retries."""
    res: Dict[str, Any] = {}
    attempt = 0
    for attempt in range(MAX_RETRIES + 1):
        res = await _one(extra, body, drain)
        if res["accepted"] or not is_quota(res["error"] or ""):
            if attempt:
                res["retries"] = attempt
            return res
        retries.append({"label": label, "attempt": attempt + 1, "error": short(res["error"], 160)})
        print(f"  ! 429/quota on {label}; sleeping {RETRY_SLEEP:.0f}s (retry {attempt + 1}/{MAX_RETRIES})")
        await asyncio.sleep(RETRY_SLEEP)
    return res


# --------------------------------------------------------------------------- bodies


async def body_resumption_stream(s: LiveSession) -> Dict[str, Any]:
    turns = []
    for text in ["안녕, 한 문장으로 인사해줘.", "오늘 뭐 하면 좋을까? 한 문장.", "고마워, 한 문장으로 마무리해줘."]:
        turns.append(await turn(s, text))
    return {
        "turns": turns,
        "update_count": len(updates_from(s.log)),
        "updates": updates_from(s.log),
        "latest_handle": latest_handle(updates_from(s.log)),
        "kinds": sorted(set(s.kinds())),
    }


FACT = "내가 제일 좋아하는 숫자는 47이야. 잘 기억해 둬."


async def body_resume_a(s: LiveSession) -> Dict[str, Any]:
    turns = [await turn(s, FACT)]
    turns.append(await turn(s, "그리고 내가 사는 도시는 부산이야."))
    turns.append(await turn(s, "응, 알겠어. 지금은 그냥 짧게 대답해줘."))
    upd = updates_from(s.log)
    return {
        "turns": turns,
        "updates": upd,
        "update_count": len(upd),
        "latest_handle": latest_handle(upd),
        "kinds": sorted(set(s.kinds())),
    }


async def body_resume_b(s: LiveSession) -> Dict[str, Any]:
    t = await turn(s, "내가 제일 좋아하는 숫자가 뭐라고 했지? 숫자만 말해.")
    return {"turns": [t], "answer": t["out"], "updates": updates_from(s.log)}


def body_goaway(seconds: int, keepalive_every: float = 20.0):
    async def _body(s: LiveSession) -> Dict[str, Any]:
        t0 = time.monotonic()
        seen: List[Dict[str, Any]] = []
        keepalives = 0
        next_ka = time.monotonic() + keepalive_every
        closed = None
        goaway = None
        while True:
            now = time.monotonic()
            elapsed = now - t0
            if elapsed >= seconds:
                break
            if now >= next_ka:
                try:
                    await s.send({"clientContent": {"turns": [], "turnComplete": False}})
                    keepalives += 1
                except Exception as exc:  # noqa: BLE001
                    closed = f"keepalive send failed: {type(exc).__name__}: {exc}"
                    break
                next_ka = now + keepalive_every
            wait = max(0.2, min(next_ka - time.monotonic(), seconds - (time.monotonic() - t0), 5.0))
            try:
                msg = await s.recv(timeout=wait)
            except asyncio.TimeoutError:
                continue
            except Exception as exc:  # noqa: BLE001
                closed = f"{type(exc).__name__}: {exc}"
                break
            kind = next(iter(msg), "{}")
            seen.append({"t": round(time.monotonic() - t0, 2), "kind": kind})
            if kind == "goAway":
                goaway = {"t": round(time.monotonic() - t0, 2), "msg": msg["goAway"]}
                break
        return {
            "requested_seconds": seconds,
            "observed_seconds": round(time.monotonic() - t0, 1),
            "keepalives_sent": keepalives,
            "messages": seen,
            "goaway": goaway,
            "socket_closed": closed,
            "close_code": getattr(s.ws, "close_code", None),
            "close_reason": getattr(s.ws, "close_reason", None),
            "update_count": len(updates_from(s.log)),
        }

    return _body


async def body_compression(s: LiveSession, turns_n: int = 6) -> Dict[str, Any]:
    prompts = [
        "1부터 10까지 세어줘.",
        "가장 좋아하는 계절은? 한 문장.",
        "지금 몇 시쯤일까? 한 문장.",
        "커피와 차 중 뭐가 좋아? 한 문장.",
        "오늘 날씨를 상상해서 한 문장.",
        "마지막으로 응원 한마디 해줘.",
    ][:turns_n]
    turns = []
    for p in prompts:
        turns.append(await turn(s, p))
    kind_counts: Dict[str, int] = {}
    for e in s.log:
        k = next(iter(e["msg"]), "{}")
        kind_counts[k] = kind_counts.get(k, 0) + 1
    top_keys, sc_keys = set(), set()
    for e in s.log:
        top_keys |= set(e["msg"].keys())
        sc_keys |= set((e["msg"].get("serverContent") or {}).keys())
    return {
        "turns": turns,
        "turns_requested": len(prompts),
        "turns_with_turn_complete": sum(1 for t in turns if t["turn_complete"]),
        "kinds": kind_counts,
        "top_level_keys_seen": sorted(top_keys),
        "server_content_keys_seen": sorted(sc_keys),
        "update_count": len(updates_from(s.log)),
        "latest_handle": latest_handle(updates_from(s.log)),
    }


# --------------------------------------------------------------------------- main


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--goaway-seconds", type=int, default=120)
    args = ap.parse_args()

    results: Dict[str, Any] = {
        "model": MODEL,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "config_variants": [],
        "resumption_stream": None,
        "resume_test": None,
        "goaway": None,
        "compression": None,
        "endpoint_notes": "server-side numbers (session limit, goAway timeLeft) are only reported if observed",
    }

    # ---- 1) resumption stream over a normal 3-turn session -------------------
    print("== A. sessionResumption:{} + 3 turns (handle stream) ==")
    a = await probe({"sessionResumption": {}}, body=body_resumption_stream, drain=1.0, label="resumption_stream")
    results["resumption_stream"] = a
    handle = (a.get("post") or {}).get("latest_handle")
    print(f"  accepted={a['accepted']} setup={a['setup_ms']}ms updates={(a.get('post') or {}).get('update_count')} handle={handle}")

    # ---- 2) config acceptance matrix ----------------------------------------
    variants: List[tuple[str, Dict[str, Any]]] = [
        ("(baseline: no session config)", {}),
        ("sessionResumption: {}", {"sessionResumption": {}}),
        ("sessionResumption: {handle: <real>}", {"sessionResumption": {"handle": handle}} if handle else {"sessionResumption": {"handle": "0000-unsupported"}}),
        ("sessionResumption: {handle: <invalid>}", {"sessionResumption": {"handle": "not-a-real-handle-0000"}}),
        ("contextWindowCompression: {slidingWindow: {targetTokens: 2000}}", {"contextWindowCompression": {"slidingWindow": {"targetTokens": 2000}}}),
        ("contextWindowCompression: {slidingWindow: {triggerTokens: 8000}}", {"contextWindowCompression": {"slidingWindow": {"triggerTokens": 8000}}}),
        ("contextWindowCompression: {slidingWindow: {}}", {"contextWindowCompression": {"slidingWindow": {}}}),
        ("contextWindowCompression: {}", {"contextWindowCompression": {}}),
    ]
    for name, extra in variants:
        print(f"== B. config {name} ==")
        r = await probe(extra, drain=2.0, label=name)
        r["variant"] = name
        r["extra"] = extra
        results["config_variants"].append(r)
        print(f"  accepted={r['accepted']} close={r['close_code']} err={short(r['error'], 140)}")

    # ---- 3) does the handle restore context? --------------------------------
    print("== C. resume: session A writes a fact, session B asks for it ==")
    ra = await probe({"sessionResumption": {}}, body=body_resume_a, drain=1.0, label="resume_a")
    handle_a = (ra.get("post") or {}).get("latest_handle")
    print(f"  A accepted={ra['accepted']} handle={handle_a}")
    rb = await probe(
        {"sessionResumption": {"handle": handle_a}} if handle_a else {"sessionResumption": {}},
        body=body_resume_b,
        drain=1.0,
        label="resume_b",
    )
    answer = ((rb.get("post") or {}).get("answer") or "")
    # Control: same question on a fresh socket with NO handle. Without this, "47" could be luck.
    print("== C2. control: same question, no handle ==")
    rc = await probe({}, body=body_resume_b, drain=1.0, label="control_no_handle")
    control_answer = ((rc.get("post") or {}).get("answer") or "")
    print(f"  control accepted={rc['accepted']} answer={control_answer!r}")
    results["resume_test"] = {
        "session_a": ra,
        "session_b": rb,
        "control_no_handle": rc,
        "handle_used": handle_a,
        "handle_len": len(handle_a or ""),
        "answer_text": answer,
        "remembered_47": "47" in answer,
        "control_answer_text": control_answer,
        "control_remembered_47": "47" in control_answer,
        "a_turns": (ra.get("post") or {}).get("turns"),
        "b_turns": (rb.get("post") or {}).get("turns"),
        "b_accepted": rb["accepted"],
        "b_error": rb["error"],
    }
    print(f"  B accepted={rb['accepted']} answer={answer!r} remembered_47={'47' in answer}")

    # ---- 4) goAway watch ----------------------------------------------------
    print(f"== D. goAway watch: {args.goaway_seconds}s idle with keepalives ==")
    g = await probe({"sessionResumption": {}}, body=body_goaway(args.goaway_seconds), drain=0, label="goaway")
    results["goaway"] = g
    post = g.get("post") or {}
    print(f"  observed={post.get('observed_seconds')}s keepalives={post.get('keepalives_sent')} goaway={post.get('goaway')} closed={post.get('socket_closed')}")

    # ---- 5) compression over 6 turns ---------------------------------------
    print("== E. contextWindowCompression + 6 turns ==")
    c = await probe({"contextWindowCompression": {"slidingWindow": {"targetTokens": 2000}}}, body=body_compression, drain=1.0, label="compression")
    results["compression"] = c
    print(f"  accepted={c['accepted']} turns_ok={(c.get('post') or {}).get('turns_with_turn_complete')}/6")

    results["retries"] = retries
    results["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    OUT_JSON.write_text(json.dumps(results, ensure_ascii=False, indent=2))

    print_report(results)
    print(f"\njson -> {OUT_JSON}")


# --------------------------------------------------------------------------- report


def evidence_text(r: Dict[str, Any]) -> str:
    if r.get("accepted"):
        bits = [f"setupComplete {r.get('setup_ms')}ms"]
        if r.get("first_msg_kind"):
            bits.append(f"first msg `{r['first_msg_kind']}`")
        n = len(r.get("resumption_updates") or [])
        if n:
            h = (r["resumption_updates"][0].get("newHandle") or "")
            bits.append(f"{n} sessionResumptionUpdate, first handle {len(h)} chars")
        return "; ".join(bits)
    reason = short(r.get("close_reason") or r.get("error") or "", 110)
    return f"close {r.get('close_code')}: {reason}"


def print_report(res: Dict[str, Any]) -> None:
    print("\n\n## Spike 2 — Gemini Live session/resumption/compression\n")
    print("All rows below are measurements from this run (model `%s`).\n" % res["model"])

    print("| config variant | accepted? | error string | observed evidence |")
    print("|---|---|---|---|")
    for r in res["config_variants"]:
        err = short(r.get("close_reason") or r.get("error") or "—", 110).replace("|", "\\|") or "—"
        print(f"| `{r['variant']}` | {'yes' if r['accepted'] else 'NO'} | {err} | {evidence_text(r).replace('|', chr(92)+'|')} |")

    rs = res.get("resumption_stream") or {}
    rp = rs.get("post") or {}
    print("\n### Resumption handles over a 3-turn session\n")
    print(f"- accepted: {rs.get('accepted')}, updates: {rp.get('update_count')}")
    for u in (rp.get("updates") or [])[:12]:
        print(f"  - t={u['t']}s resumable={u['resumable']} len={u['handle_len']} fields={u['fields']} handle={u['newHandle']}")

    rt = res.get("resume_test") or {}
    print("\n### Resume: does session B remember session A?\n")
    print(f"- handle used: `{rt.get('handle_used')}` ({rt.get('handle_len')} chars)")
    print(f"- A turns: {[(t['in'][:40], t['out'][:40]) for t in (rt.get('a_turns') or [])]}")
    print(f"- B accepted: {rt.get('b_accepted')} err={short(rt.get('b_error'), 120)}")
    print(f"- B answer: {rt.get('answer_text')!r}")
    print(f"- control (same question, fresh socket, no handle): {rt.get('control_answer_text')!r}")
    print(f"- remembered 47: **{rt.get('remembered_47')}**; control remembered 47: **{rt.get('control_remembered_47')}**")

    g = res.get("goaway") or {}
    gp = g.get("post") or {}
    print("\n### goAway watch\n")
    print(f"- requested {gp.get('requested_seconds')}s, observed {gp.get('observed_seconds')}s, keepalives sent {gp.get('keepalives_sent')}")
    print(f"- goAway message: {gp.get('goaway')}")
    print(f"- socket closed during watch: {gp.get('socket_closed')} close={gp.get('close_code')} {short(gp.get('close_reason'), 80)}")
    print(f"- sessionResumptionUpdate during watch: {gp.get('update_count')}")
    kinds = {}
    for m in gp.get("messages") or []:
        kinds[m["kind"]] = kinds.get(m["kind"], 0) + 1
    print(f"- message kinds during watch: {kinds}")

    c = res.get("compression") or {}
    cp = c.get("post") or {}
    print("\n### Context-window compression + 6 turns\n")
    print(f"- accepted: {c.get('accepted')} err={short(c.get('error'), 140)}")
    print(f"- turns with turnComplete: {cp.get('turns_with_turn_complete')}/{cp.get('turns_requested')}")
    print(f"- message kind counts: {cp.get('kinds')}")
    print(f"- top-level keys seen: {cp.get('top_level_keys_seen')}")
    print(f"- serverContent keys seen: {cp.get('server_content_keys_seen')}")
    for t in cp.get("turns") or []:
        print(f"  - {t['ms']:5d}ms complete={t['turn_complete']} audio={t['audio_bytes']}B out={t['out'][:50]!r}")

    if res.get("retries"):
        print("\n### Quota retries\n")
        for r in res["retries"]:
            print(f"- {r['label']} attempt {r['attempt']}: {r['error']}")
    else:
        print("\n### Quota retries\n- none (no 429 during this run)")

    print("\n### Doc claims — UNVERIFIED (not measured by this script)\n")
    print("- Docs describe a finite session lifetime with a server-initiated `goAway` before close; "
          "**no timeLeft value was observed in this run**, so no limit number is asserted here.")
    print("- Docs describe `sessionResumption.handle` as restoring prior context and "
          "`contextWindowCompression` as extending session lifetime; the acceptance results above "
          "are measured, the lifetime claims are not.")


if __name__ == "__main__":
    asyncio.run(main())
