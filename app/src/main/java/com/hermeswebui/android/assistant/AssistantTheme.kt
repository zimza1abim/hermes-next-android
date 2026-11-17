package com.hermeswebui.android.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rose light, fixed: the panel stays light in system dark mode. Text and icons on a filled button use
 * [Deep] (4.9:1 under white); [Rose] is for flowers, borders and the caret only.
 */
internal object Rose {
    val Panel = Color(0xFFFFF9FC)
    val Soft = Color(0xFFFAEDF3)
    val Bubble = Color(0xFFF9E1ED)
    val Rose = Color(0xFFE8A1C0)
    val Petal = Color(0xFFF3BED4)
    val Deep = Color(0xFFB04F7D)
    val ChipText = Color(0xFF8E3F66)
    val Text = Color(0xFF643C50)
    /** Bold text and headings: a step darker than [Text], so emphasis reads at a glance. */
    val Strong = Color(0xFF4A2638)
    val Muted = Color(0xFF866173)
    val Border = Color(0xFFEED6E2)
    val Dots = Color(0xFFEFCFDD)
    val Field = Color(0xFFFFFCFD)
    val Online = Color(0xFF4F9A72)
    val Scrim = Color(0x385C3448)
    val Disabled = Color(0xFFF3D5E3)
}

/**
 * Every text style of the native screens. With a font picked in the app ([HermesFonts]) all of them use
 * it; without one, chat text uses the phone's own font and the display styles keep their own faces.
 * Read during composition, so a new pick redraws the text.
 */
internal object RoseType {
    private val picked: FontFamily? get() = HermesFonts.family
    val Body: TextStyle get() = TextStyle(fontFamily = picked, fontSize = 16.sp, lineHeight = 26.sp, color = Rose.Text)
    val Wordmark: TextStyle get() = TextStyle(fontFamily = picked ?: FontFamily.Serif, fontStyle = if (picked == null) FontStyle.Italic else FontStyle.Normal,
        fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Rose.Text)
    val Greeting: TextStyle get() = TextStyle(fontFamily = picked ?: FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp, color = Rose.Text)
    val Meta: TextStyle get() = TextStyle(fontFamily = picked ?: FontFamily.Monospace, fontSize = 10.5.sp, letterSpacing = 0.6.sp, color = Rose.Muted)
    val Label: TextStyle get() = TextStyle(fontFamily = picked, fontSize = 13.sp, color = Rose.Muted)
}

private val PETALS = listOf(
    1 to 0, 2 to 0, 4 to 0, 5 to 0,
    0 to 1, 1 to 1, 2 to 1, 4 to 1, 5 to 1, 6 to 1,
    0 to 2, 1 to 2, 5 to 2, 6 to 2,
    0 to 4, 1 to 4, 5 to 4, 6 to 4,
    0 to 5, 1 to 5, 2 to 5, 4 to 5, 5 to 5, 6 to 5,
    1 to 6, 2 to 6, 4 to 6, 5 to 6,
)

private val HEART = listOf(
    1 to 0, 2 to 0, 4 to 0, 5 to 0,
    0 to 1, 1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1, 6 to 1,
    0 to 2, 1 to 2, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2,
    1 to 3, 2 to 3, 3 to 3, 4 to 3, 5 to 3,
    2 to 4, 3 to 4, 4 to 4,
    3 to 5,
)

/** The 7×7 pixel flower: four petals around a deep rose centre with one highlight pixel. */
@Composable
internal fun PixelFlower(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val cell = this.size.width / 7f
        val px = Size(cell, cell)
        PETALS.forEach { (x, y) -> drawRect(Rose.Petal, Offset(x * cell, y * cell), px) }
        drawRect(Rose.Deep, Offset(2 * cell, 2 * cell), Size(3 * cell, 3 * cell))
        drawRect(Color.White.copy(alpha = 0.7f), Offset(2 * cell, 2 * cell), px)
    }
}

@Composable
internal fun PixelHeart(width: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width, width * 6f / 7f)) {
        val cell = this.size.width / 7f
        HEART.forEach { (x, y) -> drawRect(Rose.Rose, Offset(x * cell, y * cell), Size(cell, cell)) }
    }
}

/** The faint dot grid at the top of the panel, fading out downwards. */
@Composable
internal fun DotGrid(modifier: Modifier = Modifier, spacing: Dp = 11.dp) {
    Canvas(modifier) {
        val step = spacing.toPx()
        val radius = 1.1.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            val alpha = (1f - y / size.height).coerceIn(0f, 1f)
            var x = step / 2
            while (x < size.width) {
                drawCircle(Rose.Dots.copy(alpha = alpha), radius, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}
