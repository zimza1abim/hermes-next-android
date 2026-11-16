package com.hermeswebui.android.assistant

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * The little Markdown an answer needs in a small panel: bold, inline code, headings, bullets, code
 * fences and links. An answer is split into paragraph blocks so that, while it streams, only the last
 * block is laid out again.
 */
internal object AssistantMarkdown {
    private val HEADING = Regex("^#{1,6}\\s+")
    private val BULLET = Regex("^(\\s*)[-*]\\s+")
    /** Bold, inline code, a Markdown link, or a bare web address. */
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(https?://[^\\s<>\"']+)")
    private val TRAILING = charArrayOf('.', ',', ')', ';', ':', '!', '?', '。')

    /** Paragraphs, split on blank lines; a code fence stays one block whatever it contains. */
    fun blocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        var fence = false
        fun flush() {
            if (current.isNotBlank()) blocks += current.toString().trimEnd('\n')
            current.setLength(0)
        }
        text.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) fence = !fence
            if (line.isBlank() && !fence) flush() else current.append(line).append('\n')
        }
        flush()
        return blocks
    }

    /** One block as styled text. [caret] adds the writing cursor after it. */
    fun render(block: String, caret: Boolean = false): AnnotatedString = buildAnnotatedString {
        val lines = block.lines()
        var fence = false
        lines.forEachIndexed { index, raw ->
            val marker = raw.trimStart().startsWith("```")
            when {
                marker -> fence = !fence
                fence -> withStyle(CODE_BLOCK) { append(raw) }
                else -> {
                    val heading = HEADING.find(raw)
                    val bullet = BULLET.find(raw)
                    when {
                        heading != null -> withStyle(BOLD) { inline(raw.substring(heading.range.last + 1)) }
                        bullet != null -> {
                            append(bullet.groupValues[1])
                            append("• ")
                            inline(raw.substring(bullet.range.last + 1))
                        }
                        else -> inline(raw)
                    }
                }
            }
            if (index < lines.lastIndex && !marker) append('\n')
        }
        if (caret) withStyle(SpanStyle(brush = Brush.verticalGradient(listOf(Pearl[0], Pearl[1], Pearl[2])))) { append(" ▍") }
    }

    /** The web addresses [line] links to; only http and https ever become links. */
    fun links(line: String): List<String> = INLINE.findAll(line).mapNotNull { match ->
        match.groups[4]?.value ?: match.groups[5]?.value?.trimEnd(*TRAILING)
    }.toList()

    private fun AnnotatedString.Builder.inline(line: String) {
        var cursor = 0
        INLINE.findAll(line).forEach { match ->
            append(line.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            val bold = match.groups[1]?.value
            val code = match.groups[2]?.value
            val bare = match.groups[5]?.value
            when {
                bold != null -> withStyle(BOLD) { append(bold) }
                code != null -> withStyle(CODE) { append(code) }
                bare != null -> {
                    val url = bare.trimEnd(*TRAILING)
                    link(url, url)
                    append(bare.substring(url.length))
                }
                else -> link(match.groups[4]!!.value, match.groups[3]!!.value)
            }
        }
        append(line.substring(cursor))
    }

    private fun AnnotatedString.Builder.link(url: String, label: String) {
        withLink(LinkAnnotation.Url(url, LINK)) { append(label) }
    }

    private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
    private val CODE = SpanStyle(fontFamily = FontFamily.Monospace, background = Rose.Soft)
    private val CODE_BLOCK = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, background = Rose.Soft)
    private val LINK = TextLinkStyles(SpanStyle(color = Rose.Deep, textDecoration = TextDecoration.Underline))
}
