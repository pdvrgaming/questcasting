package com.questcast.app

import com.questcast.app.model.CastConfig
import com.questcast.app.model.CastState
import com.questcast.app.model.DiagnosticsInfo
import com.questcast.app.util.NetworkUtils
import org.junit.Assert.*
import org.junit.Test

class CastModelsAndConfigTest {

    @Test
    fun testCastConfigDefaults() {
        val config = CastConfig()
        assertEquals(1280, config.width)
        assertEquals(720, config.height)
        assertEquals(30, config.fps)
        assertEquals(4000, config.bitrateKbps)
        assertEquals(8080, config.httpPort)
        assertEquals(8443, config.httpsPort)
        assertEquals(8088, config.wsPort)
        assertEquals(8089, config.wssPort)
    }

    @Test
    fun testCastConfigCustomParameters() {
        val config = CastConfig(
            width = 1920,
            height = 1080,
            fps = 60,
            bitrateKbps = 8000,
            httpPort = 9000,
            httpsPort = 9443,
            wsPort = 9001,
            wssPort = 9002
        )
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(60, config.fps)
        assertEquals(8000, config.bitrateKbps)
        assertEquals(9000, config.httpPort)
        assertEquals(9443, config.httpsPort)
        assertEquals(9001, config.wsPort)
        assertEquals(9002, config.wssPort)
    }

    @Test
    fun testDiagnosticsInfoStateTransitions() {
        val initial = DiagnosticsInfo()
        assertEquals(CastState.IDLE, initial.state)
        assertEquals(0, initial.connectedReceivers)

        val ready = initial.copy(state = CastState.SERVER_READY, ipAddress = "192.168.1.42")
        assertEquals(CastState.SERVER_READY, ready.state)
        assertEquals("192.168.1.42", ready.ipAddress)

        val streaming = ready.copy(
            state = CastState.STREAMING,
            connectedReceivers = 1,
            iceState = "Connected"
        )
        assertEquals(CastState.STREAMING, streaming.state)
        assertEquals(1, streaming.connectedReceivers)
        assertEquals("Connected", streaming.iceState)
    }

    @Test
    fun testNetworkUtilsUrlBuilders() {
        val ip = "192.168.1.20"
        val httpUrl = NetworkUtils.buildReceiverUrl(ip, 8080)
        assertEquals("http://192.168.1.20:8080/", httpUrl)

        val httpsUrl = NetworkUtils.buildHttpsReceiverUrl(ip, 8443)
        assertEquals("https://192.168.1.20:8443/", httpsUrl)

        val wsUrl = NetworkUtils.buildWsUrl(ip, 8088)
        assertEquals("ws://192.168.1.20:8088/", wsUrl)

        val wssUrl = NetworkUtils.buildWssUrl(ip, 8089)
        assertEquals("wss://192.168.1.20:8089/", wssUrl)
    }

    @Test
    fun testCastStateEnumValues() {
        val states = CastState.values()
        assertTrue(states.contains(CastState.IDLE))
        assertTrue(states.contains(CastState.STARTING))
        assertTrue(states.contains(CastState.SERVER_READY))
        assertTrue(states.contains(CastState.RECEIVER_CONNECTED))
        assertTrue(states.contains(CastState.STREAMING))
        assertTrue(states.contains(CastState.STOPPED))
        assertTrue(states.contains(CastState.ERROR))
    }
}
