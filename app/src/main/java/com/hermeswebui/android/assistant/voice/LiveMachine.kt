package com.hermeswebui.android.assistant.voice

/** Where a call is. The plan's state machine, one screen: Idle → Connecting → Listening ⇄ ModelSpeaking. */
sealed interface VoicePhase {
    data object Idle : VoicePhase
    data object Connecting : VoicePhase
    data object Listening : VoicePhase
    data object ModelSpeaking : VoicePhase

    /** The model asked for Hermes; we are waiting for the answer. [startedAt] is the timeout clock. */
    data class Delegating(val callId: String, val request: String, val startedAt: Long) : VoicePhase

    /** The session was cancelled server-side; we are re-attaching with [handle]. */
    data class Reconnecting(val handle: String?) : VoicePhase

    data class Ended(val reason: VoiceEndReason) : VoicePhase
}

enum class VoiceEndReason { USER, KEY_MISSING, QUOTA, NETWORK, ERROR }

/** Everything `Ended` must release. Cleanup is one call, so the list lives in one place. */
enum class VoiceResource { MICROPHONE, PLAYBACK, SOCKET, SERVICE, TIMERS }

enum class Speaker { ME, BOT }

data class VoiceLine(val speaker: Speaker, val text: String)

/** A question currently with Hermes. It survives an interruption — the answer still matters. */
data class PendingCall(val id: String, val request: String, val submissionId: String, val startedAt: Long)

/** An approval the call must show, and tell the model about. */
data class VoiceApproval(val id: String, val tool: String, val prompt: String)

data class VoiceState(
    val phase: VoicePhase = VoicePhase.Idle,
    val settings: VoiceSettings = VoiceSettings(),
    val botName: String? = null,
    /** The last resumable handle the server issued. */
    val handle: String? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val subtitlesOn: Boolean = true,
    val transcript: List<VoiceLine> = emptyList(),
    val botPartial: String = "",
    val myPartial: String = "",
    /** Audio chunks queued but not yet played; [Interrupted] must take this to zero at once. */
    val queuedChunks: Int = 0,
    val pendingCall: PendingCall? = null,
    val approval: VoiceApproval? = null,
    val reconnectAttempts: Int = 0,
    val endReason: VoiceEndReason? = null,
    val notice: String? = null,
    /** Set exactly once, with the single [VoiceEffect.Cleanup]; afterwards every input is ignored. */
    val cleanedUp: Boolean = false,
    /**
     * 내가 말을 끝냈고(ACTIVITY_END) 봇의 첫 응답(음성·자막·도구 호출)을 기다리는 중.
     * "생각 중" 오라와 상태 문구가 이 값을 읽는다.
     */
    val awaitingReply: Boolean = false,
)

sealed interface VoiceInput {
    /** The call button: start a call with these settings from this bot. */
    data class Connect(val settings: VoiceSettings, val botName: String? = null) : VoiceInput

    data object SocketOpened : VoiceInput
    data object SocketClosed : VoiceInput
    data class Server(val event: LiveEvent) : VoiceInput

    /** Hermes finished; [speech] is already polished. */
    data class HermesDone(val callId: String, val speech: String, val isError: Boolean = false) : VoiceInput
    data class HermesFailed(val callId: String, val failure: HandOffFailure) : VoiceInput

    data object HangUp : VoiceInput
    data object ToggleMute : VoiceInput
    data object ToggleSpeaker : VoiceInput
    data object ToggleSubtitles : VoiceInput
    data class ApprovalRequested(val approval: VoiceApproval) : VoiceInput
    /** The approval was answered (or withdrawn); the card goes away. */
    data object ApprovalResolved : VoiceInput
    data class Fatal(val reason: VoiceEndReason, val notice: String? = null) : VoiceInput

    /** A timer fired; the machine reads `now` and decides about the delegation timeout. */
    data object Tick : VoiceInput

    /** The caller pressed 다시 시도. From a finished call that is a fresh call, not a reconnect. */
    data object Retry : VoiceInput

