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
}
