package com.nexusai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

class WakeWordService : Service() {

    private val tag = "NEXUS-WakeWordService"

    companion object {
        private const val CHANNEL_ID = "nexus_wake_word"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.nexusai.app.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.nexusai.app.action.STOP_WAKE_WORD"
        const val ACTION_WAKE_DETECTED = "com.nexusai.app.action.WAKE_DETECTED"
        const val EXTRA_WAKE_TOKEN = "com.nexusai.app.extra.WAKE_TOKEN"
    }

    private lateinit var ownerToken: String

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ownerToken = WakeWordCoordinator.newOwnerToken()
        createNotificationChannel()
        WakeWordCoordinator.attachOwner(ownerToken, applicationContext)
        Log.i(
            tag,
            "VOICE_EVENT elapsedMs=${SystemClock.elapsedRealtime()} " +
                "event=SERVICE_CREATED owner=$ownerToken"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        Log.i(
            tag,
            "VOICE_EVENT elapsedMs=${SystemClock.elapsedRealtime()} " +
                "event=SERVICE_START_COMMAND action=${intent?.action} startId=$startId owner=$ownerToken"
        )
        when (intent?.action) {
            ACTION_STOP -> {
                val stopImmediately = WakeWordCoordinator.requestStop(
                    ownerToken = ownerToken,
                    startId = startId,
                    service = this
                )
                if (stopImmediately) {
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                // Foreground promotion happens for each start request before
                // any model or microphone work is queued.
                startAsForegroundService()
                WakeWordCoordinator.requestStart(ownerToken, startId)
            }
        }

        return START_STICKY
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(
            tag,
            "VOICE_EVENT elapsedMs=${SystemClock.elapsedRealtime()} " +
                "event=SERVICE_FOREGROUND_PROMOTED owner=$ownerToken"
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEXUS Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Hey Nexus wake-word detection active"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NEXUS is active")
            .setContentText("Listening locally for Hey Nexus")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.i(
            tag,
            "VOICE_EVENT elapsedMs=${SystemClock.elapsedRealtime()} " +
                "event=SERVICE_DESTROYED owner=$ownerToken"
        )
        WakeWordCoordinator.detachOwner(ownerToken)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
