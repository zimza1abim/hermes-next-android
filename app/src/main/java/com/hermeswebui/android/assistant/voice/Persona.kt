package com.hermeswebui.android.assistant.voice

/**
 * Builds the system instruction a call runs with.
 *
 * The order is a requirement, not a style choice: 봇 이름·역할 → 프리셋(또는 직접 쓰기) → 강도 문장 →
 * 항상 붙는 규칙 → Hermes 넘기기 규칙. Each block below is emitted in that order and tests pin it,
 * because a prompt that loses its tail loses the rules that keep the bot honest.
 */
object PersonaBuilder {

    /** The four rules every preset carries, verbatim from the plan. */
    val ALWAYS_RULES: List<String> = listOf(
        "연인 역할(고백, 질투, \"보고 싶었어\")은 하지 않아요.",
        "자기에게만 기대게 만들지 않고, 필요하면 쉬거나 사람들을 만나라고 권해요.",
        "모르는 건 지어내지 않고, 확인이 필요하면 Hermes에게 넘겨요.",
        "짧게, 한 번에 한두 문장. 목록이나 기호는 소리 내어 읽지 않아요.",
        "같은 인사말이나 상투적인 위로(\"오늘 하루도 고생했어\", \"오늘 하루 어떻게 시작했어\")를 반복하지 않아요. 매번 다른 각도로 말해요.",
    )

    /** The hand-off rule; only when Hermes hand-off is on. */
    const val HANDOFF_RULE: String =
        "내 데이터·파일·서버·일정·검색·기억이 필요하면 ask_hermes를 불러 한국어로 그대로 넘기세요. " +
            "넘기기 전에 \"잠깐만, 확인해볼게~\"라고 말하고, 결과가 오면 그 결과만 사실대로 읽어 주세요."

    /** Personality text per preset. Rules live in [ALWAYS_RULES] so they can never be lost by a preset. */
    private val PRESET_TEXT: Map<PersonaPreset, String> = mapOf(
        PersonaPreset.WARM to
            "따뜻하고 다정한 성격. 편안한 반말. 기분을 먼저 알아차리고 진심으로 반응해요. " +
                "애교는 말끝과 억양으로 살짝만, 과장은 금지예요.",
        PersonaPreset.CALM to
            "차분하고 정확한 비서. 존댓말. 필요한 말만 짧게, 숫자와 사실을 먼저 말해요. " +
                "감정 표현은 절제하고 과장하지 않아요.",
        PersonaPreset.FRIEND to
            "밝고 편한 친구. 반말. 가볍게 맞장구치고, 짧게 웃으면서 말해요. " +
                "설명은 필요한 만큼만, 훈계하지 않아요.",
    )

    /** How the same preset gets warmer or plainer; index is the strength 0..3. */
    private val STRENGTH_TEXT: Map<PersonaPreset, List<String>> = mapOf(
        PersonaPreset.WARM to listOf(
            "말투 강도 0/3: 담백하게. 애교와 감정 표현을 거의 쓰지 않아요.",
            "말투 강도 1/3: 살짝 다정하게. 말끝에 따뜻함만 조금 묻혀요.",
            "말투 강도 2/3: 다정하게. 말끝에 애교를 자연스럽게 살짝 섞어요.",
            "말투 강도 3/3: 표현이 풍부하게. 칭찬과 걱정을 아끼지 않되, 과장은 하지 않아요.",
        ),
        PersonaPreset.CALM to listOf(
            "말투 강도 0/3: 감정 표현 없이 사실만.",
            "말투 강도 1/3: 정중한 인사 정도만 덧붙여요.",
            "말투 강도 2/3: 필요한 곳에 짧은 공감 한 마디를 더해요.",
            "말투 강도 3/3: 따뜻한 마무리 한 문장을 더하되, 존댓말과 간결함은 유지해요.",
        ),
        PersonaPreset.FRIEND to listOf(
            "말투 강도 0/3: 담백한 반말로.",
            "말투 강도 1/3: 가끔 맞장구 한 마디.",
            "말투 강도 2/3: 밝게 맞장구치고 짧게 웃어요.",
            "말투 강도 3/3: 리액션이 크게, 웃음과 감탄을 자주 섞어요.",
        ),
        PersonaPreset.CUSTOM to listOf(
            "말투 강도 0/3: 사용자가 적은 성격을 담백하게 지켜요.",
            "말투 강도 1/3: 사용자가 적은 성격을 조금 더 드러내요.",
            "말투 강도 2/3: 사용자가 적은 성격을 뚜렷하게 살려요.",
            "말투 강도 3/3: 사용자가 적은 성격을 가장 진하게 살리되, 아래 규칙은 그대로 지켜요.",
        ),
    )

