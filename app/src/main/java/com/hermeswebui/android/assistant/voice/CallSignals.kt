package com.hermeswebui.android.assistant.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp
import kotlin.math.log10

/** 프레임마다 끌어오는 값. 잠금 없는 읽기여야 한다. */
interface CallSignals {
    /** 마이크 RMS 0..1 (음소거면 0). */
    fun micLevel(): Float
    /** 지금 들리는 재생 RMS 0..1. */
    fun playbackLevel(): Float
    /** 이번 봇 턴에서 이미 들린 글자 수. */
    fun revealedChars(): Int
}

/** RMS → 0..1. -50 dBFS 아래는 0, -10 dBFS 위는 1. */
fun normalizeLevel(rms: Float): Float {
    if (rms <= 1e-5f) return 0f
    val db = 20f * log10(rms)
    return ((db + 50f) / 40f).coerceIn(0f, 1f)
}

/** 한 극 저역 필터. 올라갈 때 τ=80ms, 내려갈 때 τ=250ms. */
class LevelSmoother(
    private val upMs: Float = CallTokens.VolumeUpMs.toFloat(),
    private val downMs: Float = CallTokens.VolumeDownMs.toFloat(),
) {
    var value = 0f
        private set

    fun step(target: Float, dtMs: Float): Float {
        val tau = if (target > value) upMs else downMs
        val a = 1f - exp(-dtMs / tau)
        value += (target - value) * a
        return value
    }

    fun reset() { value = 0f }
}

/** 프레임 루프 결과. frameTick만 상태(그리기 무효화용), 나머지는 일반 필드라 읽어도 재구성이 없다. */
@Stable
class CallFrame {
    val uniforms = AuraUniforms()
    var frameTick by mutableLongStateOf(0L)
        internal set
    /** 자막만 이걸 컴포지션에서 읽는다. 단어가 바뀔 때만 재구성. */
    var revealedChars by mutableIntStateOf(0)
        internal set
    var mic = 0f
        internal set
    var playback = 0f
        internal set
}

/**
 * Choreographer 프레임마다: 신호 끌어오기 → 정규화 → 스무딩 → AuraAnimator 값과 합쳐 AuraUniforms에 쓰기.
 * 이 루프는 상태를 frameTick과 revealedChars 두 개에만 쓴다.
 */
@Composable
fun rememberCallFrame(
    signals: CallSignals,
    animator: AuraAnimator,
    calm: Boolean,
    detail: () -> Float = { 4f },
    skipEveryOther: () -> Boolean = { false },
    heightMap: (Float) -> Float = { it },
): CallFrame {
    val frame = remember { CallFrame() }
    val currentSignals by rememberUpdatedState(signals)
    val currentCalm by rememberUpdatedState(calm)
    LaunchedEffect(animator) {
        val micS = LevelSmoother()
        val playS = LevelSmoother()
        var last = 0L
        var time = 0f
        var flow = 0f
        var odd = false
        while (true) {
            withFrameNanos { now ->
                val dtMs = if (last == 0L) 16.7f else ((now - last) / 1_000_000f).coerceIn(0f, 100f)
                last = now
                val s = currentSignals
                frame.mic = micS.step(normalizeLevel(s.micLevel()), dtMs)
                frame.playback = playS.step(normalizeLevel(s.playbackLevel()), dtMs)
                val dt = dtMs / 1000f
                time = (time + dt) % 3600f
                flow = (flow + dt * animator.flowSpeed.value) % 3600f
                val u = frame.uniforms
                u.time = time
                u.flow = flow
                u.height = heightMap(animator.height.value)
                u.swell = animator.swell.value * frame.playback
                u.ripple = animator.edgeRipple.value * (0.35f + 0.65f * frame.mic)
                u.sweep = animator.sweep.value
                u.flash = animator.flash.value
                u.dir = animator.flowDir.value
                // 동작 줄이기: 부풂 대신 밝기로 목소리를 표현한다.
                u.brightness = animator.brightness.value + if (currentCalm) 0.25f * frame.playback else 0f
                u.saturation = animator.saturation.value
                u.detail = detail()
                val chars = s.revealedChars()
                if (chars != frame.revealedChars) frame.revealedChars = chars
                odd = !odd
                if (!(skipEveryOther() && odd)) frame.frameTick = now
            }
        }
    }
    return frame
}
