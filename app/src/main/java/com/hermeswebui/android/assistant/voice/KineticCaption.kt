package com.hermeswebui.android.assistant.voice

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermeswebui.android.assistant.HermesFonts
import com.hermeswebui.android.assistant.RoseType

enum class CaptionRole { PAST, USER, USER_LIVE, BOT, BOT_LIVE, BOT_CUT, NOTICE, CHIP, HINT }

@Immutable
data class CaptionItem(
    val key: String,
    val role: CaptionRole,
    val text: String,
    val cutFrom: Int = -1,          // BOT_CUT: 이 글자부터 못 한 말
    val chipMeta: String? = null,   // CHIP
)

@Immutable
sealed interface CaptionModel {
    data class Centered(val title: String, val subtitle: String, val hint: String) : CaptionModel
    data class Lines(val items: List<CaptionItem>) : CaptionModel
}

/**
 * VoiceState(+끼어들기 마크, +자막 타임라인 텍스트) → 화면 줄 목록.
 * [liveBotText]: captions의 이번 턴 텍스트. botPartial이 커밋으로 비어도 끝까지 그리려고 쓴다.
 */
fun captionModel(
    state: VoiceState,
    botName: String,
    interrupt: InterruptMark?,
    interruptedRecently: Boolean,
    liveBotText: String,
    clock: String,
): CaptionModel {
    val phase = state.phase
    val lastBot = state.transcript.lastOrNull { it.speaker == Speaker.BOT }?.text
    val lastMe = state.transcript.lastOrNull { it.speaker == Speaker.ME }?.text
    val me = state.myPartial.trim()
    val bot = (if (state.botPartial.isBlank()) liveBotText else state.botPartial).trim()
    val base = state.transcript.size
    fun past(t: String?) = t?.let { CaptionItem("t-past-$base", CaptionRole.PAST, it) }

    return when {
        phase is VoicePhase.Connecting || phase is VoicePhase.Idle ->
            CaptionModel.Centered("${botName}에게", "연결하는 중…", "잠깐이면 돼요")
        phase is VoicePhase.Reconnecting ->
            CaptionModel.Centered("잠깐 끊겼어요", "다시 연결하는 중…", "통화는 그대로 이어져요")
        phase is VoicePhase.Ended ->
            CaptionModel.Centered("통화 종료", clock, state.notice ?: "")
        interruptedRecently && interrupt != null && phase is VoicePhase.Listening -> CaptionModel.Lines(listOfNotNull(
            CaptionItem("cut-${interrupt.seq}", CaptionRole.BOT_CUT, interrupt.text.trim(), cutFrom = interrupt.revealedChars),
            CaptionItem("me-live", CaptionRole.USER_LIVE, me),
            CaptionItem("hint", CaptionRole.HINT, "말을 멈추고 듣는 중"),
        ))
        phase is VoicePhase.Delegating -> CaptionModel.Lines(listOfNotNull(
            (me.ifBlank { lastMe })?.let { CaptionItem("me-$base", CaptionRole.USER, it) },
            (bot.ifBlank { lastBot })?.let { CaptionItem("bot-$base", CaptionRole.BOT, it) },
            CaptionItem("chip-${phase.callId}", CaptionRole.CHIP, toolLabel(phase.request), chipMeta = toolMeta(phase.request)),
        ))
        phase is VoicePhase.ModelSpeaking || state.queuedChunks > 0 ||
            (bot.isNotBlank() && phase is VoicePhase.Listening && !state.awaitingReply && me.isBlank()) ->
            CaptionModel.Lines(listOfNotNull(
                (me.ifBlank { lastMe })?.let { CaptionItem("me-$base", CaptionRole.USER, it) },
                CaptionItem("bot-live", CaptionRole.BOT_LIVE, bot),
            ))
        state.muted -> CaptionModel.Lines(listOfNotNull(
            past(lastBot),
            CaptionItem("notice-mute", CaptionRole.NOTICE, "마이크가 꺼져 있어요"),
            CaptionItem("hint", CaptionRole.HINT, "음소거 버튼을 누르면 다시 들을게요"),
        ))
        else -> CaptionModel.Lines(listOfNotNull(
            past(lastBot),
            CaptionItem("me-live", CaptionRole.USER_LIVE, me),
            CaptionItem("hint", CaptionRole.HINT, when {
                state.awaitingReply -> "생각하는 중"
                me.isNotBlank() -> "나 · 말하는 중"
                else -> "듣고 있어요"
            }),
        ))
    }
}

