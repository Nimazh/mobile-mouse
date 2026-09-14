package com.mobilemouse

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the WebSocket server alive
 * even when MainActivity is in background.
 */
class StylusServerService : Service() {

    private val TAG = "StylusServerService"
    private val NOTIF_ID = 1
    private val CHANNEL_ID = "stylus_server"

    private var server: WebSocketServer? = null

    inner class LocalBinder : Binder() {
        fun getServer(): WebSocketServer? = server
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Starting server..."))

        server = WebSocketServer(
            port = 8765,
            onClientCountChanged = { count ->
                Log.i(TAG, "Clients: $count")
                updateNotification("$count client(s) connected — ws://localhost:8765")
            },
            onError = { msg ->
                Log.e(TAG, "Server error: $msg")
                updateNotification("Error: $msg")
            }
        )
        server?.start()
        updateNotification("Listening on ws://localhost:8765/stylus")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mobile Mouse")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notifManager = getSystemService(NotificationManager::class.java)
        notifManager?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stylus Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mobile Mouse WebSocket server"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
