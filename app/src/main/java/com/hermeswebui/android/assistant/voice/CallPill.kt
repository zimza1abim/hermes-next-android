package com.hermeswebui.android.assistant.voice

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun CallPill(
    muted: Boolean,
    ended: Boolean,
    actions: VoiceCallActions,
    /** 알약 뒤 오라를 블러로 다시 그리는 람다(API 31+). null이면 반투명 틴트만. */
    backdrop: (@Composable (origin: () -> Offset, modifier: Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(CallTokens.PillRadius)
    val origin = remember { floatArrayOf(0f, 0f) }
    Box(
        modifier
            .height(CallTokens.PillHeight)
            .shadow(40.dp, shape, ambientColor = CallTokens.PillShadow, spotColor = CallTokens.PillShadow)
            .clip(shape)
            .onGloballyPositioned { val p = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y },
    ) {
        if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurPx = with(LocalDensity.current) { CallTokens.PillBlur.toPx() }
            backdrop({ Offset(origin[0], origin[1]) }, Modifier.matchParentSize().graphicsLayer {
                val sat = android.graphics.ColorMatrix().apply { setSaturation(CallTokens.PillSaturation) }
                renderEffect = android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(sat)),
                    android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP),
                ).asComposeRenderEffect()
                clip = true
                this.shape = shape
            })
            Box(Modifier.matchParentSize().background(CallTokens.PillTint))
        } else {
            Box(Modifier.matchParentSize().background(CallTokens.PillTintNoBlur))
        }
        // 테두리 14% + 위쪽 안쪽 하이라이트 1dp
        Box(
            Modifier.matchParentSize().border(1.dp, CallTokens.PillBorder, shape).drawWithContent {
                drawContent()
                drawLine(
                    CallTokens.PillHighlight,
                    Offset(size.height / 2, 1.dp.toPx()),
                    Offset(size.width - size.height / 2, 1.dp.toPx()),
                    1.dp.toPx(),
                )
            },
        )
        Row(
            Modifier.padding(horizontal = CallTokens.PillPadH).height(CallTokens.PillHeight),
            horizontalArrangement = Arrangement.spacedBy(CallTokens.PillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                icon = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                label = if (muted) "음소거 해제" else "음소거",
                state = if (muted) "마이크 꺼짐" else "마이크 켜짐",
                on = muted,
                onClick = { CallHaptics.toggle(view); actions.onMute() },
                onLongClick = { CallHaptics.longPress(view); actions.onSpeaker() },
                longLabel = "스피커 전환",
            )
            PillButton(
                icon = Icons.Rounded.Keyboard, label = "글자로 입력", state = null, on = false,
                onClick = { CallHaptics.toggle(view); actions.onType() },
                onLongClick = { CallHaptics.longPress(view); actions.onSubtitles() },
                longLabel = "자막 켜기 또는 끄기",
            )
            HangUpButton(enabled = !ended) { CallHaptics.hangUp(view); actions.onHangUp() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillButton(
    icon: ImageVector,
    label: String,
    state: String?,
    on: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    longLabel: String,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.42f, stiffness = 900f),
        label = "pill",
    )
    Box(
        Modifier
            .size(CallTokens.PillButton)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (on) CallTokens.PillButtonOn else CallTokens.PillButtonBg)
            .combinedClickable(
                interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = label, onLongClickLabel = longLabel,
                onClick = onClick, onLongClick = onLongClick,
            )
            .semantics { contentDescription = label; if (state != null) stateDescription = state },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, null,
            tint = if (on) CallTokens.PillButtonOnIcon else CallTokens.TextStrong,
            modifier = Modifier.size(CallTokens.PillIcon),
        )
    }
}

@Composable
private fun HangUpButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(CallTokens.HangUpRadius)
    Bouncy(onClick, "통화 끊기", enabled = enabled) {
        Box(
            Modifier
                .size(CallTokens.HangUpWidth, CallTokens.HangUpHeight)
                .shadow(22.dp, shape, ambientColor = CallTokens.HangUpShadow, spotColor = CallTokens.HangUpShadow)
                .clip(shape)
                .background(CallTokens.HangUp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(CallTokens.PillIcon))
        }
    }
}