private data class Word(val start: Int, val text: String)

private fun words(text: String): List<Word> {
    val out = ArrayList<Word>()
    var i = 0
    while (i < text.length) {
        while (i < text.length && text[i].isWhitespace()) i++
        val s = i
        while (i < text.length && !text[i].isWhitespace()) i++
        if (i > s) out += Word(s, text.substring(s, i))
    }
    return out
}

@Composable
fun CaptionArea(model: CaptionModel, revealedChars: () -> Int, calm: Boolean, modifier: Modifier = Modifier) {
    val type = CallTypeScale.current
    when (model) {
        is CaptionModel.Centered -> Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val big = RoseType.Greeting.copy(
                fontSize = type.connectTitle,
                lineHeight = (type.connectTitle.value * 1.26f).sp,
                textAlign = TextAlign.Center,
            )
            Text(model.title, style = big.copy(color = CallTokens.TextStrong))
            Text(model.subtitle, style = big.copy(color = CallTokens.TextStrong.copy(alpha = 0.42f)))
            if (model.hint.isNotBlank()) Text(
                model.hint,
                style = RoseType.Label.copy(fontSize = type.hint, color = CallTokens.CaptionHint),
                modifier = Modifier.padding(top = CallTokens.HintTopGap),
            )
        }
        is CaptionModel.Lines -> CaptionStack(model.items, revealedChars, calm, modifier)
    }
}

@Composable
private fun CaptionStack(items: List<CaptionItem>, revealedChars: () -> Int, calm: Boolean, modifier: Modifier) {
    // 화면 읽기: 단어마다 읽으면 시끄럽다. 확정된 문장만 한 덩어리로 알린다.
    val spoken = items.lastOrNull { it.role == CaptionRole.BOT || it.role == CaptionRole.PAST }?.text.orEmpty()
    val height = CallTokens.CaptionHeight
    LazyColumn(
        modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = CallTokens.CaptionHPadding)
            .semantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
        reverseLayout = true,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.Bottom,
    ) {
        items(items.asReversed(), key = { it.key }) { item ->
            Box(
                Modifier
                    .animateItem(
                        fadeInSpec = tween(220),
                        placementSpec = spring(dampingRatio = 1f, stiffness = 300f),
                        fadeOutSpec = tween(160),
                    )
                    .padding(
                        top = when (item.role) {
                            CaptionRole.CHIP -> CallTokens.ChipTopGap
                            CaptionRole.HINT -> CallTokens.HintTopGap - CallTokens.CaptionLineGap
                            else -> 0.dp
                        },
                        bottom = CallTokens.CaptionLineGap,
                    ),
            ) {
                CaptionLine(item, revealedChars, calm)
            }
        }
    }
}

@Composable
private fun CaptionLine(item: CaptionItem, revealedChars: () -> Int, calm: Boolean) {
    val type = CallTypeScale.current
    val base = RoseType.Greeting   // 앱 폰트 또는 Serif
    if (item.role == CaptionRole.CHIP) {
        WorkingChip(item.text, item.chipMeta, calm)
        return
    }
    // 역할별 목표 크기·색 → 역할이 바뀌면 부드럽게 바뀐다(BOT_LIVE → PAST).
    val (sizeSp, color) = when (item.role) {
        CaptionRole.BOT, CaptionRole.BOT_LIVE -> type.caption.value to CallTokens.TextStrong
        CaptionRole.BOT_CUT -> type.captionSecondary.value to CallTokens.TextStrong
        CaptionRole.PAST -> type.past.value to CallTokens.CaptionPast
        CaptionRole.USER -> type.user.value to CallTokens.CaptionUser
        CaptionRole.USER_LIVE -> type.captionSecondary.value to CallTokens.CaptionUserLive
        CaptionRole.NOTICE -> type.captionSecondary.value to Color(0x80FFFFFF)
        CaptionRole.HINT -> type.hint.value to CallTokens.CaptionHint
        CaptionRole.CHIP -> 0f to Color.Transparent
    }
    val animSize by animateFloatAsState(sizeSp, spring(dampingRatio = 1f, stiffness = 300f), label = "capSize")
    val animColor by animateColorAsState(color, tween(260), label = "capColor")
    val style = base.copy(
        fontSize = animSize.sp,
        lineHeight = (animSize * 1.26f).sp,
        letterSpacing = if (item.role == CaptionRole.BOT || item.role == CaptionRole.BOT_LIVE) type.captionLetterSpacing else 0.sp,
        color = animColor,
        fontWeight = if (HermesFonts.family == null &&
            item.role in setOf(CaptionRole.BOT, CaptionRole.BOT_LIVE, CaptionRole.BOT_CUT)
        ) FontWeight.SemiBold else FontWeight.Normal,
    )
    when (item.role) {
        CaptionRole.BOT_LIVE -> RevealLine(item.text, revealedChars(), style, calm)
        CaptionRole.BOT_CUT -> RevealLine(item.text, item.text.length, style, calm, cutFrom = item.cutFrom)
        CaptionRole.USER_LIVE -> UserLiveLine(item.text, style, caret = true, calm = calm)
        else -> Text(item.text, style = style)
    }
}

