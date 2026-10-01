package com.questcast.app

import com.questcast.app.server.HttpServer
import com.questcast.app.util.SslUtils
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class SslUtilsTest {

    @Test
    fun testSslContextGenerationAndCaching() {
        val sslContext1 = SslUtils.getOrCreateSslContext("192.168.1.50")
        assertNotNull(sslContext1)

        val sslContext2 = SslUtils.getOrCreateSslContext("192.168.1.50")
        assertSame(sslContext1, sslContext2)

        val sslServerSocket = sslContext1.serverSocketFactory.createServerSocket()
        assertNotNull(sslServerSocket)
        sslServerSocket.close()
    }

    @Test
    fun testHttpsServerServesAssetOverSsl() {
        // Find free port
        val tempSocket = ServerSocket(0)
        val testPort = tempSocket.localPort
        tempSocket.close()

        val sslContext = SslUtils.getOrCreateSslContext("127.0.0.1")
        val mockAssets = mapOf(
            "index.html" to "<html><body><h1>QuestCast HTTPS</h1></body></html>".toByteArray(Charsets.UTF_8)
        )

        val server = HttpServer(
            port = testPort,
            sslContext = sslContext,
            assetProvider = { path ->
                val cleanPath = path.removePrefix("receiver/")
                mockAssets[cleanPath]
            }
        ).apply { start() }

        try {
            Thread.sleep(150)

            // Setup a trust-all SSLSocketFactory for testing the self-signed certificate
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) {}
            })
            val clientSslContext = SSLContext.getInstance("TLS")
            clientSslContext.init(null, trustAllCerts, SecureRandom())

            val url = URL("https://127.0.0.1:$testPort/index.html")
            val connection = url.openConnection() as HttpsURLConnection
            connection.sslSocketFactory = clientSslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val responseCode = connection.responseCode
            assertEquals(200, responseCode)

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            assertTrue(body.contains("QuestCast HTTPS"))
            connection.disconnect()
        } finally {
            server.stop()
        }
    }
}
