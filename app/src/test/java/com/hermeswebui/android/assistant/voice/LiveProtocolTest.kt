package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.util.Base64

/** AC-01..AC-08: what we put on the wire, and what we make of what comes back. */
class LiveProtocolTest {

    private fun setup(config: LiveSetup = LiveSetup(instructions = "지시문")): JSONObject =
        JSONObject(LiveProtocol.setup(config)).getJSONObject("setup")

    @Test
    fun `setup carries model voice language both transcripts and the instruction`() {
        val setup = setup()
        assertThat(setup.getString("model")).isEqualTo("models/gemini-3.8-live")

        val generation = setup.getJSONObject("generationConfig")
        assertThat(generation.getJSONArray("responseModalities").getString(0)).isEqualTo("AUDIO")
        assertThat(generation.getJSONObject("speechConfig").getString("languageCode")).isEqualTo("ko-KR")
        assertThat(
            generation.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName")
        ).isEqualTo("Kore")

        assertThat(setup.has("inputAudioTranscription")).isTrue()
        assertThat(setup.has("outputAudioTranscription")).isTrue()
        assertThat(setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
            .isEqualTo("지시문")
    }

    @Test
    fun `setup declares ask_hermes and marks it non-blocking only when asked`() {
        val asyncDecl = setup().getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertThat(asyncDecl.getString("name")).isEqualTo("ask_hermes")
        assertThat(asyncDecl.getString("behavior")).isEqualTo("NON_BLOCKING")
        assertThat(asyncDecl.getJSONObject("parameters").getJSONArray("required").getString(0)).isEqualTo("request")

        val blocking = setup(LiveSetup(asyncTools = false)).getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0)
        assertThat(blocking.has("behavior")).isFalse()
    }

    @Test
    fun `hand-off off means no tools at all`() {
        assertThat(setup(LiveSetup(handOff = false)).has("tools")).isFalse()
    }

    @Test
    fun `session resumption compression and vad are requestable`() {
        val setup = setup(LiveSetup(sessionResumption = true, contextWindowCompression = true))
        assertThat(setup.has("sessionResumption")).isTrue()
        assertThat(setup.getJSONObject("contextWindowCompression").getJSONObject("slidingWindow").getInt("targetTokens"))
            .isEqualTo(12_800)
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertThat(vad.has("startOfSpeechSensitivity")).isTrue()
        assertThat(vad.has("endOfSpeechSensitivity")).isTrue()
    }

    @Test
    fun `the shipped voice-activity setting is the one that was measured`() {
        // tools/spike_03_vad.py + spike_03_conclude.py: HIGH + 500 ms ended the turn 1,042 ms after the
        // last speech sample; the default (LOW, 1,000 ms) took ~1,264 ms and LOW + 1,500 ms took 2,100 ms.
        val vad = setup().getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertThat(vad.getString("endOfSpeechSensitivity")).isEqualTo("END_SENSITIVITY_HIGH")
        assertThat(vad.getInt("silenceDurationMs")).isEqualTo(500)
        assertThat(vad.getInt("prefixPaddingMs")).isEqualTo(40)
    }

