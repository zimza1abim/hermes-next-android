package com.hermeswebui.android.assistant.voice

/**
 * The voices the app offers — the ones `tools/spike_04_voices.py` measured changing the audio on the
 * free key. An unknown name still returns audio, so a free-text field would lie about which voice the
 * user is hearing; a fixed list is the only honest control.
 *
 * Numbers are the final sweep's median F0 (`tools/spike_04_report.txt`), ordered high to low. `Iapetus`
 * is absent on purpose: its timbre fingerprint matched Fenrir's, so it is a fallback, not a voice. The
 * settings screen shows a coarse band rather than the Hz, because F0 moves 10-20 Hz between runs.
 */
enum class Voice(val id: String, val label: String, val pitchHz: Int) {
    ACHERNAR("Achernar", "아케르나르", 274),
    LAOMEDEIA("Laomedeia", "라오메데이아", 233),
    DESPINA("Despina", "데스피나", 220),
    AUTONOE("Autonoe", "아우토노에", 208),
    SADACHBIA("Sadachbia", "사다크비아", 205),
    LEDA("Leda", "레다", 204),
    SULAFAT("Sulafat", "술라파트", 203),
    UMBRIEL("Umbriel", "움브리엘", 202),
    CALLIRRHOE("Callirrhoe", "칼리로에", 200),
    KORE("Kore", "코레", 199),
    ZEPHYR("Zephyr", "제피르", 199),
    VINDEMIATRIX("Vindemiatrix", "빈데미아트릭스", 195),
    AOEDE("Aoede", "아오이데", 187),
    ERINOME("Erinome", "에리노메", 185),
    RASALGETHI("Rasalgethi", "라살게티", 179),
    SCHEDAR("Schedar", "셰다르", 166),
    ORUS("Orus", "오루스", 155),
    ZUBENELGENUBI("Zubenelgenubi", "주베넬게누비", 145),
    PULCHERRIMA("Pulcherrima", "풀케리마", 139),
    GACRUX("Gacrux", "가크룩스", 133),
    ACHIRD("Achird", "아치르드", 131),
    FENRIR("Fenrir", "펜리르", 128),
    PUCK("Puck", "퍽", 120),
    ENCELADUS("Enceladus", "엔켈라두스", 119),
    CHARON("Charon", "카론", 116),
    ALGIEBA("Algieba", "알기에바", 116),
    ALNILAM("Alnilam", "알닐람", 108),
    SADALTAGER("Sadaltager", "사달타거", 97),
    ALGENIB("Algenib", "알게니브", 75);

    /** Three coarse bands, which is as much as a measurement supports on a phone speaker. */
    val range: String get() = when {
        pitchHz >= 185 -> "높은 편"
        pitchHz >= 140 -> "중간"
        else -> "낮은 편"
    }

    val female: Boolean get() = pitchHz >= 185

    companion object {
        /** Null for a name the server does not offer; the settings screen never lets one be typed. */
        fun fromId(id: String?): Voice? = entries.firstOrNull { it.id == id }

        val DEFAULT: Voice = KORE

        /** The six the plan verified first, kept for the settings screen's short list. */
        val VERIFIED: List<Voice> = listOf(KORE, ZEPHYR, LEDA, AOEDE, CHARON, PUCK)
    }
}

/**
 * The languages a call may speak.
 *
 * The six defaults are the ones the plan supports out of the box. Anything else is reachable through
 * [CUSTOM] with a BCP-47 code, so the list never becomes a wall.
 */
enum class VoiceLanguage(val code: String, val label: String, val native: String) {
    KOREAN("ko-KR", "한국어", "한국어"),
    ENGLISH("en-US", "영어", "English"),
    JAPANESE("ja-JP", "일본어", "日本語"),
    SPANISH("es-ES", "스페인어", "Español"),
    FRENCH("fr-FR", "프랑스어", "Français"),
    GERMAN("de-DE", "독일어", "Deutsch"),
    CUSTOM("", "직접 입력", "");

