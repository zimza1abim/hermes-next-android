package com.hermeswebui.android.assistant.voice

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.assistant.DotGrid
import com.hermeswebui.android.assistant.RoseType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The call screen's callbacks; one object per activity so recomposition does not rebuild them. */
class VoiceCallActions(
    val onHangUp: () -> Unit,
    val onMute: () -> Unit,
    val onSpeaker: () -> Unit,
    val onSubtitles: () -> Unit,
    val onSettings: () -> Unit,
    /** 허용 / 거절 for the approval card the call is showing. */
    val onApprove: (Boolean) -> Unit,
    /** Starts the call again after an error ended it. */
    val onRetry: () -> Unit,
    /** 글자로: 띠로 접고 채팅 입력창으로. */
    val onType: () -> Unit,
)

/**
 * The call screen: an aura that answers the voice, kinetic captions that follow the audio, and one
 * glass pill. Values live in [CallTokens]; the mood table in [AuraMoods].
 */
@Composable
internal fun VoiceCallScreen(
    state: VoiceState,
    signals: CallSignals,
    interruptFlow: StateFlow<InterruptMark?>,
    liveBotText: () -> String,
    avatar: ImageBitmap?,
    palette: AuraPalette,
    accent: Color,
    startedAt: Long,
    calm: Boolean,
    actions: VoiceCallActions,
    contentAlpha: () -> Float = { 1f },
) {
    val view = LocalView.current
    val botName = state.botName?.takeIf { it.isNotBlank() } ?: "Hermes"
    val animator = remember { AuraAnimator() }
    val interrupt by interruptFlow.collectAsState()
    var interruptedRecently by remember { mutableStateOf(false) }
    LaunchedEffect(interrupt?.seq) {
        interrupt ?: return@LaunchedEffect
        interruptedRecently = true
        launch { animator.flashNow(calm) }
        CallHaptics.interrupt(view)
        VoiceTrace.mark(LiveController.UI_INTERRUPT_STATE)
        delay(CallTokens.InterruptHoldMs)
        interruptedRecently = false
    }
    val mood = AuraMoods.moodOf(state, interruptedRecently)
    LaunchedEffect(mood, calm) { animator.go(mood, calm) }
    val tier = rememberAuraTier()
    val frame = rememberCallFrame(
        signals, animator, calm,
        detail = { tier.detail },
        skipEveryOther = { tier.halfRate },
    )
    var screenSizePx by remember { mutableStateOf(Size.Zero) }

    // 그리기 직후에 한 번만 찍는 측정 마크(끼어들기·생각 중 화면 지연).
    val pendingMark = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    LaunchedEffect(interrupt?.seq) { if (interrupt != null) pendingMark.set(LiveController.UI_INTERRUPT_DRAWN) }
    LaunchedEffect(mood) { if (mood == AuraMood.Thinking) pendingMark.set(LiveController.UI_THINKING_DRAWN) }

    Box(Modifier.fillMaxSize().onSizeChanged { screenSizePx = it.toSize() }) {
        AuraLayer(
            uniforms = frame.uniforms,
            frameTick = { frame.frameTick },
            palette = palette,
            modifier = Modifier.fillMaxSize().drawWithContent {
                drawContent()
                pendingMark.getAndSet(null)?.let { VoiceTrace.mark(it) }
            },
        )
        DotGrid(Modifier.fillMaxWidth().height(CallTokens.DotGridHeight), color = CallTokens.DotGridDark)

        Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha() }.statusBarsPadding()) {
            CallTopBar(state, botName, avatar, startedAt, accent, onSettings = actions.onSettings)
        }

        val model = captionModel(
            state = state,
            botName = botName,
            interrupt = interrupt,
            interruptedRecently = interruptedRecently,
            liveBotText = liveBotText(),
            clock = formatClock(if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt),
        )
        Box(
            Modifier.fillMaxWidth().graphicsLayer { alpha = contentAlpha() }
                .statusBarsPadding().padding(top = CallTokens.CaptionTop),
        ) {
            if (state.subtitlesOn || model is CaptionModel.Centered) {
                CaptionArea(
                    model, { frame.revealedChars }, calm,
                    if (model is CaptionModel.Centered) Modifier.padding(top = 120.dp) else Modifier,
                )
            } else {
                Text(
                    "자막 꺼짐 · 글자로 버튼을 길게 눌러 켜기",
                    style = RoseType.Label.copy(color = CallTokens.CaptionHint),
                    modifier = Modifier.padding(horizontal = CallTokens.CaptionHPadding, vertical = 150.dp),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { alpha = contentAlpha() }
                .navigationBarsPadding()
                .padding(bottom = CallTokens.PillBottom, start = 22.dp, end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.approval?.let {
                ApprovalSheet(it, actions)
                Spacer(Modifier.height(10.dp))
            }
            val ended = state.phase is VoicePhase.Ended
            val failed = ended && (state.endReason == VoiceEndReason.ERROR || state.endReason == VoiceEndReason.QUOTA ||
                state.endReason == VoiceEndReason.KEY_MISSING)
            if (failed) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(
                        state.notice ?: "통화를 이어갈 수 없어요",
                        style = RoseType.Label.copy(color = VoiceTokens.Error),
                    )
                    Spacer(Modifier.size(10.dp))
                    GlassChip("다시 시도") { actions.onRetry() }
                }
            }
            CallPill(
                muted = state.muted,
                ended = ended,
                actions = actions,
                backdrop = { origin, m ->
                    AuraLayer(
                        frame.uniforms, { frame.frameTick }, palette, m,
                        screenSize = { screenSizePx }, origin = origin,
                    )
                },
            )
        }
    }
}

