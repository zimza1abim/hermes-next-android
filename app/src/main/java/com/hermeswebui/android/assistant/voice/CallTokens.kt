package com.hermeswebui.android.assistant.voice

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.hermeswebui.android.assistant.HermesFonts
import com.hermeswebui.android.assistant.Rose

/** 통화 화면(오라 시안)의 모든 값. 시안 CSS px = dp/sp. */
object CallTokens {

    // ── 방(배경) ───────────────────────────────────────────────
    val Base = VoiceTokens.Base                  // #0B0B10 (셰이더 bg 위쪽 끝과 같음)
    val BaseDeep = Color(0xFF07070B)             // 셰이더 bg 아래쪽 끝
    val DotGridDark = Color(0x1AF3BED4)          // Rose.Petal 10%
    val DotGridHeight = 300.dp

    // ── 글자 색 ────────────────────────────────────────────────
    val TextStrong = VoiceTokens.TextStrong
    val TextMuted = VoiceTokens.TextMuted
    val TextFaint = VoiceTokens.TextFaint
    val CaptionPast = Color(0x47FFFFFF)          // 28%
    val CaptionUser = Color(0x75FFFFFF)          // 46%
    val CaptionUserLive = Color(0x9EFFFFFF)      // 62%
    val CaptionUserAfterInterrupt = Color(0xA8FFFFFF) // 66%
    /** 시안은 40%였지만 17sp 본문이 3.4:1이라 AA 미달. 52%로 올리면 약 5.3:1. */
    val CaptionHint = Color(0x85FFFFFF)          // 52%
    const val CaptionCutAlpha = 0.16f
    const val CaptionNowStartAlpha = 0.5f
    val CaptionNowTint = Rose.Petal
    val Caret = Rose.Rose

    // ── 대표색(기본 Nova) ──────────────────────────────────────
    val Accent = Rose.Deep
    val AccentLight = Rose.Rose
    val AccentHighlight = Rose.Petal
    val Secondary = Color(0xFF977CBD)
    val AvatarCore = Color(0xFFD77FA6)

    // ── 상단 바 ────────────────────────────────────────────────
    val TopBarTop = 10.dp
    val TopBarHeight = 40.dp
    val ScreenHPadding = 22.dp
    val AvatarDot = 22.dp
    val AvatarRing = 1.5.dp
    val AvatarRingColor = Color(0x59F3BED4)
    val AvatarGlow = 14.dp
    val AvatarGlowColor = Color(0x59E8A1C0)
    val NameGap = 10.dp
    val StatusDot = 6.dp
    val StatusGap = 6.dp
    val TimerPadH = 11.dp
    val TimerPadV = 5.dp
    val TimerRadius = 16.dp
    val TimerBg = Color(0x0DFFFFFF)
    val TimerBorder = Color(0x12FFFFFF)
    val TimerLetterSpacing = 1.4.sp

    // ── 자막 영역 ──────────────────────────────────────────────
    val CaptionTop = 68.dp
    val CaptionHeight = 270.dp
    val CaptionHPadding = 28.dp
    val CaptionLineGap = 14.dp
    val ChipTopGap = 18.dp
    val HintTopGap = 16.dp
    val WordRise = 4.dp
    val WordBlur = 1.6.dp
    val CutBlur = 1.dp

    // ── 작업 칩 ────────────────────────────────────────────────
    val ChipRadius = 16.dp
    val ChipPadStart = 12.dp
    val ChipPadEnd = 16.dp
    val ChipPadV = 9.dp
    val ChipGap = 10.dp
    val ChipBg = Color(0x12FFFFFF)
    val ChipBorder = Color(0x1FFFFFFF)
    val ChipShimmer = Color(0x38F3BED4)
    val ChipSpinner = 16.dp
    val ChipSpinnerStroke = 2.dp
    val ChipSpinnerTrack = Color(0x40F3BED4)

    // ── 유리 알약 ──────────────────────────────────────────────
    val PillBottom = 20.dp
    val PillHeight = 72.dp
    val PillRadius = 36.dp
    val PillPadH = 10.dp
    val PillGap = 8.dp
    val PillTint = Color(0x47141018)
    val PillTintNoBlur = Color(0xB3141018)
    val PillBorder = Color(0x24FFFFFF)
    val PillHighlight = Color(0x24FFFFFF)
    val PillShadow = Color(0x59000000)
    val PillBlur = 26.dp
    const val PillSaturation = 1.6f
    val PillButton = 56.dp
    val PillButtonBg = VoiceTokens.Glass
    val PillButtonOn = Color(0xEBFFFFFF)
    val PillButtonOnIcon = Base
    val PillIcon = 26.dp
    val HangUpWidth = 76.dp
    val HangUpHeight = 56.dp
    val HangUpRadius = 28.dp
    val HangUp = VoiceTokens.HangUp
    val HangUpShadow = Color(0x59FF3B30)

