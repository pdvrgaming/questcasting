package com.questcast.app.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Retrieves the device's local IPv4 address on the active LAN / Wi-Fi network.
     * Prioritizes wlan0 (Wi-Fi) followed by eth interfaces, excluding loopback.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var candidateIp: String? = null

            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue

                val isWifi = iface.name.contains("wlan", ignoreCase = true)
                val addresses = Collections.list(iface.inetAddresses)

                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress ?: continue
                        // Avoid link-local or carrier-grade non-LAN if possible
                        if (hostAddress.startsWith("127.")) continue

                        if (isWifi) {
                            return hostAddress
                        }
                        if (candidateIp == null) {
                            candidateIp = hostAddress
                        }
                    }
                }
            }

            return candidateIp ?: "127.0.0.1"
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }

    fun buildReceiverUrl(ip: String, port: Int): String {
        return "http://$ip:$port/"
    }

    fun buildHttpsReceiverUrl(ip: String, port: Int): String {
        return "https://$ip:$port/"
    }

    fun buildWsUrl(ip: String, port: Int): String {
        return "ws://$ip:$port/"
    }

    fun buildWssUrl(ip: String, port: Int): String {
        return "wss://$ip:$port/"
    }
}
