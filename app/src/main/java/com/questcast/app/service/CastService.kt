package com.questcast.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.questcast.app.util.AppLogger as Log
import androidx.core.app.NotificationCompat
import com.questcast.app.MainActivity
import com.questcast.app.model.CastConfig
import com.questcast.app.model.CastState
import com.questcast.app.model.DiagnosticsInfo
import com.questcast.app.model.SignalingMessage
import com.questcast.app.server.HttpServer
import com.questcast.app.server.SignalingServer
import com.questcast.app.util.NetworkUtils
import com.questcast.app.util.SslUtils
import com.questcast.app.webrtc.WebRtcManager
import android.os.BatteryManager
import com.questcast.app.tracker.AppTrackerManager
import com.questcast.app.tracker.AppSessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.questcast.app.audio.IntercomManager
import java.net.URLDecoder
import org.java_websocket.WebSocket
import org.webrtc.PeerConnection
import org.json.JSONObject

class CastService : Service() {

    companion object {
        const val TAG = "QuestCast"
        const val ACTION_START = "com.questcast.app.ACTION_START"
        const val ACTION_STOP = "com.questcast.app.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "questcast_service_channel"
        private const val NOTIFICATION_ID = 1001

        private val _diagnostics = MutableStateFlow(DiagnosticsInfo())
        val diagnostics: StateFlow<DiagnosticsInfo> = _diagnostics.asStateFlow()

        private val _currentApp = MutableStateFlow<AppSessionRecord?>(null)
        val currentApp: StateFlow<AppSessionRecord?> = _currentApp.asStateFlow()

        fun isCasting(): Boolean {
            return _diagnostics.value.state != CastState.IDLE &&
                   _diagnostics.value.state != CastState.STOPPED &&
                   _diagnostics.value.state != CastState.ERROR
        }
    }

