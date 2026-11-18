package com.hermeswebui.android.assistant.voice

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.assistant.RoseType
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallTopBar(
    state: VoiceState,
    botName: String,
    avatar: ImageBitmap?,
    startedAt: Long,
    accent: Color,
    onSettings: () -> Unit = {},
) {
    val type = CallTypeScale.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CallTokens.ScreenHPadding)
            .padding(top = CallTokens.TopBarTop)
            .height(CallTokens.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarDot(avatar, CallTokens.AvatarDot)
        Spacer(Modifier.size(CallTokens.NameGap))
        // 이름을 길게 누르면 음성 설정: 알약에는 세 버튼만 남기고 기능은 하나도 버리지 않는다.
        Text(
            botName,
            style = RoseType.Greeting.copy(fontSize = type.name, color = CallTokens.TextStrong),
            maxLines = 1,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = onSettings,
                onLongClickLabel = "음성 설정",
                role = Role.Button,
            ),
        )
        Spacer(Modifier.size(CallTokens.StatusGap + 2.dp))
        Row(
            Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CallTokens.StatusGap),
        ) {
            StatusDot(state, accent, CallTokens.StatusDot)
            Text(callStatus(state), style = RoseType.Label.copy(fontSize = type.status, color = CallTokens.TextMuted), maxLines = 1)
        }
        CallTimer(startedAt)
    }
}

/** 기존 statusLine에서 Delegating의 요청문을 뺐다(칩으로 옮김). */
fun callStatus(state: VoiceState): String = when (val p = state.phase) {
    is VoicePhase.Delegating -> "Hermes가 확인 중"
    VoicePhase.Listening -> when {
        state.muted -> "음소거됨"
        state.awaitingReply -> "생각하는 중"
        else -> "듣고 있어요"
    }
    VoicePhase.Connecting -> "연결하는 중"
    else -> statusLine(state)
}

/** 타이머만 1초마다 재구성된다(화면 전체가 아니라). */
@Composable
fun CallTimer(startedAt: Long) {
    val type = CallTypeScale.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000 - (now - startedAt).mod(1_000L))
        }
    }
    val text = formatClock(if (startedAt == 0L) 0 else now - startedAt)
    val shape = RoundedCornerShape(CallTokens.TimerRadius)
    Box(
        Modifier.clip(shape).background(CallTokens.TimerBg).border(1.dp, CallTokens.TimerBorder, shape)
            .padding(horizontal = CallTokens.TimerPadH, vertical = CallTokens.TimerPadV)
            .semantics { contentDescription = "통화 시간 $text" },
    ) {
        Text(
            text,
            style = RoseType.Meta.copy(
                fontSize = type.timer,
                color = CallTokens.TextMuted,
                letterSpacing = CallTokens.TimerLetterSpacing,
            ),
        )
    }
}

/** 22dp 프사 점. 프사가 없으면 앱 목록과 같은 흰 꽃을 분홍 원 위에 그린다. */
@Composable
fun AvatarDot(avatar: ImageBitmap?, size: Dp) {
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val glow = CallTokens.AvatarGlow.toPx()
                drawCircle(
                    Brush.radialGradient(
                        listOf(CallTokens.AvatarGlowColor, Color.Transparent),
                        center,
                        this.size.minDimension / 2 + glow,
                    ),
                    radius = this.size.minDimension / 2 + glow,
                )
            }
            .border(CallTokens.AvatarRing, CallTokens.AvatarRingColor, CircleShape)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Canvas(Modifier.size(size)) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(CallTokens.AccentHighlight, CallTokens.AvatarCore),
                        center = Offset(this.size.width * 0.35f, this.size.height * 0.30f),
                        radius = this.size.width * 0.7f,
                    ),
                )
                val u = this.size.width / 24f * 0.72f
                repeat(5) { i ->
                    rotate(i * 72f, center) {
                        drawOval(
                            Color.White,
                            topLeft = Offset(center.x - 4.1f * u, center.y - 11.4f * u),
                            size = Size(8.2f * u, 11.6f * u),
                        )
                    }
                }
                drawCircle(CallTokens.AvatarCore, radius = 2.6f * u, center = center)
            }
        }
    }
}
