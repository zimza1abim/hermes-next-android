package com.hermeswebui.android.assistant.voice

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/** 셰이더에 넘기는 한 프레임 값. Kotlin 쪽에서 음량과 곱한 뒤의 최종값이다. */
class AuraUniforms {
    var time = 0f
    var flow = 0f
    var height = 0.05f
    var swell = 0f
    var ripple = 0f
    var sweep = 0f
    var flash = 0f
    var dir = 0f
    var brightness = 0.75f
    var saturation = 1f
    var detail = 4f
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class AuraShader(palette: AuraPalette, private val mini: Boolean) {
    val shader = RuntimeShader(AuraAgsl.SOURCE)
    val brush = ShaderBrush(shader)

    init { setPalette(palette) }

    fun setPalette(p: AuraPalette) {
        shader.setColorUniform("cA", p.a.toArgb())
        shader.setColorUniform("cB", p.b.toArgb())
        shader.setColorUniform("cC", p.c.toArgb())
        shader.setColorUniform("cD", p.d.toArgb())
    }

    /** [screenW]/[screenH]: 기준 화면 크기. [originX]/[originY]: 이 레이어의 화면 좌표(알약 배경 블러용). */
    fun update(u: AuraUniforms, screenW: Float, screenH: Float, originX: Float = 0f, originY: Float = 0f) {
        shader.setFloatUniform("uRes", screenW, screenH)
        shader.setFloatUniform("uOrigin", originX, originY)
        shader.setFloatUniform("uTime", u.time)
        shader.setFloatUniform("uFlow", u.flow)
        shader.setFloatUniform("uH", u.height)
        shader.setFloatUniform("uSwell", u.swell)
        shader.setFloatUniform("uRipple", u.ripple)
        shader.setFloatUniform("uSweep", u.sweep)
        shader.setFloatUniform("uFlash", u.flash)
        shader.setFloatUniform("uDir", u.dir)
        shader.setFloatUniform("uBright", u.brightness)
        shader.setFloatUniform("uSat", u.saturation)
        shader.setFloatUniform("uMini", if (mini) 1f else 0f)
        shader.setFloatUniform("uDetail", u.detail)
    }
}

/**
 * 오라 한 장. [uniforms]는 프레임 루프가 매 프레임 채운다. 여기서는 그리기 단계에서만 읽으므로
 * 값이 바뀌어도 재구성이 일어나지 않는다. [frameTick]은 그리기를 무효화하는 용도로만 읽는다.
 */
@Composable
fun AuraLayer(
    uniforms: AuraUniforms,
    frameTick: () -> Long,
    palette: AuraPalette,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    /** 화면 좌표 기준 크기. null이면 자기 크기. */
    screenSize: (() -> Size)? = null,
    origin: () -> Offset = { Offset.Zero },
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val aura = remember(mini) { AuraShader(palette, mini) }
        SideEffect { aura.setPalette(palette) }
        Box(modifier.drawBehind {
            frameTick()
            val ref = screenSize?.invoke() ?: size
            val o = origin()
            aura.update(uniforms, ref.width, ref.height, o.x, o.y)
            drawRect(aura.brush)
        })
    } else {
        FallbackAura(uniforms, frameTick, palette, modifier, mini)
    }
}

/**
 * API 33 미만. RuntimeShader가 없으니 방사형 그라데이션 3개와 윗선 띠로 흉내 낸다.
 * 같은 AuraUniforms를 읽어 상태별 차이(높이·부풂·밝기·채도·섬광)는 그대로 살린다.
 */
@Composable
private fun FallbackAura(
    u: AuraUniforms,
    frameTick: () -> Long,
    palette: AuraPalette,
    modifier: Modifier,
    mini: Boolean,
) {
    val blur = if (!mini && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        Modifier.blur(40.dp, BlurredEdgeTreatment.Unbounded) else Modifier
    Canvas(modifier.then(blur)) {
        frameTick()
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(CallTokens.Base, CallTokens.BaseDeep)))
        val edgeY = h * (1f - u.height - 0.10f * u.swell)
        val drift = sin(u.flow * 0.3f)
        val matrix = ColorMatrix().apply { setToSaturation(u.saturation) }
        val filter = ColorFilter.colorMatrix(matrix)
        val k = u.brightness
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.02f to palette.b.copy(alpha = 0.55f * k),
                0.25f to palette.a.copy(alpha = 0.75f * k),
                1f to lerp(palette.a, palette.d, 0.5f).copy(alpha = 0.35f * k),
                startY = edgeY - h * 0.04f, endY = h,
            ),
            colorFilter = filter,
        )
        listOf(
            Triple(0.25f + 0.08f * drift, 0.15f, palette.c),
            Triple(0.70f - 0.06f * drift, 0.08f, palette.d),
            Triple(0.50f + 0.10f * sin(u.flow * 0.17f + 1f), 0.25f, palette.b),
        ).forEach { (x, dy, color) ->
            val center = Offset(w * x, edgeY + h * dy)
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.45f * k), Color.Transparent), center, w * 0.55f),
                radius = w * 0.55f, center = center, colorFilter = filter,
            )
        }
        val crestAlpha = (0.35f + 0.25f * u.swell + 0.5f * u.flash) * k
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.5f to palette.c.copy(alpha = crestAlpha), 1f to Color.Transparent,
                startY = edgeY - h * 0.03f, endY = edgeY + h * 0.03f,
            ),
            colorFilter = filter,
        )
        if (u.sweep > 0.01f) {
            val sx = (((u.time * 0.18f + 0.35f) % 1f) * 1.8f - 0.4f) * w
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent, 0.5f to palette.c.copy(alpha = 0.35f * u.sweep * k), 1f to Color.Transparent,
                    startX = sx - w * 0.25f, endX = sx + w * 0.25f,
                ),
                topLeft = Offset(0f, edgeY - h * 0.05f),
                size = Size(w, h * 0.10f),
                colorFilter = filter,
            )
        }
    }
}
