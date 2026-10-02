package com.questcast.app

import com.questcast.app.model.SignalingMessage
import com.questcast.app.server.SignalingServer
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SignalingServerTest {

    private var server: SignalingServer? = null
    private var testPort: Int = 0

    private var connectedCallbackCount = 0
    private var disconnectedCallbackCount = 0
    private var lastReceivedAnswer: String? = null
    private var lastReceivedCandidate: String? = null

    @Before
    fun setUp() {
        val tempSocket = ServerSocket(0)
        testPort = tempSocket.localPort
        tempSocket.close()

        server = SignalingServer(
            wsPort = testPort,
            listener = object : SignalingServer.Listener {
                override fun onReceiverConnected(conn: WebSocket) {
                    connectedCallbackCount++
                }

                override fun onReceiverDisconnected(conn: WebSocket) {
                    disconnectedCallbackCount++
                }

                override fun onOfferReceived(conn: WebSocket, sdp: String) {}

                override fun onAnswerReceived(conn: WebSocket, sdp: String) {
                    lastReceivedAnswer = sdp
                }

                override fun onIceCandidateReceived(
                    conn: WebSocket,
                    candidate: String,
                    sdpMid: String?,
                    sdpMLineIndex: Int
                ) {
                    lastReceivedCandidate = candidate
                }

                override fun onError(ex: Exception) {}
            }
        ).apply { start() }

        Thread.sleep(150)
    }

    @After
    fun tearDown() {
        server?.stop(500)
        server = null
    }

    @Test
    fun testWebSocketConnectAndSignalingExchange() {
        val connectLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        var receivedByClient: String? = null

        val client = object : WebSocketClient(URI("ws://127.0.0.1:$testPort/")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                connectLatch.countDown()
            }

            override fun onMessage(message: String?) {
                receivedByClient = message
                messageLatch.countDown()
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: java.lang.Exception?) {}
        }

        val connected = client.connectBlocking(5, TimeUnit.SECONDS)
        assertTrue("Client should connect within 5s", connected)
        Thread.sleep(100)
        assertEquals(1, connectedCallbackCount)
        assertEquals(1, server?.getConnectedCount())

        // Server sends offer
        val testOfferSdp = "v=0\r\no=questcast 123 123 IN IP4 127.0.0.1\r\ns=stream\r\n"
        server?.broadcastOffer(testOfferSdp)

        assertTrue("Client should receive offer within 5s", messageLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedByClient)
        val parsedOffer = SignalingMessage.parse(receivedByClient!!)
        assertTrue(parsedOffer is SignalingMessage.Offer)
        assertEquals(testOfferSdp, (parsedOffer as SignalingMessage.Offer).sdp)

        // Client sends answer
        val testAnswerSdp = "v=0\r\no=receiver 456 456 IN IP4 127.0.0.1\r\ns=stream\r\n"
        val answerMsg = SignalingMessage.Answer(testAnswerSdp).toJson()
        client.send(answerMsg)

        Thread.sleep(150)
        assertEquals(testAnswerSdp, lastReceivedAnswer)

        // Client sends ICE candidate
        val testCandidate = "candidate:1 1 UDP 2122260223 127.0.0.1 50000 typ host"
        val iceMsg = SignalingMessage.IceCandidate(testCandidate, "0", 0).toJson()
        client.send(iceMsg)

        Thread.sleep(150)
        assertEquals(testCandidate, lastReceivedCandidate)

        // Client disconnects
        client.closeBlocking()
        Thread.sleep(150)
        assertEquals(1, disconnectedCallbackCount)
        assertEquals(0, server?.getConnectedCount())
    }

    @Test
    fun testClientReconnectBehavior() {
        val client1 = object : WebSocketClient(URI("ws://127.0.0.1:$testPort/")) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String?) {}
            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: java.lang.Exception?) {}
        }
        val connected1 = client1.connectBlocking(5, TimeUnit.SECONDS)
        assertTrue("Client 1 should connect", connected1)
        Thread.sleep(100)
        assertEquals(1, server?.getConnectedCount())

        client1.closeBlocking()
        Thread.sleep(150)
        assertEquals(0, server?.getConnectedCount())

        // Reconnect new client
        val client2 = object : WebSocketClient(URI("ws://127.0.0.1:$testPort/")) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String?) {}
            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: java.lang.Exception?) {}
        }
        val connected2 = client2.connectBlocking(5, TimeUnit.SECONDS)
        assertTrue("Client 2 should connect", connected2)
        Thread.sleep(100)
        client2.closeBlocking()
    }

    @Test
    fun testConcurrentMultiClientSignaling() {
        val client1Messages = mutableListOf<String>()
        val client2Messages = mutableListOf<String>()

        val client1 = object : WebSocketClient(URI("ws://127.0.0.1:$testPort/")) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String?) {
                if (message != null) client1Messages.add(message)
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: java.lang.Exception?) {}
        }

        val client2 = object : WebSocketClient(URI("ws://127.0.0.1:$testPort/")) {
            override fun onOpen(handshakedata: ServerHandshake?) {}
            override fun onMessage(message: String?) {
                if (message != null) client2Messages.add(message)
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {}
            override fun onError(ex: java.lang.Exception?) {}
        }

        // Both clients connect simultaneously
        assertTrue("Client 1 should connect", client1.connectBlocking(5, TimeUnit.SECONDS))
        assertTrue("Client 2 should connect", client2.connectBlocking(5, TimeUnit.SECONDS))
        Thread.sleep(150)

        assertEquals("Server should have 2 connected clients concurrently", 2, server?.getConnectedCount())

        // Disconnect Client 1, Client 2 should stay connected
        client1.closeBlocking()
        Thread.sleep(150)
        assertEquals("Server should have 1 connected client remaining", 1, server?.getConnectedCount())

        // Disconnect Client 2
        client2.closeBlocking()
        Thread.sleep(150)
        assertEquals("Server should have 0 connected clients", 0, server?.getConnectedCount())
    }
}