    // ── 통화 중 띠(채팅 화면) ──────────────────────────────────
    val BarTop = 10.dp
    val BarHPadding = 16.dp
    val BarHeight = 56.dp
    val BarRadius = 28.dp
    val BarShadow = Color(0x474A2638)
    val BarInnerBorder = Color(0x0FFFFFFF)
    val BarPadStart = 12.dp
    val BarPadEnd = 7.dp
    val BarGap = 10.dp
    val BarAvatar = 24.dp
    val BarEnd = 38.dp
    val BarTimer = Color(0xBFFFFFFF)

    // ── 모션 ───────────────────────────────────────────────────
    const val VolumeUpMs = VoiceTokens.VolumeUpMs
    const val VolumeDownMs = VoiceTokens.VolumeDownMs
    const val WordRevealMs = 280
    const val WordTintMs = 420
    const val WordTintDelayMs = 120
    const val CutMs = 180
    const val FlashMs = 900
    const val ConnectRiseMs = 2_000
    const val EndSinkMs = 600
    const val ChipShimmerMs = 1_600
    const val SpinnerMs = 900
    const val InterruptHoldMs = 2_500L
    const val CollapseMs = 380
}

/** 글자 크기. [picked]면 시안 값, 아니면 시스템 폰트용으로 줄인 값. */
@Immutable
data class CallTypeScale(
    val caption: TextUnit, val captionLineHeight: TextUnit, val captionLetterSpacing: TextUnit,
    val captionSecondary: TextUnit,
    val past: TextUnit, val user: TextUnit, val hint: TextUnit,
    val name: TextUnit, val status: TextUnit, val timer: TextUnit,
    val chip: TextUnit, val chipMeta: TextUnit,
    val connectTitle: TextUnit,
    val barName: TextUnit, val barStatus: TextUnit, val barTimer: TextUnit,
) {
    companion object {
        val Picked = CallTypeScale(
            caption = 37.sp, captionLineHeight = 46.sp, captionLetterSpacing = (-0.6).sp,
            captionSecondary = 30.sp, past = 25.sp, user = 19.sp, hint = 17.sp,
            name = 23.sp, status = 16.sp, timer = 17.sp, chip = 19.sp, chipMeta = 14.sp,
            connectTitle = 33.sp, barName = 18.sp, barStatus = 14.sp, barTimer = 17.sp,
        )
        val System = CallTypeScale(
            caption = 32.sp, captionLineHeight = 40.sp, captionLetterSpacing = (-0.02).em,
            captionSecondary = 26.sp, past = 21.sp, user = 16.sp, hint = 15.sp,
            name = 20.sp, status = 14.sp, timer = 15.sp, chip = 16.sp, chipMeta = 12.sp,
            connectTitle = 28.sp, barName = 16.sp, barStatus = 12.sp, barTimer = 15.sp,
        )
        /** Compose 상태라서 폰트를 새로 고르면 다시 읽힌다. */
        val current: CallTypeScale get() = if (HermesFonts.family != null) Picked else System
    }
}

/** 오라 색 4개. a=대표색, b=밝은 대표색, c=하이라이트, d=보조색. */
@Immutable
data class AuraPalette(val a: Color, val b: Color, val c: Color, val d: Color) {
    companion object {
        val Nova = AuraPalette(CallTokens.Accent, CallTokens.AccentLight, CallTokens.AccentHighlight, CallTokens.Secondary)

        /**
         * 다른 봇: 대표색 하나에서 4색을 만든다. 규칙은 Nova에서 역산했다.
         * b = accent와 흰색 45% 섞음, c = 68% 섞음, d = HSL(h-65°, s×0.87, l+0.11)
         */
        fun fromAccent(accent: Color): AuraPalette {
            if (accent == VoiceTokens.DefaultAccent) return Nova
            val argb = accent.toArgb()
            val white = 0xFFFFFFFF.toInt()
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(argb, it) }
            val secondary = ColorUtils.HSLToColor(floatArrayOf(
                ((hsl[0] - 65f) % 360f + 360f) % 360f,
                (hsl[1] * 0.87f).coerceIn(0f, 1f),
                (hsl[2] + 0.11f).coerceIn(0f, 1f),
            ))
            return AuraPalette(
                a = accent,
                b = Color(ColorUtils.blendARGB(argb, white, 0.45f)),
                c = Color(ColorUtils.blendARGB(argb, white, 0.68f)),
                d = Color(secondary),
            )
        }
    }
}
