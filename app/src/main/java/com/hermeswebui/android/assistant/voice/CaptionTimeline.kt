package com.hermeswebui.android.assistant.voice

/** 끼어들기 순간의 스냅샷. [revealedChars] 뒤는 "못 한 말"이다. */
data class InterruptMark(val seq: Int, val text: String, val revealedChars: Int, val atMs: Long)

/**
 * 봇 한 턴의 자막 글자 ↔ 오디오 시각. 소켓 스레드에서 쓰고(onAudio/onTranscript) UI 프레임에서 읽는다.
 * 소켓 콜백 스레드가 하나라고 가정하지 않고 전부 lock으로 감싼다.
 */
class CaptionTimeline(private val msPerWeight: Float = 105f) {
    private data class Anchor(val chars: Int, val ms: Float)
    data class Snapshot(val text: String, val revealedChars: Int)

    private val lock = Any()
    private val text = StringBuilder()
    private val anchors = ArrayList<Anchor>()
    /** 글자 i 앞까지의 발음 무게 누적. weights[i] = Σ weight(text[0 until i]). */
    private val weights = ArrayList<Float>().apply { add(0f) }
    private var inTurn = false
    private var turnBase = 0L
    private var received = 0L
    private var rate = LiveProtocol.OUTPUT_SAMPLE_RATE

    fun onAudio(frames: Int, sampleRate: Int, enqueuedBefore: Long) = synchronized(lock) {
        if (!inTurn) begin(enqueuedBefore)
        rate = sampleRate
        received += frames
    }

    fun onTranscript(chunk: String, enqueuedNow: Long) = synchronized(lock) {
        if (!inTurn) begin(enqueuedNow)
        chunk.forEach { ch ->
            text.append(ch)
            weights.add(weights.last() + weight(ch))
        }
        anchors.add(Anchor(text.length, received * 1000f / rate))
    }

    fun endTurn() = synchronized(lock) { inTurn = false }

    /** 이번 턴의 자막 원문. 턴이 끝나도 다음 턴이 시작될 때까지 남는다. */
    fun currentText(): String = synchronized(lock) { text.toString() }

    fun revealedChars(playedFrames: Long): Int = synchronized(lock) { revealedLocked(playedFrames) }

    /** 글자 [i]가 소리로 시작되는 추정 시각(ms). 테스트가 "앞서지 않음"을 직접 확인한다. */
    fun estimatedStartMs(i: Int): Float = synchronized(lock) { startMs(i) }

    fun snapshot(playedFrames: Long): Snapshot = synchronized(lock) {
        Snapshot(text.toString(), revealedLocked(playedFrames))
    }

    private fun begin(base: Long) {
        inTurn = true
        turnBase = base
        received = 0
        text.setLength(0)
        anchors.clear()
        weights.clear(); weights.add(0f)
    }

    private fun revealedLocked(playedFrames: Long): Int {
        val heardMs = (playedFrames - turnBase).coerceAtLeast(0L) * 1000f / rate
        var n = 0
        // 엄격한 부등호: 첫 글자의 추정 시작이 0ms라도, 아직 아무 소리도 안 나왔으면(heardMs=0)
        // 자막이 먼저 뜨면 안 된다.
        while (n < text.length && startMs(n) < heardMs) n++
        return n      // 앞에서부터 n글자는 이미 소리로 나왔다(추정, 늦는 쪽)
    }

    /** 글자 i가 소리로 시작되는 가장 이른 추정 시각(ms). */
    private fun startMs(i: Int): Float {
        val floor = weights[i] * msPerWeight
        var prevC = 0
        var prevMs = 0f
        for (a in anchors) {
            if (i < a.chars) {
                val span = (a.chars - prevC).coerceAtLeast(1)
                val interp = prevMs + (a.ms - prevMs) * (i - prevC) / span
                return maxOf(interp, floor)
            }
            prevC = a.chars
            prevMs = a.ms
        }
        return maxOf(prevMs, floor)
    }

    private fun weight(ch: Char): Float = when {
        ch in '\uAC00'..'\uD7A3' -> 1f            // 한글 음절
        ch.isLetter() -> 0.35f
        ch.isDigit() -> 0.6f
        else -> 0f                                // 공백, 문장부호, 이모지
    }
}
