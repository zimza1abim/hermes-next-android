package com.hermeswebui.android.assistant.voice

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 통화의 진동. `View.performHapticFeedback`은 권한이 필요 없고 사용자의 "터치 진동" 설정을 따른다.
 */
object CallHaptics {
    private fun confirm() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    private fun reject() = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

    fun connected(v: View) = v.performHapticFeedback(confirm())
    fun toggle(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    fun longPress(v: View) = v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    fun working(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    fun interrupt(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun hangUp(v: View) = v.performHapticFeedback(reject())

    fun error(v: View) {
        v.performHapticFeedback(reject())
        v.postDelayed({ v.performHapticFeedback(reject()) }, 120)
    }
}
