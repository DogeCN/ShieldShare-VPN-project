package com.example.shieldshare.managers.proxy

import android.content.Context
import android.util.Log
import com.example.shieldshare.managers.hotspot.HotspotManager
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/** Main proxy server implementation handling HTTP/HTTPS and SOCKS5 protocols */
class ProxyServerImpl(
        private val context: Context,
        private val trafficMeter: TrafficMeter,
        private val vpnManager: VpnManager,
        private val hotspotManager: HotspotManager
) : ProxyServer {
    companion object {
        private const val TAG = "ProxyServerImpl"
    }

    private var serverSocket: ServerSocket? = null
    private var webServerSocket: ServerSocket? = null
    private val proxyHandlers = ConcurrentHashMap<String, ProxyHandler>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientDetectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentInstance: ProxyInstance? = null

    // Track unique client IPs for counting
    private val connectedClients = ConcurrentHashMap<String, Long>() // IP -> timestamp
    private val CLIENT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes timeout

    override suspend fun startProxy(config: ProxyConfig): Result<ProxyInstance> =
            withContext(Dispatchers.IO) {
                try {
                    if (serverSocket != null) {
                        return@withContext Result.failure(Exception("Proxy server already running"))
                    }

                    // Try binding to the specific hotspot interface
                    val hotspotIp = hotspotManager.getHotspotIpAddress()
                    val bindAddress =
                            if (hotspotIp != null) {
                                java.net.InetSocketAddress(hotspotIp, config.port)
                            } else {
                                java.net.InetSocketAddress("0.0.0.0", config.port)
                            }
                    serverSocket =
                            ServerSocket().apply {
                                reuseAddress = true
                                bind(bindAddress)
                            }
                    val instance =
                            ProxyInstance(
                                    instanceId = "proxy_${System.currentTimeMillis()}",
                                    config = config
                            )
                    currentInstance = instance

                    Log.i(TAG, "Starting proxy server on port ${config.port}")

                    // Start accepting connections
                    serviceScope.launch {
                        Log.d(TAG, "Starting connection acceptor coroutine")
                        acceptConnections()
                    }

                    // Start web server for auto-configuration
                    serviceScope.launch { startWebServer(config.port) }

                    // Start client cleanup coroutine
                    serviceScope.launch { startClientCleanup() }

                    // ENHANCEMENT: Start proactive client detection
                    serviceScope.launch { 
                        Log.i(TAG, "Starting proactive client detection")
                        startClientScanning() 
                    }

                    Result.success(instance)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start proxy server", e)
                    Result.failure(e)
                }
            }

    override suspend fun stopProxy(): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    serverSocket?.close()
                    serverSocket = null

                    webServerSocket?.close()
                    webServerSocket = null

                    // Stop all active handlers
                    proxyHandlers.values.forEach { handler ->
                        // Close handler connections
                    }
                    proxyHandlers.clear()

                    // Clear connected clients
                    connectedClients.clear()

                    currentInstance = null
                    Log.i(TAG, "Proxy server stopped")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop proxy server", e)
                    Result.failure(e)
                }
            }

    override fun getProxyInfo(): ProxyInfo {
        val instance = currentInstance
        val clientCount = connectedClients.size
        Log.d(TAG, "getProxyInfo() called - Connected clients count: $clientCount")
        return if (instance != null) {
            ProxyInfo(
                    isRunning = serverSocket != null && !serverSocket!!.isClosed,
                    port = instance.config.port,
                    proxyType = instance.config.proxyType,
                    activeConnections = clientCount, // Use unique client count
                    pacFileUrl =
                            hotspotManager.getHotspotIpAddress()?.let { hotspotIp ->
                                "http://$hotspotIp:${instance.config.port}/proxy.pac"
                            }
                                    ?: "Not available"
            )
        } else {
            ProxyInfo(
                    isRunning = false,
                    port = 0,
                    proxyType = ProxyType.BOTH,
                    activeConnections = 0
            )
        }
    }

    override fun handleClientConnection(socket: Socket) {
        val clientId = "${socket.remoteSocketAddress}_${System.currentTimeMillis()}"

        // Track unique client IP for counting - ENHANCED LOGGING
        val clientIp = socket.remoteSocketAddress.toString().substringAfter("/").substringBefore(':')
        val isNewClient = !connectedClients.containsKey(clientIp)
        connectedClients[clientIp] = System.currentTimeMillis()
        
        if (isNewClient) {
            Log.i(TAG, "NEW CLIENT connected via proxy: $clientIp (Total unique clients: ${connectedClients.size})")
            
            // Try to get device name for new clients
            serviceScope.launch {
                val deviceName = tryGetDeviceName(clientIp)
                if (deviceName != null && deviceName != clientIp) {
                    Log.i(TAG, "Device name for new client $clientIp: $deviceName")
                }
            }
        } else {
            Log.d(TAG, "Existing client reconnected: $clientIp (Total unique clients: ${connectedClients.size})")
        }

        // Create appropriate handler based on proxy type
        val handler =
                when (currentInstance?.config?.proxyType) {
                    ProxyType.HTTP_HTTPS ->
                            HttpProxyHandler(socket, trafficMeter) { bytesUp, bytesDown ->
                                // TODO: JIALU - Traffic metering integration point
                                // Record traffic through traffic meter
                                trafficMeter.recordTraffic(
                                        socket.remoteSocketAddress.toString(),
                                        bytesUp,
                                        bytesDown
                                )
                                proxyHandlers.remove(clientId)
                            }
                    ProxyType.SOCKS5 ->
                            Socks5ProxyHandler(socket, trafficMeter) { bytesUp, bytesDown ->
                                // TODO: JIALU - Traffic metering integration point
                                // Record traffic through traffic meter
                                trafficMeter.recordTraffic(
                                        socket.remoteSocketAddress.toString(),
                                        bytesUp,
                                        bytesDown
                                )
                                proxyHandlers.remove(clientId)
                            }
                    ProxyType.BOTH -> {
                        // For BOTH type, we need to detect the protocol from the first bytes
                        // For now, default to HTTP
                        HttpProxyHandler(socket, trafficMeter) { bytesUp, bytesDown ->
                            // TODO: JIALU - Traffic metering integration point
                            trafficMeter.recordTraffic(
                                    socket.remoteSocketAddress.toString(),
                                    bytesUp,
                                    bytesDown
                            )
                            proxyHandlers.remove(clientId)
                        }
                    }
                    null -> return // No active instance
                }

        proxyHandlers[clientId] = handler

        // Start the handler in a coroutine
        serviceScope.launch {
            try {
                handler.handleConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client connection", e)
                proxyHandlers.remove(clientId)
            }
        }
    }

    private suspend fun acceptConnections() =
            withContext(Dispatchers.IO) {
                val socket = serverSocket
                if (socket == null) {
                    Log.e(TAG, "Server socket is null, cannot accept connections")
                    return@withContext
                }

                Log.d(
                        TAG,
                        "Connection acceptor started, waiting for connections on port ${socket.localPort}"
                )

                while (isActive && !socket.isClosed) {
                    try {
                        Log.d(TAG, "Waiting for client connection...")
                        val clientSocket = socket.accept()
                        Log.d(TAG, "New client connection: ${clientSocket.remoteSocketAddress}")
                        handleClientConnection(clientSocket)
                    } catch (e: Exception) {
                        if (!socket.isClosed) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
                Log.d(TAG, "Connection acceptor stopped")
            }

    /**
     * VPN Integration Point for Hanchen
     *
     * HANCHEN: This method is called by the proxy handlers to forward data through VPN. You need to
     * implement the actual VPN forwarding logic here.
     *
     * Current behavior: Just checks VPN status (placeholder) Expected behavior: Forward data
     * through your VPN tunnel
     *
     * @param data The data to forward through VPN tunnel
     */
    private suspend fun forwardThroughVpn(data: ByteArray) {
        // TODO: HANCHEN - Implement actual VPN forwarding
        //
        // Steps you need to implement:
        // 1. Check if VPN is connected: vpnManager.getConnectionStatus()
        // 2. If connected, forward data through VPN tunnel
        // 3. If not connected, handle appropriately (block, queue, or allow)
        //
        // Example integration:
        // when (vpnManager.getConnectionStatus()) {
        //     VpnStatus.CONNECTED -> {
        //         // Forward through VPN tunnel
        //         vpnManager.forwardData(data)
        //     }
        //     else -> {
        //         // Handle disconnected state
        //         Log.w(TAG, "VPN not connected, blocking traffic")
        //     }
        // }

        val vpnStatus = vpnManager.getConnectionStatus()
        Log.d(TAG, "VPN Status = $vpnStatus")

        // Basic forwarding implementation
        when (vpnStatus) {
            VpnStatus.CONNECTED -> {
                Log.d(TAG, "VPN connected, forwarding data through VPN tunnel")
                // TODO: Implement actual VPN tunnel forwarding
                // For now, we'll allow the traffic to pass through
                // This is a placeholder - you need to implement the actual VPN forwarding
            }
            VpnStatus.DISCONNECTED -> {
                Log.w(TAG, "VPN not connected, but allowing traffic to pass through")
                // Allow traffic even without VPN
            }
            else -> {
                Log.w(TAG, "VPN status unknown: $vpnStatus, allowing traffic")
            }
        }
    }

    /** Start web server for auto-configuration page */
    private suspend fun startWebServer(proxyPort: Int) {
        try {
            val hotspotIp = hotspotManager.getHotspotIpAddress()
            val bindAddress =
                    if (hotspotIp != null) {
                        java.net.InetSocketAddress(hotspotIp, proxyPort + 1)
                    } else {
                        java.net.InetSocketAddress("0.0.0.0", proxyPort + 1)
                    }

            webServerSocket =
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(bindAddress)
                    }

            Log.i(TAG, "Web server started on port ${proxyPort + 1}")

            while (webServerSocket?.isClosed == false) {
                try {
                    val clientSocket = webServerSocket?.accept()
                    if (clientSocket != null) {
                        serviceScope.launch { handleWebRequest(clientSocket, proxyPort) }
                    }
                } catch (e: Exception) {
                    if (webServerSocket?.isClosed == false) {
                        Log.e(TAG, "Error accepting web connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
        }
    }

    /** Handle web requests for auto-configuration */
    private suspend fun handleWebRequest(socket: Socket, proxyPort: Int) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val request = input.bufferedReader().readLine()
            Log.d(TAG, "Web request: $request")

            val hotspotIp = hotspotManager.getHotspotIpAddress() ?: "192.168.43.1"

            val htmlContent = generateAutoConfigPage(hotspotIp, proxyPort)
            val contentBytes = htmlContent.toByteArray(Charsets.UTF_8)

            // Proper HTTP response format with \r\n line endings
            val response = StringBuilder()
            response.append("HTTP/1.1 200 OK\r\n")
            response.append("Content-Type: text/html; charset=UTF-8\r\n")
            response.append("Content-Length: ${contentBytes.size}\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")
            response.append(htmlContent)

            output.write(response.toString().toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling web request", e)
        } finally {
            socket.close()
        }
    }

    /** Generate auto-configuration HTML page */
    private fun generateAutoConfigPage(hotspotIp: String, proxyPort: Int): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ShieldShare Auto-Configuration</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
        .container { max-width: 600px; margin: 0 auto; background: white; padding: 20px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        .header { text-align: center; color: #2c3e50; margin-bottom: 30px; }
        .config-section { margin: 20px 0; padding: 15px; background: #ecf0f1; border-radius: 5px; }
        .button { background: #3498db; color: white; padding: 12px 24px; border: none; border-radius: 5px; cursor: pointer; font-size: 16px; margin: 10px 5px; }
        .button:hover { background: #2980b9; }
        .success { background: #27ae60; }
        .info { background: #f39c12; color: white; padding: 10px; border-radius: 5px; margin: 10px 0; }
        .manual-config { background: #e8f4f8; padding: 15px; border-radius: 5px; margin: 10px 0; }
        .code { background: #2c3e50; color: #ecf0f1; padding: 10px; border-radius: 3px; font-family: monospace; }
        .mini-code { background: #2c3e50; color: #ecf0f1; padding: 5px; border-radius: 3px; font-family: monospace;font-size:9px }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>ShieldShare Auto-Configuration</h1>
            <p>Configure your device to use ShieldShare proxy automatically</p>
        </div>
        
        <div class="info">
            <strong>Proxy Server:</strong> $hotspotIp:$proxyPort<br>
            <strong>PAC URL:</strong> http://$hotspotIp:$proxyPort/proxy.pac
        </div>
        
        <div class="config-section">
            <h3>One-Click Auto-Configuration</h3>
            <p>Click the button below to automatically configure your device:</p>
            <button class="button" onclick="autoConfigure()">Auto-Configure Proxy</button>
            <button class="button success" onclick="downloadPAC()">Download PAC File</button>
        </div>
        
        <div class="manual-config">
            <h3>Manual Configuration</h3>
            <p><strong>For Android:</strong></p>
            <ol>
                <li>Go to Settings → Wi-Fi</li>
                <li>Long press your connected network</li>
                <li>Modify → Advanced → Proxy → Manual</li>
                <li>Server: <span class="mini-code">$hotspotIp</span></li>
                <li>Port: <span class="mini-code">$proxyPort</span></li>
            </ol>
            
            <p><strong>For iOS:</strong></p>
            <ol>
                <li>Settings → Wi-Fi → (i) icon</li>
                <li>Configure Proxy → Manual</li>
                <li>Server: <span class="mini-code">$hotspotIp</span></li>
                <li>Port: <span class="mini-code">$proxyPort</span></li>
            </ol>
        </div>
        
        <div class="config-section">
            <h3>PAC Auto-Configuration</h3>
            <p>Use PAC file for automatic proxy routing:</p>
            <div class="code">http://$hotspotIp:$proxyPort/proxy.pac</div>
            <p><small>Copy this URL to your device's Proxy Auto-Configuration settings</small></p>
        </div>
    </div>
    
    <script>
        function autoConfigure() {
            // Try to open system proxy settings
            if (navigator.userAgent.includes('Android')) {
                alert('Please manually configure proxy in Android Wi-Fi settings:\\n\\nServer: $hotspotIp\\nPort: $proxyPort');
            } else if (navigator.userAgent.includes('iPhone') || navigator.userAgent.includes('iPad')) {
                alert('Please manually configure proxy in iOS Wi-Fi settings:\\n\\nServer: $hotspotIp\\nPort: $proxyPort');
            } else {
                alert('Auto-configuration not supported on this device.\\nPlease use manual configuration.');
            }
        }
        
        function downloadPAC() {
            window.open('http://$hotspotIp:$proxyPort/proxy.pac', '_blank');
        }
        
        // Auto-detect device type and show appropriate instructions
        document.addEventListener('DOMContentLoaded', function() {
            const isAndroid = navigator.userAgent.includes('Android');
            const isIOS = navigator.userAgent.includes('iPhone') || navigator.userAgent.includes('iPad');
            
            if (isAndroid || isIOS) {
                document.querySelector('.info').innerHTML += '<br><strong>Device:</strong> ' + (isAndroid ? 'Android' : 'iOS');
            }
        });
    </script>
</body>
</html>
        """.trimIndent()
    }

    /** Clean up old client IPs that haven't connected recently */
    private suspend fun startClientCleanup() {
        coroutineScope {
            while (isActive) {
                try {
                    val currentTime = System.currentTimeMillis()
                    val iterator = connectedClients.iterator()

                    while (iterator.hasNext()) {
                        val (ip, timestamp) = iterator.next()
                        if (currentTime - timestamp > CLIENT_TIMEOUT_MS) {
                            iterator.remove()
                            Log.d(TAG, "Removed stale client IP: $ip")
                        }
                    }

                    delay(30_000) // Clean up every 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in client cleanup", e)
                    delay(60_000) // Wait longer on error
                }
            }
        }
    }

    /** ENHANCEMENT: Proactive client detection through subnet scanning */
    private suspend fun startClientScanning() {
        coroutineScope {
            while (isActive) {
                try {
                    Log.i(TAG, "Running client scan...")
                    scanForConnectedDevices()
                    delay(15_000) // Scan every 15 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in client scanning", e)
                    delay(30_000) // Wait longer on error
                }
            }
        }
    }

    /** Scan hotspot subnet for connected devices */
    private suspend fun scanForConnectedDevices() {
        val hotspotIp = hotspotManager.getHotspotIpAddress()
        if (hotspotIp == null) {
            Log.d(TAG, "No hotspot IP available for scanning")
            return
        }

        val subnet = hotspotIp.substringBeforeLast(".")
        Log.i(TAG, "Scanning subnet: $subnet.x for connected devices")

        // Scan common device IPs (usually .2 to .10 for most hotspots)
        val scanJobs = (2..10).map { i ->
            clientDetectionScope.async {
                val targetIp = "$subnet.$i"
                Log.d(TAG, "Pinging $targetIp...")
                if (pingDevice(targetIp)) {
                    Log.i(TAG, "Ping successful for $targetIp!")
                    // Device found - add to connected clients if not already tracked
                    if (!connectedClients.containsKey(targetIp)) {
                        connectedClients[targetIp] = System.currentTimeMillis()
                        Log.i(TAG, "Discovered new device via ping: $targetIp (Total clients: ${connectedClients.size})")
                        
                        // Try to get device name for better identification
                        clientDetectionScope.launch {
                            val deviceName = tryGetDeviceName(targetIp)
                            if (deviceName != null && deviceName != targetIp) {
                                Log.i(TAG, "Device name for $targetIp: $deviceName")
                            }
                        }
                    } else {
                        // Update last seen time for existing device
                        connectedClients[targetIp] = System.currentTimeMillis()
                        Log.d(TAG, "Updated last seen for device: $targetIp")
                    }
                } else {
                    Log.d(TAG, "No response from $targetIp")
                }
            }
        }

        // Wait for all ping operations (max 3 seconds)
        withTimeoutOrNull(3000) {
            scanJobs.awaitAll()
        }
        
        // Log final scan results
        Log.i(TAG, "Scan completed. Total connected clients: ${connectedClients.size}")
        if (connectedClients.isNotEmpty()) {
            Log.i(TAG, "Connected client IPs: ${connectedClients.keys.joinToString(", ")}")
        }
    }

    /** Fast ping check for device availability */
    private suspend fun pingDevice(ip: String): Boolean = withTimeoutOrNull(800) {
        try {
            val address = InetAddress.getByName(ip)
            address.isReachable(500) // 500ms timeout per ping
        } catch (e: Exception) {
            false
        }
    } ?: false

    /** Try to get device name via reverse DNS lookup */
    private suspend fun tryGetDeviceName(ip: String): String? = withTimeoutOrNull(1000) {
        try {
            InetAddress.getByName(ip).hostName.takeIf { it != ip }
        } catch (e: Exception) {
            null
        }
    }
}
