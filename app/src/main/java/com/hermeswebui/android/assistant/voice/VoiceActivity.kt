package com.hermeswebui.android.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.hermeswebui.android.assistant.AssistantController
import com.hermeswebui.android.assistant.ChatActivity
import kotlinx.coroutines.launch

/**
 * The call screen. A separate full-screen activity, like a phone call: the conversation is one screen
 * behind it, and the call keeps running when this screen is left.
 *
 * Leaving no longer just finishes: the whole screen shrinks into the strip the conversation is already
 * drawing, and the two share the same rectangle, so the transition reads as one surface.
 */
class VoiceActivity : ComponentActivity() {

    private val key by lazy { GeminiKey(this) }
    private val store by lazy { VoiceSettingsStore(this) }
    private val assistant by lazy { AssistantController.obtainChats(applicationContext) }
    /** The process-wide call: returning to this screen must not start a second socket. */
    private val controller by lazy { LiveController.obtain(applicationContext) }

    private var botName: String? = null
    private var startedAt = 0L
    private var pendingApproval: com.hermeswebui.android.webapp.RpcId? = null

    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginCall()
        // Without the permission there is nothing to listen with; the call is closed with a sentence
        // rather than opening a socket first and failing after it connected.
        else controller.fail(VoiceEndReason.ERROR, "마이크 권한을 허용해야 통화할 수 있어요. 설정에서 허용해 주세요.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        botName = intent.getStringExtra(EXTRA_BOT)
        val fromBar = intent.getBooleanExtra(EXTRA_FROM_BAR, false)

        // The settings first, when there is no key: a call never starts silently without one.
        if (key.get() == null) {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
            finish()
            return
        }

        val registered = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (registered) beginCall() else askMicrophone.launch(Manifest.permission.RECORD_AUDIO)

        // SOUL.md는 서버에서 읽기만 한다(비공개, 앱에서 수정 불가). 통화가 시작되기 전에 한 번 받아 둔다.
        assistant.refreshSoul()

        // The bot's picture is what gives the screen its colour, so it is loaded before drawing.
        assistant.loadAvatars()
        val avatarKey = botName?.let { assistant.avatarKey(it) }.orEmpty()

        setContent {
            val state by controller.state.collectAsState()
            val context = LocalContext.current
            val avatars by assistant.avatars.collectAsState()
            val avatar = avatars[avatarKey]
            val accent = remember(avatar) { VoiceTokens.accentFrom(avatar) }
            val palette = remember(accent) { AuraPalette.fromAccent(accent).also { controller.palette = it } }
            val calm = remember { VoiceTokens.calmMotion(context) }
            val scope = rememberCoroutineScope()
            val collapse = remember { CallCollapse(startCollapsed = fromBar) }
            LaunchedEffect(Unit) { if (fromBar) collapse.progress.snapTo(0f) }
            val view = androidx.compose.ui.platform.LocalView.current

            LaunchedEffect(state.phase) {
                if (state.phase !is VoicePhase.Idle && startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            }

            // 상태 전환의 진동: 연결되면 한 번, 오류면 두 번, Hermes가 확인하면 짧게.
            var lastPhase: String? = remember { null }
            LaunchedEffect(state.phase) {
                val key = state.phase::class.simpleName
                if (key != lastPhase) {
                    when (state.phase) {
                        is VoicePhase.Listening -> CallHaptics.connected(view)
                        is VoicePhase.Delegating -> CallHaptics.working(view)
                        is VoicePhase.Ended -> if (state.endReason != VoiceEndReason.USER) CallHaptics.error(view)
                        else -> Unit
                    }
                    lastPhase = key
                }
            }

            LaunchedEffect(state.phase is VoicePhase.Ended) {
                if (state.phase is VoicePhase.Ended) {
                    kotlinx.coroutines.delay(1_400)
                    // The caller may have pressed 다시 시도 inside that delay.
                    if (state.phase is VoicePhase.Ended) finish()
                }
            }

            // An approval raised while the call is running belongs on the call screen.
            LaunchedEffect(Unit) {
                var showing = false
                assistant.state.collect { chat ->
                    val request = chat.requests
                        .filterIsInstance<com.hermeswebui.android.webapp.GatewayEvent.ApprovalRequested>()
                        .firstOrNull()
                    if (request != null && !showing) {
                        showing = true
                        pendingApproval = request.rpcId
                        controller.approvalShown(VoiceApproval(
                            id = request.rpcId.toString(),
                            tool = request.toolName.orEmpty(),
                            prompt = (request.description ?: request.command).orEmpty(),
                        ))
                    } else if (request == null && showing) {
                        showing = false
                        pendingApproval = null
                        controller.approvalResolved()
                    }
                }
            }

            /** 띠로 접고 채팅으로. 통화는 끊지 않는다. */
            fun leaveToChat(then: (() -> Unit)? = null) {
                scope.launch {
                    collapse.collapse()
                    finish()
                    if (Build.VERSION.SDK_INT >= 34) {
                        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                    } else {
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                    then?.invoke()
                }
            }

            // 뒤로 가기 = 띠로 접기(통화 유지).
            androidx.activity.compose.BackHandler { leaveToChat() }

            val actions = remember {
                VoiceCallActions(
                    onHangUp = { controller.hangUp() },
                    onMute = { controller.toggleMute() },
                    onSpeaker = { controller.toggleSpeaker() },
                    onSubtitles = { controller.toggleSubtitles() },
                    onSettings = { context.startActivity(Intent(context, VoiceSettingsActivity::class.java)) },
                    onApprove = { allow ->
                        pendingApproval?.let { rpc -> assistant.approve(rpc, allow) }
                        pendingApproval = null
                        controller.approvalResolved()
                    },
                    onRetry = { controller.retry() },
                    onType = {
                        leaveToChat {
                            context.startActivity(
                                Intent(context, ChatActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                            )
                        }
                    },
                )
            }

            // The call rises from the bottom, the way a call screen takes over a phone. Returning from the
            // strip, it is already the right place, so it does not rise again.
            var entered by remember { mutableStateOf(fromBar) }
            LaunchedEffect(Unit) { entered = true }

            CollapseHost(
                collapse = collapse,
                aura = {},
            ) { alpha ->
                AnimatedVisibility(
                    visible = entered,
                    enter = if (fromBar) androidx.compose.animation.EnterTransition.None
                    else slideInVertically(spring(dampingRatio = 0.82f, stiffness = 260f)) { it },
                ) {
                    VoiceCallScreen(
                        state = state,
                        signals = controller.signals,
                        interruptFlow = controller.interrupt,
                        liveBotText = { controller.liveBotText() },
                        avatar = avatar,
                        palette = palette,
                        accent = accent,
                        startedAt = startedAt,
                        calm = calm,
                        actions = actions,
                        contentAlpha = alpha,
                    )
                }
            }
        }
    }

    /** Starts only when no call is already running: coming back to this screen resumes the same call. */
    private fun beginCall() {
        if (controller.state.value.phase is VoicePhase.Idle || controller.state.value.phase is VoicePhase.Ended) {
            controller.start(
                store.load(), botName,
                context = recentConversation(),
                soul = assistant.soulText(),
            )
        }
    }

    /**
     * The last dozen exchanges of the open chat, so the voice does not meet the person for the first time.
     * Deeper recall is Hermes's job (ask_hermes), not a bigger prompt here.
     */
    private fun recentConversation(): String {
        val state = assistant.state.value
        val recent = buildList {
            state.history.takeLast(12).forEach { row ->
                add((if (row.role == com.hermeswebui.android.assistant.HistoryRole.USER) Speaker.ME else Speaker.BOT) to row.text)
            }
            state.turns.takeLast(6).forEach { turn ->
                if (turn.question.isNotBlank()) add(Speaker.ME to turn.question)
                turn.final?.takeIf { it.isNotBlank() }?.let { add(Speaker.BOT to it) }
            }
        }
        val topics = state.profiles.mapNotNull { it.chat?.title } + listOfNotNull(state.title)
        return CallContext.block(recent, topics)
    }

    override fun onDestroy() {
        // Leaving the screen keeps the call; only the hang-up button, the notification, an error or the
        // socket ending stops it.
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_BOT = "bot"
        private const val EXTRA_FROM_BAR = "from_bar"

        fun intent(context: Context, botName: String?, fromBar: Boolean = false): Intent =
            Intent(context, VoiceActivity::class.java)
                .putExtra(EXTRA_BOT, botName)
                .putExtra(EXTRA_FROM_BAR, fromBar)

        fun start(context: Context, botName: String?, fromBar: Boolean = false) {
            context.startActivity(intent(context, botName, fromBar).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
