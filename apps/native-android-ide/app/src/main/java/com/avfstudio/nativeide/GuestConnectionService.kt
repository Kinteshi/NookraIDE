package com.avfstudio.nativeide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder

class GuestConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT).orEmpty()
        val state = intent?.getStringExtra(EXTRA_STATE) ?: "Connected"
        createNotificationChannel()
        val notification = buildNotification(endpoint, state)
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Guest connection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the active AVF guest connection available in the background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(endpoint: String, state: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val host = Uri.parse(endpoint).host.orEmpty()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MaterialCode")
            .setContentText(if (host.isBlank()) state else "$state · $host")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guest_connection"
        private const val NOTIFICATION_ID = 2201
        private const val EXTRA_ENDPOINT = "endpoint"
        private const val EXTRA_STATE = "state"

        fun start(context: Context, endpoint: String, state: String) {
            val intent = Intent(context, GuestConnectionService::class.java)
                .putExtra(EXTRA_ENDPOINT, endpoint)
                .putExtra(EXTRA_STATE, state)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuestConnectionService::class.java))
        }
    }
}
