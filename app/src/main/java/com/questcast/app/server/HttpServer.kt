package com.questcast.app.server

import com.questcast.app.util.AppLogger as Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpServer(
    private val port: Int = 8080,
    private val sslContext: javax.net.ssl.SSLContext? = null,
    private val assetProvider: (String) -> ByteArray?,
    private val statusProvider: () -> String = { "{}" },
    private val auditLogProvider: (queryParams: String?) -> String = { "{}" },
    private val auditCsvProvider: (queryParams: String?) -> String = { "" },
    private val deviceInfoProvider: () -> String = { "{}" },
    private val auditClearHandler: () -> Unit = {}
) {
    companion object {
        private const val TAG = "QuestCast"
    }

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private val isRunning = AtomicBoolean(false)

    @Synchronized
    fun start() {
        if (isRunning.get()) return

        try {
            val socket = if (sslContext != null) {
                sslContext.serverSocketFactory.createServerSocket()
            } else {
                ServerSocket()
            }
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = socket
            isRunning.set(true)
            executor = Executors.newCachedThreadPool()

            val protocol = if (sslContext != null) "HTTPS" else "HTTP"
            Log.i(TAG, "QuestCast: $protocol server started on port $port")

            Thread({
                while (isRunning.get() && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        executor?.submit { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "QuestCast: error accepting client socket", e)
                    }
                }
            }, "QuestCast-HttpServer").start()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error starting HTTP server on port $port", e)
            throw e
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error closing server socket", e)
        }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        Log.i(TAG, "QuestCast: HTTP server stopped")
    }

    fun isServerRunning(): Boolean = isRunning.get()

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]

            // Extract query parameters if present
            var path = fullPath
            var queryParams: String? = null
            val queryIdx = fullPath.indexOf('?')
            if (queryIdx != -1) {
                path = fullPath.substring(0, queryIdx)
                queryParams = fullPath.substring(queryIdx + 1)
            }

            if (path == "/" || path.isBlank()) {
                path = "/index.html"
            }

            // Consume remaining request headers
            var headerLine = reader.readLine()
            while (!headerLine.isNullOrBlank()) {
                headerLine = reader.readLine()
            }

            if (method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)) {
                when (path) {
                    "/status", "/api/status" -> {
                        val statusJson = statusProvider().toByteArray(Charsets.UTF_8)
                        sendResponse(out, 200, "OK", "application/json; charset=utf-8", statusJson, method)
                    }
                    "/api/audit-log", "/audit-log" -> {
                        val auditJson = auditLogProvider(queryParams).toByteArray(Charsets.UTF_8)
                        sendResponse(out, 200, "OK", "application/json; charset=utf-8", auditJson, method)
                    }
                    "/api/audit-log/export.csv", "/audit-log/export.csv" -> {
                        val csvData = auditCsvProvider(queryParams).toByteArray(Charsets.UTF_8)
                        sendCsvResponse(out, csvData)
                    }
                    "/api/audit-log/clear" -> {
                        auditClearHandler()
                        val resJson = """{"success":true,"message":"Audit log cleared"}""".toByteArray(Charsets.UTF_8)
                        sendResponse(out, 200, "OK", "application/json; charset=utf-8", resJson, method)
                    }
                    "/api/device-info", "/device-info" -> {
                        val devJson = deviceInfoProvider().toByteArray(Charsets.UTF_8)
                        sendResponse(out, 200, "OK", "application/json; charset=utf-8", devJson, method)
                    }
                    else -> {
                        val assetPath = if (path.startsWith("/")) path.substring(1) else path
                        val data = assetProvider(assetPath) ?: assetProvider("receiver/$assetPath")

                        if (data != null) {
                            val mimeType = getMimeType(assetPath)
                            sendResponse(out, 200, "OK", mimeType, data, method)
                        } else {
                            val notFoundBody = "<html><body><h1>404 Not Found</h1><p>QuestCast file not found: $path</p></body></html>".toByteArray()
                            sendResponse(out, 404, "Not Found", "text/html; charset=utf-8", notFoundBody, method)
                        }
                    }
                }
            } else if (method.equals("POST", ignoreCase = true)) {
                if (path == "/api/audit-log/clear") {
                    auditClearHandler()
                    val resJson = """{"success":true,"message":"Audit log cleared"}""".toByteArray(Charsets.UTF_8)
                    sendResponse(out, 200, "OK", "application/json; charset=utf-8", resJson, method)
                } else {
                    sendResponse(out, 404, "Not Found", "text/plain", "Not Found".toByteArray(), method)
                }
            } else if (method.equals("OPTIONS", ignoreCase = true)) {
                sendOptionsResponse(out)
            } else {
                val errorBody = "Method Not Allowed".toByteArray()
                sendResponse(out, 405, "Method Not Allowed", "text/plain", errorBody, method)
            }
        } catch (e: Exception) {
            // Socket closed or timeout
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray,
        method: String
    ) {
        val headers = StringBuilder()
        headers.append("HTTP/1.1 $statusCode $statusText\r\n")
        headers.append("Content-Type: $contentType\r\n")
        headers.append("Content-Length: ${body.size}\r\n")
        headers.append("Access-Control-Allow-Origin: *\r\n")
        headers.append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
        headers.append("Access-Control-Allow-Headers: *\r\n")
        headers.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
        headers.append("Connection: close\r\n")
        headers.append("\r\n")

        out.write(headers.toString().toByteArray(Charsets.UTF_8))
        if (!method.equals("HEAD", ignoreCase = true)) {
            out.write(body)
        }
        out.flush()
    }

    private fun sendCsvResponse(out: OutputStream, body: ByteArray) {
        val headers = StringBuilder()
        headers.append("HTTP/1.1 200 OK\r\n")
        headers.append("Content-Type: text/csv; charset=utf-8\r\n")
        headers.append("Content-Disposition: attachment; filename=\"questcast_audit_log.csv\"\r\n")
        headers.append("Content-Length: ${body.size}\r\n")
        headers.append("Access-Control-Allow-Origin: *\r\n")
        headers.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        headers.append("Access-Control-Allow-Headers: *\r\n")
        headers.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
        headers.append("Connection: close\r\n")
        headers.append("\r\n")

        out.write(headers.toString().toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun sendOptionsResponse(out: OutputStream) {
        val headers = StringBuilder()
        headers.append("HTTP/1.1 204 No Content\r\n")
        headers.append("Access-Control-Allow-Origin: *\r\n")
        headers.append("Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n")
        headers.append("Access-Control-Allow-Headers: *\r\n")
        headers.append("Content-Length: 0\r\n")
        headers.append("Connection: close\r\n")
        headers.append("\r\n")
        out.write(headers.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun getMimeType(path: String): String {
        return when {
            path.endsWith(".html", ignoreCase = true) -> "text/html; charset=utf-8"
            path.endsWith(".js", ignoreCase = true) -> "application/javascript; charset=utf-8"
            path.endsWith(".css", ignoreCase = true) -> "text/css; charset=utf-8"
            path.endsWith(".json", ignoreCase = true) -> "application/json; charset=utf-8"
            path.endsWith(".csv", ignoreCase = true) -> "text/csv; charset=utf-8"
            path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            path.endsWith(".ico", ignoreCase = true) -> "image/x-icon"
            else -> "application/octet-stream"
        }
    }
}
