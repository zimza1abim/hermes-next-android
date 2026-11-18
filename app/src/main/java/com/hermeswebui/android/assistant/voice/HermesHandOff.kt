package com.hermeswebui.android.assistant.voice

import java.util.Locale

/** Why a hand-off produced no answer. The speech for each is the truth, not a consolation prize. */
sealed interface HandOffFailure {
    /** 90 seconds passed with no turn completion. */
    data object Timeout : HandOffFailure
    /** The gateway answered with an error, or the turn ended failed. */
    data class Error(val message: String?) : HandOffFailure
    /** The socket was not usable at all. */
    data object Unreachable : HandOffFailure
}

/** A finished Hermes turn as the call sees it: the answer, or why there is none. */
data class VoiceTurn(val text: String?, val error: String?)

/**
 * One question handed to Hermes on behalf of the live call.
 *
 * [submissionId] is the id the gateway echoes back for that turn; [callId] is the model's function-call
 * id. Keeping both is what lets a late completion find its call (and stops one turn's answer being read
 * for another's).
 */
data class HandOff(
    val callId: String,
    val request: String,
    val submissionId: String,
    val surface: String = HermesHandOff.SURFACE,
) {
    fun matches(completedSubmissionId: String?): Boolean =
        completedSubmissionId != null && completedSubmissionId == submissionId
}

/**
 * Turns a live conversation's question into a Hermes turn, and a Hermes answer into something worth
 * reading out loud.
 *
 * Speech is not a transcript: markdown, tables, URLs and code read as noise through a speaker, so the
 * text that reaches the model is reduced here — once, in one place, with tests.
 */
object HermesHandOff {

    /** The marker the server already understands: this question came from a voice call. */
    const val SURFACE: String = "voice-live"

    /**
     * What a finished call leaves in a session.
     *
     * The first line is something a person actually said, never a label: a session is titled from its own
     * content, so a mechanical "🎙 음성 통화 02:11" at the top becomes the title and says nothing about the
     * conversation. The call is marked at the end instead, where it cannot win the title.
     */
    fun callRecord(clock: String, bot: String?, lines: List<String>): String {
        val spoken = lines.map { it.trim() }.filter { it.isNotBlank() }
        if (spoken.isEmpty()) return ""
        val mark = if (bot.isNullOrBlank()) "음성 통화 $clock" else "음성 통화 $clock · $bot"
        return spoken.joinToString("\n") + "\n\n[$mark] 이 통화를 두 문장으로 요약해 줘."
    }

    /** Past this, the caller is told the truth about not getting an answer. */
    const val TIMEOUT_MS: Long = 90_000

    /** What a code block becomes when it is spoken. */
    const val CODE_SPOKEN: String = "코드는 화면에 띄웠어요"

    /** What an empty answer becomes. Never silence, never an invented answer. */
    const val EMPTY_SPEECH: String = "답이 비어 있어서 읽어 줄 내용이 없어요. 화면에서 확인해 주세요."

    /** Speech is cut at a sentence boundary between these two lengths. */
    const val MIN_TRUNCATED: Int = 2_000
    const val MAX_SPEECH: Int = 3_000

    fun prompt(callId: String, request: String, submissionId: String = "voice-$callId"): HandOff =
        HandOff(callId = callId, request = request, submissionId = submissionId)

    /** The sentence the model says instead of an answer when Hermes could not answer. */
    fun failureSpeech(failure: HandOffFailure): String = when (failure) {
        HandOffFailure.Timeout -> "확인하는 데 90초가 넘어서 답을 못 받았어요. 지금은 확실한 답을 드릴 수 없어요."
        HandOffFailure.Unreachable -> "Hermes에 연결하지 못해서 확인을 못 했어요. 잠시 뒤에 다시 물어봐 주세요."
        is HandOffFailure.Error -> {
            val detail = failure.message?.trim().orEmpty()
            if (detail.isEmpty()) {
                "Hermes가 답을 주지 못했어요. 확인하지 못했다는 것만 말씀드릴 수 있어요."
            } else {
                "Hermes가 답을 주지 못했어요. 확인하지 못했어요. 화면에 이런 오류가 있었어요: $detail"
            }
        }
    }

    /** The tool result sent back for a failure: same shape as a success, with the error field set. */
    fun failureResult(callId: String, failure: HandOffFailure): String =
        LiveProtocol.toolResult(callId, failureSpeech(failure), isError = true)

    /**
     * Rewrites a markdown answer into something a voice can read.
     *
     * Order matters: code is replaced before markers are stripped (or the fence markers would leave a
     * stray "코드"), tables are dropped before pipes are read as text, and links lose their target
     * before any URL survives into speech.
     */
    fun polish(answer: String?): String {
        val raw = answer ?: return EMPTY_SPEECH
        if (raw.isBlank()) return EMPTY_SPEECH

        var text = raw.replace("\r\n", "\n").replace('\r', '\n')

        // 1. fenced code blocks, with or without a language tag
        text = Regex("```[\\s\\S]*?```").replace(text, " $CODE_SPOKEN ")
        // an unterminated fence still has to go (a truncated stream), keep what follows the marker
        text = Regex("```[\\s\\S]*$").replace(text, " $CODE_SPOKEN ")

        // 2. markdown tables: separator rows go, real rows become "항목: 값" so the numbers survive
        text = text.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("|") && trimmed.count { it == '|' } < 2) {
                line
            } else {
                val cells = trimmed.trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    cells.isEmpty() -> null
                    cells.all { cell -> cell.all { it == '-' || it == ':' || it == ' ' } } -> null
                    cells.size == 1 -> cells.first()
                    else -> cells.joinToString(separator = ": ", limit = 2)
                }
            }
        }.joinToString("\n")

        // 3. links: keep the label, drop the address
        text = Regex("!?\\[([^\\]]*)]\\(([^)]*)\\)").replace(text) { it.groupValues[1] }
        text = Regex("https?://\\S+").replace(text, "")
        text = Regex("www\\.\\S+").replace(text, "")

        // 4. headings, quotes, bullets and numbering
        text = Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE).replace(text, "")
        text = Regex("^\\s*>\\s?", RegexOption.MULTILINE).replace(text, "")
        text = Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE).replace(text, "")
        text = Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE).replace(text, "")

        // 5. emphasis and inline code markers
        text = Regex("(\\*\\*|__|~~|`)").replace(text, "")
        text = Regex("(?<![\\w*])\\*(?=\\S)|(?<=\\S)\\*(?![\\w*])").replace(text, "")

        // 6. whitespace: one line per sentence group, no runs of blank lines
        text = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        text = Regex("[ \\t]{2,}").replace(text, " ")

        if (text.isBlank()) return EMPTY_SPEECH
        return truncate(text)
    }

    /**
     * Cuts at the last sentence end inside [MIN_TRUNCATED]..[MAX_SPEECH]; a text without one is cut at
     * the limit. Short answers are returned unchanged.
     */
    private fun truncate(text: String): String {
        if (text.length <= MAX_SPEECH) return text
        val window = text.substring(0, MAX_SPEECH)
        val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '。', '！', '？', '요', '다'))
        return if (cut >= MIN_TRUNCATED) {
            window.substring(0, cut + 1).trim()
        } else {
            window.trim()
        }
    }

    /** True when [text] still carries anything that should not be spoken (used by tests and logs). */
    fun looksLikeMarkdown(text: String): Boolean {
        val markers = listOf("**", "```", "|", "](", "##")
        return markers.any { text.contains(it) } || text.lowercase(Locale.ROOT).contains("http")
    }
}