@Composable
private fun ApprovalSheet(approval: VoiceApproval, actions: VoiceCallActions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(VoiceTokens.Glass)
            .border(1.dp, VoiceTokens.GlassBorder, RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Text("승인이 필요해요", style = RoseType.Body.copy(color = VoiceTokens.TextStrong, fontWeight = FontWeight.SemiBold))
        if (approval.tool.isNotBlank()) Text(approval.tool, style = RoseType.Meta.copy(color = VoiceTokens.TextMuted))
        Text(approval.prompt, style = RoseType.Label.copy(color = VoiceTokens.TextMuted), maxLines = 3)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassChip("허용", Color(0xFF4F9A72), solid = true) { actions.onApprove(true) }
            GlassChip("거절") { actions.onApprove(false) }
        }
    }
}

@Composable
private fun GlassChip(label: String, accent: Color = VoiceTokens.GlassBorder, solid: Boolean = false, onClick: () -> Unit) {
    Bouncy(onClick, label) {
        Box(
            Modifier.clip(RoundedCornerShape(16.dp))
                .background(if (solid) accent.copy(alpha = 0.85f) else VoiceTokens.Glass)
                .border(1.dp, if (solid) Color.Transparent else VoiceTokens.GlassBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = RoseType.Label.copy(color = VoiceTokens.TextStrong)) }
    }
}

/** A press that gives: down to 0.92 and back on a spring, the same on every control. */
@Composable
internal fun Bouncy(onClick: () -> Unit, label: String, enabled: Boolean = true, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 900f),
        label = "bounce",
    )
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) { content() }
}

@Composable
internal fun StatusDot(state: VoiceState, accent: Color, size: Dp = 7.dp) {
    val phase = state.phase
    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(if (phase is VoicePhase.Connecting || phase is VoicePhase.Reconnecting) 520 else 1_500),
            RepeatMode.Reverse,
        ),
        label = "dotPulse",
    )
    val colour = when {
        phase is VoicePhase.Reconnecting -> VoiceTokens.Reconnecting
        phase is VoicePhase.Ended -> VoiceTokens.TextFaint
        state.muted -> VoiceTokens.TextMuted
        else -> accent
    }
    Box(Modifier.size(size).graphicsLayer { alpha = pulse }.clip(CircleShape).background(colour))
}

internal fun statusLine(state: VoiceState): String = when (val phase = state.phase) {
    VoicePhase.Idle -> "준비됨"
    VoicePhase.Connecting -> "연결하는 중…"
    VoicePhase.Listening -> if (state.muted) "음소거됨" else "듣고 있어요"
    VoicePhase.ModelSpeaking -> "말하는 중"
    is VoicePhase.Delegating -> "Hermes가 확인 중 · " + phase.request.take(18)
    is VoicePhase.Reconnecting -> "다시 연결하는 중"
    is VoicePhase.Ended -> "통화 종료"
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(total / 60, total % 60)
}
