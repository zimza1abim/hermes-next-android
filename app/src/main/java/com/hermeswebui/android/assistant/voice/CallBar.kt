package com.hermeswebui.android.assistant.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.assistant.RoseType
import kotlinx.coroutines.delay

/** 채팅 화면 루트 맨 위에 둔다. 통화가 없으면 아무것도 그리지 않는다. */
@Composable
fun CallBarHost(modifier: Modifier = Modifier) {
    val controller by LiveController.active.collectAsState()
    val c = controller ?: return
    val state by c.state.collectAsState()
    val phase = state.phase
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(phase is VoicePhase.Ended, phase is VoicePhase.Idle) {
        visible = phase !is VoicePhase.Idle
        // 종료된 통화도 1.4초는 보여 준다(통화 화면과 같은 시간).
        if (phase is VoicePhase.Ended) { delay(1_400); visible = false }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(spring(dampingRatio = 0.86f, stiffness = 380f)),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        CallBar(c, state)
    }
}

@Composable
fun CallBar(controller: LiveController, state: VoiceState) {
    val context = LocalContext.current
    val type = CallTypeScale.current
    val botName = state.botName?.takeIf { it.isNotBlank() } ?: "Hermes"
    val shape = RoundedCornerShape(CallTokens.BarRadius)
    val animator = remember { AuraAnimator() }
    val mood = AuraMoods.moodOf(state, interruptedRecently = false)
    LaunchedEffect(mood) { animator.go(mood, calm = VoiceTokens.calmMotion(context)) }
    val frame = rememberCallFrame(
        controller.signals, animator, calm = false,
        detail = { 2f }, heightMap = { 0.18f + 0.24f * it },
    )
    Box(
        Modifier
            .statusBarsPadding()
            .padding(top = CallTokens.BarTop, start = CallTokens.BarHPadding, end = CallTokens.BarHPadding)
            .fillMaxWidth()
            .height(CallTokens.BarHeight)
            .shadow(28.dp, shape, ambientColor = CallTokens.BarShadow, spotColor = CallTokens.BarShadow)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "통화 화면 열기") {
                VoiceActivity.start(context, state.botName, fromBar = true)
            }
            .semantics(mergeDescendants = true) { contentDescription = "$botName 통화 중, 누르면 통화 화면으로" },
    ) {
        AuraLayer(
            uniforms = frame.uniforms,
            frameTick = { frame.frameTick },
            palette = controller.palette,
            modifier = Modifier.matchParentSize(),
            mini = true,
        )
        Box(Modifier.matchParentSize().border(1.dp, CallTokens.BarInnerBorder, shape))
        Row(
            Modifier.matchParentSize().padding(start = CallTokens.BarPadStart, end = CallTokens.BarPadEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CallTokens.BarGap),
        ) {
            AvatarDot(avatar = null, size = CallTokens.BarAvatar)
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(botName, style = RoseType.Greeting.copy(fontSize = type.barName, color = CallTokens.TextStrong), maxLines = 1)
                Text(callStatus(state), style = RoseType.Label.copy(fontSize = type.barStatus, color = CallTokens.TextMuted), maxLines = 1)
            }
            BarTimer(controller.callStartedAtMs)
            Box(
                Modifier.size(CallTokens.BarEnd).clip(CircleShape).background(CallTokens.HangUp)
                    .clickable(role = Role.Button, onClickLabel = "통화 끊기") { controller.hangUp() }
                    .semantics { contentDescription = "통화 끊기" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.CallEnd, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun BarTimer(startedAt: Long) {
    val type = CallTypeScale.current
    var now by remember { mutableLongStateOf(startedAt) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Text(
        formatClock(if (startedAt == 0L) 0 else (now - startedAt).coerceAtLeast(0L)),
        style = RoseType.Meta.copy(fontSize = type.barTimer, color = CallTokens.BarTimer),
    )
}