    @Test
    fun `audio parts become pcm bytes at the output sample rate`() {
        val pcm = byteArrayOf(0, 1, 2, 3, 4, 5)
        val raw = JSONObject()
            .put("serverContent", JSONObject().put("modelTurn", JSONObject().put("parts",
                org.json.JSONArray().put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "audio/pcm;rate=24000")
                    .put("data", Base64.getEncoder().encodeToString(pcm)))))))
            .toString()

        val events = LiveProtocol.parse(raw)
        val audio = events.filterIsInstance<LiveEvent.Audio>().single()
        assertThat(audio.pcm).isEqualTo(pcm)
        assertThat(audio.sampleRate).isEqualTo(LiveProtocol.OUTPUT_SAMPLE_RATE)
    }

    @Test
    fun `the audio frame names its own rate and the samples are 16-bit`() {
        val pcm = ByteArray(64) { it.toByte() }
        fun frame(mime: String?) = JSONObject().put("serverContent", JSONObject().put("modelTurn",
            JSONObject().put("parts", org.json.JSONArray().put(
                JSONObject().put("inlineData", JSONObject().put("data", Base64.getEncoder().encodeToString(pcm))
                    .apply { if (mime != null) put("mimeType", mime) }))))).toString()

        assertThat(LiveProtocol.parse(frame("audio/pcm;rate=24000")).filterIsInstance<LiveEvent.Audio>().single().sampleRate)
            .isEqualTo(24_000)
        // A rate the server changes later is honoured instead of being played at the old one.
        assertThat(LiveProtocol.parse(frame("audio/pcm;rate=16000")).filterIsInstance<LiveEvent.Audio>().single().sampleRate)
            .isEqualTo(16_000)
        // No mimeType at all: the documented default.
        assertThat(LiveProtocol.parse(frame(null)).filterIsInstance<LiveEvent.Audio>().single().sampleRate)
            .isEqualTo(LiveProtocol.OUTPUT_SAMPLE_RATE)

        // 16-bit mono: an odd byte count would be a broken frame, so the size must stay even.
        val audio = LiveProtocol.parse(frame("audio/pcm;rate=24000")).filterIsInstance<LiveEvent.Audio>().single()
        assertThat(audio.pcm.size % 2).isEqualTo(0)
        assertThat(audio.pcm.size).isEqualTo(64)
    }

    @Test
    fun `input and output transcription are read separately`() {
        val raw = JSONObject().put("serverContent", JSONObject()
            .put("inputTranscription", JSONObject().put("text", "오늘 회사에서"))
            .put("outputTranscription", JSONObject().put("text", "고생 많았어~"))).toString()

        val events = LiveProtocol.parse(raw)
        assertThat(events.filterIsInstance<LiveEvent.InputTranscript>().single().text).isEqualTo("오늘 회사에서")
        assertThat(events.filterIsInstance<LiveEvent.OutputTranscript>().single().text).isEqualTo("고생 많았어~")
    }

    @Test
    fun `a function call carries id name and the verbatim request`() {
        val raw = JSONObject().put("toolCall", JSONObject().put("functionCalls", org.json.JSONArray().put(
            JSONObject()
                .put("id", "call-1")
                .put("name", "ask_hermes")
                .put("args", JSONObject().put("request", "내 서버 디스크 용량 알려줘"))))).toString()

        val call = LiveProtocol.parse(raw).filterIsInstance<LiveEvent.ToolCall>().single().calls.single()
        assertThat(call.id).isEqualTo("call-1")
        assertThat(call.name).isEqualTo("ask_hermes")
        assertThat(call.request).isEqualTo("내 서버 디스크 용량 알려줘")
    }

    @Test
    fun `interrupted and turnComplete are both read from one frame`() {
        val raw = JSONObject().put("serverContent", JSONObject()
            .put("interrupted", true)
            .put("turnComplete", true)).toString()
        val events = LiveProtocol.parse(raw)
        assertThat(events).contains(LiveEvent.Interrupted)
        assertThat(events).contains(LiveEvent.TurnComplete)
    }

    @Test
    fun `goAway timeLeft is read as seconds`() {
        val fromString = LiveProtocol.parse("""{"goAway":{"timeLeft":"18s"}}""")
            .filterIsInstance<LiveEvent.GoAway>().single()
        assertThat(fromString.timeLeftSeconds).isEqualTo(18)

        val fromNumber = LiveProtocol.parse("""{"goAway":{"timeLeft":7}}""")
            .filterIsInstance<LiveEvent.GoAway>().single()
        assertThat(fromNumber.timeLeftSeconds).isEqualTo(7)

        val absent = LiveProtocol.parse("""{"goAway":{}}""")
            .filterIsInstance<LiveEvent.GoAway>().single()
        assertThat(absent.timeLeftSeconds).isNull()
    }

    @Test
    fun `resume handle keeps its resumable flag`() {
        val resumable = LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"h-1","resumable":true}}""")
            .filterIsInstance<LiveEvent.ResumeHandle>().single()
        assertThat(resumable.handle).isEqualTo("h-1")
        assertThat(resumable.resumable).isTrue()

        val notYet = LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"h-2","resumable":false}}""")
            .filterIsInstance<LiveEvent.ResumeHandle>().single()
        assertThat(notYet.resumable).isFalse()
    }

    @Test
    fun `voice activity is read, because its END is the speech end the plan measures`() {
        val start = LiveProtocol.parse("""{"voiceActivity":{"type":"ACTIVITY_START"}}""")
            .filterIsInstance<LiveEvent.VoiceActivity>().single()
        assertThat(start.type).isEqualTo("ACTIVITY_START")

        val end = LiveProtocol.parse("""{"voiceActivity":{"type":"ACTIVITY_END"}}""")
            .filterIsInstance<LiveEvent.VoiceActivity>().single()
        assertThat(end.type).isEqualTo("ACTIVITY_END")

        assertThat(LiveProtocol.parse("""{"voiceActivity":{}}""")).isEmpty()
    }

    @Test
    fun `a message can carry several events at once`() {
        val raw = JSONObject().put("serverContent", JSONObject()
            .put("modelTurn", JSONObject().put("parts", org.json.JSONArray().put(JSONObject()
                .put("inlineData", JSONObject().put("data", Base64.getEncoder().encodeToString(byteArrayOf(9, 9)))))))
            .put("outputTranscription", JSONObject().put("text", "네"))
            .put("turnComplete", true)).toString()

        val events = LiveProtocol.parse(raw)
        assertThat(events).hasSize(3)
        assertThat(events.first()).isInstanceOf(LiveEvent.Audio::class.java)
        assertThat(events.last()).isEqualTo(LiveEvent.TurnComplete)
    }

    @Test
    fun `unknown and broken frames are ignored without throwing`() {
        val broken = listOf(
            "",
            "{",
            "null",
            "[]",
            "{}",
            """{"somethingNew":{"a":1}}""",
            """{"serverContent":null}""",
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"!!!!not-base64"}}]}}}""",
            """{"serverContent":{"inputTranscription":"text instead of object"}}""",
            """{"toolCall":{"functionCalls":[{"id":""},{"name":"ask_hermes"}]}}""",
            """{"sessionResumptionUpdate":{"newHandle":""}}""",
        )
        broken.forEach { raw ->
            assertThat(LiveProtocol.parse(raw)).isEmpty()
        }
    }
}
