package com.mobilemouse

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Service that keeps the WebSocket server running for the active session.
 */
class StylusServerService : Service() {

    private val TAG = "StylusServerService"
    private var server: WebSocketServer? = null

    inner class LocalBinder : Binder() {
        fun getServer(): WebSocketServer? = server
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        server = WebSocketServer(
            port = 8765,
            onClientCountChanged = { count ->
                Log.i(TAG, "Clients: $count")
            },
            onError = { msg ->
                Log.e(TAG, "Server error: $msg")
            }
        )
        server?.start()
        Log.i(TAG, "StylusServerService started on port 8765")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
        Log.i(TAG, "StylusServerService destroyed")
    }
}
