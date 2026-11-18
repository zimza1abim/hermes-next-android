package com.hermeswebui.android.assistant.voice

/**
 * What a call knows before it starts.
 *
 * The live model is a fresh session: it hears only the persona and the rule that lets it hand work to
 * Hermes. Without this, a call behaves like meeting the person for the first time — "우리 어제 얘기한
 * 거 기억해?" gets nothing. The block is deliberately small and says it is background, so the model does
 * not read the transcript aloud or pretend to remember more than it was given.
 */
object CallContext {

    /** Roughly 1,200 characters: enough for a dozen exchanges, small enough to keep the persona intact. */
    const val MAX_CHARS = 1_200

    private const val HEADER = "참고: 방금 전에 이 사람과 나눈 대화야. 맥락으로만 쓰고, 먼저 꺼내지는 마."

    /** Lines kept per exchange line; a long answer is cut, not dropped. */
    private const val LINE_CHARS = 180

    fun block(recent: List<Pair<Speaker, String>>, titles: List<String> = emptyList()): String {
        val lines = recent.mapNotNull { (speaker, raw) ->
            val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) null else (if (speaker == Speaker.ME) "나: " else "봇: ") + text.take(LINE_CHARS)
        }.fold(mutableListOf<String>()) { acc, line ->
            // 같은 말이 연속으로 들어오는 경우(재전송·재개) 한 번만.
            if (acc.lastOrNull() != line) acc += line
            acc
        }

        val topics = titles.mapNotNull { it.trim().takeIf { t -> t.isNotBlank() } }.distinct().take(5)
        if (lines.isEmpty() && topics.isEmpty()) return ""

        // 최신 것부터 채우고 뒤에서 자른다: 오래된 한 줄보다 방금 한 말이 중요하다.
        val kept = ArrayList<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length + 1 > MAX_CHARS) break
            kept += line
            used += line.length + 1
        }
        kept.reverse()

        return buildString {
            append(HEADER)
            if (kept.isNotEmpty()) {
                append('\n')
                kept.forEach { append('\n').append(it) }
            }
            if (topics.isNotEmpty() && used < MAX_CHARS - 40) {
                append("\n지난 대화 주제: ").append(topics.joinToString(", ").take(160))
            }
        }
    }
}
