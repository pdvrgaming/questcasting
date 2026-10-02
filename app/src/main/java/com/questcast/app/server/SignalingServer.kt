package com.questcast.app.server

import com.questcast.app.util.AppLogger as Log
import com.questcast.app.model.SignalingMessage
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class SignalingServer(
    val wsPort: Int = 8088,
    private val listener: Listener,
    sslContext: javax.net.ssl.SSLContext? = null,
    val relayManager: SignalingRelayManager? = null
) : WebSocketServer(InetSocketAddress("0.0.0.0", wsPort)) {

    companion object {
        private const val TAG = "QuestCast"
    }

    interface Listener {
        fun onReceiverConnected(conn: WebSocket)
        fun onReceiverDisconnected(conn: WebSocket)
        fun onOfferReceived(conn: WebSocket, sdp: String)
        fun onAnswerReceived(conn: WebSocket, sdp: String)
        fun onIceCandidateReceived(conn: WebSocket, candidate: String, sdpMid: String?, sdpMLineIndex: Int)
        fun onRequestOffer(conn: WebSocket) {}
        fun onPttAudioReceived(conn: WebSocket, pcmBytes: ByteArray) {}
        fun onPttStarted(conn: WebSocket) {}
        fun onPttStopped(conn: WebSocket) {}
        fun onError(ex: Exception)
    }

    private val connectedClients = CopyOnWriteArrayList<WebSocket>()

    init {
        isReuseAddr = true
        isTcpNoDelay = true
        if (sslContext != null) {
            setWebSocketFactory(org.java_websocket.server.DefaultSSLWebSocketServerFactory(sslContext))
        }
    }

    override fun onStart() {
        val proto = if (webSocketFactory is org.java_websocket.server.DefaultSSLWebSocketServerFactory) "WSS" else "WS"
        Log.i(TAG, "QuestCast: $proto server started on port $wsPort")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        connectedClients.add(conn)
        Log.i(TAG, "QuestCast: receiver connected: ${conn.remoteSocketAddress}")
        listener.onReceiverConnected(conn)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        connectedClients.remove(conn)
        relayManager?.onBrowserDisconnected(conn)
        Log.i(TAG, "QuestCast: receiver disconnected: ${conn.remoteSocketAddress}, code=$code, reason=$reason")
        listener.onReceiverDisconnected(conn)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            // Check for multi-station relay tunnel messages
            if (message.contains("\"type\":\"relay_") || message.contains("\"type\": \"relay_")) {
                try {
                    val json = org.json.JSONObject(message)
                    relayManager?.handleRelayMessage(conn, json)
                    return
                } catch (_: Exception) {}
            }

            when (val parsed = SignalingMessage.parse(message)) {
                is SignalingMessage.Offer -> {
                    Log.i(TAG, "QuestCast: received offer from receiver")
                    listener.onOfferReceived(conn, parsed.sdp)
                }
                is SignalingMessage.RequestOffer -> {
                    Log.i(TAG, "QuestCast: received request_offer from receiver")
                    listener.onRequestOffer(conn)
                }
                is SignalingMessage.Answer -> {
                    Log.i(TAG, "QuestCast: received answer from receiver")
                    listener.onAnswerReceived(conn, parsed.sdp)
                }
                is SignalingMessage.IceCandidate -> {
                    Log.i(TAG, "QuestCast: ICE candidate from receiver: ${parsed.candidate.take(30)}...")
                    listener.onIceCandidateReceived(conn, parsed.candidate, parsed.sdpMid, parsed.sdpMLineIndex)
                }
                is SignalingMessage.PttAudio -> {
                    try {
                        val bytes = android.util.Base64.decode(parsed.audioBase64, android.util.Base64.NO_WRAP)
                        listener.onPttAudioReceived(conn, bytes)
                    } catch (e: Exception) {
                        Log.e(TAG, "QuestCast: error decoding base64 audio", e)
                    }
                }
                is SignalingMessage.PttStart -> {
                    Log.i(TAG, "QuestCast: PTT started by ${conn.remoteSocketAddress}")
                    listener.onPttStarted(conn)
                }
                is SignalingMessage.PttStop -> {
                    Log.i(TAG, "QuestCast: PTT stopped by ${conn.remoteSocketAddress}")
                    listener.onPttStopped(conn)
                }
                is SignalingMessage.Ping -> {
                    val pong = SignalingMessage.Pong(parsed.timestamp).toJson()
                    conn.send(pong)
                }
                is SignalingMessage.Pong -> {
                    // Heartbeat acknowledged
                }
                else -> {
                    Log.d(TAG, "QuestCast: received unhandled message: $message")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error processing WebSocket message", e)
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        try {
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            listener.onPttAudioReceived(conn, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error handling binary audio frame", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "QuestCast: WebSocket error on ${conn?.remoteSocketAddress}", ex)
        listener.onError(ex)
    }

    fun broadcastOffer(sdp: String) {
        val msg = SignalingMessage.Offer(sdp).toJson()
        broadcast(msg)
    }

    fun sendOfferTo(conn: WebSocket, sdp: String) {
        val msg = SignalingMessage.Offer(sdp).toJson()
        if (conn.isOpen) {
            conn.send(msg)
        }
    }

    fun broadcastIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val msg = SignalingMessage.IceCandidate(candidate, sdpMid, sdpMLineIndex).toJson()
        broadcast(msg)
    }

    fun sendIceCandidateTo(conn: WebSocket, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        val msg = SignalingMessage.IceCandidate(candidate, sdpMid, sdpMLineIndex).toJson()
        if (conn.isOpen) {
            conn.send(msg)
        }
    }

    fun broadcastStatus(state: String, resolution: String, fps: Int, bitrateKbps: Int) {
        val msg = SignalingMessage.Status(state, resolution, fps, bitrateKbps).toJson()
        broadcast(msg)
    }

    fun getConnectedCount(): Int = connectedClients.size
}
