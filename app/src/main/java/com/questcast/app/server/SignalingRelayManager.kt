package com.questcast.app.server

import com.questcast.app.util.AppLogger as Log
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class SignalingRelayManager {
    companion object {
        private const val TAG = "QuestCast"
    }

    private val activeRelays = ConcurrentHashMap<String, RemoteStationClient>()

    fun handleRelayMessage(browserSocket: WebSocket, json: JSONObject) {
        val type = json.optString("type")
        val channelId = json.optString("channelId", "default")
        val targetIp = json.optString("targetIp")
        val targetPort = json.optInt("targetPort", 8088)

        val key = "${browserSocket.hashCode()}_$channelId"

        when (type) {
            "relay_connect" -> {
                if (targetIp.isBlank()) return
                activeRelays.remove(key)?.close()

                val uri = URI("ws://$targetIp:$targetPort/")
                Log.i(TAG, "QuestCast: [Relay] Opening relay tunnel from browser to remote station $uri (channel=$channelId)")

                val client = object : WebSocketClient(uri) {
                    override fun onOpen(handshakedata: ServerHandshake?) {
                        Log.i(TAG, "QuestCast: [Relay] Connected to remote station $targetIp:$targetPort (channel=$channelId)")
                        activeRelays[key]?.flushQueue()
                        if (browserSocket.isOpen) {
                            val notify = JSONObject().apply {
                                put("type", "relay_status")
                                put("channelId", channelId)
                                put("status", "connected")
                            }
                            browserSocket.send(notify.toString())
                        }
                    }

                    override fun onMessage(message: String?) {
                        if (message != null && browserSocket.isOpen) {
                            val wrapper = JSONObject().apply {
                                put("type", "relay_message")
                                put("channelId", channelId)
                                put("data", message)
                            }
                            browserSocket.send(wrapper.toString())
                        }
                    }

                    override fun onMessage(bytes: ByteBuffer?) {
                        if (bytes != null && browserSocket.isOpen) {
                            browserSocket.send(bytes)
                        }
                    }

                    override fun onClose(code: Int, reason: String?, remote: Boolean) {
                        Log.i(TAG, "QuestCast: [Relay] Remote station $targetIp disconnected (channel=$channelId)")
                        if (browserSocket.isOpen) {
                            val notify = JSONObject().apply {
                                put("type", "relay_status")
                                put("channelId", channelId)
                                put("status", "disconnected")
                            }
                            browserSocket.send(notify.toString())
                        }
                        activeRelays.remove(key)
                    }

                    override fun onError(ex: Exception?) {
                        Log.w(TAG, "QuestCast: [Relay] Remote station $targetIp error: ${ex?.message}")
                        if (browserSocket.isOpen) {
                            try {
                                val notify = JSONObject().apply {
                                    put("type", "relay_status")
                                    put("channelId", channelId)
                                    put("status", "error")
                                    put("error", ex?.message ?: "Connection failed")
                                }
                                browserSocket.send(notify.toString())
                            } catch (_: Exception) {}
                        }
                    }
                }

                client.connectionLostTimeout = 10
                client.isTcpNoDelay = true
                val rsc = RemoteStationClient(client, targetIp, targetPort)
                activeRelays[key] = rsc
                client.connect()
            }

            "relay_send" -> {
                val data = json.optString("data")
                val relay = activeRelays[key]
                if (relay != null) {
                    relay.sendOrQueue(data)
                }
            }

            "relay_disconnect" -> {
                activeRelays.remove(key)?.close()
            }
        }
    }

    fun handlePttAudio(browserSocket: WebSocket, channelId: String, pcmBytes: ByteArray) {
        val key = "${browserSocket.hashCode()}_$channelId"
        val relay = activeRelays[key]
        if (relay != null && relay.client.isOpen) {
            relay.client.send(pcmBytes)
        }
    }

    fun handleBroadcastPttAudio(pcmBytes: ByteArray) {
        activeRelays.values.forEach { r ->
            if (r.client.isOpen) {
                try { r.client.send(pcmBytes) } catch (_: Exception) {}
            }
        }
    }

    fun onBrowserDisconnected(browserSocket: WebSocket) {
        val prefix = "${browserSocket.hashCode()}_"
        val it = activeRelays.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key.startsWith(prefix)) {
                try { entry.value.close() } catch (_: Exception) {}
                it.remove()
            }
        }
    }

    fun stopAll() {
        activeRelays.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        activeRelays.clear()
    }

    private class RemoteStationClient(
        val client: WebSocketClient,
        val ip: String,
        val port: Int
    ) {
        private val pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

        fun sendOrQueue(data: String) {
            if (client.isOpen) {
                client.send(data)
            } else if (pendingQueue.size < 25) {
                pendingQueue.offer(data)
            }
        }

        fun flushQueue() {
            while (client.isOpen && !pendingQueue.isEmpty()) {
                val item = pendingQueue.poll() ?: break
                try { client.send(item) } catch (_: Exception) {}
            }
        }

        fun close() {
            pendingQueue.clear()
            try { client.close() } catch (_: Exception) {}
        }
    }
}
