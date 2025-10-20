package com.example.shieldshare.managers.hotspot

// WIFI_AP_STATE constants are not available in newer Android versions
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class HotspotManagerImpl(private val context: Context) : HotspotManager {
    companion object {
        private const val TAG = "HotspotManagerImpl"
        private const val DEFAULT_SSID = "ShieldShare"
        private const val DEFAULT_PASSWORD = "shieldshare123"
        private const val HOTSPOT_IP = "192.168.43.1"
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun startTethering() {
        try {
            Log.i(TAG, "Starting hotspot tethering...")

            // Note: On Android 10+ (API 29+), apps cannot directly enable/disable hotspot
            // We can only check status and guide users to enable it manually
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Log.i(TAG, "Android 10+ detected - hotspot must be enabled manually by user")
                // User guidance dialog implementation pending
                return
            }

            // For older Android versions, try to enable hotspot programmatically
            val wifiConfiguration =
                    WifiConfiguration().apply {
                        SSID = "\"$DEFAULT_SSID\""
                        preSharedKey = "\"$DEFAULT_PASSWORD\""
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    }

            // This method is deprecated and may not work on newer devices
            @Suppress("DEPRECATION")
            val method =
                    wifiManager.javaClass.getMethod(
                            "setWifiApEnabled",
                            WifiConfiguration::class.java,
                            Boolean::class.javaPrimitiveType
                    )
            method.invoke(wifiManager, wifiConfiguration, true)

            Log.i(TAG, "Hotspot tethering started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotspot tethering", e)
        }
    }

    fun stopTethering() {
        try {
            Log.i(TAG, "Stopping hotspot tethering...")

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Log.i(TAG, "Android 10+ detected - hotspot must be disabled manually by user")
                return
            }

            @Suppress("DEPRECATION")
            val method =
                    wifiManager.javaClass.getMethod(
                            "setWifiApEnabled",
                            WifiConfiguration::class.java,
                            Boolean::class.javaPrimitiveType
                    )
            method.invoke(wifiManager, null, false)

            Log.i(TAG, "Hotspot tethering stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop hotspot tethering", e)
        }
    }

    fun isTetheringEnabled(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // For Android 10+, check if hotspot is enabled
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                method.invoke(wifiManager) as Boolean
            } else {
                @Suppress("DEPRECATION")
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                method.invoke(wifiManager) as Boolean
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hotspot status", e)
            false
        }
    }

    override fun getHotspotClients(): List<ConnectedClient> {
        val clients = mutableListOf<ConnectedClient>()

        try {
            // Read ARP table to get connected devices
            val arpTable = readArpTable()
            Log.d(TAG, "ARP table entries: $arpTable")

            for ((ip, mac) in arpTable) {
                Log.d(TAG, "Checking IP: $ip, MAC: $mac")
                // Check if this is a client IP (not the gateway)
                val hotspotIp = getHotspotIpAddress()
                if (hotspotIp != null &&
                                ip.startsWith(hotspotIp.substring(0, hotspotIp.lastIndexOf('.'))) &&
                                ip != hotspotIp
                ) {
                    Log.d(TAG, "Found hotspot client: $ip")
                    clients.add(
                            ConnectedClient(
                                    macAddress = mac,
                                    ipAddress = ip,
                                    hostname = getHostname(ip)
                            )
                    )
                }
            }

            Log.d(TAG, "Total hotspot clients found: ${clients.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected clients", e)
        }

        return clients
    }

    override fun subscribeToClientChanges(): Flow<List<ConnectedClient>> = flow {
        while (true) {
            try {
                val clients = getHotspotClients()
                emit(clients)
                delay(5000) // Poll every 5 seconds
            } catch (e: Exception) {
                Log.e(TAG, "Error in client monitoring", e)
                emit(emptyList())
                delay(5000)
            }
        }
    }

    override fun guideUserToEnableHotspot() {
        // User guidance for hotspot setup implementation pending
        Log.i(TAG, "Guiding user to enable hotspot")
        val candidates =
                listOf(
                        Intent("android.settings.WIFI_TETHER_SETTINGS"),
                        Intent("android.settings.TETHER_SETTINGS"),
                        Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
                        Intent(android.provider.Settings.ACTION_SETTINGS)
                )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    override fun detectHotspotState(): HotspotState {
        return if (isTetheringEnabled()) {
            HotspotState.ENABLED
        } else {
            HotspotState.DISABLED
        }
    }

    fun getHotspotInfo(): HotspotInfo? {
        return try {
            if (isTetheringEnabled()) {
                HotspotInfo(
                        ssid = DEFAULT_SSID,
                        password = DEFAULT_PASSWORD,
                        ipAddress = HOTSPOT_IP,
                        isEnabled = true
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot info", e)
            null
        }
    }

    private fun readArpTable(): Map<String, String> {
        val arpTable = mutableMapOf<String, String>()

        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?

            // Skip header line
            reader.readLine()

            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3]
                    if (mac != "00:00:00:00:00:00" && mac != "<incomplete>") {
                        arpTable[ip] = mac
                    }
                }
            }
            reader.close()
            Log.d(TAG, "ARP table read successfully: $arpTable")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ARP table, using alternative method", e)
            // Fallback: Use alternative method for client detection
            return getConnectedClientsAlternative()
        }

        return arpTable
    }

    private fun getConnectedClientsAlternative(): Map<String, String> {
        val clients = mutableMapOf<String, String>()

        try {
            Log.d(TAG, "Using alternative client detection method")

            // Alternative method: Use NetworkInterface to detect connected devices
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                Log.d(
                        TAG,
                        "Checking interface: ${networkInterface.name}, up: ${networkInterface.isUp}, loopback: ${networkInterface.isLoopback}"
                )

                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            val ip = address.hostAddress
                            if (ip != null) {
                                Log.d(
                                        TAG,
                                        "Found address: $ip on interface ${networkInterface.name}"
                                )

                                // Check for hotspot clients dynamically
                                val hotspotIp = getHotspotIpAddress()
                                if (hotspotIp != null &&
                                                ip.startsWith(
                                                        hotspotIp.substring(
                                                                0,
                                                                hotspotIp.lastIndexOf('.')
                                                        )
                                                ) &&
                                                ip != hotspotIp
                                ) {
                                    // This is likely a hotspot client (not the host)
                                    clients[ip] = "hotspot_client"
                                    Log.d(TAG, "Added hotspot client: $ip")
                                } else if (isLocalNetwork(ip)) {
                                    // This might be a client on other local networks
                                    clients[ip] = "local_client"
                                    Log.d(TAG, "Added local client: $ip")
                                }
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Alternative method found ${clients.size} clients: $clients")
        } catch (e: Exception) {
            Log.e(TAG, "Error in alternative client detection", e)
        }

        return clients
    }

    private fun getHostname(ipAddress: String): String? {
        return try {
            val address = InetAddress.getByName(ipAddress)
            address.hostName
        } catch (e: Exception) {
            null
        }
    }

    override fun getHotspotIpAddress(): String? {
        return try {
            // First, check if hotspot is enabled
            if (!isTetheringEnabled()) {
                Log.d(TAG, "Hotspot not enabled, using default IP")
                return HOTSPOT_IP
            }

            // Get the local IP address of the device
            val wifiInfo = wifiManager.connectionInfo
            val dhcpInfo = wifiManager.dhcpInfo

            // Check if hotspot is enabled by looking at the DHCP info
            if (dhcpInfo != null) {
                // Convert int IP to string
                val ipAddress =
                        String.format(
                                "%d.%d.%d.%d",
                                (dhcpInfo.serverAddress and 0xff),
                                (dhcpInfo.serverAddress shr 8 and 0xff),
                                (dhcpInfo.serverAddress shr 16 and 0xff),
                                (dhcpInfo.serverAddress shr 24 and 0xff)
                        )

                // Prioritize local network IPs
                if (isLocalNetwork(ipAddress)) {
                    Log.d(TAG, "Detected local network IP: $ipAddress")
                    return ipAddress
                }
            }

            // Fallback: try to get the local IP from network interfaces
            // Prioritize hotspot IP over VPN IP
            val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val hotspotCandidates = mutableListOf<String>()
            val wifiCandidates = mutableListOf<String>()
            val vpnCandidates = mutableListOf<String>()

            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address.isSiteLocalAddress) {
                            val ip = address.hostAddress
                            if (ip != null) {
                                Log.d(
                                        TAG,
                                        "Found network interface: ${networkInterface.name} with IP: $ip"
                                )

                                // Detect network interface type instead of IP ranges
                                val interfaceName = networkInterface.name.lowercase()
                                val isHotspotInterface =
                                        interfaceName.contains("wlan") ||
                                                interfaceName.contains("ap") ||
                                                interfaceName.contains("hotspot") ||
                                                interfaceName.contains("swlan")

                                if (isHotspotInterface) {
                                    hotspotCandidates.add(ip)
                                    Log.d(
                                            TAG,
                                            "Added hotspot interface candidate: $ip (interface: $interfaceName)"
                                    )
                                } else if (isLocalNetwork(ip)) {
                                    // Fallback for other local networks
                                    hotspotCandidates.add(ip)
                                    Log.d(
                                            TAG,
                                            "Added local network candidate: $ip (interface: $interfaceName)"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Return the first available local network IP
            if (hotspotCandidates.isNotEmpty()) {
                val selectedIp = hotspotCandidates.first()
                Log.d(TAG, "Using local network IP: $selectedIp")
                return selectedIp
            }

            // No local network IP found
            Log.w(TAG, "No local network IP address found")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot IP address", e)
            null
        }
    }

    /** Check if an IP address is in a local network range */
    private fun isLocalNetwork(ip: String): Boolean {
        return try {
            val address = java.net.InetAddress.getByName(ip).address
            when {
                // 192.168.x.x
                address[0] == 192.toByte() && address[1] == 168.toByte() -> true
                // 10.x.x.x
                address[0] == 10.toByte() -> true
                // 172.16.x.x - 172.31.x.x
                address[0] == 172.toByte() && address[1] in 16..31 -> true
                // 127.x.x.x (localhost)
                address[0] == 127.toByte() -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}
