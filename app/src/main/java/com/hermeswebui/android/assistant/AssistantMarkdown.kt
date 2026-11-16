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
    private val QUOTE = Regex("^\\s*>\\s?")
    private val MARKS = Regex("\\*\\*|`|^#{1,6}\\s+|^\\s*>\\s?|^\\s*[-*]\\s+", RegexOption.MULTILINE)

    /** [text] on one line with its Markdown marks removed, for a list preview. */
    fun plain(text: String): String = MARKS.replace(text, "").replace(Regex("\\s+"), " ").trim()

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

    /** One piece of an answer, each drawn in its own way. */
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        /** [marker] is the number of an ordered item ("3."), or null for a bullet. */
        data class Item(val depth: Int, val marker: String?, val text: String)
        data class Items(val items: List<Item>) : Block
        data class Quote(val text: String) : Block
        data class Fence(val code: Code) : Block
        /** The first row is the header. */
        data class Table(val rows: List<List<String>>) : Block
        data object Rule : Block
    }

    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    /**
     * [text] as blocks, line by line: headings, lists, quotes, tables and rules need no blank line
     * around them, and an unfinished code fence (an answer still streaming) is a fence already.
     */
    fun parse(text: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = text.lines()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += Block.Paragraph(paragraph.toString().trimEnd('\n'))
            paragraph.setLength(0)
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) body += lines[i++]
                    out += Block.Fence(Code(trimmed.removePrefix("```").trim().take(24), body.joinToString("\n")))
                }
                line.isBlank() -> flush()
                HEADING.containsMatchIn(line) -> {
                    flush()
                    out += Block.Heading(trimmed.takeWhile { it == '#' }.length, trimmed.trimStart('#').trim())
                }
                RULE.matches(line) -> {
                    flush()
                    out += Block.Rule
                }
                QUOTE.containsMatchIn(line) -> {
                    flush()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && QUOTE.containsMatchIn(lines[i])) quoted += QUOTE.replaceFirst(lines[i++], "")
                    i--
                    out += Block.Quote(quoted.joinToString("\n"))
                }
                trimmed.startsWith("|") && i + 1 < lines.size && TABLE_RULE.matches(lines[i + 1]) -> {
                    flush()
                    val rows = mutableListOf(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                    i--
                    out += Block.Table(rows)
                }
                BULLET.containsMatchIn(line) || ORDERED.containsMatchIn(line) -> {
                    flush()
                    val items = mutableListOf<Block.Item>()
                    while (i < lines.size) {
                        val current = lines[i]
                        val bullet = BULLET.find(current)
                        val ordered = ORDERED.find(current)
                        when {
                            bullet != null -> items += Block.Item(bullet.groupValues[1].length / 2, null, current.substring(bullet.range.last + 1))
                            ordered != null -> items += Block.Item(ordered.groupValues[1].length / 2, ordered.groupValues[2] + ".", current.substring(ordered.range.last + 1))
                            // An indented line continues the item above it.
                            current.isNotBlank() && current.startsWith("  ") && items.isNotEmpty() ->
                                items[items.lastIndex] = items.last().let { it.copy(text = it.text + "\n" + current.trim()) }
                            else -> break
                        }
                        i++
                    }
                    i--
                    out += Block.Items(items)
                }
                else -> paragraph.append(line).append('\n')
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String): List<String> = line.trim().trim('|').split('|').map { it.trim() }

    /** One line of text with its inline Markdown: bold, inline code and links. */
    fun inlineText(text: String, caret: Boolean = false): AnnotatedString = buildAnnotatedString {
        text.lines().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            inline(line)
        }
        if (caret) withStyle(SpanStyle(brush = Brush.verticalGradient(listOf(Pearl[0], Pearl[1], Pearl[2])))) { append(" ▍") }
    }

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while", "do", "in", "is", "as", "null", "true", "false",
        "def", "import", "from", "pass", "None", "True", "False", "elif", "try", "except", "finally", "with", "lambda", "yield", "async", "await", "raise",
        "function", "const", "let", "new", "this", "typeof", "export", "default", "extends", "implements", "static", "public", "private", "protected",
        "void", "int", "string", "bool", "boolean", "float", "double", "struct", "enum", "type", "switch", "case", "break", "continue", "throw", "catch",
        "package", "func", "go", "defer", "use", "pub", "fn", "impl", "mut", "match", "self", "then", "fi", "done", "echo", "export", "select", "where",
    )
    private val TOKEN = Regex("(#.*$|//.*$|--\\s.*$)|(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`)|\\b(\\d+(?:\\.\\d+)?)\\b|\\b([A-Za-z_][A-Za-z0-9_]*)\\b", RegexOption.MULTILINE)

    /** Code tinted the way an editor does it: comments, strings, numbers and common keywords. Language-blind on purpose. */
    fun highlight(code: String, comment: SpanStyle, string: SpanStyle, number: SpanStyle, keyword: SpanStyle): AnnotatedString = buildAnnotatedString {
        var cursor = 0
        TOKEN.findAll(code).forEach { match ->
            val style = when {
                match.groups[1] != null -> comment
                match.groups[2] != null -> string
                match.groups[3] != null -> number
                match.groups[4]?.value in KEYWORDS -> keyword
                else -> null
            } ?: return@forEach
            append(code.substring(cursor, match.range.first))
            withStyle(style) { append(match.value) }
            cursor = match.range.last + 1
        }
        append(code.substring(cursor))
    }

    /** A fenced block's language tag and body. */
    data class Code(val language: String, val text: String)

    /** [block] as code when all of it is one fence; an unfinished fence (still streaming) counts. */
    fun codeBlock(block: String): Code? {
        val lines = block.lines()
        val first = lines.first().trim()
        if (!first.startsWith("```")) return null
        val body = lines.drop(1)
        val closed = body.indexOfFirst { it.trim().startsWith("```") }
        if (closed >= 0 && closed != body.lastIndex) return null
        return Code(first.removePrefix("```").trim().take(24), (if (closed >= 0) body.dropLast(1) else body).joinToString("\n"))
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
                    val quote = QUOTE.find(raw)
                    when {
                        quote != null -> withStyle(QUOTED) { inline(raw.substring(quote.range.last + 1)) }
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
    private val QUOTED = SpanStyle(color = Rose.Muted)
    private val CODE = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Rose.ChipText, background = Rose.Soft)
    private val CODE_BLOCK = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.5.sp, background = Rose.Soft)
    private val LINK = TextLinkStyles(SpanStyle(color = Rose.Deep, textDecoration = TextDecoration.Underline))
}
