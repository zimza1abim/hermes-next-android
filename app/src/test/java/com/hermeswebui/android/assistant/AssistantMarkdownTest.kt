package com.hermeswebui.android.assistant

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AssistantMarkdownTest {
    @Test
    fun `an answer splits into paragraphs and a code fence stays whole`() {
        val text = "first line\nsecond line\n\n```\ncode\n\nmore code\n```\n\nlast"
        assertThat(AssistantMarkdown.blocks(text)).containsExactly(
            "first line\nsecond line", "```\ncode\n\nmore code\n```", "last",
        ).inOrder()
        assertThat(AssistantMarkdown.blocks("  \n\n")).isEmpty()
        // A fence still open while the answer streams keeps what follows in the same block.
        assertThat(AssistantMarkdown.blocks("```\na\n\nb")).containsExactly("```\na\n\nb")
    }

    @Test
    fun `only web addresses become links`() {
        assertThat(AssistantMarkdown.links("See [docs](https://example.com/a) and https://example.org/b."))
            .containsExactly("https://example.com/a", "https://example.org/b").inOrder()
        assertThat(AssistantMarkdown.links("[run](intent://evil) javascript:alert(1) file:///sdcard/x")).isEmpty()
    }

    @Test
    fun `an answer parses into headings, lists, quotes, tables, rules and fences without blank lines between them`() {
        val blocks = AssistantMarkdown.parse(
            "## Plan\nfirst line\nsecond line\n- one\n  - nested\n    more\n1. step\n> quoted\n> again\n---\n| a | b |\n|---|:-:|\n| 1 | 2 |\n```kotlin\nval x = 1\n\nprintln(x)\n```\nend\n```py\nopen"
        )
        assertThat(blocks).containsExactly(
            AssistantMarkdown.Block.Heading(2, "Plan"),
            AssistantMarkdown.Block.Paragraph("first line\nsecond line"),
            AssistantMarkdown.Block.Items(listOf(AssistantMarkdown.Block.Item(0, null, "one"), AssistantMarkdown.Block.Item(1, null, "nested\nmore"), AssistantMarkdown.Block.Item(0, "1.", "step"))),
            AssistantMarkdown.Block.Quote("quoted\nagain"),
            AssistantMarkdown.Block.Rule,
            AssistantMarkdown.Block.Table(listOf(listOf("a", "b"), listOf("1", "2"))),
            AssistantMarkdown.Block.Fence(AssistantMarkdown.Code("kotlin", "val x = 1\n\nprintln(x)")),
            AssistantMarkdown.Block.Paragraph("end"),
            AssistantMarkdown.Block.Fence(AssistantMarkdown.Code("py", "open")),
        ).inOrder()
    }

    @Test
    fun `code is tinted by token kind and its text is unchanged`() {
        fun tag(name: String) = androidx.compose.ui.text.SpanStyle(fontFeatureSettings = name)
        val code = "val n = 42 // count\nprint(\"val\")"
        val tinted = AssistantMarkdown.highlight(code, tag("c"), tag("s"), tag("n"), tag("k"))
        assertThat(tinted.text).isEqualTo(code)
        assertThat(tinted.spanStyles.map { it.item.fontFeatureSettings to code.substring(it.start, it.end) })
            .containsExactly("k" to "val", "n" to "42", "c" to "// count", "s" to "\"val\"").inOrder()
    }
}
