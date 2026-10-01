package com.questcast.app

import com.questcast.app.server.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class HttpServerTest {

    private var server: HttpServer? = null
    private var testPort: Int = 0

    @Before
    fun setUp() {
        // Find a random available free port
        val tempSocket = ServerSocket(0)
        testPort = tempSocket.localPort
        tempSocket.close()

        val mockAssets = mapOf(
            "index.html" to "<html><body><h1>QuestCast Receiver</h1></body></html>".toByteArray(Charsets.UTF_8),
            "app.js" to "console.log('QuestCast WebRTC ready');".toByteArray(Charsets.UTF_8),
            "style.css" to "body { background: #0a0d14; }".toByteArray(Charsets.UTF_8),
            "favicon.svg" to "<svg></svg>".toByteArray(Charsets.UTF_8)
        )

        server = HttpServer(
            port = testPort,
            assetProvider = { path ->
                val cleanPath = path.removePrefix("receiver/")
                mockAssets[cleanPath]
            },
            statusProvider = {
                """{"state":"STREAMING","ip":"192.168.1.100","fps":30}"""
            },
            auditLogProvider = { query ->
                """{"summary":{"totalSessions":5},"filter":"$query"}"""
            },
            auditCsvProvider = { query ->
                "Date,App Name,Duration\r\n2026-10-01,Beat Saber,1800"
            },
            deviceInfoProvider = {
                """{"model":"Quest 2","battery":95}"""
            }
        ).apply { start() }

        // Short sleep for server thread readiness
        Thread.sleep(100)
    }

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun testServerServesIndexHtmlAtRoot() {
        val url = URL("http://127.0.0.1:$testPort/")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"

        val responseCode = conn.responseCode
        assertEquals(200, responseCode)
        assertTrue(conn.contentType.contains("text/html"))

        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("QuestCast Receiver"))
        conn.disconnect()
    }

    @Test
    fun testServerServesJavaScriptAsset() {
        val url = URL("http://127.0.0.1:$testPort/app.js")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("application/javascript"))
        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("QuestCast WebRTC ready"))
        conn.disconnect()
    }

    @Test
    fun testServerServesCssAsset() {
        val url = URL("http://127.0.0.1:$testPort/style.css")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("text/css"))
        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("background: #0a0d14"))
        conn.disconnect()
    }

    @Test
    fun testServerServesStatusJson() {
        val url = URL("http://127.0.0.1:$testPort/status")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("application/json"))
        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("\"state\":\"STREAMING\""))
        assertTrue(content.contains("\"fps\":30"))
        conn.disconnect()
    }

    @Test
    fun testServerReturns404ForNonexistentFile() {
        val url = URL("http://127.0.0.1:$testPort/does_not_exist.html")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(404, conn.responseCode)
        val errorText = conn.errorStream.bufferedReader().readText()
        assertTrue(errorText.contains("404 Not Found"))
        conn.disconnect()
    }

    @Test
    fun testServerServesAuditLogWithQueryParams() {
        val url = URL("http://127.0.0.1:$testPort/api/audit-log?date=2026-10-01&app=Beat%20Saber")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("application/json"))
        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("\"summary\":{\"totalSessions\":5}"))
        assertTrue(content.contains("date=2026-10-01&app=Beat%20Saber"))
        conn.disconnect()
    }

    @Test
    fun testServerServesAuditCsvExport() {
        val url = URL("http://127.0.0.1:$testPort/api/audit-log/export.csv")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("text/csv"))
        val contentDisposition = conn.getHeaderField("Content-Disposition")
        assertNotNull(contentDisposition)
        assertTrue(contentDisposition.contains("questcast_audit_log.csv"))

        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("Date,App Name,Duration"))
        assertTrue(content.contains("2026-10-01,Beat Saber,1800"))
        conn.disconnect()
    }

    @Test
    fun testServerServesDeviceInfo() {
        val url = URL("http://127.0.0.1:$testPort/api/device-info")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.contains("application/json"))
        val content = conn.inputStream.bufferedReader().readText()
        assertTrue(content.contains("\"model\":\"Quest 2\""))
        assertTrue(content.contains("\"battery\":95"))
        conn.disconnect()
    }

    @Test
    fun testServerLifecycle() {
        assertTrue(server?.isServerRunning() == true)
        server?.stop()
        assertFalse(server?.isServerRunning() == true)
    }
}
