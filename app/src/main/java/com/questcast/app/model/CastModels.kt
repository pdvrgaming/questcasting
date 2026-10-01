package com.questcast.app.model

import org.json.JSONException
import org.json.JSONObject

enum class CastState {
    IDLE,
    STARTING,
    SERVER_READY,
    RECEIVER_CONNECTED,
    STREAMING,
    ERROR,
    STOPPED
}

data class CastConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 4000,
    val minBitrateKbps: Int = 2000,
    val maxBitrateKbps: Int = 8000,
    val httpPort: Int = 8080,
    val httpsPort: Int = 8443,
    val wsPort: Int = 8088,
    val wssPort: Int = 8089
)

data class DiagnosticsInfo(
    val ipAddress: String = "0.0.0.0",
    val httpPort: Int = 8080,
    val httpsPort: Int = 8443,
    val wsPort: Int = 8088,
    val wssPort: Int = 8089,
    val receiverUrl: String = "",
    val httpsReceiverUrl: String = "",
    val state: CastState = CastState.IDLE,
    val connectedReceivers: Int = 0,
    val resolution: String = "1280x720",
    val fps: Int = 30,
    val bitrateKbps: Int = 4000,
    val iceState: String = "New",
    val connectionState: String = "New",
    val lastError: String? = null
)

sealed class SignalingMessage {
    data class Offer(val sdp: String) : SignalingMessage()
    data class Answer(val sdp: String) : SignalingMessage()
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int
    ) : SignalingMessage()
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : SignalingMessage()
    data class Pong(val timestamp: Long = System.currentTimeMillis()) : SignalingMessage()
    data class Status(
        val state: String,
        val resolution: String,
        val fps: Int,
        val bitrateKbps: Int
    ) : SignalingMessage()
    data class PttAudio(val audioBase64: String) : SignalingMessage()
    object PttStart : SignalingMessage()
    object PttStop : SignalingMessage()
    data class Unknown(val raw: String) : SignalingMessage()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is Offer -> {
                json.put("type", "offer")
                json.put("kind", "offer")
                json.put("sdp", sdp)
            }
            is Answer -> {
                json.put("type", "answer")
                json.put("kind", "answer")
                json.put("sdp", sdp)
            }
            is IceCandidate -> {
                json.put("type", "ice")
                json.put("kind", "ice")
                val candObj = JSONObject().apply {
                    put("candidate", candidate)
                    if (sdpMid != null) put("sdpMid", sdpMid)
                    put("sdpMLineIndex", sdpMLineIndex)
                }
                json.put("candidate", candObj)
            }
            is Ping -> {
                json.put("type", "ping")
                json.put("timestamp", timestamp)
            }
            is Pong -> {
                json.put("type", "pong")
                json.put("timestamp", timestamp)
            }
            is Status -> {
                json.put("type", "status")
                json.put("state", state)
                json.put("resolution", resolution)
                json.put("fps", fps)
                json.put("bitrateKbps", bitrateKbps)
            }
            is PttAudio -> {
                json.put("type", "ptt_audio")
                json.put("data", audioBase64)
            }
            is PttStart -> {
                json.put("type", "ptt_start")
            }
            is PttStop -> {
                json.put("type", "ptt_stop")
            }
            is Unknown -> {
                return raw
            }
        }
        return json.toString()
    }

    companion object {
        fun parse(jsonString: String): SignalingMessage {
            return try {
                val json = JSONObject(jsonString)
                val type = when {
                    json.has("type") -> json.getString("type")
                    json.has("kind") -> json.getString("kind")
                    else -> ""
                }

                when (type.lowercase()) {
                    "offer" -> {
                        val sdp = json.optString("sdp", "")
                        Offer(sdp)
                    }
                    "answer" -> {
                        val sdp = json.optString("sdp", "")
                        Answer(sdp)
                    }
                    "ice" -> {
                        if (json.has("candidate")) {
                            val candidateVal = json.get("candidate")
                            if (candidateVal is JSONObject) {
                                val sdp = candidateVal.optString("candidate", "")
                                val sdpMid = if (candidateVal.has("sdpMid") && !candidateVal.isNull("sdpMid")) {
                                    candidateVal.getString("sdpMid")
                                } else null
                                val sdpMLineIndex = candidateVal.optInt("sdpMLineIndex", 0)
                                IceCandidate(sdp, sdpMid, sdpMLineIndex)
                            } else if (candidateVal is String) {
                                val sdpMid = if (json.has("sdpMid") && !json.isNull("sdpMid")) {
                                    json.getString("sdpMid")
                                } else null
                                val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
                                IceCandidate(candidateVal, sdpMid, sdpMLineIndex)
                            } else {
                                Unknown(jsonString)
                            }
                        } else {
                            Unknown(jsonString)
                        }
                    }
                    "ping" -> Ping(json.optLong("timestamp", System.currentTimeMillis()))
                    "pong" -> Pong(json.optLong("timestamp", System.currentTimeMillis()))
                    "status" -> Status(
                        state = json.optString("state", ""),
                        resolution = json.optString("resolution", ""),
                        fps = json.optInt("fps", 0),
                        bitrateKbps = json.optInt("bitrateKbps", 0)
                    )
                    "ptt_audio" -> {
                        val data = json.optString("data", "")
                        PttAudio(data)
                    }
                    "ptt_start" -> PttStart
                    "ptt_stop" -> PttStop
                    else -> Unknown(jsonString)
                }
            } catch (e: JSONException) {
                Unknown(jsonString)
            }
        }
    }
}