    /** Everything queued has been played. */
    data object AudioDrained : VoiceInput

    /** The reconnect delay is over; open the socket again. */
    data object ReconnectNow : VoiceInput

    /**
     * The server refused the resume handle (close 1008 "session not found"): the stored handle is dead,
     * and retrying it would fail the same way forever. Forget it and reconnect fresh.
     */
    data object HandleRejected : VoiceInput
}

sealed interface VoiceEffect {
    data class OpenSocket(val handle: String?) : VoiceEffect
    data object CloseSocket : VoiceEffect
    data object StartMicrophone : VoiceEffect
    data object StopMicrophone : VoiceEffect
    /** Play this PCM chunk now (24 kHz, 16-bit, mono). */
    data class Play(val pcm: ByteArray) : VoiceEffect
    data object StopPlayback : VoiceEffect
    data class AskHermes(val callId: String, val request: String, val submissionId: String) : VoiceEffect
    data class AnswerModel(val callId: String, val payload: String, val isError: Boolean) : VoiceEffect
    data class ScheduleReconnect(val attempt: Int, val delayMs: Long) : VoiceEffect
    data class AnnounceApproval(val approval: VoiceApproval) : VoiceEffect

    /**
     * The single teardown. The controller releases every resource named here from one function, so a
     * resource can never be forgotten by one exit path and remembered by another.
     */
    data class Cleanup(val reason: VoiceEndReason, val resources: Set<VoiceResource>) : VoiceEffect {
        constructor(reason: VoiceEndReason) : this(reason, ALL_RESOURCES)
    }

    companion object {
        val ALL_RESOURCES: Set<VoiceResource> = VoiceResource.entries.toSet()
    }
}

data class Transition(val state: VoiceState, val effects: List<VoiceEffect>)

/**
 * The call, as a pure function. Sound, sockets, timers and Hermes all sit outside; every branch below
 * is reachable in a JVM test, which is the only way a "late message after hang-up" or a "90 s timeout"
 * can be proven instead of hoped for.
 */
object LiveMachine {

    /** How many reconnects are tried before a call is declared dead. */
    const val MAX_RECONNECTS = 3

    /** First reconnect delay; doubles per attempt. */
    const val RECONNECT_BASE_MS = 500L

    /**
     * 첫 마디.
     *
     * 고정 질문 목록은 틀린 접근이었다: 점심에 "아침에 뭐 했어"를 묻고, 빠진 게 없는 사람에게 "요즘 빠진 것"을
     * 묻고, 아무것도 안 한 사람에게 "뭐 했어"를 물었다. 그래서 각도를 정해 주지 않고, 지금 시각과
     * 대화 유무만 알려 준다. 질문은 모델이 그 자리에서 고르되, 답이 없을 수 있는 질문은 금지한다.
     */
    fun greeting(hour: Int, hasHistory: Boolean, seed: Int): String {
        val part = when (hour) {
            in 5..10 -> "아침"
            in 11..13 -> "점심때"
            in 14..17 -> "오후"
            in 18..21 -> "저녁"
            else -> "늦은 시간"
        }
        val variety = if (Math.floorMod(seed, 2) == 0) "짧은 인사" else "아주 짧게 인사"
        return buildString {
            append("통화가 연결됐어. 지금은 $part. 인사는 한 문장, $variety.")
            append(" \"오늘 하루도 고생했어\", \"오늘 하루 어떻게 시작했어\", \"오늘 어땠어\" 같은 상투어는 쓰지 마.")
            if (hasHistory) {
                append(" 최근에 나눈 대화가 있으면 그중 하나를 가볍게 이어 가도 되지만, 억지로 꺼내지는 마.")
            } else {
                append(" 이어 갈 대화가 없으면 인사만 하고 사용자가 말을 걸 때까지 기다려도 돼.")
            }
            append(" 질문은 하나만, 답이 없을 수 있는 질문(\"뭐 했어?\", \"요즘 빠진 게 뭐야?\")은 하지 마.")
            append(" 묻기 애매하면 그냥 인사만 해.")
        }
    }

