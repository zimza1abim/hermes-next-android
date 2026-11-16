package com.hermeswebui.android.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.launch

/*
 * Motion rule for this file: a value that changes every frame is read only while drawing (inside
 * `graphicsLayer {}`, `drawBehind {}` or a Canvas), never while composing. A frame of animation then
 * redraws one small layer and recomposes nothing, which is what keeps the panel smooth at 120 Hz.
 */

/** The pearl tones that flow over borders and sheens: rose, lilac, mint, peach. */
internal val Pearl = listOf(Color(0xFFF3BED4), Color(0xFFDCC6F5), Color(0xFFBFE6EE), Color(0xFFFBE2C4))
internal val Lilac = Color(0xFF9A86D6)

/** True when the system's animation scale is off: flowing light stops, state changes stay. */
internal val LocalCalmMotion = staticCompositionLocalOf { false }

/** 0 to 1 over [periodMs], repeating; stays 0 in calm motion. Read it only while drawing. */
@Composable
internal fun flowing(periodMs: Int, label: String, running: Boolean = true): State<Float> {
    // A stopped light asks for no frames at all; an unread infinite transition would still tick every frame.
    if (LocalCalmMotion.current || !running) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = label)
        .animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label)
}

/** A pearl brush that slides along x as [phase] goes 0 to 1, repeating seamlessly. */
internal fun pearlBrush(phase: Float, span: Float = 600f): Brush {
    val shift = phase * span
    return Brush.linearGradient(
        Pearl + Pearl.first(),
        start = Offset(-shift, 0f),
        end = Offset(span - shift, span * 0.2f),
        tileMode = TileMode.Repeated,
    )
}

/** A light streak that crosses the element once a cycle; drawn over it, so colours below keep their contrast. */
internal fun Modifier.pearlSheen(phase: () -> Float): Modifier = drawWithContent {
    drawContent()
    // The streak crosses in the first 60% of the cycle, then rests.
    val t = phase() / 0.6f
    if (t >= 1.2f) return@drawWithContent
    val w = size.width
    val x = -w * 0.6f + t * w * 2f
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.45f to Color.White.copy(alpha = 0.7f),
            0.55f to Pearl[1].copy(alpha = 0.55f),
            1f to Color.Transparent,
            start = Offset(x, 0f),
            end = Offset(x + w * 0.5f, size.height),
        )
    )
}

/** A rounded outline in flowing pearl, drawn over the element's own border. */
internal fun Modifier.pearlBorder(width: Dp, radius: Dp, phase: () -> Float): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    drawRoundRect(
        pearlBrush(phase(), span = size.width.coerceAtLeast(1f)),
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((radius.toPx() - stroke / 2).coerceAtLeast(0f)),
        style = Stroke(stroke),
    )
}

/** The thin pearl line along the panel's top edge. */
@Composable
internal fun PearlEdge(modifier: Modifier = Modifier) {
    val phase = flowing(4_000, "edge")
    Box(modifier.size(width = 320.dp, height = 2.dp).drawBehind {
        drawRoundRect(pearlBrush(phase.value), cornerRadius = CornerRadius(size.height / 2), alpha = 0.9f)
    })
}

private val HEART = listOf(
    1 to 0, 2 to 0, 4 to 0, 5 to 0,
    0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1, 6 to 1,
    0 to 2, 1 to 2, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2,
    1 to 3, 2 to 3, 3 to 3, 4 to 3, 5 to 3,
    2 to 4, 3 to 4, 4 to 4,
    3 to 5,
)

/**
 * The app's own character, cut out of its icon, in a round badge. It floats gently, pops in when the
 * panel opens, breathes inside a turning pearl ring with twinkling pixels while connecting or
 * answering, and lets a few pixel hearts rise each time an answer finishes.
 */
