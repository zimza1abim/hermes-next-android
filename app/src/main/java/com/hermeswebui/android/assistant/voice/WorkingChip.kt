package com.hermeswebui.android.assistant.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.assistant.RoseType
import kotlin.math.cos
import kotlin.math.sin

/** 요청 문장 → 칩 문구. 순서대로 처음 맞는 것. */
fun toolLabel(request: String): String {
    val r = request.lowercase()
    return when {
        listOf("캘린더", "일정", "스케줄", "calendar").any { it in r } -> "캘린더 확인 중"
        listOf("메일", "이메일", "mail").any { it in r } -> "메일 확인 중"
        listOf("날씨", "weather").any { it in r } -> "날씨 보는 중"
        listOf("검색", "찾아", "search").any { it in r } -> "찾아보는 중"
        listOf("파일", "문서", "노트").any { it in r } -> "문서 여는 중"
        else -> "Hermes가 확인 중"
    }
}

/** 칩 보조 글자: 요청 18자(기존 statusLine과 같은 길이). */
fun toolMeta(request: String): String? = request.trim().take(18).takeIf { it.isNotBlank() }

@Composable
fun WorkingChip(label: String, meta: String?, calm: Boolean) {
    val type = CallTypeScale.current
    val shape = RoundedCornerShape(CallTokens.ChipRadius)
    val infinite = rememberInfiniteTransition(label = "chip")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(CallTokens.SpinnerMs, easing = LinearEasing)), label = "spin")
    val shimmer by infinite.animateFloat(-1f, 1.6f, infiniteRepeatable(tween(CallTokens.ChipShimmerMs, easing = LinearEasing), RepeatMode.Restart), label = "shim")
    Row(
        Modifier
            .clip(shape)
            .background(CallTokens.ChipBg)
            .border(1.dp, CallTokens.ChipBorder, shape)
            .drawWithContent {
                drawContent()
                if (!calm) {
                    // 100° 방향 빛 띠: 1.6초마다 왼쪽에서 오른쪽으로
                    val x = size.width * shimmer
                    drawRect(
                        Brush.linearGradient(
                            0f to Color.Transparent, 0.5f to CallTokens.ChipShimmer, 1f to Color.Transparent,
                            start = Offset(x - size.width * 0.4f, 0f),
                            end = Offset(x + size.width * 0.4f, size.height * 0.18f),
                        ),
                    )
                }
            }
            .padding(
                start = CallTokens.ChipPadStart, end = CallTokens.ChipPadEnd,
                top = CallTokens.ChipPadV, bottom = CallTokens.ChipPadV,
            )
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(label, meta).joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(CallTokens.ChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(CallTokens.ChipSpinner)) {
            val stroke = CallTokens.ChipSpinnerStroke.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawCircle(CallTokens.ChipSpinnerTrack, radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (calm) {
                repeat(3) { i ->
                    val a = Math.toRadians((i * 120.0) - 90.0)
                    drawCircle(
                        CallTokens.AccentHighlight, stroke * 0.7f,
                        center + Offset(
                            (cos(a) * (size.width / 2 - inset)).toFloat(),
                            (sin(a) * (size.height / 2 - inset)).toFloat(),
                        ),
                    )
                }
            } else {
                drawArc(
                    CallTokens.AccentHighlight, startAngle = spin - 90f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(label, style = RoseType.Label.copy(fontSize = type.chip, color = CallTokens.TextStrong))
        if (meta != null) Text(
            "· $meta",
            style = RoseType.Label.copy(fontSize = type.chipMeta, color = CallTokens.TextFaint),
            maxLines = 1,
        )
    }
}
