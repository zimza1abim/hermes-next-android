package com.hermeswebui.android.assistant.voice

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64

/** One `ask_hermes` call the model made. [request] is the model's own words, kept verbatim. */
data class ToolInvocation(val id: String, val name: String, val request: String)

/** What the server told us, reduced to what the call needs. A message can carry several of these. */
sealed interface LiveEvent {
    data object SetupComplete : LiveEvent

    /** 24 kHz, 16-bit, mono PCM — decoded here so the player never touches base64. */
    data class Audio(val pcm: ByteArray, val sampleRate: Int = 24_000) : LiveEvent {
        override fun equals(other: Any?): Boolean =
            other is Audio && other.sampleRate == sampleRate && other.pcm.contentEquals(pcm)

        override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRate
    }

    data class InputTranscript(val text: String) : LiveEvent
    data class OutputTranscript(val text: String) : LiveEvent

    data object TurnComplete : LiveEvent

    /** The user talked over the bot: drop whatever is queued right now. */
    data object Interrupted : LiveEvent

    data class ToolCall(val calls: List<ToolInvocation>) : LiveEvent

    /** The session is about to end; [timeLeftSeconds] is null when the server sent no number. */
    data class GoAway(val timeLeftSeconds: Long?) : LiveEvent

    /** A resume handle; only a `resumable` one may be kept. */
    data class ResumeHandle(val handle: String, val resumable: Boolean) : LiveEvent

    /** The server's own voice-activity decision; `ACTIVITY_END` is the "말 끝" the plan measures. */
    data class VoiceActivity(val type: String) : LiveEvent
}

/** The speech config a call runs with, on top of the user's settings. */
data class LiveSetup(
    val model: LiveModel = LiveModel.DEFAULT,
    val voice: Voice = Voice.DEFAULT,
    /**
     * Speaking rate. The server accepts `speechConfig.speed` but measured 0.8 vs 1.5 changed a sentence's
     * duration by only 8% (tools/spike_05_speed.py), so the audible speed is applied by the player
     * ([LiveAudio.setSpeed]); the field is still sent in case the server starts honouring it.
     */
    val speed: Float = 1.0f,
    val instructions: String = "",
    val handOff: Boolean = true,
    /** Whether the tool is declared so the model can keep talking while Hermes works. */
    val asyncTools: Boolean = true,
    val sessionResumption: Boolean = true,
    /** A handle from an earlier session, to continue the same conversation after a drop. */
    val resumeHandle: String? = null,
    /** `speechConfig.languageCode`: the language the answer comes back in. */
    val languageCode: String = "ko-KR",
    val contextWindowCompression: Boolean = true,
    val targetTokens: Int = 12_800,
    /** Measured on the free key (tools/spike_03*.py): HIGH + 500 ms ended a turn 1,042 ms after the
     *  last speech sample, inside the plan's 1.5 s budget; LOW needed ~2.1 s and felt late. */
    val startSensitivity: String = "START_SENSITIVITY_HIGH",
    val endSensitivity: String = "END_SENSITIVITY_HIGH",
    val prefixPaddingMs: Int = 40,
    val silenceDurationMs: Int = 500,
)

/**
 * Builds and reads Gemini Live's JSON. Pure: no socket, no clock, no Android.
 *
 * Every reader is defensive on purpose — a live socket delivers messages this app has never seen
 * (server-side additions, empty `{}` keepalives), and a parse error must never take a call down.
 */
object LiveProtocol {

    const val ASK_HERMES = "ask_hermes"

    /** Server-side speech rate of the returned audio; the player is built for this. */
    const val OUTPUT_SAMPLE_RATE = 24_000

    /** Microphone rate the server expects. */
    const val INPUT_SAMPLE_RATE = 16_000