@Composable
internal fun AssistantAvatar(working: Boolean, answersDone: Int, size: Dp = 44.dp) {
    val calm = LocalCalmMotion.current
    val loop = rememberInfiniteTransition(label = "avatar")
    val float = loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val breath = loop.animateFloat(1f, 1.06f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val orbit = loop.animateFloat(0f, 360f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "orbit")

    val pop = remember { Animatable(if (calm) 1f else 0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }

    val burst = remember { Animatable(1f) }
    val seen = remember { intArrayOf(answersDone) }
    LaunchedEffect(answersDone) {
        if (answersDone > seen[0] && !calm) {
            burst.snapTo(0f)
            launch { burst.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) }
        }
        seen[0] = answersDone
    }

    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        if (working) Canvas(Modifier.size(size + 8.dp)) {
            rotate(if (calm) 0f else orbit.value) {
                drawCircle(
                    Brush.sweepGradient(Pearl + Pearl.first()),
                    radius = this.size.minDimension / 2 - 1.5.dp.toPx(),
                    style = Stroke(3.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    translationY = if (calm) 0f else float.value * 1.6.dp.toPx()
                    val scale = (0.6f + 0.4f * pop.value) * (if (working && !calm) breath.value else 1f)
                    scaleX = scale
                    scaleY = scale
                    rotationZ = (1f - pop.value) * -14f
                }
                .clip(CircleShape)
                .background(Rose.Field)
                .border(1.5.dp, Rose.Border, CircleShape),
        ) {
            Image(
                painterResource(R.drawable.assistant_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(top = 3.dp).size(size),
            )
        }
        Canvas(Modifier.size(size + 8.dp)) {
            val t = burst.value
            if (t >= 1f) return@Canvas
            val cell = 1.6.dp.toPx()
            floatArrayOf(-0.9f, -0.3f, 0.35f, 0.95f).forEachIndexed { i, side ->
                val lag = (t - i * 0.08f).coerceIn(0f, 1f)
                if (lag <= 0f) return@forEachIndexed
                val x = center.x + side * this.size.width * 0.45f - 3.5f * cell
                val y = center.y - lag * this.size.height * 0.9f
                val color = (if (i % 2 == 0) Rose.Rose else Lilac).copy(alpha = 1f - lag)
                HEART.forEach { (hx, hy) -> drawRect(color, Offset(x + hx * cell, y + hy * cell), Size(cell, cell)) }
            }
        }
        if (working && !calm) {
            Sparkle(Lilac.copy(alpha = 0.8f), 9.dp, 200, Modifier.align(Alignment.TopStart))
            Sparkle(Pearl[2], 7.dp, 1_100, Modifier.align(Alignment.TopEnd).padding(top = 8.dp))
            Sparkle(Pearl[0], 8.dp, 1_800, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** A pixel cross that twinkles in and out, [delayMs] after its siblings. */
@Composable
private fun Sparkle(color: Color, size: Dp, delayMs: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spark").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), initialStartOffset = StartOffset(delayMs)), label = "spark",
    )
    Canvas(modifier.size(size).graphicsLayer {
        val glow = (1f - abs(t.value - 0.5f) * 2f).coerceIn(0f, 1f)
        alpha = glow
        scaleX = 0.4f + 0.6f * glow
        scaleY = 0.4f + 0.6f * glow
    }) {
        val c = this.size.width / 5f
        drawRect(color, Offset(2 * c, 0f), Size(c, 5 * c))
        drawRect(color, Offset(0f, 2 * c), Size(5 * c, c))
    }
}

/** The link's square status dot; it blinks while something is in progress. */
@Composable
internal fun StatusDot(color: Color, blinking: Boolean) {
    val alpha = rememberInfiniteTransition(label = "dot")
        .animateFloat(1f, 0.25f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "blink")
    val calm = LocalCalmMotion.current
    Box(Modifier.size(6.dp).graphicsLayer { this.alpha = if (blinking && !calm) alpha.value else 1f }
        .clip(RoundedCornerShape(1.dp)).background(color))
}

/** The greeting's pixel heart, beating softly. */
@Composable
internal fun BeatingHeart(modifier: Modifier = Modifier) {
    val beat = rememberInfiniteTransition(label = "heart")
        .animateFloat(1f, 1.18f, infiniteRepeatable(tween(560, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beat")
    val calm = LocalCalmMotion.current
    PixelHeart(13.dp, modifier.graphicsLayer {
        val scale = if (calm) 1f else beat.value
        scaleX = scale
        scaleY = scale
    })
}

/** "생각하는 중": the pixel flower turns in quarter steps, light crosses the words, three pixels hop. */
@Composable
internal fun ThinkingIndicator() {
    val calm = LocalCalmMotion.current
    val loop = rememberInfiniteTransition(label = "thinking")
    val turn = loop.animateFloat(0f, 4f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "turn")
    val wave = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "hop")
    val light = flowing(2_000, "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelFlower(16.dp, Modifier.graphicsLayer { rotationZ = if (calm) 0f else floor(turn.value) * 90f })
        Text(
            "생각하는 중",
            style = RoseType.Label,
            modifier = Modifier.padding(start = 8.dp)
                // Offscreen, so the light below tints only the letters.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (calm) return@drawWithContent
                    val w = size.width
                    val x = -w + light.value * 3f * w
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Lilac, Rose.Deep, Color.Transparent),
                            start = Offset(x, 0f),
                            end = Offset(x + w * 0.8f, 0f),
                        ),
                        blendMode = BlendMode.SrcAtop,
                    )
                },
        )
        Row(Modifier.padding(start = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i ->
                Box(Modifier.size(4.dp).graphicsLayer {
                    val phase = ((wave.value - i * 0.18f) % 1f + 1f) % 1f
                    val hop = if (calm || phase >= 0.4f) 0f else sin(phase / 0.4f * PI.toFloat())
                    translationY = -hop * 4.dp.toPx()
                }.background(if (i == 1) Lilac else Rose.Rose))
            }
        }
    }
}

/**
 * The part of [target] to show now. Chunks arrive in bursts; the text on screen flows at a calm,
 * steady pace measured in time, not frames (so a 120 Hz screen is not twice as fast): about
 * [BASE_CPS] characters a second, speeding up gently only when far behind, up to [MAX_CPS].
 * Calm motion shows everything at once.
 */
@Composable
internal fun smoothReveal(key: String, target: String): String {
    val calm = LocalCalmMotion.current
    // Text already there when this first composes (a reopened panel, an item scrolled back into view) is
    // shown at once; only text that arrives later is revealed.
    val shown = remember(key) { mutableIntStateOf(target.length) }
    val latest by rememberUpdatedState(target)
    val onScreen = remember(key) { arrayOf(target) }
    LaunchedEffect(key, target.length, calm) {
        if (calm) {
            shown.intValue = target.length
            return@LaunchedEffect
        }
        var carry = 0f
        var last = withFrameNanos { it }
        while (true) {
            val goal = latest
            var at = shown.intValue.coerceAtMost(goal.length)
            // A final text that replaced the streamed one continues from where the two still agree.
            if (goal !== onScreen[0]) {
                if (at > 0 && !goal.regionMatches(0, onScreen[0], 0, at)) at = goal.commonPrefixWith(onScreen[0]).length.coerceAtMost(at)
                onScreen[0] = goal
            }
            if (at >= goal.length) {
                shown.intValue = goal.length
                break
            }
            val now = withFrameNanos { it }
            val seconds = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            carry += (BASE_CPS + (goal.length - at) * CATCH_UP).coerceAtMost(MAX_CPS) * seconds
            val step = carry.toInt()
            if (step == 0) continue
            carry -= step
            var next = (at + step).coerceAtMost(goal.length)
            if (next < goal.length && Character.isHighSurrogate(goal[next - 1])) next++
            shown.intValue = next
        }
    }
    return target.take(shown.intValue.coerceAtMost(target.length))
}

/** Characters a second when the text on screen has caught up with what arrived. */
private const val BASE_CPS = 24f
/** Extra characters a second for each character still waiting, so a long burst is not left far behind. */
private const val CATCH_UP = 0.6f
private const val MAX_CPS = 120f
