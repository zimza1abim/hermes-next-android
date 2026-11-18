package com.hermeswebui.android.assistant.voice

import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.abs

/**
 * Every colour, size and duration the call screen uses, in one place.
 *
 * The design brief asks for a dark, quiet room with the bot's own colour in it: one accent is derived
 * from the bot's picture, and the glow, the petals and the background all read from that accent (plus
 * [base] for the state tints). Swapping the accent is enough to give a bot its own atmosphere, so the
 * values live here rather than inside the drawing code.
 */
object VoiceTokens {

    /** The room: nearly black, never pure black, so the glow has something to sit on. */
    val Base = Color(0xFF0B0B10)

    val TextStrong = Color(0xEBFFFFFF) // 92%
    val TextMuted = Color(0x99FFFFFF) // 60%
    val TextFaint = Color(0x66FFFFFF) // 40%

    /** Glass controls: white at 8% over the room, with a 10% hairline. */
    val Glass = Color(0x14FFFFFF)
    val GlassBorder = Color(0x1AFFFFFF)
    val GlassActive = Color(0x2EFFFFFF)

    val HangUp = Color(0xFFFF3B30)
    val Error = Color(0xFFFF6B6B)
    val Reconnecting = Color(0xFF8E8E93)

    // Sizes, in dp. The brief fixes these, so they are not per-screen choices.
    const val AvatarDp = 160
    const val ButtonDp = 64
    const val HangUpDp = 72
    const val PetalCount = 8
    const val CallerBars = 5

    // Motion. Springs everywhere; the numbers are the brief's 200–400 ms window.
    const val EnterMs = 400
    const val StateMs = 260
    const val VolumeUpMs = 80
    const val VolumeDownMs = 250

    /** The accent a bot starts with before its picture is read: warm, not a system blue. */
    val DefaultAccent = Color(0xFFB04F7D)

    /**
     * One saturated colour from a bot's picture, averaged over a tiny downscale.
     *
     * Cheap enough to run when the call screen is built (a 24×24 read), and stable enough that the same
     * picture always yields the same room colour.
     */
    fun accentFrom(bitmap: ImageBitmap?): Color {
        if (bitmap == null) return DefaultAccent
        val source = runCatching { bitmap.asAndroidBitmap() }.getOrNull() ?: return DefaultAccent
        val sample = runCatching {
            android.graphics.Bitmap.createScaledBitmap(source, 24, 24, true)
        }.getOrNull() ?: return DefaultAccent
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var weight = 0.0
        val pixels = IntArray(24 * 24)
        runCatching { sample.getPixels(pixels, 0, 24, 0, 0, 24, 24) }
        pixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val max = maxOf(r, g, b) / 255f
            val min = minOf(r, g, b) / 255f
            val saturation = if (max == 0f) 0f else (max - min) / max
            // Washed-out pixels (a white background, a grey logo) must not decide the room colour.
            val w = (saturation * saturation) * (0.25f + max)
            red += r * w
            green += g * w
            blue += b * w
            weight += w
        }
        if (weight < 1.0) return DefaultAccent
        val color = Color(
            red = (red / weight / 255.0).toFloat().coerceIn(0f, 1f),
            green = (green / weight / 255.0).toFloat().coerceIn(0f, 1f),
            blue = (blue / weight / 255.0).toFloat().coerceIn(0f, 1f),
        )
        // A nearly-black accent would vanish in the dark room; lift it until it reads.
        val luminance = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
        return if (luminance < 0.28f) lighten(color, 0.28f - luminance) else color
    }

    private fun lighten(color: Color, by: Float): Color = Color(
        red = (color.red + by).coerceAtMost(1f),
        green = (color.green + by).coerceAtMost(1f),
        blue = (color.blue + by).coerceAtMost(1f),
        alpha = 1f,
    )

    /** Whether the phone asks for less motion; the petals then hold still and only change colour. */
    fun calmMotion(anyActivity: android.content.Context?): Boolean = runCatching {
        anyActivity?.let {
            Settings.Global.getFloat(it.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } ?: false
    }.getOrDefault(false)

    /** Relative luminance, used for the tint under a bot's colour. */
    fun luminance(color: Color): Float = abs(0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue)
}