    private var httpServer: HttpServer? = null
    private var httpsServer: HttpServer? = null
    private var signalingServer: SignalingServer? = null
    private var secureSignalingServer: SignalingServer? = null
    private var webRtcManager: WebRtcManager? = null
    private var appTrackerManager: AppTrackerManager? = null
    private var intercomManager: IntercomManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var lanDiscoveryManager: com.questcast.app.discovery.LanDiscoveryManager? = null
    private var signalingRelayManager: com.questcast.app.server.SignalingRelayManager? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    private var config = CastConfig()
    private val clientToSocket = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
    private val socketToClient = java.util.concurrent.ConcurrentHashMap<WebSocket, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            Log.i(TAG, "QuestCast: casting stopped via intent")
            stopCasting()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != Activity.RESULT_OK || resultData == null) {
                Log.e(TAG, "QuestCast: MediaProjection permission not granted or missing resultData")
                updateState(CastState.ERROR, error = "Screen capture permission was denied")
                stopSelf()
                return START_NOT_STICKY
            }

            startForegroundServiceWithNotification()
            startCastingPipeline(resultData)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val stopIntent = Intent(this, CastService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openActivityIntent = Intent(this, MainActivity::class.java)
        val openActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("QuestCast Active")
            .setContentText("Casting Quest 2 screen to local Wi-Fi receiver")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openActivityPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Casting", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun parseQueryParams(params: String?): Map<String, String> {
        if (params.isNullOrBlank()) return emptyMap()
        return try {
            params.split("&").mapNotNull { part ->
                val eqIdx = part.indexOf('=')
                if (eqIdx != -1) {
                    val key = part.substring(0, eqIdx)
                    val value = URLDecoder.decode(part.substring(eqIdx + 1), "UTF-8")
                    key to value
                } else null
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun startCastingPipeline(permissionData: Intent) {
        updateState(CastState.STARTING)
        Log.i(TAG, "QuestCast: starting casting pipeline")

        val ip = NetworkUtils.getLocalIpAddress()
        val receiverUrl = NetworkUtils.buildReceiverUrl(ip, config.httpPort)
        val httpsReceiverUrl = NetworkUtils.buildHttpsReceiverUrl(ip, config.httpsPort)
        val wsUrl = NetworkUtils.buildWsUrl(ip, config.wsPort)
        val wssUrl = NetworkUtils.buildWssUrl(ip, config.wssPort)

        _diagnostics.value = _diagnostics.value.copy(
            ipAddress = ip,
            httpPort = config.httpPort,
            httpsPort = config.httpsPort,
            wsPort = config.wsPort,
            wssPort = config.wssPort,
            receiverUrl = receiverUrl,
            httpsReceiverUrl = httpsReceiverUrl,
            resolution = "${config.width}x${config.height}",
            fps = config.fps,
            bitrateKbps = config.bitrateKbps,
            state = CastState.STARTING
        )

        Log.i(TAG, "QuestCast: local address = $ip, HTTP = $receiverUrl, HTTPS = $httpsReceiverUrl, ws = $wsUrl, wss = $wssUrl")

        try {
            // 0. Start App Tracker for VR game audit logs
            appTrackerManager = AppTrackerManager(this).apply {
                startTracking()
            }

            // 0b. Initialize low-latency voice intercom player
            intercomManager = IntercomManager().apply {
                start()
            }

            // 0c. Initialize SSLContext for secure HTTPS/WSS (enables Mic Push-to-Talk)
            val sslContext = try {
                SslUtils.getOrCreateSslContext(ip)
            } catch (e: Exception) {
                Log.e(TAG, "QuestCast: Could not initialize SSLContext for HTTPS/WSS", e)
                null
            }

            val assetProvider: (String) -> ByteArray? = { path ->
                try {
                    assets.open(path).use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }
            }
            val statusProvider: () -> String = {
                val d = _diagnostics.value
                val curApp = appTrackerManager?.currentApp?.value
                val appName = curApp?.appName ?: "Standby"
                _currentApp.value = curApp
                JSONObject().apply {
                    put("state", d.state.toString())
                    put("ip", d.ipAddress)
                    put("receivers", clientToSocket.size)
                    put("fps", d.fps)
                    put("currentGame", appName)
                }.toString()
            }
            val auditLogProvider: (String?) -> String = { queryParams ->
                val params = parseQueryParams(queryParams)
                appTrackerManager?.getStructuredAuditLogJson(
                    dateFilter = params["date"],
                    appFilter = params["app"],
                    view = params["view"]
                ) ?: "{}"
            }
            val auditCsvProvider: (String?) -> String = { queryParams ->
                val params = parseQueryParams(queryParams)
                appTrackerManager?.exportCsv(
                    dateFilter = params["date"],
                    appFilter = params["app"]
                ) ?: ""
            }
            val deviceInfoProvider: () -> String = {
                val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val isCharging = bm?.isCharging ?: false
                val ctrlBatt = com.questcast.app.util.ControllerBatteryUtils.getBatteryLevels(applicationContext)
                val d = _diagnostics.value
                val curApp = appTrackerManager?.currentApp?.value
                val androidId = try {
                    android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
                } catch (_: Exception) { "" }
                val uniqueSuffix = if (androidId.isNotBlank()) androidId.takeLast(4).uppercase() else ip.substringAfterLast('.')
                val uniqueId = if (androidId.isNotBlank()) "quest_$androidId" else "quest_${ip.replace('.', '_')}"
                val defaultStationName = "Quest 2 ($uniqueSuffix)"
                val prefs = getSharedPreferences("questcast_prefs", Context.MODE_PRIVATE)
                val stationName = prefs.getString("station_name", defaultStationName) ?: defaultStationName
                JSONObject().apply {
                    put("stationName", stationName)
                    put("model", Build.MODEL)
                    put("serial", uniqueId)
                    put("battery", batteryPct)
                    put("isCharging", isCharging)
                    if (ctrlBatt.left != null) put("controllerL", ctrlBatt.left)
                    if (ctrlBatt.right != null) put("controllerR", ctrlBatt.right)
                    put("ip", d.ipAddress)
                    put("httpPort", config.httpPort)
                    put("httpsPort", config.httpsPort)
                    put("wsPort", config.wsPort)
                    put("wssPort", config.wssPort)
                    put("state", d.state.toString())
                    put("currentGame", curApp?.appName ?: "Standby")
                    put("receivers", clientToSocket.size)
                }.toString()
            }
            val auditClearHandler: () -> Unit = {
                appTrackerManager?.clearAuditLog()
            }

            // 0d. Initialize Signaling Relay Manager for cross-headset tunneling
            signalingRelayManager = com.questcast.app.server.SignalingRelayManager()

            // 0e. Initialize LAN Auto-Discovery Manager
            val androidId = try {
                android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
            } catch (_: Exception) { "" }
            val uniqueSuffix = if (androidId.isNotBlank()) androidId.takeLast(4).uppercase() else ip.substringAfterLast('.')
            val uniqueId = if (androidId.isNotBlank()) "quest_$androidId" else "quest_${ip.replace('.', '_')}"
            val defaultStationName = "Quest 2 ($uniqueSuffix)"
            val prefs = getSharedPreferences("questcast_prefs", Context.MODE_PRIVATE)
            val stationName = prefs.getString("station_name", defaultStationName) ?: defaultStationName
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            lanDiscoveryManager = com.questcast.app.discovery.LanDiscoveryManager(
                context = applicationContext,
                selfId = uniqueId,
                selfName = stationName,
                httpPort = config.httpPort,
                httpsPort = config.httpsPort,
                wsPort = config.wsPort,
                wssPort = config.wssPort,
                currentGameProvider = { appTrackerManager?.currentApp?.value?.appName ?: "Standby" },
                batteryProvider = { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 },
                controllerBatteryProvider = {
                    val cb = com.questcast.app.util.ControllerBatteryUtils.getBatteryLevels(applicationContext)
                    Pair(cb.left ?: -1, cb.right ?: -1)
                }
            ).apply {
                start()
            }

            val stationsProvider: () -> String = {
                lanDiscoveryManager?.getStationsJson() ?: "[]"
            }

            // 1. Start HTTP Server (port 8080)
            httpServer = HttpServer(
                port = config.httpPort,
                assetProvider = assetProvider,
                statusProvider = statusProvider,
                auditLogProvider = auditLogProvider,
                auditCsvProvider = auditCsvProvider,
                deviceInfoProvider = deviceInfoProvider,
                auditClearHandler = auditClearHandler,
                stationsProvider = stationsProvider
            ).apply { start() }

            // 1b. Start HTTPS Server (port 8443) with self-signed SSL for native Mic permissions
            if (sslContext != null) {
                try {
                    httpsServer = HttpServer(
                        port = config.httpsPort,
                        sslContext = sslContext,
                        assetProvider = assetProvider,
                        statusProvider = statusProvider,
                        auditLogProvider = auditLogProvider,
                        auditCsvProvider = auditCsvProvider,
                        deviceInfoProvider = deviceInfoProvider,
                        auditClearHandler = auditClearHandler,
                        stationsProvider = stationsProvider
                    ).apply { start() }
                    Log.i(TAG, "QuestCast: HTTPS secure server started on port ${config.httpsPort}")
                } catch (e: Exception) {
                    Log.e(TAG, "QuestCast: error starting HTTPS server", e)
                }
            }

            // 2. Start WebSocket Signaling Server(s) with multi-client routing
            val signalingListener = object : SignalingServer.Listener {
                override fun onReceiverConnected(conn: WebSocket) {
                    val clientId = java.util.UUID.randomUUID().toString().take(8) + "_" + System.identityHashCode(conn)
                    clientToSocket[clientId] = conn
                    socketToClient[conn] = clientId

                    val count = clientToSocket.size
                    _diagnostics.value = _diagnostics.value.copy(
                        connectedReceivers = count,
                        state = CastState.RECEIVER_CONNECTED
                    )
                    Log.i(TAG, "QuestCast: receiver connected (clientId=$clientId, addr=${conn.remoteSocketAddress}, total=$count), initiating WebRTC offer")
                    webRtcManager?.createPeerConnection(clientId)
                    webRtcManager?.createAndSendOffer(clientId)
                }

                override fun onReceiverDisconnected(conn: WebSocket) {
                    val clientId = socketToClient.remove(conn)
                    if (clientId != null) {
                        clientToSocket.remove(clientId)
                        webRtcManager?.closePeerConnection(clientId)
                    }
                    val count = clientToSocket.size
                    val nextState = if (count > 0) CastState.STREAMING else CastState.SERVER_READY
                    _diagnostics.value = _diagnostics.value.copy(
                        connectedReceivers = count,
                        state = nextState
                    )
                    Log.i(TAG, "QuestCast: receiver disconnected (clientId=$clientId), remaining receivers: $count")
                }

                override fun onOfferReceived(conn: WebSocket, sdp: String) {
                    Log.d(TAG, "QuestCast: unexpected offer received from receiver")
                }

                override fun onAnswerReceived(conn: WebSocket, sdp: String) {
                    val clientId = socketToClient[conn] ?: run {
                        Log.w(TAG, "QuestCast: received answer from untracked socket ${conn.remoteSocketAddress}")
                        return
                    }
                    Log.i(TAG, "QuestCast: received answer from receiver (clientId=$clientId)")
                    webRtcManager?.handleRemoteAnswer(clientId, sdp)
                }

                override fun onIceCandidateReceived(
                    conn: WebSocket,
                    candidate: String,
                    sdpMid: String?,
                    sdpMLineIndex: Int
                ) {
                    val clientId = socketToClient[conn] ?: return
                    Log.d(TAG, "QuestCast: ICE candidate from receiver (clientId=$clientId)")
                    webRtcManager?.addRemoteIceCandidate(clientId, candidate, sdpMid, sdpMLineIndex)
                }

                override fun onRequestOffer(conn: WebSocket) {
                    val clientId = socketToClient[conn] ?: return
                    Log.i(TAG, "QuestCast: client requested new WebRTC offer (clientId=$clientId)")
                    webRtcManager?.createPeerConnection(clientId)
                    webRtcManager?.createAndSendOffer(clientId)
                }

                override fun onPttAudioReceived(conn: WebSocket, pcmBytes: ByteArray) {
                    intercomManager?.writePcm(pcmBytes)
                }

                override fun onPttStarted(conn: WebSocket) {
                    Log.i(TAG, "QuestCast Intercom: Operator speaking to headset")
                }

                override fun onPttStopped(conn: WebSocket) {
                    intercomManager?.stopPtt()
                }

                override fun onError(ex: Exception) {
                    Log.e(TAG, "QuestCast: error in signaling server", ex)
                }
            }

            signalingServer = SignalingServer(
                wsPort = config.wsPort,
                listener = signalingListener,
                relayManager = signalingRelayManager
            ).apply { start() }

            if (sslContext != null) {
                try {
                    secureSignalingServer = SignalingServer(
                        wsPort = config.wssPort,
                        listener = signalingListener,
                        sslContext = sslContext,
                        relayManager = signalingRelayManager
                    ).apply { start() }
                    Log.i(TAG, "QuestCast: WSS secure signaling server started on port ${config.wssPort}")
                } catch (e: Exception) {
                    Log.e(TAG, "QuestCast: error starting WSS secure signaling server", e)
                }
            }

            // 3. Initialize WebRTC Manager & Screen Capture with multi-peer listener
            webRtcManager = WebRtcManager(
                context = applicationContext,
                config = config,
                listener = object : WebRtcManager.Listener {
                    override fun onLocalDescriptionCreated(clientId: String, sdp: String) {
                        Log.i(TAG, "QuestCast: sending targeted offer to receiver client $clientId")
                        val conn = clientToSocket[clientId]
                        if (conn != null && conn.isOpen) {
                            val msg = SignalingMessage.Offer(sdp).toJson()
                            conn.send(msg)
                        } else {
                            Log.w(TAG, "QuestCast: cannot send offer, client $clientId socket is not open")
                        }
                    }

                    override fun onIceCandidateGenerated(clientId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                        val conn = clientToSocket[clientId]
                        if (conn != null && conn.isOpen) {
                            val msg = SignalingMessage.IceCandidate(candidate, sdpMid, sdpMLineIndex).toJson()
                            conn.send(msg)
                        }
                    }

                    override fun onIceConnectionChange(clientId: String, newState: PeerConnection.IceConnectionState) {
                        _diagnostics.value = _diagnostics.value.copy(iceState = newState.name)
                        if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                            updateState(CastState.STREAMING)
                            Log.i(TAG, "QuestCast: ICE state for $clientId = connected! WebRTC streaming live")
                        } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                                   newState == PeerConnection.IceConnectionState.FAILED) {
                            if (clientToSocket.isEmpty()) {
                                updateState(CastState.SERVER_READY)
                            }
                        }
                    }

                    override fun onConnectionChange(clientId: String, newState: PeerConnection.PeerConnectionState) {
                        _diagnostics.value = _diagnostics.value.copy(connectionState = newState.name)
                        if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                            updateState(CastState.STREAMING)
                        }
                    }

                    override fun onError(clientId: String?, description: String) {
                        Log.e(TAG, "QuestCast: WebRTC error (client=$clientId): $description")
                        if (clientId == null) {
                            updateState(CastState.ERROR, error = description)
                        }
                    }
                }
            ).apply {
                initialize()
                startScreenCapture(permissionData)
            }

            updateState(CastState.SERVER_READY)
            Log.i(TAG, "QuestCast: servers and capture pipeline ready. Waiting for receiver at $receiverUrl")

            // Register screen / proximity sleep detector to notify receivers instantly
            val screenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            screenStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            Log.i(TAG, "QuestCast: Proximity sleep / Screen OFF detected")
                            val sleepMsg = """{"type":"headset_sleep","ip":"$ip"}"""
                            signalingServer?.broadcast(sleepMsg)
                            secureSignalingServer?.broadcast(sleepMsg)
                        }
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                            Log.i(TAG, "QuestCast: Proximity wake / Screen ON detected")
                            val wakeMsg = """{"type":"headset_wake","ip":"$ip"}"""
                            signalingServer?.broadcast(wakeMsg)
                            secureSignalingServer?.broadcast(wakeMsg)
                        }
                    }
                }
            }
            registerReceiver(screenStateReceiver, screenFilter)
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error starting casting pipeline", e)
            updateState(CastState.ERROR, error = e.message ?: "Failed to start pipeline")
        }
    }

    private fun updateState(newState: CastState, error: String? = null) {
        _diagnostics.value = _diagnostics.value.copy(
            state = newState,
            lastError = error
        )
    }

    private fun stopCasting() {
        Log.i(TAG, "QuestCast: stopping all casting servers and WebRTC")
        updateState(CastState.STOPPED)

        try {
            webRtcManager?.stopCapture()
            webRtcManager?.release()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error releasing WebRTC", e)
        }
        webRtcManager = null

        try {
            signalingServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping signaling server", e)
        }
        signalingServer = null

        try {
            secureSignalingServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping secure signaling server", e)
        }
        secureSignalingServer = null

        try {
            httpServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping HTTP server", e)
        }
        httpServer = null

        try {
            httpsServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping HTTPS server", e)
        }
        httpsServer = null

        try {
            appTrackerManager?.stopTracking()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping app tracker", e)
        }
        appTrackerManager = null
        _currentApp.value = null

        try {
            lanDiscoveryManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping discovery manager", e)
        }
        lanDiscoveryManager = null

        try {
            signalingRelayManager?.stopAll()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping relay manager", e)
        }
        signalingRelayManager = null

        try {
            intercomManager?.release()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping intercom manager", e)
        }
        intercomManager = null

        try {
            screenStateReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        screenStateReceiver = null

        clientToSocket.clear()
        socketToClient.clear()
        releaseLocks()

        _diagnostics.value = DiagnosticsInfo(
            state = CastState.IDLE,
            ipAddress = NetworkUtils.getLocalIpAddress()
        )
        Log.i(TAG, "QuestCast: casting stopped")
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "QuestCast::CastWakeLock"
            )?.apply {
                acquire()
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "QuestCast::CastWifiLock"
            )?.apply {
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "QuestCast: could not acquire wake/wifi locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "QuestCast Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows casting status for QuestCast screen mirroring"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCasting()
        super.onDestroy()
    }
}
