package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.assistant.AssistantProtocol
import org.json.JSONObject
import org.junit.Test

/** AC-18..AC-21: what reaches the speaker, and what the gateway is told about the question. */
class VoiceHandOffTest {

    @Test
    fun `markdown tables links and code never reach the speaker`() {
        val answer = """
            ## 디스크 상태

            | 항목 | 값 |
            |---|---|
            | 남은 용량 | 312GB |

            **요약**: 여유가 충분해요. 자세한 건 [대시보드](https://hermes.example.com/d/1)에서 보세요.

            ```bash
            df -h /
            ```
        """.trimIndent()

        val spoken = HermesHandOff.polish(answer)

        assertThat(spoken).doesNotContain("**")
        assertThat(spoken).doesNotContain("|")
        assertThat(spoken).doesNotContain("#")
        assertThat(spoken).doesNotContain("http")
        assertThat(spoken).doesNotContain("df -h")
        assertThat(spoken).contains(HermesHandOff.CODE_SPOKEN)
        assertThat(spoken).contains("312GB")
        assertThat(spoken).contains("대시보드")
        assertThat(HermesHandOff.looksLikeMarkdown(spoken)).isFalse()
    }

    @Test
    fun `the spoken replacements and the length window are the plan's, in words`() {
        // Literals, not references: a constant quietly changed would otherwise still pass.
        assertThat(HermesHandOff.CODE_SPOKEN).isEqualTo("코드는 화면에 띄웠어요")
        assertThat(HermesHandOff.MAX_SPEECH).isAtLeast(2_000)
        assertThat(HermesHandOff.MAX_SPEECH).isAtMost(3_000)

        val spoken = HermesHandOff.polish("```\nfun main() = 1\n```")
        assertThat(spoken).isEqualTo("코드는 화면에 띄웠어요")

        val sentence = "서버 상태는 정상이고 남은 공간도 충분해요. "
        val cut = HermesHandOff.polish(sentence.repeat(200))
        assertThat(cut.length).isAtMost(3_000)
        assertThat(cut.length).isAtLeast(2_000)
    }

    @Test
    fun `an unterminated code fence is still removed`() {
        val spoken = HermesHandOff.polish("결과는 이래요.\n```kotlin\nfun main() { println(\"hi\") }")
        assertThat(spoken).contains(HermesHandOff.CODE_SPOKEN)
        assertThat(spoken).doesNotContain("fun main")
    }

    @Test
    fun `a long answer is cut at a sentence inside the window`() {
        val sentence = "서버 상태는 정상이고 남은 공간도 충분해요. "
        val long = sentence.repeat(200) // ~4,400 chars
        val spoken = HermesHandOff.polish(long)

        assertThat(spoken.length).isAtMost(HermesHandOff.MAX_SPEECH)
        assertThat(spoken.length).isAtLeast(HermesHandOff.MIN_TRUNCATED)
        assertThat(spoken.trimEnd().last()).isAnyOf('.', '!', '?', '요', '다')
    }

    @Test
    fun `a short answer is left alone`() {
        val short = "디스크는 312GB 남았어요. 여유가 충분해요."
        assertThat(HermesHandOff.polish(short)).isEqualTo(short)
    }

    @Test
    fun `an empty answer becomes one honest sentence`() {
        assertThat(HermesHandOff.polish(null)).isEqualTo(HermesHandOff.EMPTY_SPEECH)
        assertThat(HermesHandOff.polish("")).isEqualTo(HermesHandOff.EMPTY_SPEECH)
        assertThat(HermesHandOff.polish("   \n\t ")).isEqualTo(HermesHandOff.EMPTY_SPEECH)
    }

    @Test
    fun `the question carries the voice-live surface and pairs with its turn`() {
        val handOff = HermesHandOff.prompt(callId = "call-9", request = "내일 일정 알려줘", submissionId = "voice-call-9")
        assertThat(handOff.surface).isEqualTo("voice-live")
        assertThat(handOff.matches("voice-call-9")).isTrue()
        assertThat(handOff.matches("voice-other")).isFalse()
        assertThat(handOff.matches(null)).isFalse()
    }

    @Test
    fun `the gateway payload marks the surface and the submission id`() {
        val frame = JSONObject(AssistantProtocol.submit(
            id = 7,
            sessionId = "s-1",
            text = "내 서버 디스크 용량 알려줘",
            submissionId = "voice-call-9",
            surface = HermesHandOff.SURFACE,
        ))
        val params = frame.getJSONObject("params")
        assertThat(params.getString("surface")).isEqualTo("voice-live")
        assertThat(params.getString("submission_id")).isEqualTo("voice-call-9")
        assertThat(params.getString("session_id")).isEqualTo("s-1")
        assertThat(frame.getString("method")).isEqualTo("prompt.submit")
    }

    @Test
    fun `a payload without a surface stays exactly as it was`() {
        val frame = JSONObject(AssistantProtocol.submit(1, "s-1", "질문", "sub-1"))
        assertThat(frame.getJSONObject("params").has("surface")).isFalse()
    }

    @Test
    fun `failure speech never claims success`() {
        val timeout = HermesHandOff.failureSpeech(HandOffFailure.Timeout)
        assertThat(timeout).contains("90초")
        assertThat(timeout).doesNotContain("완료")
        assertThat(timeout).doesNotContain("성공")

        val unreachable = HermesHandOff.failureSpeech(HandOffFailure.Unreachable)
        assertThat(unreachable).contains("연결")

        val error = HermesHandOff.failureSpeech(HandOffFailure.Error("gateway 502"))
        assertThat(error).contains("gateway 502")
        assertThat(error).contains("확인하지 못했")
    }

    @Test
    fun `a call record starts with what was said, so the session is titled from its content`() {
        val text = HermesHandOff.callRecord(
            clock = "02:11",
            bot = "Nova",
            lines = listOf("나: 서버 디스크 얼마나 남았어?", "Nova: 132GB 남았어."),
        )
        // The first line decides the session's title: a "🎙 음성 통화 02:11" label there names the session
        // after the label instead of after the conversation.
        assertThat(text.lines().first()).isEqualTo("나: 서버 디스크 얼마나 남았어?")
        assertThat(text).endsWith("[음성 통화 02:11 · Nova] 이 통화를 두 문장으로 요약해 줘.")
    }

    @Test
    fun `a call with nothing said leaves no record at all`() {
        assertThat(HermesHandOff.callRecord("00:04", "Nova", listOf("   ", ""))).isEmpty()
    }

    @Test
    fun `a failed tool result is marked as an error on the wire`() {
        val payload = JSONObject(HermesHandOff.failureResult("call-9", HandOffFailure.Timeout))
        val response = payload.getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertThat(response.getString("id")).isEqualTo("call-9")
        assertThat(response.getJSONObject("response").has("error")).isTrue()
        assertThat(response.getJSONObject("response").getString("result")).contains("90초")
    }
}
