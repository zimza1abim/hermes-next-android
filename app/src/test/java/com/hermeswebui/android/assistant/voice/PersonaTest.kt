package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AC-15..AC-17: every preset keeps the rules, the order is fixed, per-bot overrides win. */
class PersonaTest {

    private val presets = PersonaPreset.entries

    @Test
    fun `every preset and strength keeps all four rules`() {
        for (preset in presets) {
            for (warmth in Persona.MIN_WARMTH..Persona.MAX_WARMTH) {
                val text = PersonaBuilder.instructions("하늘", "서버 봇", Persona(preset, warmth, "느긋하게"), handOff = true)
                PersonaBuilder.ALWAYS_RULES.forEach { rule ->
                    assertThat(text).contains(rule)
                }
                assertThat(text).contains("말투 강도 $warmth/3")
            }
        }
    }

    @Test
    fun `the assembly order never changes`() {
        val text = PersonaBuilder.instructions("하늘", "서버 봇", Persona(PersonaPreset.WARM, 2), handOff = true)
        val name = text.indexOf("하늘")
        val preset = text.indexOf("따뜻하고 다정한 성격")
        val strength = text.indexOf("말투 강도 2/3")
        val rules = text.indexOf(PersonaBuilder.ALWAYS_RULES.first())
        val handOff = text.indexOf(PersonaBuilder.HANDOFF_RULE)

        assertThat(name >= 0).isTrue()
        assertThat(name < preset).isTrue()
        assertThat(preset < strength).isTrue()
        assertThat(strength < rules).isTrue()
        assertThat(rules < handOff).isTrue()
    }

    @Test
    fun `write-your-own sits in the preset slot, not after the rules`() {
        val text = PersonaBuilder.instructions("하늘", "서버 봇", Persona(PersonaPreset.CUSTOM, 2, "느긋하게"), handOff = true)
        val name = text.indexOf("하늘")
        val custom = text.indexOf("느긋하게")
        val strength = text.indexOf("말투 강도 2/3")
        val rules = text.indexOf(PersonaBuilder.ALWAYS_RULES.first())
        val handOff = text.indexOf(PersonaBuilder.HANDOFF_RULE)

        assertThat(name).isAtLeast(0)
        assertThat(name < custom).isTrue()
        assertThat(custom < strength).isTrue()
        assertThat(strength < rules).isTrue()
        assertThat(rules < handOff).isTrue()
    }

    @Test
    fun `custom words replace the preset text and never remove the rules`() {
        val text = PersonaBuilder.instructions("하늘", null, Persona(PersonaPreset.CUSTOM, 3, "느긋하고 유머 있게, 존댓말로"))
        assertThat(text).contains("느긋하고 유머 있게, 존댓말로")
        assertThat(text).doesNotContain("따뜻하고 다정한 성격")
        PersonaBuilder.ALWAYS_RULES.forEach { assertThat(text).contains(it) }
    }

    @Test
    fun `empty custom text falls back to the warm preset instead of an empty prompt`() {
        val text = PersonaBuilder.instructions(null, null, Persona(PersonaPreset.CUSTOM, 1, "   "))
        assertThat(text).contains("따뜻하고 다정한 성격")
        PersonaBuilder.ALWAYS_RULES.forEach { assertThat(text).contains(it) }
    }

    @Test
    fun `hand-off rule appears only when hand-off is on`() {
        assertThat(PersonaBuilder.instructions("하늘", null, Persona.DEFAULT, handOff = true))
            .contains(PersonaBuilder.HANDOFF_RULE)
        assertThat(PersonaBuilder.instructions("하늘", null, Persona.DEFAULT, handOff = false))
            .doesNotContain(PersonaBuilder.HANDOFF_RULE)
    }

    @Test
    fun `a bot with an override uses its own voice and tone`() {
        val settings = VoiceSettings(
            voice = Voice.KORE,
            persona = Persona(PersonaPreset.WARM, 2),
            perBot = mapOf("atlas" to BotVoice(Voice.PUCK, Persona(PersonaPreset.CALM, 1))),
        )
        assertThat(settings.voiceFor("atlas")).isEqualTo(Voice.PUCK)
        assertThat(settings.personaFor("atlas").preset).isEqualTo(PersonaPreset.CALM)
        assertThat(settings.personaFor("atlas").warmth).isEqualTo(1)

        assertThat(settings.voiceFor("nova")).isEqualTo(Voice.KORE)
        assertThat(settings.personaFor("nova").preset).isEqualTo(PersonaPreset.WARM)
        assertThat(settings.voiceFor(null)).isEqualTo(Voice.KORE)
    }

    @Test
    fun `instructionsFor applies the override of the bot on the call`() {
        val settings = VoiceSettings(
            persona = Persona(PersonaPreset.WARM, 3),
            perBot = mapOf("atlas" to BotVoice(Voice.PUCK, Persona(PersonaPreset.CALM, 0))),
        )
        val atlas = PersonaBuilder.instructionsFor("atlas", "서버 봇", settings)
        assertThat(atlas).contains("차분하고 정확한 비서")
        assertThat(atlas).contains("말투 강도 0/3")
        assertThat(atlas).doesNotContain("따뜻하고 다정한 성격")

        val nova = PersonaBuilder.instructionsFor("nova", "친구 봇", settings)
        assertThat(nova).contains("따뜻하고 다정한 성격")
        assertThat(nova).contains("말투 강도 3/3")
    }

    @Test
    fun `the voice list is a fixed list and refuses an unknown name`() {
        assertThat(Voice.fromId("Kore")).isEqualTo(Voice.KORE)
        assertThat(Voice.fromId("NoSuchVoice")).isNull()
        assertThat(Voice.fromId(null)).isNull()

        // Every voice here was measured to change the audio (tools/spike_04_voices.py); the invalid name
        // that produced no distinct voice is not in the list.
        // The final sweep (spike_04_report.txt): 29 names changed the audio; Iapetus matched Fenrir and
        // the invalid name produced no voice of its own.
        assertThat(Voice.entries).hasSize(29)
        assertThat(Voice.entries.map { it.id }).containsAtLeast("Kore", "Zephyr", "Leda", "Aoede", "Charon", "Puck")
        assertThat(Voice.entries.map { it.id }).doesNotContain("NoSuchVoiceXYZ")
        assertThat(Voice.entries.map { it.id }).doesNotContain("Iapetus")
    }

    @Test
    fun `warmth outside 0 to 3 is a programming error, not a silent clamp`() {
        try {
            Persona(PersonaPreset.WARM, 4)
            throw AssertionError("warmth 4 should not be constructible")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("warmth")
        }
    }
}