/** 내 말 + 깜빡이는 커서. */
@Composable
private fun UserLiveLine(text: String, style: TextStyle, caret: Boolean, calm: Boolean) {
    val density = LocalDensity.current
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        1f, if (calm) 1f else 0.15f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caretA",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.Bottom) {
        if (text.isNotEmpty()) Text(text, style = style)
        if (caret) {
            val h = with(density) { (style.fontSize.value * 0.95f).sp.toDp() }
            Box(
                Modifier
                    .padding(start = 3.dp)
                    .width(2.dp)
                    .height(h)
                    .graphicsLayer { alpha = blink }
                    .drawBehind {
                        drawRoundRect(
                            CallTokens.Caret.copy(alpha = 0.35f),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            topLeft = Offset(-3.dp.toPx(), -3.dp.toPx()),
                            size = Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()),
                        )
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(CallTokens.Caret)
                    .align(Alignment.Bottom),
            )
        }
    }
}

/**
 * 단어씩 나타나는 줄. [revealed]보다 앞에서 시작한 단어만 그린다(소리보다 먼저 나오지 않음).
 * [cutFrom] ≥ 0이면 그 뒤 단어는 "못 한 말"이라 16% + 1dp 블러로 흐려진다.
 */
@Composable
private fun RevealLine(text: String, revealed: Int, style: TextStyle, calm: Boolean, cutFrom: Int = -1) {
    val all = remember(text) { words(text) }
    val gap = with(LocalDensity.current) { (style.fontSize.value * 0.28f).sp.toDp() }
    FlowRow(
        Modifier.fillMaxWidth().clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        all.forEach { w ->
            val cut = cutFrom >= 0 && w.start >= cutFrom
            if (w.start < revealed || cut) {
                key(w.start) { RevealWord(w.text, style, calm, cut) }
            }
        }
    }
}

@Composable
private fun RevealWord(text: String, style: TextStyle, calm: Boolean, cut: Boolean) {
    val density = LocalDensity.current
    val appear = remember { Animatable(if (cut) 1f else 0f) }
    val tint = remember { Animatable(if (cut) 1f else 0f) }
    val cutP = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!cut) appear.animateTo(1f, tween(if (calm) 120 else CallTokens.WordRevealMs, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        if (!cut) tint.animateTo(1f, tween(CallTokens.WordTintMs, delayMillis = CallTokens.WordTintDelayMs))
    }
    LaunchedEffect(cut) {
        if (cut) cutP.animateTo(1f, tween(CallTokens.CutMs))
    }
    val rise = with(density) { CallTokens.WordRise.toPx() }
    val blurNow = with(density) { CallTokens.WordBlur.toPx() }
    val blurCut = with(density) { CallTokens.CutBlur.toPx() }
    Text(
        text,
        style = style.copy(color = lerp(CallTokens.CaptionNowTint, style.color, tint.value)),
        modifier = Modifier.graphicsLayer {
            val a = appear.value
            val c = cutP.value
            alpha = (CallTokens.CaptionNowStartAlpha + (1f - CallTokens.CaptionNowStartAlpha) * a) *
                (1f - (1f - CallTokens.CaptionCutAlpha) * c)
            translationY = if (calm) 0f else (1f - a) * rise
            val r = if (calm) 0f else (1f - a) * blurNow + c * blurCut
            renderEffect = if (r > 0.05f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                BlurEffect(r, r, TileMode.Decal) else null
        },
    )
}
