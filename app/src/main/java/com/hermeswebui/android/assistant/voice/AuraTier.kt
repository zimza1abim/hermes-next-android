package com.hermeswebui.android.assistant.voice

import android.app.ActivityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 품질 단계. 저사양 기기(RAM 2GB 이하급)에서는 옥타브를 줄이고 한 프레임 건너 그린다.
 *
 * 명세의 JankStats 기반 자동 강등은 metrics-performance 의존성이 이 프로젝트에 없어서 넣지 않았다:
 * 측정 없이 임계값을 정하면 근거 없는 숫자가 된다.
 */
@Stable
class AuraTier {
    var detail by mutableFloatStateOf(4f)
        internal set
    var halfRate by mutableStateOf(false)
        internal set
}

@Composable
fun rememberAuraTier(): AuraTier {
    val context = LocalContext.current
    val tier = remember { AuraTier() }
    LaunchedEffect(context) {
        val am = context.getSystemService(ActivityManager::class.java)
        if (am != null && am.isLowRamDevice) {
            tier.detail = 2f
            tier.halfRate = true
        }
    }
    return tier
}
