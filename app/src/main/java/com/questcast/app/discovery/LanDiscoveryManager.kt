package com.questcast.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.questcast.app.util.AppLogger as Log
import com.questcast.app.util.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredStation(
    val id: String,
    val name: String,
    val ip: String,
    val httpPort: Int = 8080,
    val httpsPort: Int = 8443,
    val wsPort: Int = 8088,
    val wssPort: Int = 8089,
    val currentGame: String = "Standby",
    val battery: Int = -1,
    val controllerL: Int = -1,
    val controllerR: Int = -1,
    val isSelf: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ip", ip)
        put("httpPort", httpPort)
        put("httpsPort", httpsPort)
        put("wsPort", wsPort)
        put("wssPort", wssPort)
        put("currentGame", currentGame)
        put("battery", battery)
        if (controllerL >= 0) put("controllerL", controllerL)
        if (controllerR >= 0) put("controllerR", controllerR)
        put("isSelf", isSelf)
        put("lastSeen", lastSeenTimestamp)
    }
}

class LanDiscoveryManager(
    private val context: Context,
    private val selfId: String,
    private val selfName: String,
    private val httpPort: Int = 8080,
    private val httpsPort: Int = 8443,
    private val wsPort: Int = 8088,
    private val wssPort: Int = 8089,
    private val currentGameProvider: () -> String = { "Standby" },
    private val batteryProvider: () -> Int = { -1 },
    private val controllerBatteryProvider: () -> Pair<Int, Int> = { -1 to -1 }
) {
    companion object {
        private const val TAG = "QuestCast"
        const val DISCOVERY_PORT = 8889
        private const val BEACON_INTERVAL_MS = 2000L
        private const val PEER_EXPIRATION_MS = 16000L
    }

    private val isRunning = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiverSocket: DatagramSocket? = null
    private var senderSocket: DatagramSocket? = null
    private var scheduler: ScheduledExecutorService? = null
    private var receiverThread: Thread? = null
    private var lastSubnetProbeTime = 0L

    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredStation>()

    @Synchronized
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        Log.i(TAG, "QuestCast: Starting LAN Auto-Discovery (UDP port $DISCOVERY_PORT, selfId=$selfId, name=$selfName)")

        // 1. Acquire Wi-Fi Multicast lock so Android Wi-Fi chipset receives broadcast packets
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("QuestCastDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "QuestCast: Could not acquire MulticastLock: ${e.message}")
        }

        // 2. Start UDP Receiver
        startReceiver()

        // 3. Start Periodic Beacon Broadcast, Subnet Probing & Peer Cleanup
        scheduler = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleAtFixedRate({
                try {
                    sendBeacon()
                    maybeProbeSubnet()
                    pruneStalePeers()
                } catch (e: Exception) {
                    Log.d(TAG, "QuestCast: Discovery loop error: ${e.message}")
                }
            }, 500, BEACON_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "QuestCast: Stopping LAN Auto-Discovery")

        try {
            scheduler?.shutdownNow()
        } catch (_: Exception) {}
        scheduler = null

        try {
            receiverSocket?.close()
        } catch (_: Exception) {}
        receiverSocket = null

        try {
            senderSocket?.close()
        } catch (_: Exception) {}
        senderSocket = null

        try {
            receiverThread?.interrupt()
        } catch (_: Exception) {}
        receiverThread = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null

        discoveredPeers.clear()
    }

    private fun startReceiver() {
        receiverThread = Thread({
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), DISCOVERY_PORT))
                    broadcast = true
                }
                receiverSocket = socket

                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isRunning.get() && !socket.isClosed) {
                    try {
                        packet.length = buffer.size
                        socket.receive(packet)

                        val senderIp = packet.address.hostAddress ?: continue
                        val rawMsg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        handleIncomingBeacon(senderIp, rawMsg)
                    } catch (e: Exception) {
                        if (!isRunning.get() || socket.isClosed) break
                        Log.d(TAG, "QuestCast: Packet receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "QuestCast: UDP Discovery receiver socket failed on port $DISCOVERY_PORT", e)
                }
            }
        }, "QuestCast-DiscoveryReceiver").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleIncomingBeacon(senderIp: String, rawMsg: String) {
        try {
            if (!rawMsg.startsWith("{\"questcast\":")) return
            val json = JSONObject(rawMsg)
            val peerIp = json.optString("ip", senderIp)

            val myIp = NetworkUtils.getLocalIpAddress()
            // Ignore beacons from ourselves (compare IP)
            if (peerIp == myIp || peerIp == "127.0.0.1" || peerIp.isBlank()) {
                return
            }

            val peerId = json.optString("id").takeIf { it.isNotBlank() && it != "unknown" } ?: "quest_${peerIp.replace('.', '_')}"
            val name = json.optString("name", "Quest 2 ($peerIp)")

            val station = DiscoveredStation(
                id = peerId,
                name = name,
                ip = peerIp,
                httpPort = json.optInt("httpPort", 8080),
                httpsPort = json.optInt("httpsPort", 8443),
                wsPort = json.optInt("wsPort", 8088),
                wssPort = json.optInt("wssPort", 8089),
                currentGame = json.optString("game", "Standby"),
                battery = json.optInt("battery", -1),
                controllerL = json.optInt("controllerL", -1),
                controllerR = json.optInt("controllerR", -1),
                isSelf = false,
                lastSeenTimestamp = System.currentTimeMillis()
            )

            val isNew = !discoveredPeers.containsKey(peerIp)
            discoveredPeers[peerIp] = station

            if (isNew) {
                Log.i(TAG, "QuestCast: [Auto-Discovery] Discovered new Quest headset via UDP: ${station.name} @ $peerIp")
            }
        } catch (_: Exception) {}
    }

    private fun sendBeacon() {
        val myIp = NetworkUtils.getLocalIpAddress()
        if (myIp == "127.0.0.1" || myIp.isBlank()) return

        val ctrlBatt = controllerBatteryProvider()
        val beaconJson = JSONObject().apply {
            put("questcast", true)
            put("id", selfId)
            put("name", selfName)
            put("ip", myIp)
            put("httpPort", httpPort)
            put("httpsPort", httpsPort)
            put("wsPort", wsPort)
            put("wssPort", wssPort)
            put("game", currentGameProvider())
            put("battery", batteryProvider())
            if (ctrlBatt.first >= 0) put("controllerL", ctrlBatt.first)
            if (ctrlBatt.second >= 0) put("controllerR", ctrlBatt.second)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        val bytes = beaconJson.toByteArray(Charsets.UTF_8)

        if (senderSocket == null || senderSocket?.isClosed == true) {
            senderSocket = DatagramSocket().apply {
                broadcast = true
            }
        }

        val socket = senderSocket ?: return

        // 1. Broadcast to universal 255.255.255.255
        try {
            val globalPacket = DatagramPacket(
                bytes,
                bytes.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket.send(globalPacket)
        } catch (_: Exception) {}

        // 2. Broadcast to specific subnet broadcast address (e.g. 192.168.0.255)
        try {
            val broadcastAddresses = getBroadcastAddresses()
            for (addr in broadcastAddresses) {
                try {
                    val subnetPacket = DatagramPacket(bytes, bytes.size, addr, DISCOVERY_PORT)
                    socket.send(subnetPacket)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 3. Direct Unicast to any already known peers
        for (peerIp in discoveredPeers.keys) {
            try {
                val peerAddr = InetAddress.getByName(peerIp)
                val unicastPacket = DatagramPacket(bytes, bytes.size, peerAddr, DISCOVERY_PORT)
                socket.send(unicastPacket)
            } catch (_: Exception) {}
        }
    }

    private fun maybeProbeSubnet() {
        val now = System.currentTimeMillis()
        val minInterval = if (discoveredPeers.isEmpty()) 20000L else 60000L
        if (now - lastSubnetProbeTime < minInterval) return
        lastSubnetProbeTime = now

        Thread({
            try {
                val myIp = NetworkUtils.getLocalIpAddress()
                if (myIp == "127.0.0.1" || myIp.isBlank() || !myIp.contains('.')) return@Thread
                val subnetPrefix = myIp.substringBeforeLast('.') + "."
                val myLastOctet = myIp.substringAfterLast('.').toIntOrNull() ?: -1

                val probePool = Executors.newFixedThreadPool(16)
                for (i in 1..254) {
                    if (i == myLastOctet) continue
                    val targetIp = "$subnetPrefix$i"
                    probePool.execute {
                        try {
                            val s = Socket()
                            s.connect(InetSocketAddress(targetIp, httpPort), 300)
                            s.close()
                            // Port 8080 reachable, verify if it's a QuestCast station
                            fetchPeerStationHttp(targetIp)
                        } catch (_: Exception) {}
                    }
                }
                probePool.shutdown()
                try {
                    if (!probePool.awaitTermination(6, TimeUnit.SECONDS)) {
                        probePool.shutdownNow()
                    }
                } catch (_: InterruptedException) {
                    probePool.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            } catch (_: Exception) {}
        }, "QuestCast-SubnetProber").apply {
            isDaemon = true
            start()
        }
    }

    private fun fetchPeerStationHttp(targetIp: String) {
        try {
            val url = URL("http://$targetIp:$httpPort/api/device-info")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                requestMethod = "GET"
            }
            if (conn.responseCode == 200) {
                val text = InputStreamReader(conn.inputStream, Charsets.UTF_8).readText()
                val json = JSONObject(text)
                val peerId = json.optString("serial").takeIf { it.isNotBlank() && it != "unknown" } ?: "quest_${targetIp.replace('.', '_')}"
                val name = json.optString("stationName", "Quest 2 ($targetIp)")
                val station = DiscoveredStation(
                    id = peerId,
                    name = name,
                    ip = targetIp,
                    httpPort = json.optInt("httpPort", 8080),
                    httpsPort = json.optInt("httpsPort", 8443),
                    wsPort = json.optInt("wsPort", 8088),
                    wssPort = json.optInt("wssPort", 8089),
                    currentGame = json.optString("currentGame", "Standby"),
                    battery = json.optInt("battery", -1),
                    controllerL = json.optInt("controllerL", -1),
                    controllerR = json.optInt("controllerR", -1),
                    isSelf = false,
                    lastSeenTimestamp = System.currentTimeMillis()
                )

                val isNew = !discoveredPeers.containsKey(targetIp)
                discoveredPeers[targetIp] = station
                if (isNew) {
                    Log.i(TAG, "QuestCast: [Auto-Discovery] Discovered new Quest headset via HTTP Subnet Probe: ${station.name} @ $targetIp")
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (interfaceAddress in netIf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun pruneStalePeers() {
        val now = System.currentTimeMillis()
        val it = discoveredPeers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastSeenTimestamp > PEER_EXPIRATION_MS) {
                Log.i(TAG, "QuestCast: [Auto-Discovery] Headset ${entry.value.name} (${entry.value.ip}) timed out, removing from active stations")
                it.remove()
            }
        }
    }

    fun getAllStations(): List<DiscoveredStation> {
        val myIp = NetworkUtils.getLocalIpAddress()
        val ctrlBatt = controllerBatteryProvider()
        val selfStation = DiscoveredStation(
            id = selfId,
            name = selfName,
            ip = myIp,
            httpPort = httpPort,
            httpsPort = httpsPort,
            wsPort = wsPort,
            wssPort = wssPort,
            currentGame = currentGameProvider(),
            battery = batteryProvider(),
            controllerL = ctrlBatt.first,
            controllerR = ctrlBatt.second,
            isSelf = true,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        val list = mutableListOf<DiscoveredStation>()
        list.add(selfStation)

        // Sort discovered peers cleanly by IP address
        val sortedPeers = discoveredPeers.values.toList().sortedBy { it.ip }
        list.addAll(sortedPeers)
        return list
    }

    fun getStationsJson(): String {
        val array = JSONArray()
        for (st in getAllStations()) {
            array.put(st.toJson())
        }
        return array.toString()
    }
}
