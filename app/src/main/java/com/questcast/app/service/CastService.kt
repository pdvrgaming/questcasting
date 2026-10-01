package com.questcast.app.service

import android.app.*
import android.content.Context
import android.content.Intent
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

    private var config = CastConfig()
    private var activeReceiverWs: WebSocket? = null

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
                val appName = curApp?.appName ?: "QuestCast"
                _currentApp.value = curApp
                """{"state":"${d.state}","ip":"${d.ipAddress}","receivers":${d.connectedReceivers},"fps":${d.fps},"currentGame":"$appName"}"""
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
                val d = _diagnostics.value
                val curApp = appTrackerManager?.currentApp?.value
                val serial = Build.SERIAL.takeIf { it != Build.UNKNOWN } ?: Build.MODEL
                val prefs = getSharedPreferences("questcast_prefs", Context.MODE_PRIVATE)
                val stationName = prefs.getString("station_name", "Station 1 - ${Build.MODEL}") ?: "Station 1"
                """{"stationName":"$stationName","model":"${Build.MODEL}","serial":"$serial","battery":$batteryPct,"isCharging":$isCharging,"ip":"${d.ipAddress}","httpPort":${config.httpPort},"httpsPort":${config.httpsPort},"wsPort":${config.wsPort},"wssPort":${config.wssPort},"state":"${d.state}","currentGame":"${curApp?.appName ?: "Home"}"}"""
            }
            val auditClearHandler: () -> Unit = {
                appTrackerManager?.clearAuditLog()
            }

            // 1. Start HTTP Server (port 8080)
            httpServer = HttpServer(
                port = config.httpPort,
                assetProvider = assetProvider,
                statusProvider = statusProvider,
                auditLogProvider = auditLogProvider,
                auditCsvProvider = auditCsvProvider,
                deviceInfoProvider = deviceInfoProvider,
                auditClearHandler = auditClearHandler
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
                        auditClearHandler = auditClearHandler
                    ).apply { start() }
                    Log.i(TAG, "QuestCast: HTTPS secure server started on port ${config.httpsPort}")
                } catch (e: Exception) {
                    Log.e(TAG, "QuestCast: error starting HTTPS server", e)
                }
            }

            // 2. Start WebSocket Signaling Server(s)
            val getTotalReceivers: () -> Int = {
                (signalingServer?.getConnectedCount() ?: 0) + (secureSignalingServer?.getConnectedCount() ?: 0)
            }

            val signalingListener = object : SignalingServer.Listener {
                override fun onReceiverConnected(conn: WebSocket) {
                    activeReceiverWs = conn
                    val count = getTotalReceivers()
                    _diagnostics.value = _diagnostics.value.copy(
                        connectedReceivers = count,
                        state = CastState.RECEIVER_CONNECTED
                    )
                    Log.i(TAG, "QuestCast: receiver connected (${conn.remoteSocketAddress}), initiating WebRTC offer")
                    webRtcManager?.createPeerConnection()
                    webRtcManager?.createAndSendOffer()
                }

                override fun onReceiverDisconnected(conn: WebSocket) {
                    if (activeReceiverWs == conn) {
                        activeReceiverWs = null
                    }
                    val count = getTotalReceivers()
                    val nextState = if (count > 0) CastState.RECEIVER_CONNECTED else CastState.SERVER_READY
                    _diagnostics.value = _diagnostics.value.copy(
                        connectedReceivers = count,
                        state = nextState
                    )
                    Log.i(TAG, "QuestCast: receiver disconnected, remaining receivers: $count")
                }

                override fun onOfferReceived(conn: WebSocket, sdp: String) {
                    // Quest is sender/offerer, but if receiver sends offer, we can log
                    Log.d(TAG, "QuestCast: unexpected offer received from receiver")
                }

                override fun onAnswerReceived(conn: WebSocket, sdp: String) {
                    Log.i(TAG, "QuestCast: received answer from receiver")
                    webRtcManager?.handleRemoteAnswer(sdp)
                }

                override fun onIceCandidateReceived(
                    conn: WebSocket,
                    candidate: String,
                    sdpMid: String?,
                    sdpMLineIndex: Int
                ) {
                    Log.d(TAG, "QuestCast: ICE candidate from receiver")
                    webRtcManager?.addRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
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
                listener = signalingListener
            ).apply { start() }

            if (sslContext != null) {
                try {
                    secureSignalingServer = SignalingServer(
                        wsPort = config.wssPort,
                        listener = signalingListener,
                        sslContext = sslContext
                    ).apply { start() }
                    Log.i(TAG, "QuestCast: WSS secure signaling server started on port ${config.wssPort}")
                } catch (e: Exception) {
                    Log.e(TAG, "QuestCast: error starting WSS secure signaling server", e)
                }
            }

            // 3. Initialize WebRTC Manager & Screen Capture
            webRtcManager = WebRtcManager(
                context = applicationContext,
                config = config,
                listener = object : WebRtcManager.Listener {
                    override fun onLocalDescriptionCreated(sdp: String) {
                        Log.i(TAG, "QuestCast: broadcasting offer to all receivers")
                        signalingServer?.broadcastOffer(sdp)
                        secureSignalingServer?.broadcastOffer(sdp)
                    }

                    override fun onIceCandidateGenerated(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                        signalingServer?.broadcastIceCandidate(candidate, sdpMid, sdpMLineIndex)
                        secureSignalingServer?.broadcastIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    }

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                        _diagnostics.value = _diagnostics.value.copy(iceState = newState.name)
                        if (newState == PeerConnection.IceConnectionState.CONNECTED) {
                            updateState(CastState.STREAMING)
                            Log.i(TAG, "QuestCast: ICE state = connected! WebRTC streaming live")
                        } else if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                                   newState == PeerConnection.IceConnectionState.FAILED) {
                            if (_diagnostics.value.connectedReceivers == 0) {
                                updateState(CastState.SERVER_READY)
                            }
                        }
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                        _diagnostics.value = _diagnostics.value.copy(connectionState = newState.name)
                        if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                            updateState(CastState.STREAMING)
                        }
                    }

                    override fun onError(description: String) {
                        Log.e(TAG, "QuestCast: WebRTC error: $description")
                        updateState(CastState.ERROR, error = description)
                    }
                }
            ).apply {
                initialize()
                startScreenCapture(permissionData)
            }

            updateState(CastState.SERVER_READY)
            Log.i(TAG, "QuestCast: servers and capture pipeline ready. Waiting for receiver at $receiverUrl")
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
            intercomManager?.release()
        } catch (e: Exception) {
            Log.e(TAG, "QuestCast: error stopping intercom manager", e)
        }
        intercomManager = null

        activeReceiverWs = null
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
                acquire(10 * 60 * 1000L /* 10 minutes max or refreshed */)
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
