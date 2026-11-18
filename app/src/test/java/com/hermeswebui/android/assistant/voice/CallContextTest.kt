package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A call starts with only the persona; this block is what keeps it from meeting the person for the first
 * time. The rules here are the ones a prompt needs: newest wins, it never grows past its budget, and it
 * says it is background so the model does not read it aloud.
 */
class CallContextTest {

    @Test
    fun `nothing to say means no block at all`() {
        assertThat(CallContext.block(emptyList())).isEmpty()
        assertThat(CallContext.block(listOf(Speaker.ME to "   "), listOf(" "))).isEmpty()
    }

    @Test
    fun `the recent exchanges are kept in the order they happened`() {
        val text = CallContext.block(listOf(
            Speaker.ME to "오늘 회사에서 진짜 힘들었어",
            Speaker.BOT to "고생 많았어. 푹 쉬자.",
        ))
        assertThat(text).contains("나: 오늘 회사에서 진짜 힘들었어")
        assertThat(text.indexOf("나:")).isLessThan(text.indexOf("봇:"))
        assertThat(text).startsWith("참고:")
    }

    @Test
    fun `it stays inside its budget and drops the oldest line first`() {
        val long = "가".repeat(170)
        val recent = (1..30).map { Speaker.ME to "$it $long" }
        val text = CallContext.block(recent)
        assertThat(text.length).isAtMost(CallContext.MAX_CHARS + 200)
        assertThat(text).contains("나: 30 ")
        assertThat(text).doesNotContain("나: 1 ")
    }

    @Test
    fun `the same line arriving twice is written once`() {
        val text = CallContext.block(listOf(
            Speaker.ME to "뭐 해?",
            Speaker.ME to "뭐 해?",
        ))
        assertThat(text.split("나: 뭐 해?").size - 1).isEqualTo(1)
    }

    @Test
    fun `the chat topics ride along when there is room`() {
        val text = CallContext.block(listOf(Speaker.BOT to "응"), listOf("수원 경기", "서버 용량", "수원 경기"))
        assertThat(text).contains("지난 대화 주제: 수원 경기, 서버 용량")
    }
}
