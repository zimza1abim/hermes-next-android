package com.hermeswebui.android.assistant.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Immutable
data class AuraParams(
    val height: Float,      // uH
    val swell: Float,       // × 재생 음량 = uSwell
    val flowSpeed: Float,   // 적분해서 uFlow
    val flowDir: Float,     // uDir
    val edgeRipple: Float,  // × (0.35 + 0.65 × 마이크) = uRipple
    val sweep: Float,       // uSweep
    val flash: Float,       // uFlash
    val brightness: Float,  // uBright
    val saturation: Float,  // uSat
)

enum class AuraMood { Idle, Connecting, Listening, Thinking, Speaking, Working, Interrupted, Muted, Reconnecting, Ended }

object AuraMoods {
    val table: Map<AuraMood, AuraParams> = mapOf(
        AuraMood.Idle         to AuraParams(0.05f, 0f,    0.5f,  0f, 0f,    0f,   0f, 0.75f, 0.9f),
        AuraMood.Connecting   to AuraParams(0.22f, 0f,    0.5f,  0f, 0f,    0f,   0f, 0.75f, 0.9f),
        AuraMood.Listening    to AuraParams(0.38f, 0.05f, 0.6f,  0f, 1f,    0f,   0f, 0.80f, 1f),
        AuraMood.Thinking     to AuraParams(0.42f, 0.1f,  1.2f,  1f, 0f,    0.5f, 0f, 0.85f, 1f),
        AuraMood.Speaking     to AuraParams(0.50f, 0.9f,  1.0f,  0f, 0.2f,  0f,   0f, 1.10f, 1f),
        AuraMood.Working      to AuraParams(0.44f, 0.2f,  1.6f,  1f, 0f,    1f,   0f, 0.90f, 1f),
        AuraMood.Interrupted  to AuraParams(0.28f, 0f,    0.25f, 0f, 0.15f, 0f,   0f, 0.80f, 1f),
        AuraMood.Muted        to AuraParams(0.32f, 0f,    0f,    0f, 0f,    0f,   0f, 0.42f, 0.45f),
        AuraMood.Reconnecting to AuraParams(0.26f, 0f,    0.3f,  0f, 0f,    0f,   0f, 0.50f, 0.30f),
        AuraMood.Ended        to AuraParams(0f,    0f,    0.3f,  0f, 0f,    0f,   0f, 0f,    0.6f),
    )

    /** [interruptedRecently]: InterruptMark.atMs가 CallTokens.InterruptHoldMs 안. */
    fun moodOf(state: VoiceState, interruptedRecently: Boolean): AuraMood {
        val phase = state.phase
        return when {
            phase is VoicePhase.Idle -> AuraMood.Idle
            phase is VoicePhase.Connecting -> AuraMood.Connecting
            phase is VoicePhase.Reconnecting -> AuraMood.Reconnecting
            phase is VoicePhase.Ended -> AuraMood.Ended
            interruptedRecently && phase is VoicePhase.Listening && !state.muted -> AuraMood.Interrupted
            phase is VoicePhase.Delegating -> AuraMood.Working
            phase is VoicePhase.ModelSpeaking || state.queuedChunks > 0 -> AuraMood.Speaking
            state.muted -> AuraMood.Muted
            state.awaitingReply -> AuraMood.Thinking
            else -> AuraMood.Listening
        }
    }

    /** 기본: 임계 감쇠, 98% 도달 약 330ms. */
    val Default: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 200f)
    /** 끼어들기: 90% 도달 약 100ms. */
    val Snap: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1_500f)
    val ConnectRise: AnimationSpec<Float> = tween(CallTokens.ConnectRiseMs, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f))
    val EndSink: AnimationSpec<Float> = tween(CallTokens.EndSinkMs, easing = FastOutLinearInEasing)
    val FlashDecay: AnimationSpec<Float> = tween(CallTokens.FlashMs, easing = LinearEasing)

    fun specFor(to: AuraMood): AnimationSpec<Float> = when (to) {
        AuraMood.Interrupted -> Snap
        AuraMood.Ended -> EndSink
        else -> Default
    }
}

/**
 * 9개 값을 Animatable로 들고 있는다. `.value`는 스냅샷 상태라서 그리기 단계에서만 읽으면
 * 재구성 없이 다시 그리기만 일어난다.
 */
@Stable
class AuraAnimator(initial: AuraParams = AuraMoods.table.getValue(AuraMood.Idle)) {
    val height = Animatable(initial.height)
    val swell = Animatable(initial.swell)
    val flowSpeed = Animatable(initial.flowSpeed)
    val flowDir = Animatable(initial.flowDir)
    val edgeRipple = Animatable(initial.edgeRipple)
    val sweep = Animatable(initial.sweep)
    val flash = Animatable(0f)
    val brightness = Animatable(initial.brightness)
    val saturation = Animatable(initial.saturation)

    suspend fun go(mood: AuraMood, calm: Boolean) = coroutineScope {
        val t = AuraMoods.table.getValue(mood)
        val spec = AuraMoods.specFor(mood)
        // 동작 줄이기: 흐름·잔물결·훑기·부풂 없음. 밝기만 바뀐다.
        val speed = if (calm) 0f else t.flowSpeed
        val ripple = if (calm) 0f else t.edgeRipple
        val sweepT = if (calm) 0f else t.sweep
        val swellT = if (calm) 0f else t.swell
        launch {
            if (mood == AuraMood.Connecting && !calm) {
                height.snapTo(0.05f)
                height.animateTo(t.height, AuraMoods.ConnectRise)
            } else height.animateTo(t.height, spec)
        }
        launch { swell.animateTo(swellT, spec) }
        launch { flowSpeed.animateTo(speed, spec) }
        launch { flowDir.animateTo(t.flowDir, spec) }
        launch { edgeRipple.animateTo(ripple, spec) }
        launch { sweep.animateTo(sweepT, spec) }
        launch { brightness.animateTo(t.brightness, spec) }
        launch { saturation.animateTo(t.saturation, spec) }
    }

    /** 끼어들기 섬광: 즉시 1, 900ms 동안 선형으로 0. calm이면 0.35에서 시작. */
    suspend fun flashNow(calm: Boolean) {
        flash.snapTo(if (calm) 0.35f else 1f)
        flash.animateTo(0f, AuraMoods.FlashDecay)
    }
}