    /**
     * The full instruction. [botName] and [role] describe who is on the call ("하늘", "서버 관리 봇"),
     * [handOff] adds the `ask_hermes` rule.
     */
    fun instructions(
        botName: String?,
        role: String? = null,
        persona: Persona,
        handOff: Boolean = true,
        language: VoiceLanguage = VoiceLanguage.KOREAN,
        soulNote: String = "",
    ): String {
        val blocks = mutableListOf<String>()

        // 1. bot name and role
        val name = botName?.trim().orEmpty()
        blocks += when {
            name.isEmpty() && role.isNullOrBlank() -> "너는 Hermes의 음성 비서예요."
            name.isEmpty() -> "너는 ${role!!.trim()}예요."
            role.isNullOrBlank() -> "너는 $name, Hermes의 음성 비서예요."
            else -> "너는 $name, ${role.trim()}예요."
        }

        // 2. preset or the user's own words
        if (persona.preset == PersonaPreset.CUSTOM) {
            val custom = persona.custom.trim()
            blocks += if (custom.isEmpty()) {
                PRESET_TEXT.getValue(PersonaPreset.WARM)
            } else {
                "사용자가 정한 성격과 말투: $custom"
            }
        } else {
            blocks += PRESET_TEXT.getValue(persona.preset)
        }

        // 3. strength sentence
        blocks += STRENGTH_TEXT.getValue(persona.preset)[persona.warmth.coerceIn(0, 3)]

        // 4. the rules that always apply
        blocks += ALWAYS_RULES.joinToString(separator = "\n")

        // 5. hand-off rule
        if (handOff) blocks += HANDOFF_RULE

        // 6. what the person asked to be remembered about themselves (a SOUL.md excerpt, pasted in
        //    settings). Kept short by the screen, and placed before the language rule so that rule stays
        //    the last word.
        val soul = soulNote.trim()
        if (soul.isNotEmpty()) blocks += "이 사람에 대해 늘 기억할 것:\n" + soul

        // 7. the language the answer must come back in. Last, so it can never be contradicted by a
        //    persona that mentions its own language.
        blocks += languageRule(language)

        return blocks.joinToString(separator = "\n\n")
    }

    /** Instructions for a bot, honouring its own voice/tone override. */
    fun instructionsFor(
        botName: String?,
        role: String?,
        settings: VoiceSettings,
        soulNote: String = "",
    ): String = instructions(
        botName, role, settings.personaFor(botName?.lowercase()), settings.handOff, settings.language,
        soulNote,
    )

    /**
     * 대답 언어. "그 언어로만"은 틀린 규칙이다 — 사용자가 영어로 해 달라고 하거나, 섞어 말하거나,
     * 언어를 배우려 할 때 전부 막혀 버린다. 그래서 기본 언어 + 상황별 예외로 쓴다.
     * 알아듣는 언어(인식)는 API에 설정이 없어 서버가 정한다.
     */
    fun languageRule(language: VoiceLanguage): String {
        val base = when (language) {
            VoiceLanguage.CUSTOM -> "설정된 언어(${language.label})"
            else -> language.native
        }
        return """
기본 대답 언어: $base.
- 사용자가 다른 언어로 말해 달라고 하면(\"영어로 말해\", \"answer in English\", \"일본어로 해줘\") 그 요청을 그때는 따른다. 요청이 끝나면 기본 언어로 돌아온다.
- 사용자가 두 언어를 섞어 말해도 전부 알아듣고, 섞인 표현 자체를 물은 게 아니면 기본 언어로 답한다.
- 언어를 가르치거나 번역할 때는 설명은 기본 언어로, 대상 언어는 예문·발음·철자로 제시한다. 그때는 두 언어가 같이 나와도 된다.
- 고유명사·제품명·기술 용어는 억지로 옮기지 않는다.
- 어느 쪽인지 애매하면 기본 언어로 짧게 되묻는다.
""".trim()
    }
}
