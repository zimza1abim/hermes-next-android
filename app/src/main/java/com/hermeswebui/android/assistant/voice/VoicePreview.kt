package com.hermeswebui.android.assistant.voice

import android.content.Context

/**
 * 미리 듣기: the chosen voice and tone say one short sentence, over the same live pipeline a call uses,
 * then close. Nothing is stored and the call screen is not involved.
 */
class VoicePreview(context: Context, private val key: GeminiKey) {

    private val audio = LiveAudio(context)
    private var socket: LiveSocket? = null
    private var previewing = false

    val SENTENCE: String = "안녕, 나야. 오늘 하루도 정말 고생 많았어. 잠깐 쉬어 가자."

    fun isPlaying(): Boolean = previewing

    /** [onDone] receives null on success, or the reason it could not play. */
    fun play(settings: VoiceSettings, botName: String?, onDone: (String?) -> Unit) {
        stop()
        val apiKey = key.get()
        if (apiKey == null) {
            onDone("Gemini 키가 없어요. 키를 먼저 넣어 주세요.")
            return
        }
        previewing = true
        audio.setSpeed(settings.speed)
        val config = LiveSetup(
            model = settings.model,
            speed = settings.speed,
            voice = settings.voiceFor(botName),
            instructions = PersonaBuilder.instructionsFor(botName, null, settings),
            handOff = false,
            sessionResumption = false,
            contextWindowCompression = false,
        )
        socket = LiveSocket(
            onOpened = { socket?.send(LiveProtocol.setup(config)) },
            onText = { text ->
                LiveProtocol.parse(text).forEach { event ->
                    when (event) {
                        LiveEvent.SetupComplete -> socket?.send(LiveProtocol.textTurn("다음 문장만 그대로 읽어 줘: $SENTENCE"))
                        is LiveEvent.Audio -> audio.play(event.pcm)
                        LiveEvent.TurnComplete -> finish(onDone, null)
                        else -> Unit
                    }
                }
            },
            onClosed = { reason -> if (previewing) finish(onDone, reason) },
        ).also { it.connect(GeminiKey.endpoint(apiKey)) }
    }

    fun stop() {
        previewing = false
        socket?.close()
        socket = null
        audio.release()
    }

    private fun finish(onDone: (String?) -> Unit, reason: String?) {
        if (!previewing) return
        previewing = false
        socket?.close()
        socket = null
        // The audio track keeps its buffered chunk; releasing it at once would cut the last word.
        audio.stopMicrophone()
        onDone(reason)
    }
}
