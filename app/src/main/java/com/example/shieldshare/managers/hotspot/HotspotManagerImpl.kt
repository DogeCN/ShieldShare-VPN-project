package com.example.shieldshare.managers.hotspot

// WIFI_AP_STATE constants are not available in newer Android versions
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
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

    // Cache for connected clients from wificond logs
    private val connectedClients = ConcurrentHashMap<String, ConnectedClient>()
    private val connectionPattern = Pattern.compile("New station ([a-f0-9:]+) connected to hotspot")
    private val disconnectionPattern =
            Pattern.compile("Station ([a-f0-9:]+) disassociated from hotspot")

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
        // First try to get clients from wificond logs (most reliable)
        val wificondClients = getClientsFromWificondLogs()
        if (wificondClients.isNotEmpty()) {
            Log.d(TAG, "Found ${wificondClients.size} clients from wificond logs")
            return wificondClients
        }

        // Try to use system network information
        val systemClients = getClientsFromSystemInfo()
        if (systemClients.isNotEmpty()) {
            Log.d(TAG, "Found ${systemClients.size} clients from system info")
            return systemClients
        }

        // Fallback to ARP table method
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

            // Try to detect connected clients by scanning the hotspot network
            val hotspotIp = getHotspotIpAddress()
            if (hotspotIp != null) {
                val baseIp = hotspotIp.substring(0, hotspotIp.lastIndexOf('.'))
                Log.d(TAG, "Scanning hotspot network: $baseIp.x for connected clients")

                // First, try to detect clients by scanning common IP ranges
                for (i in 1..254) {
                    val testIp = "$baseIp.$i"
                    if (testIp != hotspotIp) { // Don't scan the host IP
                        try {
                            // Try multiple ports to detect if device is reachable
                            val ports = listOf(80, 443, 22, 23, 8080, 8081)
                            var found = false

                            for (port in ports) {
                                try {
                                    val socket = java.net.Socket()
                                    socket.connect(
                                            java.net.InetSocketAddress(testIp, port),
                                            50
                                    ) // 50ms timeout
                                    socket.close()

                                    // If we can connect, this might be a client
                                    clients[testIp] = "hotspot_client"
                                    Log.d(
                                            TAG,
                                            "Found potential hotspot client at: $testIp (port $port)"
                                    )
                                    found = true
                                    break
                                } catch (e: Exception) {
                                    // Connection failed on this port, try next
                                }
                            }

                            // If no ports worked, try a simple ping-like approach
                            if (!found) {
                                try {
                                    val address = java.net.InetAddress.getByName(testIp)
                                    if (address.isReachable(100)) { // 100ms timeout
                                        clients[testIp] = "hotspot_client"
                                        Log.d(
                                                TAG,
                                                "Found potential hotspot client at: $testIp (ping)"
                                        )
                                    }
                                } catch (e: Exception) {
                                    // Ping failed, not a client
                                }
                            }
                        } catch (e: Exception) {
                            // General error, not a client
                        }
                    }
                }

                // If no clients found through scanning, try to detect connected devices
                // using a more comprehensive approach
                if (clients.isEmpty()) {
                    Log.d(
                            TAG,
                            "No clients found through scanning, trying comprehensive client detection"
                    )

                    // Method 1: Try to detect clients by attempting socket connections
                    // This is more reliable than isReachable() which has permission issues
                    val commonClientRanges =
                            listOf(
                                    2..10, // Common starting range
                                    100..150, // Common DHCP range
                                    200..254 // Extended range
                            )

                    for (range in commonClientRanges) {
                        for (i in range) {
                            val testIp = "$baseIp.$i"
                            if (testIp != hotspotIp) { // Don't check the host IP
                                try {
                                    // Try to connect to common ports to detect if device exists
                                    val commonPorts =
                                            listOf(80, 443, 22, 23, 8080, 8081, 53, 67, 68)
                                    var found = false

                                    for (port in commonPorts) {
                                        try {
                                            val socket = java.net.Socket()
                                            socket.connect(
                                                    java.net.InetSocketAddress(testIp, port),
                                                    50
                                            ) // 50ms timeout
                                            socket.close()

                                            // If we can connect, this is likely a client
                                            clients[testIp] = "hotspot_client"
                                            Log.d(
                                                    TAG,
                                                    "Found hotspot client at: $testIp (port $port)"
                                            )
                                            found = true
                                            break
                                        } catch (e: Exception) {
                                            // Port not open, continue
                                        }
                                    }

                                    // If no ports worked, try a simple ping-like approach
                                    if (!found) {
                                        try {
                                            val address = java.net.InetAddress.getByName(testIp)
                                            if (address.isReachable(100)) { // 100ms timeout
                                                clients[testIp] = "hotspot_client"
                                                Log.d(
                                                        TAG,
                                                        "Found hotspot client at: $testIp (ping)"
                                                )
                                            }
                                        } catch (e: Exception) {
                                            // Ping failed, not a client
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Not a valid IP, skip
                                }
                            }
                        }
                    }

                    // Method 2: If still no clients found, use intelligent fallback
                    // Based on common hotspot behavior and known client patterns
                    if (clients.isEmpty()) {
                        Log.d(TAG, "No clients found through scanning, using intelligent fallback")

                        // Since we know from ARP table that there's a client at 10.11.64.221,
                        // and our permission-restricted methods can't detect it, we'll use
                        // a workaround that assumes common client IPs exist if they're in
                        // the hotspot subnet

                        // Dynamic approach: Try to detect actual connected clients
                        // Since we can observe the ARP table, we'll use that information
                        // to detect real clients instead of hardcoded assumptions

                        // Truly dynamic approach: Scan the entire hotspot subnet
                        // This will detect any number of clients automatically
                        Log.d(TAG, "Using comprehensive subnet scanning for client detection")

                        // Scan the entire hotspot subnet for potential clients
                        for (i in 1..254) {
                            val testIp = "$baseIp.$i"
                            if (testIp != hotspotIp) { // Don't check the host IP
                                try {
                                    // Try multiple detection methods for each IP
                                    var isClient = false

                                    // Method 1: Try to connect to common ports
                                    val commonPorts =
                                            listOf(
                                                    80,
                                                    443,
                                                    22,
                                                    23,
                                                    8080,
                                                    8081,
                                                    53,
                                                    67,
                                                    68,
                                                    135,
                                                    139,
                                                    445
                                            )
                                    for (port in commonPorts) {
                                        try {
                                            val socket = java.net.Socket()
                                            socket.connect(
                                                    java.net.InetSocketAddress(testIp, port),
                                                    25
                                            ) // Very fast timeout
                                            socket.close()

                                            // If we can connect, this is likely a client
                                            isClient = true
                                            Log.d(TAG, "Found client at $testIp (port $port)")
                                            break
                                        } catch (e: Exception) {
                                            // Port not open, continue
                                        }
                                    }

                                    // Method 2: Try ping-like approach if no ports worked
                                    if (!isClient) {
                                        try {
                                            val address = java.net.InetAddress.getByName(testIp)
                                            if (address.isReachable(50)) { // 50ms timeout
                                                isClient = true
                                                Log.d(TAG, "Found client at $testIp (ping)")
                                            }
                                        } catch (e: Exception) {
                                            // Not reachable, skip
                                        }
                                    }

                                    // Method 3: For common client IP ranges, assume they might be
                                    // clients
                                    // This is a fallback for devices that don't respond to port
                                    // scans or ping
                                    if (!isClient) {
                                        val lastOctet = i
                                        // Common DHCP-assigned ranges where clients typically
                                        // appear
                                        if (lastOctet in 2..10 ||
                                                        lastOctet in 100..150 ||
                                                        lastOctet in 200..254
                                        ) {
                                            // Try a more aggressive approach for these ranges
                                            try {
                                                val address = java.net.InetAddress.getByName(testIp)
                                                // Use a longer timeout for potential clients
                                                if (address.isReachable(200)) { // 200ms timeout
                                                    isClient = true
                                                    Log.d(
                                                            TAG,
                                                            "Found potential client at $testIp (extended ping)"
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                // Not reachable, skip
                                            }
                                        }
                                    }

                                    if (isClient) {
                                        clients[testIp] = "hotspot_client"
                                    }
                                } catch (e: Exception) {
                                    // Not a valid IP, skip
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

                // Only return if this is a hotspot IP, not just any local network IP
                if (isLocalNetwork(ipAddress)) {
                    Log.d(TAG, "Detected local network IP from DHCP: $ipAddress")
                    // Don't return immediately - let the interface scanning prioritize hotspot IPs
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

                                val isVpnInterface =
                                        interfaceName.contains("tun") ||
                                                interfaceName.contains("ppp") ||
                                                interfaceName.contains("vpn")

                                if (isHotspotInterface) {
                                    hotspotCandidates.add(ip)
                                    Log.d(
                                            TAG,
                                            "Added hotspot interface candidate: $ip (interface: $interfaceName)"
                                    )
                                } else if (isVpnInterface) {
                                    vpnCandidates.add(ip)
                                    Log.d(
                                            TAG,
                                            "Added VPN interface candidate: $ip (interface: $interfaceName)"
                                    )
                                } else if (isLocalNetwork(ip)) {
                                    wifiCandidates.add(ip)
                                    Log.d(
                                            TAG,
                                            "Added WiFi interface candidate: $ip (interface: $interfaceName)"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Return in priority order: hotspot > wifi > vpn
            if (hotspotCandidates.isNotEmpty()) {
                val selectedIp = hotspotCandidates.first()
                Log.d(TAG, "Using hotspot IP: $selectedIp")
                return selectedIp
            } else if (wifiCandidates.isNotEmpty()) {
                val selectedIp = wifiCandidates.first()
                Log.d(TAG, "Using WiFi IP: $selectedIp")
                return selectedIp
            } else if (vpnCandidates.isNotEmpty()) {
                val selectedIp = vpnCandidates.first()
                Log.d(TAG, "Using VPN IP: $selectedIp")
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

    /** Get connected clients by monitoring wificond logs */
    private fun getClientsFromWificondLogs(): List<ConnectedClient> {
        return try {
            Log.d(TAG, "Attempting to read wificond logs for client detection")

            // Run logcat command to get recent wificond logs
            val process = Runtime.getRuntime().exec("logcat -d -s wificond:*")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val currentClients = mutableListOf<ConnectedClient>()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                line?.let { logLine ->
                    // Check for connection events
                    val connectionMatcher = connectionPattern.matcher(logLine)
                    if (connectionMatcher.find()) {
                        val macAddress = connectionMatcher.group(1)
                        if (macAddress != null) {
                            Log.d(TAG, "Found connection event for MAC: $macAddress")

                            // Generate a likely IP address for this client
                            val hotspotIp = getHotspotIpAddress()
                            val clientIp = generateClientIp(hotspotIp, connectedClients.size + 1)

                            val client =
                                    ConnectedClient(
                                            macAddress = macAddress,
                                            ipAddress = clientIp,
                                            hostname = "Client-${macAddress.takeLast(6)}",
                                            connectionTime = System.currentTimeMillis()
                                    )

                            connectedClients[macAddress] = client
                            currentClients.add(client)
                            Log.d(TAG, "Added client: $client")
                        }
                    }

                    // Check for disconnection events
                    val disconnectionMatcher = disconnectionPattern.matcher(logLine)
                    if (disconnectionMatcher.find()) {
                        val macAddress = disconnectionMatcher.group(1)
                        if (macAddress != null) {
                            Log.d(TAG, "Found disconnection event for MAC: $macAddress")
                            connectedClients.remove(macAddress)
                        }
                    }
                }
            }

            reader.close()
            process.waitFor()

            // If no clients found from wificond logs, try network scanning
            if (connectedClients.isEmpty()) {
                val scannedClients = scanForConnectedClients()
                if (scannedClients.isNotEmpty()) {
                    Log.d(TAG, "Found ${scannedClients.size} clients through network scanning")
                    return scannedClients
                }
            }

            // Return current connected clients
            val result = connectedClients.values.toList()
            Log.d(TAG, "Wificond method found ${result.size} connected clients")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error reading wificond logs", e)
            emptyList()
        }
    }

    /** Generate a likely IP address for a client based on hotspot IP and client count */
    private fun generateClientIp(hotspotIp: String?, clientCount: Int): String {
        return if (hotspotIp != null) {
            val baseIp = hotspotIp.substring(0, hotspotIp.lastIndexOf('.'))
            "$baseIp.${100 + clientCount}" // Start from .100, .101, etc.
        } else {
            "192.168.43.${100 + clientCount}" // Fallback
        }
    }

    /** Get connected clients using system network information */
    private fun getClientsFromSystemInfo(): List<ConnectedClient> {
        return try {
            Log.d(TAG, "Attempting to get clients from system network information")
            val clients = mutableListOf<ConnectedClient>()
            val hotspotIp = getHotspotIpAddress()

            if (hotspotIp != null) {
                val baseIp = hotspotIp.substring(0, hotspotIp.lastIndexOf('.'))
                Log.d(TAG, "Checking system info for clients in subnet: $baseIp.x")

                // Try to use ConnectivityManager to get network information
                val connectivityManager =
                        context.getSystemService(Context.CONNECTIVITY_SERVICE) as
                                ConnectivityManager

                // Get active network info
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val networkCapabilities =
                            connectivityManager.getNetworkCapabilities(activeNetwork)
                    if (networkCapabilities != null) {
                        Log.d(TAG, "Active network capabilities: $networkCapabilities")

                        // Check if this is a WiFi network (which could be hotspot)
                        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            Log.d(TAG, "Active network is WiFi-based")

                            // Try to detect clients by checking common DHCP-assigned IPs
                            // Most DHCP servers assign IPs starting from .2 or .100
                            val commonClientRanges =
                                    listOf(
                                            2..10, // Common starting range
                                            100..150, // Common DHCP range
                                            200..254 // Extended range
                                    )

                            for (range in commonClientRanges) {
                                for (i in range) {
                                    val testIp = "$baseIp.$i"
                                    if (testIp != hotspotIp) {
                                        try {
                                            // Quick reachability check
                                            val address = java.net.InetAddress.getByName(testIp)
                                            if (address.isReachable(50)) { // 50ms timeout
                                                val client =
                                                        ConnectedClient(
                                                                macAddress = "system_detected",
                                                                ipAddress = testIp,
                                                                hostname = "Client-$testIp",
                                                                connectionTime =
                                                                        System.currentTimeMillis()
                                                        )
                                                clients.add(client)
                                                Log.d(TAG, "System detected client: $testIp")
                                            }
                                        } catch (e: Exception) {
                                            // Not reachable, skip
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "System info method found ${clients.size} clients")
            clients
        } catch (e: Exception) {
            Log.e(TAG, "Error getting clients from system info", e)
            emptyList()
        }
    }

    /** Scan for connected clients by trying to detect them on the hotspot network */
    private fun scanForConnectedClients(): List<ConnectedClient> {
        return try {
            Log.d(TAG, "Scanning for connected clients on hotspot network")
            val clients = mutableListOf<ConnectedClient>()
            val hotspotIp = getHotspotIpAddress()

            if (hotspotIp != null) {
                val baseIp = hotspotIp.substring(0, hotspotIp.lastIndexOf('.'))
                Log.d(TAG, "Scanning hotspot network: $baseIp.x")

                // Try to detect clients by scanning common IP ranges
                for (i in 1..254) {
                    val testIp = "$baseIp.$i"
                    if (testIp != hotspotIp) { // Don't scan the host IP
                        try {
                            // Try to create a socket connection to detect if device is reachable
                            val socket = java.net.Socket()
                            socket.connect(
                                    java.net.InetSocketAddress(testIp, 80),
                                    100
                            ) // 100ms timeout
                            socket.close()

                            // If we can connect, this might be a client
                            val client =
                                    ConnectedClient(
                                            macAddress = "unknown",
                                            ipAddress = testIp,
                                            hostname = "Client-$testIp",
                                            connectionTime = System.currentTimeMillis()
                                    )
                            clients.add(client)
                            Log.d(TAG, "Found potential client at: $testIp")
                        } catch (e: Exception) {
                            // Connection failed, not a client
                        }
                    }
                }
            }

            Log.d(TAG, "Network scanning found ${clients.size} potential clients")
            clients
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning for connected clients", e)
            emptyList()
        }
    }
}
