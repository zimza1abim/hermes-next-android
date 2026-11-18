package com.hermeswebui.android.assistant.voice

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp

/** 0 = 전체 화면, 1 = 띠. */
@Stable
class CallCollapse(startCollapsed: Boolean) {
    val progress = Animatable(if (startCollapsed) 1f else 0f)

    suspend fun collapse() = progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 420f))   // 약 380ms
    suspend fun expand() = progress.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 380f))
}

/** 전체 화면 사각형 → 띠 사각형(상태바+10, 좌우 16, 높이 56, 반경 28)으로 보간한 둥근 모양. */
private class CollapseShape(private val t: Float, private val barTopPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = with(density) { CallTokens.BarHPadding.toPx() }
        val barH = with(density) { CallTokens.BarHeight.toPx() }
        val rBar = with(density) { CallTokens.BarRadius.toPx() }
        val rect = Rect(
            left = lerp(0f, h, t), top = lerp(0f, barTopPx, t),
            right = lerp(size.width, size.width - h, t), bottom = lerp(size.height, barTopPx + barH, t),
        )
        return Outline.Rounded(RoundRect(rect, CornerRadius(lerp(0f, rBar, t))))
    }
}

/**
 * 통화 화면 전체를 감싼다. 오라는 모양으로 잘리고, 자막·칩·알약·상단바는 처음 35% 동안 사라진다.
 * 셰이더의 uRes는 계속 화면 크기라서, 마지막 순간에 띠로 바뀔 때 생기는 차이는 둘 다 어두운 배경 +
 * 아래쪽 빛이라 눈에 거의 띄지 않는다.
 */
@Composable
fun CollapseHost(
    collapse: CallCollapse,
    aura: @Composable (Modifier) -> Unit,
    content: @Composable (contentAlpha: () -> Float) -> Unit,
) {
    val density = LocalDensity.current
    val barTop = WindowInsets.statusBars.getTop(density) + with(density) { CallTokens.BarTop.toPx() }
    Box(Modifier.graphicsLayer {
        // 블록 안에서 progress를 읽는다 → 레이어 속성만 다시 계산, 재구성 없음.
        val t = collapse.progress.value
        clip = t > 0f
        shape = CollapseShape(t, barTop)
    }) {
        aura(Modifier)
        content { (1f - collapse.progress.value / 0.35f).coerceIn(0f, 1f) }
    }
}
