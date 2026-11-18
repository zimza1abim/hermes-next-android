package com.hermeswebui.android.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermeswebui.android.R

/**
 * Keeps a call alive with the screen off.
 *
 * The type is `microphone`, which is what Android requires for a recording that outlives the activity,
 * and the notification is a call notification: the elapsed time, mute and hang up, on the lock screen.
 */
class VoiceService : Service() {

    private var botName: String = ""
    private var startedAt: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                botName = intent.getStringExtra(EXTRA_BOT).orEmpty()
                startedAt = System.currentTimeMillis()
                startForegroundCompat()
            }
            ACTION_UPDATE -> startForegroundCompat()
            ACTION_HANG_UP -> {
                LiveController.running()?.hangUp()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_MUTE -> LiveController.running()?.toggleMute()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> startForegroundCompat()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, VoiceActivity.intent(this, botName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Delivered to this service, not to the activity: with the screen off there is no activity to
        // receive them, and the plan's floor is that a call can be ended from the notification.
        val hangUp = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mute = PendingIntent.getService(
            this, 2, Intent(this, VoiceService::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(botName.ifBlank { "Hermes" } + " 통화 중")
            .setContentText("음성 대화가 이어지고 있어요")
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "음소거", mute)
            .addAction(0, "끊기", hangUp)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "음성 통화", NotificationManager.IMPORTANCE_LOW).apply {
                description = "음성 대화가 이어지는 동안 표시돼요"
                setShowBadge(false)
            })
        }
    }

    companion object {
        const val CHANNEL = "hermes_voice_call"
        const val ID = 4411
        const val ACTION_START = "com.hermeswebui.android.voice.START"
        const val ACTION_UPDATE = "com.hermeswebui.android.voice.UPDATE"
        const val ACTION_STOP = "com.hermeswebui.android.voice.STOP"
        const val ACTION_HANG_UP = "com.hermeswebui.android.voice.HANG_UP"
        const val ACTION_MUTE = "com.hermeswebui.android.voice.MUTE"
        private const val EXTRA_BOT = "bot"

        fun start(context: Context, botName: String?) {
            val intent = Intent(context, VoiceService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BOT, botName.orEmpty())
            context.startForegroundService(intent)
        }

        /** Ends the service without starting it: startService from the background is an exception on 12+. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        }
    }
}
