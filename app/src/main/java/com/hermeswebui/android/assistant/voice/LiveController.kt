package com.hermeswebui.android.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hermeswebui.android.assistant.AssistantController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs one call: owns the state machine, and is the only place that touches the socket, the audio and
 * Hermes.
 *
 * Every exit — hang-up, key refusal, quota, network, a bug — ends in [VoiceEffect.Cleanup], and
 * [cleanup] is the single function that releases anything. Two teardown paths is how a microphone
 * indicator stays lit after a call.
 */
class LiveController private constructor(
    private val context: Context,
    private val key: GeminiKey,
    private val assistant: AssistantController,
    private val audio: LiveAudio = LiveAudio(context),
    private val onService: (Boolean) -> Unit = {},
) {

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state

    /** 봇 한 턴의 자막 글자 ↔ 오디오 시각. 화면은 이걸 글자 단위로 읽는다. */
    val captions = CaptionTimeline()

    private val _interrupt = MutableStateFlow<InterruptMark?>(null)
    /** 끼어들기 순간의 자막 스냅샷. seq가 바뀌면 화면이 섬광·흐림 처리를 한다. */
    val interrupt: StateFlow<InterruptMark?> = _interrupt
    private var interruptSeq = 0

    /** 띠(채팅 화면)가 쓰는 색. 통화 화면이 프사에서 뽑아 넣는다. */
    @Volatile
    var palette: AuraPalette = AuraPalette.Nova

    /** 채팅 화면의 띠가 읽는 시작 시각. */
    val callStartedAtMs: Long get() = callStartedAt

    /** 화면 프레임 루프가 끌어오는 값. StateFlow가 아니라 잠금 없는 읽기다. */
    val signals: CallSignals = object : CallSignals {
        override fun micLevel(): Float = if (_state.value.muted) 0f else audio.amplitude()
        override fun playbackLevel(): Float = audio.playbackLevel()
        override fun revealedChars(): Int = captions.revealedChars(audio.playedFrames())
    }

    /** 이번 봇 턴의 자막 원문(커밋 뒤에도 마지막 줄을 그리기 위해). */
    fun liveBotText(): String = captions.currentText()

    private var socket: LiveSocket? = null
    private var ticker: Runnable? = null
    private var drainer: Runnable? = null
    private var reconnect: Runnable? = null
    private var cleanedUp = false
    private var firstAudioMarked = false
    private var callStartedAt = 0L

    /** How many transcript lines have already been echoed into the conversation. */
    private var echoed = 0

    /** 첫 마디를 한 번만 보내기 위한 표시와, 통화마다 다른 인사를 고르는 씨앗. */
    private var greeted = false
    private val seed = kotlin.random.Random.nextInt()

    /** Which bot the call belongs to; used for its persona, avatar and name. */
    var botName: String? = null
        private set

    /** The recent-conversation block the call was started with (see [CallContext]). */
    private var pendingContext: String = ""

    /** 서버의 SOUL.md 본문(읽기 전용). 비어 있으면 아무것도 붙지 않는다. */
    private var pendingSoul: String = ""

    fun start(
        settings: VoiceSettings,
        bot: String?,
        role: String? = null,
        context: String? = null,
        soul: String? = null,
    ) {
        pendingSoul = soul.orEmpty()
        // 이전 대화 맥락. 재연결·재시도에서도 같은 지시문을 다시 만들어야 해서 여기 보관한다.
        pendingContext = context.orEmpty()
        // A second start would drop the reference to the live socket and leave it streaming: the old
        // call is ended through the one teardown before the new one opens.
        if (!cleanedUp && _state.value.phase !is VoicePhase.Ended && _state.value.phase !is VoicePhase.Idle) {
            hangUp()
            cleanup(_state.value.endReason ?: VoiceEndReason.USER)
        }
        if (key.get() == null) {
            val ended = LiveMachine.reduce(_state.value, VoiceInput.Fatal(VoiceEndReason.KEY_MISSING, "Gemini 키가 없어요."), now())
            _state.value = ended.state
            // No socket or mic exists yet, but the teardown is still the one path that owns the service
            // and the timers; running it here keeps a refused call from leaving either behind.
            cleanup(VoiceEndReason.KEY_MISSING)
            return
        }
        botName = bot
        cleanedUp = false
        callStartedAt = now()
        firstAudioMarked = false
        echoed = 0
        greeted = false
        VoiceTrace.reset()
        val state = LiveMachine.reduce(_state.value, VoiceInput.Connect(settings, bot), now())
        _state.value = state.state
        active.value = this
        apply(state.effects, role)
    }

    fun hangUp() = dispatch(VoiceInput.HangUp)

    fun toggleMute() = dispatch(VoiceInput.ToggleMute)

    fun toggleSpeaker() {
        dispatch(VoiceInput.ToggleSpeaker)
        audio.route(_state.value.speakerOn)
    }

    fun toggleSubtitles() = dispatch(VoiceInput.ToggleSubtitles)

    /** Ends the call with a sentence the screen can show, without opening anything first. */
    fun fail(reason: VoiceEndReason, notice: String) = dispatch(VoiceInput.Fatal(reason, notice))

    fun retry() {
        if (_state.value.phase is VoicePhase.Ended) {
            // The call is over, its audio and socket are gone; the button starts a fresh call. Without
            // this reset the ended state's guard would ignore every input and the button did nothing.
            cleanedUp = false
            callStartedAt = now()
            firstAudioMarked = false
            VoiceTrace.reset()
        }
        dispatch(VoiceInput.Retry)
    }

    /** A real call, Siri or another app took the audio: stop, then resume when it comes back. */
    fun audioFocusLost(permanent: Boolean = false) {
        audio.stopPlayback()
        audio.stopMicrophone()
        // A transient loss gets the call back; a permanent one would leave a silent call running.
        if (permanent) dispatch(VoiceInput.Fatal(VoiceEndReason.ERROR, "다른 앱이 오디오를 가져갔어요. 통화를 끝냈어요."))
    }

    fun audioFocusRegained() {
        if (_state.value.phase is VoicePhase.Listening || _state.value.phase is VoicePhase.ModelSpeaking) {
            audio.startMicrophone(::onMicChunk)
        }
    }

    fun approvalShown(approval: VoiceApproval) = dispatch(VoiceInput.ApprovalRequested(approval))

    /** The card was answered: it goes away, and the call carries on. */
    fun approvalResolved() = dispatch(VoiceInput.ApprovalResolved)

    /** The screen is going away for good. */
    fun close() {
        if (!cleanedUp) dispatch(VoiceInput.HangUp)
        cleanup(_state.value.endReason ?: VoiceEndReason.USER)
    }

    fun amplitude(): Float = audio.amplitude()

    /** How long the running call has been up; 0 when nothing is running. */
    fun elapsedMs(): Long = if (callStartedAt == 0L) 0 else now() - callStartedAt

    // ---- inputs ---------------------------------------------------------

    private fun dispatch(input: VoiceInput) {
        val transition = LiveMachine.reduce(_state.value, input, now())
        _state.value = transition.state
        echoVoiceLines(transition.state)
        apply(transition.effects, null)
    }

    /**
     * The call writes into the conversation while it happens: each finished line is shown at once, from
     * the same transcript the call screen is already drawing. Nothing is sent — the record at the end is.
     */
    private fun echoVoiceLines(state: VoiceState) {
        if (state.transcript.size <= echoed) return
        state.transcript.drop(echoed).forEach { line ->
            assistant.appendVoiceLine(me = line.speaker == Speaker.ME, text = line.text)
        }
        echoed = state.transcript.size
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    // ---- effects --------------------------------------------------------

    private fun apply(effects: List<VoiceEffect>, role: String?) {
        effects.forEach { effect ->
            when (effect) {
                is VoiceEffect.OpenSocket -> openSocket(effect.handle, role)
                VoiceEffect.CloseSocket -> {
                    socket?.close()
                    socket = null
                }
                VoiceEffect.StartMicrophone -> {
                    when {
                        !audio.hasMicrophonePermission() ->
                            dispatch(VoiceInput.Fatal(VoiceEndReason.ERROR, "마이크 권한이 없어서 통화를 할 수 없어요."))
                        // The device can refuse the recorder (busy, no microphone). Saying nothing leaves a
                        // call that looks connected and never hears anything.
                        !audio.startMicrophone(::onMicChunk) ->
                            dispatch(VoiceInput.Fatal(VoiceEndReason.ERROR, "마이크를 열지 못했어요. 다른 앱이 쓰고 있는지 확인해 주세요."))
                    }
                }
                VoiceEffect.StopMicrophone -> audio.stopMicrophone()
                is VoiceEffect.Play -> {
                    audio.play(effect.pcm)
                    scheduleDrainCheck()
                }
                VoiceEffect.StopPlayback -> {
                    audio.stopPlayback()
                    VoiceTrace.mark(PLAYBACK_CLEARED)
                }
                is VoiceEffect.AskHermes -> askHermes(effect)
                is VoiceEffect.AnswerModel -> {
                    socket?.send(effect.payload)
                    VoiceTrace.mark(RESULT_SENT, if (effect.isError) "error" else "ok")
                }
                is VoiceEffect.ScheduleReconnect -> {
                    reconnect?.let(handler::removeCallbacks)
                    reconnect = Runnable { dispatch(VoiceInput.ReconnectNow) }
                    handler.postDelayed(reconnect!!, effect.delayMs)
                }
                is VoiceEffect.AnnounceApproval -> {
                    // The model is told, in words, so it does not go silent while the card waits.
                    socket?.send(LiveProtocol.textTurn(
                        "승인 요청이 떴어요. 화면에 카드를 띄웠으니 \"화면에서 허용해 줄래?\"라고 짧게 말해 줘."
                    ))
                }
                is VoiceEffect.Cleanup -> cleanup(effect.reason)
            }
        }
        scheduleTicker()
    }

    private fun openSocket(handle: String?, role: String?) {
        val settings = _state.value.settings
        val config = LiveSetup(
            model = settings.model,
            speed = settings.speed,
            voice = settings.voiceFor(botName),
            instructions = PersonaBuilder.instructionsFor(
                settings.nameFor(botName), role, settings, soulNote = pendingSoul,
            ).let { base ->
                if (pendingContext.isBlank()) base else base + "\n\n" + pendingContext
            },
            handOff = settings.handOff,
            resumeHandle = handle,
            languageCode = settings.languageCode(),
        )
        val apiKey = key.get()
        if (apiKey == null) {
            dispatch(VoiceInput.Fatal(VoiceEndReason.KEY_MISSING, "Gemini 키가 없어요."))
            return
        }
        socket = LiveSocket(
            onOpened = { socket?.send(LiveProtocol.setup(config)) },
            onText = { text ->
                LiveProtocol.parse(text).forEach { event ->
                    when (event) {
                        is LiveEvent.VoiceActivity -> VoiceTrace.mark(
                            if (event.type.endsWith("END")) VOICE_END else VOICE_START, event.type)
                        LiveEvent.Interrupted -> {
                            VoiceTrace.mark(INTERRUPTED)
                            // StopPlayback(=flush)보다 먼저 스냅샷: 아직 재생 위치가 살아 있다.
                            val snap = captions.snapshot(audio.playedFrames())
                            _interrupt.value = InterruptMark(
                                ++interruptSeq, snap.text, snap.revealedChars, SystemClock.elapsedRealtime(),
                            )
                            captions.endTurn()
                        }
                        is LiveEvent.ToolCall -> VoiceTrace.mark(TOOL_CALL, event.calls.firstOrNull()?.request.orEmpty().take(60))
                        is LiveEvent.Audio -> {
                            if (!firstAudioMarked) {
                                firstAudioMarked = true
                                VoiceTrace.mark(FIRST_AUDIO)
                            }
                            captions.onAudio(event.pcm.size / 2, event.sampleRate, audio.enqueuedFrames())
                        }
                        is LiveEvent.OutputTranscript -> captions.onTranscript(event.text, audio.enqueuedFrames())
                        LiveEvent.TurnComplete -> captions.endTurn()
                        LiveEvent.SetupComplete -> {
                            VoiceTrace.mark(CONNECTED)
                            // 첫 마디를 여기서 정해 준다: 놔두면 매 통화 같은 인사로 시작한다.
                            if (!greeted) {
                                greeted = true
                                socket?.send(
                                    LiveProtocol.textTurn(
                                        LiveMachine.greeting(
                                            hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
                                            hasHistory = pendingContext.isNotBlank(),
                                            seed = seed,
                                        ),
                                    ),
                                )
                            }
                        }
                        else -> Unit
                    }
                    dispatch(VoiceInput.Server(event))
                }
            },
            onClosed = { reason -> onSocketClosed(reason) },
        ).also { it.connect(GeminiKey.endpoint(apiKey)) }
        onService(true)
        audio.setSpeed(settings.speed)
        audio.route(_state.value.speakerOn)
        // A real phone call or another app's audio stops the call and lets it come back (AC-31); the
        // microphone is not left running under a phone call.
        audio.requestFocus(
            onLost = { permanent -> audioFocusLost(permanent) },
            onRegained = ::audioFocusRegained,
        )
    }

    private fun onSocketClosed(reason: String?) {
        // Closing is ours during teardown; a late callback from it would schedule a reconnect for a call
        // that already ended.
        if (cleanedUp) return
        val message = reason.orEmpty()
        // A stale resume handle is refused with 1008 "session not found"; keeping it would make every
        // retry fail the same way, so it is dropped before the reconnect is scheduled.
        val staleHandle = message.contains("session not found", ignoreCase = true) || message.contains("1008")
        if (staleHandle && _state.value.handle != null) {
            VoiceTrace.mark("handle_rejected", message.take(60))
            dispatch(VoiceInput.HandleRejected)
        }
        val quota = message.contains("429") || message.contains("quota", ignoreCase = true) ||
            message.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
        if (quota) {
            dispatch(VoiceInput.Fatal(VoiceEndReason.QUOTA, "오늘 무료 한도를 다 썼어요."))
        } else {
            dispatch(VoiceInput.SocketClosed)
        }
    }

    private fun onMicChunk(pcm: ByteArray) {
        if (_state.value.muted) return
        val phase = _state.value.phase
        val sending = phase is VoicePhase.Listening || phase is VoicePhase.ModelSpeaking || phase is VoicePhase.Delegating
        if (!sending) return
        socket?.send(LiveProtocol.audioChunk(pcm))
    }

    private fun askHermes(effect: VoiceEffect.AskHermes) {
        VoiceTrace.mark(HERMES_ASK, effect.request.take(60))
        assistant.askVoice(question = effect.request, submissionId = effect.submissionId) { turn ->
            VoiceTrace.mark(HERMES_ANSWER, (turn.text ?: turn.error).orEmpty().take(60))
            handler.post {
                val failure = if (turn.text == null) HandOffFailure.Error(turn.error) else null
                if (failure != null) dispatch(VoiceInput.HermesFailed(effect.callId, failure))
                else dispatch(VoiceInput.HermesDone(effect.callId, HermesHandOff.polish(turn.text)))
            }
        }
    }

    private fun scheduleTicker() {
        val phase = _state.value.phase
        val active = phase is VoicePhase.Connecting || phase is VoicePhase.Listening ||
            phase is VoicePhase.ModelSpeaking || phase is VoicePhase.Delegating
        if (!active || cleanedUp) {
            ticker?.let(handler::removeCallbacks)
            ticker = null
            return
        }
        if (ticker != null) return
        ticker = object : Runnable {
            override fun run() {
                dispatch(VoiceInput.Tick)
                if (!cleanedUp) handler.postDelayed(this, 1_000)
            }
        }.also { handler.postDelayed(it, 1_000) }
    }

    /** Playback finishing is what returns the bot to listening; checked while anything is queued. */
    private fun scheduleDrainCheck() {
        if (drainer != null) return
        drainer = object : Runnable {
            override fun run() {
                if (audio.playbackIdle()) {
                    drainer = null
                    dispatch(VoiceInput.AudioDrained)
                } else {
                    handler.postDelayed(this, 120)
                }
            }
        }.also { handler.postDelayed(it, 120) }
    }

    /**
     * The single teardown. The machine hands it the whole resource list; this function releases every
     * one of them by name, so no resource can be forgotten by one exit path and remembered by another.
     */
    private fun cleanup(reason: VoiceEndReason) {
        if (cleanedUp) return
        cleanedUp = true
        VoiceTrace.mark(CLEANED_UP, reason.name)
        leaveCallRecord()
        ticker?.let(handler::removeCallbacks)
        drainer?.let(handler::removeCallbacks)
        reconnect?.let(handler::removeCallbacks)
        ticker = null
        drainer = null
        reconnect = null
        audio.release()
        socket?.close()
        socket = null
        onService(false)
    }

    /**
     * The call's own transcript, left in the conversation.
     *
     * A call is not a chat message stream, so without this the record of what was said disappears with
     * the call screen; the plan's promise is that the conversation still has it afterwards.
     */
    private fun leaveCallRecord() {
        val state = _state.value
        val lines = buildList {
            addAll(state.transcript)
            if (state.myPartial.isNotBlank()) add(VoiceLine(Speaker.ME, state.myPartial.trim()))
            if (state.botPartial.isNotBlank()) add(VoiceLine(Speaker.BOT, state.botPartial.trim()))
        }
        if (lines.isEmpty()) return
        val seconds = if (callStartedAt == 0L) 0 else ((now() - callStartedAt) / 1000).toInt()
        val clock = "%02d:%02d".format(seconds / 60, seconds % 60)
        val spoken = lines.takeLast(60).map { line ->
            (if (line.speaker == Speaker.ME) "나: " else "${botName ?: "Hermes"}: ") + line.text
        }
        // Submitted as a turn, not written locally: only a submitted turn becomes a session the sidebar
        // and a later restart can show. The text starts with what was said, so the session is titled
        // from the conversation instead of from a call label.
        assistant.recordVoiceCall(HermesHandOff.callRecord(clock, botName, spoken))
    }

    companion object {
        /**
         * The call, one per process.
         *
         * It outlives the call screen on purpose: the plan keeps the call running when the user goes
         * back to the conversation, and the notification's 끊기/음소거 reach it through [VoiceService]
         * even when no activity is alive. A per-activity controller would either drop the call or start
         * a second socket on return.
         */
        @Volatile
        private var shared: LiveController? = null

        /** 통화가 진행 중일 때 그 컨트롤러. 채팅 화면의 띠가 구독한다. */
        val active = MutableStateFlow<LiveController?>(null)

        fun obtain(context: Context): LiveController = shared ?: synchronized(this) {
            shared ?: LiveController(
                context = context.applicationContext,
                key = GeminiKey(context.applicationContext),
                assistant = AssistantController.obtainChats(context.applicationContext),
                onService = { running ->
                    if (running) VoiceService.start(context.applicationContext, shared?.botName)
                    else VoiceService.stop(context.applicationContext)
                },
            ).also { shared = it }
        }

        /** The call, when one is actually in progress. */
        fun running(): LiveController? = shared?.takeIf { it.state.value.phase !is VoicePhase.Ended && it.state.value.phase !is VoicePhase.Idle }

        const val VOICE_START = "vad_start"
        const val VOICE_END = "speech_end"
        const val FIRST_AUDIO = "first_audio"
        const val INTERRUPTED = "interrupted"
        const val PLAYBACK_CLEARED = "playback_cleared"
        const val TOOL_CALL = "tool_call"
        const val HERMES_ASK = "hermes_ask"
        const val HERMES_ANSWER = "hermes_answer"
        const val RESULT_SENT = "result_sent"
        const val CONNECTED = "connected"
        const val CLEANED_UP = "cleaned_up"

        /** 화면 쪽 지연 측정: 소켓 수신 → 그린 프레임. */
        const val UI_INTERRUPT_STATE = "ui_interrupt_state"
        const val UI_INTERRUPT_DRAWN = "ui_interrupt_drawn"
        const val UI_THINKING_DRAWN = "ui_thinking_drawn"
    }
}
