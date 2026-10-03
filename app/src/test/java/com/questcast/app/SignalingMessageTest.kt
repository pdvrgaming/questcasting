package com.questcast.app

import com.questcast.app.model.SignalingMessage
import org.junit.Assert.*
import org.junit.Test

class SignalingMessageTest {

    @Test
    fun testOfferSerializationAndDeserialization() {
        val originalSdp = "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        val offer = SignalingMessage.Offer(originalSdp)
        val json = offer.toJson()

        assertTrue(json.contains("\"type\":\"offer\""))
        assertTrue(json.contains("\"kind\":\"offer\""))

        val parsed = SignalingMessage.parse(json)
        assertTrue(parsed is SignalingMessage.Offer)
        assertEquals(originalSdp, (parsed as SignalingMessage.Offer).sdp)
    }

    @Test
    fun testAnswerSerializationAndDeserialization() {
        val answerSdp = "v=0\r\no=- 54321 2 IN IP4 192.168.1.50\r\ns=-\r\n"
        val answer = SignalingMessage.Answer(answerSdp)
        val json = answer.toJson()

        assertTrue(json.contains("\"type\":\"answer\""))
        val parsed = SignalingMessage.parse(json)
        assertTrue(parsed is SignalingMessage.Answer)
        assertEquals(answerSdp, (parsed as SignalingMessage.Answer).sdp)
    }

    @Test
    fun testIceCandidateObjectParsing() {
        val jsonString = """
            {
                "type": "ice",
                "candidate": {
                    "candidate": "candidate:1 1 UDP 2122260223 192.168.1.20 54321 typ host",
                    "sdpMid": "0",
                    "sdpMLineIndex": 0
                }
            }
        """.trimIndent()

        val parsed = SignalingMessage.parse(jsonString)
        assertTrue(parsed is SignalingMessage.IceCandidate)
        val ice = parsed as SignalingMessage.IceCandidate
        assertEquals("candidate:1 1 UDP 2122260223 192.168.1.20 54321 typ host", ice.candidate)
        assertEquals("0", ice.sdpMid)
        assertEquals(0, ice.sdpMLineIndex)
    }

    @Test
    fun testIceCandidateLegacyKindField() {
        val jsonString = """
            {
                "kind": "ice",
                "candidate": {
                    "candidate": "candidate:2 1 UDP 2122260223 192.168.1.25 54322 typ host",
                    "sdpMid": "video",
                    "sdpMLineIndex": 1
                }
            }
        """.trimIndent()

        val parsed = SignalingMessage.parse(jsonString)
        assertTrue(parsed is SignalingMessage.IceCandidate)
        val ice = parsed as SignalingMessage.IceCandidate
        assertEquals("candidate:2 1 UDP 2122260223 192.168.1.25 54322 typ host", ice.candidate)
        assertEquals("video", ice.sdpMid)
        assertEquals(1, ice.sdpMLineIndex)
    }

    @Test
    fun testPingPongExchange() {
        val ping = SignalingMessage.Ping(123456789L)
        val json = ping.toJson()
        val parsed = SignalingMessage.parse(json)
        assertTrue(parsed is SignalingMessage.Ping)
        assertEquals(123456789L, (parsed as SignalingMessage.Ping).timestamp)

        val pong = SignalingMessage.Pong(123456789L)
        val pongJson = pong.toJson()
        val parsedPong = SignalingMessage.parse(pongJson)
        assertTrue(parsedPong is SignalingMessage.Pong)
        assertEquals(123456789L, (parsedPong as SignalingMessage.Pong).timestamp)
    }

    @Test
    fun testStatusMessageSerialization() {
        val status = SignalingMessage.Status(
            state = "STREAMING",
            resolution = "1280x720",
            fps = 30,
            bitrateKbps = 4000
        )
        val json = status.toJson()
        val parsed = SignalingMessage.parse(json)
        assertTrue(parsed is SignalingMessage.Status)
        val s = parsed as SignalingMessage.Status
        assertEquals("STREAMING", s.state)
        assertEquals("1280x720", s.resolution)
        assertEquals(30, s.fps)
        assertEquals(4000, s.bitrateKbps)
    }

    @Test
    fun testPttAudioMessageSerialization() {
        val audioData = "UklGRiQAAABXQVZFZm10IBAAAAABAAEA"
        val ptt = SignalingMessage.PttAudio(audioData)
        val json = ptt.toJson()
        assertTrue(json.contains("\"type\":\"ptt_audio\""))
        assertTrue(json.contains("\"data\":\"$audioData\""))

        val parsed = SignalingMessage.parse(json)
        assertTrue(parsed is SignalingMessage.PttAudio)
        assertEquals(audioData, (parsed as SignalingMessage.PttAudio).audioBase64)
    }

    @Test
    fun testPttStartAndStopMessages() {
        val start = SignalingMessage.PttStart
        val startJson = start.toJson()
        assertTrue(startJson.contains("\"type\":\"ptt_start\""))
        val parsedStart = SignalingMessage.parse(startJson)
        assertTrue(parsedStart is SignalingMessage.PttStart)

        val stop = SignalingMessage.PttStop
        val stopJson = stop.toJson()
        assertTrue(stopJson.contains("\"type\":\"ptt_stop\""))
        val parsedStop = SignalingMessage.parse(stopJson)
        assertTrue(parsedStop is SignalingMessage.PttStop)
    }

    @Test
    fun testInvalidOrUnknownJsonHandling() {
        val invalidJson = "{ this is not json }"
        val parsed = SignalingMessage.parse(invalidJson)
        assertTrue(parsed is SignalingMessage.Unknown)

        val unknownType = """{"type": "non_existent_type", "foo": "bar"}"""
        val parsedUnknown = SignalingMessage.parse(unknownType)
        assertTrue(parsedUnknown is SignalingMessage.Unknown)
    }
}