    fun start(settings: VoiceSettings, botName: String?): VoiceState =
        VoiceState(settings = settings, botName = botName, subtitlesOn = settings.subtitles, speakerOn = settings.speakerOn)

    fun reduce(state: VoiceState, input: VoiceInput, now: Long): Transition {
        // Retry is the one input a finished call still answers: the button on the ended screen used to
        // do nothing at all, because only a Reconnecting call accepted a reconnect.
        if (input is VoiceInput.Retry) {
            if (state.phase !is VoicePhase.Ended) return Transition(state, emptyList())
            val next = VoiceState(
                phase = VoicePhase.Connecting,
                settings = state.settings,
                botName = state.botName,
                handle = null,
                subtitlesOn = state.subtitlesOn,
                speakerOn = state.speakerOn,
            )
            return Transition(next, listOf(VoiceEffect.OpenSocket(null)))
        }

        // A finished call stops accepting anything. This is what makes a late frame after hang-up a
        // no-op instead of a resurrected socket.
        if (state.cleanedUp && input !is VoiceInput.Connect) return Transition(state, emptyList())

        return when (input) {
            is VoiceInput.Connect -> {
                val next = VoiceState(
                    phase = VoicePhase.Connecting,
                    settings = input.settings,
                    botName = input.botName,
                    handle = state.handle,
                    subtitlesOn = input.settings.subtitles,
                    speakerOn = input.settings.speakerOn,
                )
                Transition(next, listOf(VoiceEffect.OpenSocket(state.handle)))
            }

            VoiceInput.SocketOpened -> Transition(state, emptyList())

            is VoiceInput.Server -> server(state, input.event, now)

            VoiceInput.SocketClosed -> onSocketClosed(state)

            is VoiceInput.HermesDone -> answer(state, input.callId, input.speech, input.isError)

            is VoiceInput.HermesFailed -> answer(state, input.callId, HermesHandOff.failureSpeech(input.failure), true)

            VoiceInput.HangUp -> end(state, VoiceEndReason.USER, null)

            is VoiceInput.Fatal -> end(state, input.reason, input.notice)

            VoiceInput.ToggleMute -> {
                val muted = !state.muted
                val effects = when {
                    state.phase !is VoicePhase.Listening && state.phase !is VoicePhase.ModelSpeaking -> emptyList()
                    muted -> listOf(VoiceEffect.StopMicrophone)
                    else -> listOf(VoiceEffect.StartMicrophone)
                }
                Transition(state.copy(muted = muted), effects)
            }

            VoiceInput.ToggleSpeaker -> Transition(state.copy(speakerOn = !state.speakerOn), emptyList())

            VoiceInput.ToggleSubtitles -> Transition(state.copy(subtitlesOn = !state.subtitlesOn), emptyList())

            is VoiceInput.ApprovalRequested -> Transition(
                state.copy(approval = input.approval),
                listOf(VoiceEffect.AnnounceApproval(input.approval)),
            )

            VoiceInput.ApprovalResolved -> Transition(state.copy(approval = null), emptyList())

            VoiceInput.Tick -> tick(state, now)

            VoiceInput.AudioDrained -> {
                val queued = 0
                val phase = if (state.phase is VoicePhase.ModelSpeaking) VoicePhase.Listening else state.phase
                Transition(state.copy(queuedChunks = queued, phase = phase), emptyList())
            }

            VoiceInput.HandleRejected -> Transition(
                state.copy(
                    handle = null,
                    phase = if (state.phase is VoicePhase.Reconnecting) VoicePhase.Reconnecting(null) else state.phase,
                ),
                emptyList(),
            )

            // Unreachable: Retry returns above. Present so the sealed `when` stays exhaustive.
            VoiceInput.Retry -> Transition(state, emptyList())

            VoiceInput.ReconnectNow -> {
                if (state.phase !is VoicePhase.Reconnecting) return Transition(state, emptyList())
                Transition(state.copy(phase = VoicePhase.Connecting), listOf(VoiceEffect.OpenSocket(state.handle)))
            }
        }
    }

