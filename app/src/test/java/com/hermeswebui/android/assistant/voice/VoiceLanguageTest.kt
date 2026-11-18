package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * 언어: 대답은 고른 언어로만, 나머지는 코드로. 인식 언어는 API가 정하지 않으므로 여기서 흉내내지 않는다 —
 * 이 테스트는 "무엇을 서버로 보내는가"만 확인한다.
 */
class VoiceLanguageTest {

    @Test
    fun `the six defaults carry the codes the server expects`() {
        assertThat(VoiceLanguage.KOREAN.code).isEqualTo("ko-KR")
        assertThat(VoiceLanguage.ENGLISH.code).isEqualTo("en-US")
        assertThat(VoiceLanguage.JAPANESE.code).isEqualTo("ja-JP")
        assertThat(VoiceLanguage.SPANISH.code).isEqualTo("es-ES")
        assertThat(VoiceLanguage.FRENCH.code).isEqualTo("fr-FR")
        assertThat(VoiceLanguage.GERMAN.code).isEqualTo("de-DE")
    }

    @Test
    fun `a free-typed code is normalised and a nonsense one is refused`() {
        assertThat(VoiceLanguage.normalize("ko")).isEqualTo("ko-KR")
        assertThat(VoiceLanguage.normalize("ZH-hant-tw")).isEqualTo("zh-Hant-TW")
        assertThat(VoiceLanguage.normalize("pt-br")).isEqualTo("pt-BR")
        assertThat(VoiceLanguage.normalize("한국어")).isNull()
        assertThat(VoiceLanguage.normalize("")).isNull()
    }

    @Test
    fun `a bad custom code falls back to Korean instead of being sent`() {
        val settings = VoiceSettings(language = VoiceLanguage.CUSTOM, customLanguage = "!!")
        assertThat(settings.languageCode()).isEqualTo("ko-KR")

        val good = VoiceSettings(language = VoiceLanguage.CUSTOM, customLanguage = "zh-cn")
        assertThat(good.languageCode()).isEqualTo("zh-CN")
    }

    @Test
    fun `the setup carries the chosen language`() {
        val setup = LiveProtocol.setup(LiveSetup(languageCode = "ja-JP"))
        val speech = JSONObject(setup).getJSONObject("setup")
            .getJSONObject("generationConfig").getJSONObject("speechConfig")
        assertThat(speech.getString("languageCode")).isEqualTo("ja-JP")
    }

    @Test
    fun `a call without a bot profile still knows its own name`() {
        // 예전엔 이름이 없으면 "헤르메스의 음성 비서"로 시작해서, 이름을 물으면 그렇게만 답했다.
        val settings = VoiceSettings(assistantName = "한채린")
        assertThat(settings.nameFor(null)).isEqualTo("한채린")
        // 봇 프로필 이름이 있으면 그쪽이 이긴다.
        assertThat(settings.nameFor("nova")).isEqualTo("nova")

        val text = PersonaBuilder.instructions(
            settings.nameFor(null), null, settings.personaFor(null), settings.handOff, settings.language,
        )
        assertThat(text.startsWith("너는 한채린")).isTrue()
    }

    @Test
    fun `the instruction ends with the language rule`() {
        val korean = PersonaBuilder.instructions("Nova", null, Persona.DEFAULT, true, VoiceLanguage.KOREAN)
        assertThat(korean.trim().endsWith(PersonaBuilder.languageRule(VoiceLanguage.KOREAN))).isTrue()

        val english = PersonaBuilder.instructions("Nova", null, Persona.DEFAULT, true, VoiceLanguage.ENGLISH)
        assertThat(english).contains("기본 대답 언어: English")
    }

    @Test
    fun `a pasted personality note is kept before the language rule`() {
        val text = PersonaBuilder.instructions(
            "한채린", null, Persona.DEFAULT, true, VoiceLanguage.KOREAN,
            soulNote = "내 이름은 winter. 반말로 다정하게.",
        )
        assertThat(text).contains("이 사람에 대해 늘 기억할 것:")
        assertThat(text).contains("반말로 다정하게")
        // 언어 규칙이 마지막 말이어야 한다.
        assertThat(text.trim().endsWith(PersonaBuilder.languageRule(VoiceLanguage.KOREAN))).isTrue()

        val without = PersonaBuilder.instructions("한채린", null, Persona.DEFAULT, true, VoiceLanguage.KOREAN)
        assertThat(without).doesNotContain("이 사람에 대해 늘 기억할 것")
    }

    @Test
    fun `the language rule is a policy with exceptions, not a ban on other languages`() {
        val rule = PersonaBuilder.languageRule(VoiceLanguage.KOREAN)

        // 명시 요청은 따른다 (예전 "한국어로만" 규칙은 이걸 막았다)
        assertThat(rule).contains("다른 언어로 말해 달라고 하면")
        assertThat(rule).contains("요청이 끝나면 기본 언어로 돌아온다")
        // 섞어 말해도 알아듣는다
        assertThat(rule).contains("두 언어를 섞어 말해도")
        // 언어 교육·번역은 역할 분담
        assertThat(rule).contains("가르치거나 번역할 때")
        assertThat(rule).contains("예문·발음·철자")
        // 애매하면 되묻는다
        assertThat(rule).contains("짧게 되묻는다")

        // 금지형 문장은 남아 있으면 안 된다: 그게 사용자의 요청을 막는 원인이었다.
        assertThat(rule).doesNotContain("한국어로만")
        assertThat(rule).doesNotContain("Never answer in another language")
    }
}
