package com.mobilemouse

import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WebSocketServer(
    private val port: Int = 8765,
    private val onClientCountChanged: (Int) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val TAG = "WebSocketServer"
    private var engine: ApplicationEngine? = null
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketSession>()
    private val sessionIdCounter = AtomicInteger(0)

    // High-throughput broadcast channel — capacity 256 to absorb bursts
    private val broadcastChannel = Channel<ByteArray>(capacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        serverScope.launch {
            try {
                engine = embeddedServer(CIO, port = port) {
                    install(WebSockets) {
                        pingPeriod = Duration.ofSeconds(15)
                        timeout = Duration.ofSeconds(30)
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    routing {
                        webSocket("/stylus") {
                            val sessionId = "client-${sessionIdCounter.incrementAndGet()}"
                            sessions[sessionId] = this
                            onClientCountChanged(sessions.size)
                            Log.i(TAG, "Client connected: $sessionId (total: ${sessions.size})")

                            try {
                                // Keep session alive — incoming messages ignored (one-way stream)
                                for (frame in incoming) {
                                    // Could handle commands here (e.g. calibration request)
                                    if (frame is Frame.Text) {
                                        Log.d(TAG, "Received from $sessionId: ${frame.readText()}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Session $sessionId closed: ${e.message}")
                            } finally {
                                sessions.remove(sessionId)
                                onClientCountChanged(sessions.size)
                                Log.i(TAG, "Client disconnected: $sessionId")
                            }
                        }
                    }
                }.start(wait = false)

                Log.i(TAG, "WebSocket server started on ws://0.0.0.0:$port/stylus")

                // Broadcast dispatcher coroutine
                for (packet in broadcastChannel) {
                    broadcast(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Send a binary packet to all connected clients.
     * Non-blocking: drops oldest if channel is full (maintains real-time feel).
     */
    fun sendPacket(packet: ByteArray) {
        broadcastChannel.trySend(packet)
    }

    private suspend fun broadcast(packet: ByteArray) {
        val deadSessions = mutableListOf<String>()
        sessions.forEach { (id, session) ->
            try {
                session.outgoing.trySend(Frame.Binary(true, packet))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to $id, removing")
                deadSessions.add(id)
            }
        }
        deadSessions.forEach { sessions.remove(it) }
    }

    val clientCount: Int get() = sessions.size

    fun stop() {
        broadcastChannel.close()
        engine?.stop(500, 1000)
        serverScope.cancel()
        Log.i(TAG, "WebSocket server stopped")
    }
}
