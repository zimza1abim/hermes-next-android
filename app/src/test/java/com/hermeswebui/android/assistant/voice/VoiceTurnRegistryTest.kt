package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AC-20: the question a call asks and the chat turn that answers it are paired by id, and one call can
 * never take another's answer.
 */
class VoiceTurnRegistryTest {

    private val registry = VoiceTurnRegistry()
    private val delivered = mutableListOf<String>()

    private fun waiter(label: String): (VoiceTurn) -> Unit = { delivered += "$label:${it.text}" }

    @Test
    fun `a question's id is the key its answer arrives under`() {
        val handOff = HermesHandOff.prompt(callId = "call-1", request = "디스크 용량")
        registry.expect(handOff.submissionId, waiter("first"))

        // The chat turn ends with the same id the call chose; that is the whole pairing.
        val taken = registry.take(handOff.submissionId)
        assertThat(taken).isNotNull()
        taken!!.invoke(VoiceTurn("312GB 남았어요", null))

        assertThat(delivered).containsExactly("first:312GB 남았어요")
        assertThat(registry.size).isEqualTo(0)
    }

    @Test
    fun `another turn's id never takes the answer`() {
        registry.expect("voice-call-1", waiter("first"))
        registry.expect("voice-call-2", waiter("second"))

        assertThat(registry.take("voice-call-9")).isNull()
        assertThat(registry.size).isEqualTo(2)

        registry.take("voice-call-2")!!.invoke(VoiceTurn("둘째 답", null))
        registry.take("voice-call-1")!!.invoke(VoiceTurn("첫째 답", null))

        assertThat(delivered).containsExactly("second:둘째 답", "first:첫째 답")
    }

    @Test
    fun `an answer is delivered once, even if the turn is reported twice`() {
        registry.expect("voice-call-1", waiter("first"))
        assertThat(registry.take("voice-call-1")).isNotNull()
        assertThat(registry.take("voice-call-1")).isNull()
        assertThat(registry.size).isEqualTo(0)
    }

    @Test
    fun `a failure is still that call's answer`() {
        registry.expect("voice-call-1", waiter("first"))
        registry.take("voice-call-1")!!.invoke(VoiceTurn(null, "90초가 넘었어요"))
        assertThat(delivered).containsExactly("first:null")
        assertThat(registry.isWaiting("voice-call-1")).isFalse()
    }
}