    private fun server(state: VoiceState, event: LiveEvent, now: Long): Transition = when (event) {
        LiveEvent.SetupComplete -> Transition(
            state.copy(phase = VoicePhase.Listening, reconnectAttempts = 0),
            listOf(VoiceEffect.StartMicrophone),
        )

        is LiveEvent.Audio -> {
            val phase = if (state.phase is VoicePhase.Listening) VoicePhase.ModelSpeaking else state.phase
            Transition(
                state.copy(phase = phase, queuedChunks = state.queuedChunks + 1, awaitingReply = false),
                listOf(VoiceEffect.Play(event.pcm)),
            )
        }

        is LiveEvent.OutputTranscript -> {
            val phase = if (state.phase is VoicePhase.Listening) VoicePhase.ModelSpeaking else state.phase
            Transition(state.copy(phase = phase, botPartial = state.botPartial + event.text, awaitingReply = false), emptyList())
        }

        is LiveEvent.InputTranscript -> Transition(state.copy(myPartial = state.myPartial + event.text), emptyList())

        LiveEvent.TurnComplete -> {
            // A turn ending while Hermes still works keeps Delegating: the caller is told "확인 중".
            val phase = if (state.pendingCall != null) state.phase else VoicePhase.Listening
            Transition(commit(state).copy(phase = phase, awaitingReply = false), emptyList())
        }

        LiveEvent.Interrupted -> {
            val committed = commitBot(state)
            // Interrupting the bot does not cancel the question with Hermes: the answer still arrives,
            // and dropping it here is how a user ends up asking the same thing twice.
            val phase = if (state.pendingCall != null) state.phase else VoicePhase.Listening
            Transition(
                committed.copy(phase = phase, queuedChunks = 0, awaitingReply = false),
                listOf(VoiceEffect.StopPlayback),
            )
        }

        is LiveEvent.ToolCall -> onToolCall(state, event.calls, now)

        is LiveEvent.GoAway -> {
            val effects = mutableListOf<VoiceEffect>(
                VoiceEffect.StopMicrophone,
                VoiceEffect.StopPlayback,
                VoiceEffect.CloseSocket,
                VoiceEffect.ScheduleReconnect(1, RECONNECT_BASE_MS),
            )
            Transition(
                state.copy(phase = VoicePhase.Reconnecting(state.handle), queuedChunks = 0),
                effects,
            )
        }

        is LiveEvent.VoiceActivity -> {
            // 말 끝(ACTIVITY_END) + 듣는 중 + 음소거 아님 = 봇의 첫 응답을 기다린다("생각 중").
            val waiting = event.type.endsWith("END") && state.phase is VoicePhase.Listening && !state.muted
            Transition(state.copy(awaitingReply = waiting), emptyList())
        }

        is LiveEvent.ResumeHandle ->
            // Only a handle the server says is resumable may be kept; an unusable one would make the
            // reconnect loop fail forever.
            if (event.resumable) Transition(state.copy(handle = event.handle), emptyList())
            else Transition(state, emptyList())
    }

