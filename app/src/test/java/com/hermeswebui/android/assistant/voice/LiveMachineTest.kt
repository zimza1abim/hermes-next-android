package com.hermeswebui.android.assistant.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AC-09..AC-14: interruption, delegation that survives it, the 90 s clock, reconnects and one teardown. */
class LiveMachineTest {

    private var state: VoiceState = LiveMachine.start(VoiceSettings(), "nova")
    private var effects: List<VoiceEffect> = emptyList()
    private var clock: Long = 1_000L

    /** Effects of the last input, and of everything since the last [resetEffects]. */
    private var allEffects: List<VoiceEffect> = emptyList()

    private fun feed(input: VoiceInput, now: Long = clock) {
        val transition = LiveMachine.reduce(state, input, now)
        state = transition.state
        effects = transition.effects
        allEffects = allEffects + transition.effects
    }

    private fun resetEffects() {
        allEffects = emptyList()
    }

    private fun connected(settings: VoiceSettings = state.settings) {
        feed(VoiceInput.Connect(settings, "nova"))
        feed(VoiceInput.Server(LiveEvent.SetupComplete))
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
    }

    private fun speaking(chunks: Int = 3) {
        repeat(chunks) { feed(VoiceInput.Server(LiveEvent.Audio(byteArrayOf(1, 2, 3)))) }
    }

    private fun toolCall(id: String = "call-1", request: String = "내 서버 디스크 용량") {
        feed(VoiceInput.Server(LiveEvent.ToolCall(listOf(ToolInvocation(id, LiveProtocol.ASK_HERMES, request)))))
    }

    @Test
    fun `connecting opens a socket and setup starts the microphone`() {
        feed(VoiceInput.Connect(VoiceSettings(), "nova"))
        assertThat(state.phase).isEqualTo(VoicePhase.Connecting)
        assertThat(effects.single()).isEqualTo(VoiceEffect.OpenSocket(null))

        feed(VoiceInput.Server(LiveEvent.SetupComplete))
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
        assertThat(effects).containsExactly(VoiceEffect.StartMicrophone)
    }

    @Test
    fun `caller audio fills the queue and interruption empties it at once`() {
        connected()
        speaking(3)
        assertThat(state.queuedChunks).isEqualTo(3)
        assertThat(state.phase).isEqualTo(VoicePhase.ModelSpeaking)
        assertThat(effects.filterIsInstance<VoiceEffect.Play>()).hasSize(1)

        feed(VoiceInput.Server(LiveEvent.Interrupted))
        assertThat(state.queuedChunks).isEqualTo(0)
        assertThat(effects).contains(VoiceEffect.StopPlayback)
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
    }

    @Test
    fun `interrupting while Hermes works keeps the answer`() {
        connected()
        toolCall("call-7")
        assertThat(state.phase).isInstanceOf(VoicePhase.Delegating::class.java)
        assertThat(effects.filterIsInstance<VoiceEffect.AskHermes>().single().callId).isEqualTo("call-7")

        feed(VoiceInput.Server(LiveEvent.Interrupted))
        assertThat(state.pendingCall?.id).isEqualTo("call-7")
        assertThat(state.phase).isInstanceOf(VoicePhase.Delegating::class.java)

        feed(VoiceInput.HermesDone("call-7", "디스크는 312GB 남았어요."))
        assertThat(state.pendingCall).isNull()
        assertThat(state.phase).isEqualTo(VoicePhase.ModelSpeaking)
        val answer = effects.filterIsInstance<VoiceEffect.AnswerModel>().single()
        assertThat(answer.callId).isEqualTo("call-7")
        assertThat(answer.payload).contains("312GB")
        assertThat(answer.isError).isFalse()
    }

    @Test
    fun `90 seconds is the line between waiting and failing`() {
        connected()
        toolCall()
        val started = clock

        feed(VoiceInput.Tick, now = started + HermesHandOff.TIMEOUT_MS - 1)
        assertThat(effects).isEmpty()

        feed(VoiceInput.Tick, now = started + HermesHandOff.TIMEOUT_MS)
        val answer = effects.filterIsInstance<VoiceEffect.AnswerModel>().single()
        assertThat(answer.isError).isTrue()
        assertThat(answer.payload).contains("90초")
        assertThat(state.pendingCall).isNull()
    }

    @Test
    fun `a late answer for a call we no longer wait on is ignored`() {
        connected()
        toolCall("call-1")
        feed(VoiceInput.HermesDone("call-1", "답"))
        val settled = state

        feed(VoiceInput.HermesDone("call-1", "또 다른 답"))
        assertThat(state).isEqualTo(settled)
        assertThat(effects).isEmpty()
    }

