package com.hermeswebui.android.assistant.voice

import android.os.SystemClock
import android.util.Log

/**
 * The measurement points the plan names: 말 끝, 첫 음성, 끼어들기 감지, 재생 멈춤, Hermes 질문,
 * Hermes 답, 결과 낭독 시작.
 *
 * One line per event on logcat tag [TAG], with the monotonic clock in front, so a device test is a
 * `adb logcat -s HermesVoice` capture and a subtraction — not a stopwatch held next to the phone.
 */
object VoiceTrace {

    const val TAG = "HermesVoice"

    private val signposts = mutableListOf<Pair<Long, String>>()

    fun mark(event: String, detail: String = "") {
        val at = SystemClock.elapsedRealtime()
        synchronized(signposts) {
            signposts += at to event
            if (signposts.size > 200) signposts.removeAt(0)
        }
        Log.i(TAG, "$at $event${if (detail.isBlank()) "" else " $detail"}")
    }

    /** Milliseconds between two marks of this call, or null when either is missing. */
    fun between(from: String, to: String): Long? {
        val marks = synchronized(signposts) { signposts.toList() }
        val start = marks.firstOrNull { it.second == from }?.first ?: return null
        val end = marks.firstOrNull { it.second == to && it.first >= start }?.first ?: return null
        return end - start
    }

    fun reset() = synchronized(signposts) { signposts.clear() }
}