    private fun onToolCall(state: VoiceState, calls: List<ToolInvocation>, now: Long): Transition {
        if (!state.settings.handOff) {
            val refusals = calls.map { call ->
                VoiceEffect.AnswerModel(
                    call.id,
                    LiveProtocol.toolResult(call.id, "지금은 Hermes 연결이 꺼져 있어서 확인할 수 없어요.", isError = true),
                    true,
                )
            }
            return Transition(state, refusals)
        }
        val first = calls.firstOrNull() ?: return Transition(state, emptyList())
        val request = first.request.ifBlank { state.botPartial.trim() }
        val pending = PendingCall(
            id = first.id,
            request = request,
            submissionId = "voice-${first.id}",
            startedAt = now,
        )
        val handOff = HandOff(callId = pending.id, request = pending.request, submissionId = pending.submissionId)
        val effects = mutableListOf<VoiceEffect>(VoiceEffect.AskHermes(pending.id, handOff.request, handOff.submissionId))
        // Any further call in the same frame is answered immediately: one question at a time.
        calls.drop(1).forEach {
            effects += VoiceEffect.AnswerModel(it.id, LiveProtocol.toolResult(it.id, "이미 다른 확인을 하고 있어요.", isError = true), true)
        }
        return Transition(
            state.copy(phase = VoicePhase.Delegating(pending.id, pending.request, now), pendingCall = pending, awaitingReply = false),
            effects,
        )
    }

    private fun answer(state: VoiceState, callId: String, speech: String, isError: Boolean): Transition {
        val pending = state.pendingCall
        // A result for a call we no longer wait on (a second answer, an old call) changes nothing.
        if (pending == null || pending.id != callId) return Transition(state, emptyList())
        return Transition(
            state.copy(
                phase = VoicePhase.ModelSpeaking,
                pendingCall = null,
                notice = null,
            ),
            listOf(VoiceEffect.AnswerModel(callId, LiveProtocol.toolResult(callId, speech, isError), isError)),
        )
    }

    private fun tick(state: VoiceState, now: Long): Transition {
        val pending = state.pendingCall ?: return Transition(state, emptyList())
        if (now - pending.startedAt < HermesHandOff.TIMEOUT_MS) return Transition(state, emptyList())
        // 90 seconds is a fact about the answer, not a licence to invent one.
        return answer(state, pending.id, HermesHandOff.failureSpeech(HandOffFailure.Timeout), true)
    }

    private fun onSocketClosed(state: VoiceState): Transition {
        val live = state.phase is VoicePhase.Listening ||
            state.phase is VoicePhase.ModelSpeaking ||
            state.phase is VoicePhase.Delegating ||
            state.phase is VoicePhase.Connecting
        if (!live) return Transition(state, emptyList())

        val attempt = state.reconnectAttempts + 1
        if (attempt > MAX_RECONNECTS) {
            return end(state, VoiceEndReason.NETWORK, "연결이 끊겨서 통화를 마쳤어요.")
        }
        val delay = RECONNECT_BASE_MS * (1L shl (attempt - 1))
        return Transition(
            state.copy(phase = VoicePhase.Reconnecting(state.handle), reconnectAttempts = attempt, queuedChunks = 0),
            listOf(VoiceEffect.StopMicrophone, VoiceEffect.StopPlayback, VoiceEffect.ScheduleReconnect(attempt, delay)),
        )
    }

    private fun end(state: VoiceState, reason: VoiceEndReason, notice: String?): Transition {
        if (state.cleanedUp) return Transition(state, emptyList())
        val next = state.copy(
            phase = VoicePhase.Ended(reason),
            endReason = reason,
            notice = notice,
            queuedChunks = 0,
            pendingCall = null,
            cleanedUp = true,
        )
        // One effect: the socket is one of the resources [VoiceEffect.Cleanup] closes, so listing it
        // again here would be a second teardown path for the same resource.
        return Transition(next, listOf(VoiceEffect.Cleanup(reason)))
    }

    private fun commit(state: VoiceState): VoiceState {
        // 내가 먼저 말했으니 ME를 먼저 기록한다. 예전에는 BOT → ME 순서로 뒤집혀 남았다.
        val withMe = if (state.myPartial.isBlank()) state else state.copy(
            transcript = state.transcript + VoiceLine(Speaker.ME, state.myPartial.trim()),
            myPartial = "",
        )
        return commitBot(withMe)
    }

    private fun commitBot(state: VoiceState): VoiceState {
        if (state.botPartial.isBlank()) return state
        return state.copy(
            transcript = state.transcript + VoiceLine(Speaker.BOT, state.botPartial.trim()),
            botPartial = "",
        )
    }
}