    fun setup(config: LiveSetup): String {
        val generation = JSONObject()
            .put("responseModalities", JSONArray(listOf("AUDIO")))
            .put("speechConfig", JSONObject()
                .put("languageCode", config.languageCode)
                .put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", config.voice.id)))
                .apply { if (config.speed != 1.0f) put("speed", config.speed.toDouble()) })

        val payload = JSONObject()
            .put("model", "models/${config.model.id}")
            .put("generationConfig", generation)
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", config.instructions))))

        if (config.sessionResumption) {
            val resumption = JSONObject()
            if (!config.resumeHandle.isNullOrBlank()) resumption.put("handle", config.resumeHandle)
            payload.put("sessionResumption", resumption)
        }
        if (config.contextWindowCompression) {
            payload.put("contextWindowCompression", JSONObject()
                .put("slidingWindow", JSONObject().put("targetTokens", config.targetTokens)))
        }
        payload.put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
            .put("startOfSpeechSensitivity", config.startSensitivity)
            .put("endOfSpeechSensitivity", config.endSensitivity)
            .put("prefixPaddingMs", config.prefixPaddingMs)
            .put("silenceDurationMs", config.silenceDurationMs)))
        if (config.handOff) {
            val requestProperty = JSONObject()
                .put("type", "STRING")
                .put("description", "사용자가 물어본 내용을 한국어 그대로. 요약하거나 바꾸지 않는다.")
            val parameters = JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().put("request", requestProperty))
                .put("required", JSONArray(listOf("request")))
            val declaration = JSONObject()
                .put("name", ASK_HERMES)
                .put("description", "내 데이터·파일·서버·일정·검색·기억이 필요한 질문을 Hermes에게 한국어로 그대로 넘긴다.")
                .put("parameters", parameters)
            if (config.asyncTools) declaration.put("behavior", "NON_BLOCKING")
            payload.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(declaration))))
        }
        return JSONObject().put("setup", payload).toString()
    }

    /** A text turn, used for the greeting and for tests; production turns come from the microphone. */
    fun textTurn(text: String): String = JSONObject()
        .put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true))
        .toString()

    fun audioChunk(pcm: ByteArray): String = JSONObject()
        .put("realtimeInput", JSONObject().put("audio", JSONObject()
            .put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
            .put("data", Base64.getEncoder().encodeToString(pcm))))
        .toString()

    /** The Hermes answer, carried as the tool's result. */
    fun toolResult(callId: String, text: String, isError: Boolean = false): String {
        val response = JSONObject().put("result", text)
        if (isError) response.put("error", text)
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", callId).put("name", ASK_HERMES).put("response", response)
        ))).toString()
    }

    /**
     * Reads one server frame. Returns an empty list for anything unusable: empty keepalives, unknown
     * shapes, invalid JSON. Never throws — a malformed frame must not end a call.
     */
    fun parse(raw: String): List<LiveEvent> {
        val json = try {
            val tokener = JSONTokener(raw)
            tokener.nextValue() as? JSONObject ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<LiveEvent>()

        if (json.has("setupComplete")) out += LiveEvent.SetupComplete

        json.optJSONObject("serverContent")?.let { content ->
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data", "")
                    if (data.isEmpty()) continue
                    val pcm = try {
                        Base64.getDecoder().decode(data)
                    } catch (e: IllegalArgumentException) {
                        continue
                    }
                    // The frame names its rate; trusting it keeps a server-side change from playing as
                    // a chipmunk instead of failing loudly.
                    val rate = Regex("rate=(\\d+)").find(inline.optString("mimeType", ""))
                        ?.groupValues?.get(1)?.toIntOrNull() ?: OUTPUT_SAMPLE_RATE
                    if (pcm.isNotEmpty()) out += LiveEvent.Audio(pcm, rate)
                }
            }
            content.optJSONObject("inputTranscription")?.optString("text", "")?.let {
                if (it.isNotEmpty()) out += LiveEvent.InputTranscript(it)
            }
            content.optJSONObject("outputTranscription")?.optString("text", "")?.let {
                if (it.isNotEmpty()) out += LiveEvent.OutputTranscript(it)
            }
            if (content.optBoolean("interrupted", false)) out += LiveEvent.Interrupted
            if (content.optBoolean("turnComplete", false)) out += LiveEvent.TurnComplete
        }

        json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val invocations = (0 until calls.length()).mapNotNull { i ->
                val call = calls.optJSONObject(i) ?: return@mapNotNull null
                val name = call.optString("name", "")
                val id = call.optString("id", "")
                if (name.isEmpty() || id.isEmpty()) return@mapNotNull null
                val args = call.optJSONObject("args")
                val request = args?.optString("request", "").orEmpty()
                ToolInvocation(id = id, name = name, request = request)
            }
            if (invocations.isNotEmpty()) out += LiveEvent.ToolCall(invocations)
        }

        json.optJSONObject("goAway")?.let { goAway ->
            out += LiveEvent.GoAway(seconds(goAway.opt("timeLeft")))
        }

        json.optJSONObject("voiceActivity")?.let { activity ->
            val type = activity.optString("type", "")
            if (type.isNotEmpty()) out += LiveEvent.VoiceActivity(type)
        }

        json.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle", "")
            if (handle.isNotEmpty()) {
                out += LiveEvent.ResumeHandle(handle = handle, resumable = update.optBoolean("resumable", false))
            }
        }

        return out
    }

    /** `timeLeft` arrives as `"18s"`, sometimes as a number of seconds. */
    private fun seconds(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().removeSuffix("s").toDoubleOrNull()?.toLong()
        else -> null
    }
}
