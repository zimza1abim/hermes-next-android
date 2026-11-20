package com.hermeswebui.android.webapp

import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertOriginTest {
    @Test
    fun `telegram chats and webhook runs answer on telegram`() {
        assertThat(AlertOrigin.of("telegram", "telegram")).isEqualTo(AlertOrigin.TELEGRAM)
        assertThat(AlertOrigin.of("webhook", "webhook")).isEqualTo(AlertOrigin.TELEGRAM)
    }

    @Test
    fun `a chat created over acp is openbot even when its source reads local`() {
        assertThat(AlertOrigin.of("local", "acp")).isEqualTo(AlertOrigin.OPENBOT)
        assertThat(AlertOrigin.of("acp", "acp")).isEqualTo(AlertOrigin.OPENBOT)
    }

    @Test
    fun `chats of this app and the webapp have no other origin`() {
        assertThat(AlertOrigin.of("local", "desktop")).isNull()
        assertThat(AlertOrigin.of("cron", "cron")).isNull()
        assertThat(AlertOrigin.of(null, null)).isNull()
    }

    @Test
    fun `telegram starts silent and openbot starts audible on channels of their own`() {
        assertThat(AlertOrigin.TELEGRAM.importance).isEqualTo(NotificationManager.IMPORTANCE_NONE)
        assertThat(AlertOrigin.OPENBOT.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        val kinds = GatewayAlertKind.entries.map { it.channelId }.toSet()
        assertThat(AlertOrigin.entries.map { it.channelId }.toSet()).containsNoneIn(kinds)
    }
}