    companion object {
        fun fromId(id: String?): VoiceLanguage = entries.firstOrNull { it.name == id } ?: KOREAN

        /** `ko`, `ko-kr`, `zh-Hant-TW` → `ko-KR`, `ko-KR`, `zh-Hant-TW`; null when it is not a code. */
        fun normalize(raw: String): String? {
            val trimmed = raw.trim()
            if (!Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$").matches(trimmed)) return null
            val parts = trimmed.split('-')
            // A bare tag ("ko") is valid BCP-47 but the Live API is documented with region tags; the six
            // defaults expand to the variant we know works, anything else is passed through as typed.
            if (parts.size == 1) {
                val known = entries.firstOrNull { it.code.startsWith(parts[0].lowercase() + "-", ignoreCase = true) }
                if (known != null) return known.code
            }
            return parts.mapIndexed { index, part ->
                when {
                    index == 0 -> part.lowercase()
                    part.length == 2 -> part.uppercase()
                    else -> part.replaceFirstChar { it.uppercase() }
                }
            }.joinToString("-")
        }
    }
}

/** The two live models the plan verified. Anything else is not offered. */
enum class LiveModel(val id: String, val label: String) {
    GEMINI_3_8_LIVE("gemini-3.8-live", "3.8 Live"),
    GEMINI_3_1_FLASH("gemini-3.1-flash-live-preview", "3.1 Flash Live");

    companion object {
        fun fromId(id: String?): LiveModel = entries.firstOrNull { it.id == id } ?: GEMINI_3_8_LIVE

        val DEFAULT: LiveModel = GEMINI_3_8_LIVE
    }
}

/** A tone of voice. [CUSTOM] uses the user's own words instead of a preset text. */
enum class PersonaPreset(val id: String, val label: String) {
    WARM("warm", "다정·애교"),
    CALM("calm", "차분한 비서"),
    FRIEND("friend", "친구처럼"),
    CUSTOM("custom", "직접 쓰기");

    companion object {
        fun fromId(id: String?): PersonaPreset = entries.firstOrNull { it.id == id } ?: WARM
    }
}

/** A tone setting: which preset, how strong (0..3), and — for [PersonaPreset.CUSTOM] — the user's words. */
data class Persona(
    val preset: PersonaPreset = PersonaPreset.WARM,
    val warmth: Int = 2,
    val custom: String = "",
) {
    init {
        require(warmth in 0..3) { "warmth must be 0..3, was $warmth" }
    }

    companion object {
        val DEFAULT = Persona()
        const val MIN_WARMTH = 0
        const val MAX_WARMTH = 3
    }
}

/** A bot's own voice and tone; a bot without an override uses the app-wide settings. */
data class BotVoice(val voice: Voice, val persona: Persona)

/**
 * What a call runs with. [perBot] is keyed by the bot's profile name (`atlas`, `nova`, ...), which is
 * how the chat screen already identifies the bot the call starts from.
 */
data class VoiceSettings(
    val model: LiveModel = LiveModel.DEFAULT,
    /** How fast the bot speaks: 1.0 is the model's own pace, 0.7 slow, 1.5 quick. */
    val speed: Float = 1.0f,
    val voice: Voice = Voice.DEFAULT,
    val persona: Persona = Persona.DEFAULT,
    val handOff: Boolean = true,
    val subtitles: Boolean = true,
    val speakerOn: Boolean = true,
    val perBot: Map<String, BotVoice> = emptyMap(),
    /** The language the bot answers in; recognition follows what the speaker actually says. */
    val language: VoiceLanguage = VoiceLanguage.KOREAN,
    /** A BCP-47 code, used only when [language] is [VoiceLanguage.CUSTOM]. */
    val customLanguage: String = "",
    /**
     * 통화에서 봇이 자신을 부르는 이름. 봇 프로필 이름이 없는 기본 프로필에서 통화를 걸면 예전엔
     * "헤르메스의 음성 비서"로 시작했다. 비어 있으면 그 문장 그대로 쓴다.
     */
    val assistantName: String = "",
) {
    /** What goes into `speechConfig.languageCode`; falls back to Korean rather than sending garbage. */
    fun languageCode(): String = when (language) {
        VoiceLanguage.CUSTOM -> VoiceLanguage.normalize(customLanguage) ?: VoiceLanguage.KOREAN.code
        else -> language.code
    }

    /** 통화에서 봇이 자신을 부를 이름: 봇 프로필 이름이 먼저, 없으면 설정에 적은 이름. */
    fun nameFor(bot: String?): String? =
        bot?.trim()?.takeIf { it.isNotBlank() } ?: assistantName.trim().takeIf { it.isNotBlank() }

    /** The voice this bot is called with; the override wins, otherwise the app-wide setting. */
    fun voiceFor(bot: String?): Voice = bot?.let { perBot[it]?.voice } ?: voice

    /** The tone this bot is called with. */
    fun personaFor(bot: String?): Persona = bot?.let { perBot[it]?.persona } ?: persona

    companion object {
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.5f

        /** The speeds the settings screen offers; the server's own pace is in the middle. */
        val SPEEDS: List<Float> = listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f)
    }
}