    @Test
    fun `only a resumable handle is kept and the reconnect uses it`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.ResumeHandle("h-1", resumable = false)))
        assertThat(state.handle).isNull()

        feed(VoiceInput.Server(LiveEvent.ResumeHandle("h-2", resumable = true)))
        assertThat(state.handle).isEqualTo("h-2")

        feed(VoiceInput.Server(LiveEvent.GoAway(18)))
        assertThat(state.phase).isEqualTo(VoicePhase.Reconnecting("h-2"))
        assertThat(effects.filterIsInstance<VoiceEffect.ScheduleReconnect>().single().attempt).isEqualTo(1)
        assertThat(effects).containsAtLeast(VoiceEffect.StopMicrophone, VoiceEffect.StopPlayback, VoiceEffect.CloseSocket)

        feed(VoiceInput.ReconnectNow)
        assertThat(effects.single()).isEqualTo(VoiceEffect.OpenSocket("h-2"))

        feed(VoiceInput.Server(LiveEvent.SetupComplete))
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
    }

    @Test
    fun `a refused resume handle is dropped so the reconnect can succeed`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.ResumeHandle("dead-handle", resumable = true)))
        feed(VoiceInput.Server(LiveEvent.GoAway(5)))
        assertThat(state.phase).isEqualTo(VoicePhase.Reconnecting("dead-handle"))

        // The server answered the handle with 1008 "session not found"; the controller drops it first.
        feed(VoiceInput.HandleRejected)
        assertThat(state.handle).isNull()
        assertThat(state.phase).isEqualTo(VoicePhase.Reconnecting(null))

        feed(VoiceInput.ReconnectNow)
        assertThat(effects.single()).isEqualTo(VoiceEffect.OpenSocket(null))
        feed(VoiceInput.Server(LiveEvent.SetupComplete))
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
    }

    @Test
    fun `losing the socket retries, then ends the call as a network failure`() {
        connected()
        repeat(LiveMachine.MAX_RECONNECTS) {
            feed(VoiceInput.SocketClosed)
            assertThat(state.phase).isInstanceOf(VoicePhase.Reconnecting::class.java)
            feed(VoiceInput.ReconnectNow)
        }
        feed(VoiceInput.SocketClosed)
        assertThat(state.phase).isEqualTo(VoicePhase.Ended(VoiceEndReason.NETWORK))
    }

    @Test
    fun `hang-up tears down once and names every resource`() {
        connected()
        speaking()

        feed(VoiceInput.HangUp)
        val cleanups = effects.filterIsInstance<VoiceEffect.Cleanup>()
        assertThat(cleanups).hasSize(1)
        assertThat(cleanups.single().resources).containsExactlyElementsIn(VoiceResource.entries)
        assertThat(cleanups.single().reason).isEqualTo(VoiceEndReason.USER)
        assertThat(state.phase).isEqualTo(VoicePhase.Ended(VoiceEndReason.USER))
        assertThat(state.queuedChunks).isEqualTo(0)
        assertThat(state.cleanedUp).isTrue()

        // A second hang-up must not tear anything down twice.
        feed(VoiceInput.HangUp)
        assertThat(effects).isEmpty()
    }

    @Test
    fun `messages that arrive after the call ended change nothing`() {
        connected()
        feed(VoiceInput.HangUp)
        val ended = state
        resetEffects()

        feed(VoiceInput.Server(LiveEvent.Audio(byteArrayOf(7))))
        feed(VoiceInput.Server(LiveEvent.Interrupted))
        feed(VoiceInput.Server(LiveEvent.ToolCall(listOf(ToolInvocation("x", LiveProtocol.ASK_HERMES, "질문")))))
        feed(VoiceInput.HermesDone("x", "늦은 답"))
        feed(VoiceInput.Server(LiveEvent.GoAway(5)))
        feed(VoiceInput.SocketClosed)
        feed(VoiceInput.Tick, now = clock + 600_000)

        assertThat(state).isEqualTo(ended)
        // Every input above, not only the last one: a late frame that emitted anything would show here.
        assertThat(allEffects).isEmpty()
    }

    @Test
    fun `the opening knows the time of day instead of using a fixed question list`() {
        // 고정 목록은 점심에 "아침에 뭐 했어"를 묻는 사고를 냈다.
        assertThat(LiveMachine.greeting(8, false, 0)).contains("아침")
        assertThat(LiveMachine.greeting(12, false, 0)).contains("점심때")
        assertThat(LiveMachine.greeting(16, false, 0)).contains("오후")
        assertThat(LiveMachine.greeting(20, false, 0)).contains("저녁")
        assertThat(LiveMachine.greeting(2, false, 0)).contains("늦은 시간")
    }

    @Test
    fun `the opening never demands an answer that may not exist`() {
        val noHistory = LiveMachine.greeting(12, hasHistory = false, seed = 1)
        // 아무것도 안 한 사람에게 "뭐 했어"를 캐묻지 않는다.
        assertThat(noHistory).contains("인사만 하고")
        assertThat(noHistory).contains("답이 없을 수 있는 질문")
        assertThat(noHistory).contains("뭐 했어?")
        assertThat(noHistory).doesNotContain("요즘 푹 빠져 있는지")

        val withHistory = LiveMachine.greeting(12, hasHistory = true, seed = 0)
        assertThat(withHistory).contains("최근에 나눈 대화")
        assertThat(withHistory).contains("억지로 꺼내지는 마")
        // 상투어 금지는 그대로 남는다.
        assertThat(withHistory).contains("오늘 하루 어떻게 시작했어")
    }

    @Test
    fun `the end of my speech is the thinking state until the bot answers`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.VoiceActivity("ACTIVITY_END")))
        assertThat(state.awaitingReply).isTrue()

        feed(VoiceInput.Server(LiveEvent.Audio(byteArrayOf(1, 2, 3))))
        assertThat(state.awaitingReply).isFalse()
        assertThat(state.phase).isEqualTo(VoicePhase.ModelSpeaking)
    }

    @Test
    fun `a turn is committed in the order it happened, me before the bot`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.InputTranscript("피곤했어")))
        feed(VoiceInput.Server(LiveEvent.OutputTranscript("고생했어")))
        feed(VoiceInput.Server(LiveEvent.TurnComplete))
        assertThat(state.transcript.map { it.speaker }).containsExactly(Speaker.ME, Speaker.BOT).inOrder()
    }

    @Test
    fun `retry from an ended call starts a fresh one without the old handle`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.ResumeHandle("handle-1", resumable = true)))
        feed(VoiceInput.HermesDone("call-1", "예전 답"))
        feed(VoiceInput.HangUp)
        assertThat(state.phase is VoicePhase.Ended).isTrue()
        assertThat(state.cleanedUp).isTrue()

        resetEffects()
        feed(VoiceInput.Retry)

        // The button on the ended screen: a real restart, and no stale session handle to be refused.
        assertThat(state.phase).isEqualTo(VoicePhase.Connecting)
        assertThat(state.handle).isNull()
        assertThat(effects).containsExactly(VoiceEffect.OpenSocket(null))
        assertThat(state.transcript).isEmpty()
    }

    @Test
    fun `retry during a live call is ignored`() {
        connected()
        resetEffects()
        feed(VoiceInput.Retry)
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
        assertThat(allEffects).isEmpty()
    }

    @Test
    fun `a broken frame that parses to nothing leaves the call untouched`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.ResumeHandle("h-9", true)))
        speaking(2)
        val before = state
        resetEffects()

        val garbage = listOf("", "{", "null", "[]", "{}", """{"somethingNew":1}""",
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"!!!!"}}]}}}""")
        garbage.forEach { raw ->
            LiveProtocol.parse(raw).forEach { feed(VoiceInput.Server(it)) }
        }

        assertThat(state).isEqualTo(before)
        assertThat(allEffects).isEmpty()
        assertThat(state.handle).isEqualTo("h-9")
        assertThat(state.queuedChunks).isEqualTo(2)
    }

    @Test
    fun `subtitles commit in the order they were spoken`() {
        connected()
        feed(VoiceInput.Server(LiveEvent.InputTranscript("오늘 회사에서")))
        feed(VoiceInput.Server(LiveEvent.TurnComplete))
        feed(VoiceInput.Server(LiveEvent.OutputTranscript("고생 많았어~")))
        feed(VoiceInput.Server(LiveEvent.TurnComplete))

        assertThat(state.transcript).hasSize(2)
        assertThat(state.transcript[0]).isEqualTo(VoiceLine(Speaker.ME, "오늘 회사에서"))
        assertThat(state.transcript[1]).isEqualTo(VoiceLine(Speaker.BOT, "고생 많았어~"))
    }

    @Test
    fun `mute stops sending audio without ending the call`() {
        connected()
        feed(VoiceInput.ToggleMute)
        assertThat(state.muted).isTrue()
        assertThat(effects).contains(VoiceEffect.StopMicrophone)

        feed(VoiceInput.ToggleMute)
        assertThat(state.muted).isFalse()
        assertThat(effects).contains(VoiceEffect.StartMicrophone)
    }

    @Test
    fun `hand-off disabled answers the model instead of asking Hermes`() {
        state = LiveMachine.start(VoiceSettings(handOff = false), "nova")
        connected(VoiceSettings(handOff = false))
        toolCall()

        assertThat(effects.filterIsInstance<VoiceEffect.AskHermes>()).isEmpty()
        val answer = effects.filterIsInstance<VoiceEffect.AnswerModel>().single()
        assertThat(answer.isError).isTrue()
        assertThat(state.phase).isEqualTo(VoicePhase.Listening)
    }

    @Test
    fun `the call ending takes the delegation clock with it`() {
        connected()
        toolCall()
        feed(VoiceInput.HangUp)
        val ended = state
        feed(VoiceInput.Tick, now = clock + HermesHandOff.TIMEOUT_MS + 1)
        assertThat(state).isEqualTo(ended)
    }
}
