package com.example.shieldshare.managers.proxy

import android.content.Context
import android.util.Log
import com.example.shieldshare.managers.hotspot.HotspotManager
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
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
    private var configServerSocket: ServerSocket? = null
    private val proxyHandlers = ConcurrentHashMap<String, ProxyHandler>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentInstance: ProxyInstance? = null

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

                    // Start configuration server
                    serviceScope.launch {
                        Log.d(TAG, "Starting configuration server")
                        startConfigServer(config.port)
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
                    configServerSocket?.close()
                    configServerSocket = null

                    // Stop all active handlers
                    proxyHandlers.values.forEach { handler ->
                        // Close handler connections
                    }
                    proxyHandlers.clear()

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
        return if (instance != null) {
            ProxyInfo(
                    isRunning = serverSocket != null && !serverSocket!!.isClosed,
                    port = instance.config.port,
                    proxyType = instance.config.proxyType,
                    activeConnections = proxyHandlers.size,
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
                // Allow traffic even without VPN for testing
            }
            else -> {
                Log.w(TAG, "VPN status unknown: $vpnStatus, allowing traffic")
            }
        }
    }

    private suspend fun startConfigServer(proxyPort: Int) =
            withContext(Dispatchers.IO) {
                try {
                    // Use a different port for the config server (proxyPort + 1)
                    val configPort = proxyPort + 1
                    // Try binding to the specific hotspot interface
                    val hotspotIp = hotspotManager.getHotspotIpAddress()
                    val bindAddress =
                            if (hotspotIp != null) {
                                java.net.InetSocketAddress(hotspotIp, configPort)
                            } else {
                                java.net.InetSocketAddress("0.0.0.0", configPort)
                            }
                    configServerSocket =
                            ServerSocket().apply {
                                reuseAddress = true
                                bind(bindAddress)
                            }
                    Log.i(TAG, "Configuration server listening on port $configPort")
                    Log.i(
                            TAG,
                            "Configuration server should be accessible at: http://[hotspot-ip]:$configPort/configure"
                    )

                    while (isActive && !configServerSocket!!.isClosed) {
                        try {
                            val clientSocket = configServerSocket!!.accept()
                            Log.d(
                                    TAG,
                                    "Configuration request from: ${clientSocket.remoteSocketAddress}"
                            )

                            serviceScope.launch { handleConfigRequest(clientSocket) }
                        } catch (e: Exception) {
                            if (!configServerSocket!!.isClosed) {
                                Log.e(TAG, "Error accepting configuration connection", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start configuration server", e)
                }
            }

    private fun handleConfigRequest(socket: Socket) {
        try {
            Log.d(TAG, "Handling configuration request from: ${socket.remoteSocketAddress}")
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val writer = PrintWriter(output, true)

            // Read the HTTP request
            val requestLine = reader.readLine()
            Log.d(TAG, "Config request: $requestLine")

            // Check if it's a request for /configure
            if (requestLine?.contains("/configure") == true) {
                val configPage = generateConfigPage()

                writer.println("HTTP/1.1 200 OK")
                writer.println("Content-Type: text/html; charset=UTF-8")
                writer.println("Content-Length: ${configPage.length}")
                writer.println("Cache-Control: no-cache")
                writer.println("Access-Control-Allow-Origin: *")
                writer.println()
                writer.println(configPage)
                writer.flush()

                Log.d(TAG, "Served configuration page to client")
            } else if (requestLine?.contains("/test") == true) {
                // Simple test endpoint
                writer.println("HTTP/1.1 200 OK")
                writer.println("Content-Type: text/plain")
                writer.println("Cache-Control: no-cache")
                writer.println()
                writer.println("Configuration server is working!")
                writer.flush()

                Log.d(TAG, "Served test page to client")
            } else {
                // Return 404 for other requests
                writer.println("HTTP/1.1 404 Not Found")
                writer.println("Content-Type: text/plain")
                writer.println()
                writer.println("Not Found")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling configuration request", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing configuration socket", e)
            }
        }
    }

    private fun generateConfigPage(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ShieldShare Proxy Configuration</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            margin: 0;
            padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: #333;
            min-height: 100vh;
        }
        .container {
            max-width: 600px;
            margin: 0 auto;
            background: white;
            border-radius: 16px;
            padding: 30px;
            box-shadow: 0 20px 40px rgba(0,0,0,0.1);
        }
        .header {
            text-align: center;
            margin-bottom: 30px;
        }
        .logo {
            font-size: 2.5em;
            font-weight: bold;
            color: #667eea;
            margin-bottom: 10px;
        }
        .subtitle {
            color: #666;
            font-size: 1.1em;
        }
        .device-section {
            margin: 25px 0;
            padding: 20px;
            border: 2px solid #f0f0f0;
            border-radius: 12px;
            background: #fafafa;
        }
        .device-title {
            font-size: 1.3em;
            font-weight: bold;
            color: #333;
            margin-bottom: 15px;
            display: flex;
            align-items: center;
        }
        .device-icon {
            font-size: 1.5em;
            margin-right: 10px;
        }
        .step {
            margin: 15px 0;
            padding: 15px;
            background: white;
            border-radius: 8px;
            border-left: 4px solid #667eea;
        }
        .step-number {
            font-weight: bold;
            color: #667eea;
            margin-bottom: 8px;
        }
        .proxy-info {
            background: #e8f4fd;
            padding: 15px;
            border-radius: 8px;
            margin: 20px 0;
            text-align: center;
        }
        .proxy-url {
            font-family: monospace;
            background: #f0f0f0;
            padding: 8px 12px;
            border-radius: 4px;
            margin: 5px 0;
            word-break: break-all;
        }
        .button {
            background: #667eea;
            color: white;
            border: none;
            padding: 12px 24px;
            border-radius: 8px;
            font-size: 1em;
            cursor: pointer;
            margin: 10px 5px;
            text-decoration: none;
            display: inline-block;
        }
        .button:hover {
            background: #5a6fd8;
        }
        .note {
            background: #fff3cd;
            border: 1px solid #ffeaa7;
            padding: 15px;
            border-radius: 8px;
            margin: 20px 0;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">🛡️ ShieldShare</div>
            <div class="subtitle">Proxy Configuration Assistant</div>
        </div>

        <div class="proxy-info">
            <h3>📡 Proxy Server Information</h3>
            <div class="proxy-url" id="proxyUrl">Loading...</div>
            <div class="proxy-url" id="pacUrl">Loading...</div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">📱</span>
                iOS (iPhone/iPad)
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>Settings → Wi-Fi</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Tap the <strong>(i)</strong> icon next to your connected Wi-Fi network
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Scroll down to <strong>Configure Proxy</strong> → Select <strong>Manual</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 4:</div>
                Enter the proxy server details from above and tap <strong>Save</strong>
            </div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">🤖</span>
                Android
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>Settings → Wi-Fi</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Long press your connected Wi-Fi network → <strong>Modify</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Tap <strong>Advanced options</strong> → <strong>Proxy</strong> → <strong>Manual</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 4:</div>
                Enter the proxy server details from above and tap <strong>Save</strong>
            </div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">🪟</span>
                Windows
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>Settings → Network & Internet → Proxy</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Turn on <strong>Use a proxy server</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Enter the proxy server details from above
            </div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">🍎</span>
                macOS
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>System Preferences → Network</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Select your Wi-Fi connection → <strong>Advanced</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Go to <strong>Proxies</strong> tab → Check <strong>Web Proxy (HTTP)</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 4:</div>
                Enter the proxy server details from above
            </div>
        </div>

        <div class="note">
            <strong>💡 Tip:</strong> After configuring the proxy, test it by visiting a website. If it works, you're connected through the ShieldShare proxy!
        </div>

        <div style="text-align: center; margin-top: 30px;">
            <button class="button" onclick="copyProxyInfo()">📋 Copy Proxy Info</button>
            <button class="button" onclick="testConnection()">🔗 Test Connection</button>
        </div>
    </div>

    <script>
        // Auto-detect device and show relevant instructions
        function detectDevice() {
            const userAgent = navigator.userAgent;
            const sections = document.querySelectorAll('.device-section');
            
            // Hide all sections initially
            sections.forEach(section => section.style.display = 'none');
            
            if (/iPhone|iPad|iPod/.test(userAgent)) {
                sections[0].style.display = 'block'; // iOS
            } else if (/Android/.test(userAgent)) {
                sections[1].style.display = 'block'; // Android
            } else if (/Windows/.test(userAgent)) {
                sections[2].style.display = 'block'; // Windows
            } else if (/Macintosh|Mac OS X/.test(userAgent)) {
                sections[3].style.display = 'block'; // macOS
            } else {
                // Show all sections if unknown
                sections.forEach(section => section.style.display = 'block');
            }
        }

        function copyProxyInfo() {
            const proxyUrl = document.getElementById('proxyUrl').textContent;
            const pacUrl = document.getElementById('pacUrl').textContent;
            const text = 'Proxy Server: ' + proxyUrl + '\\nPAC File: ' + pacUrl;
            
            navigator.clipboard.writeText(text).then(() => {
                alert('Proxy information copied to clipboard!');
            });
        }

        function testConnection() {
            // Test if proxy is working by making a request
            fetch('/test', { method: 'GET' })
                .then(response => {
                    if (response.ok) {
                        alert('✅ Connection successful! Proxy is working.');
                    } else {
                        alert('❌ Connection failed. Please check your proxy settings.');
                    }
                })
                .catch(() => {
                    alert('❌ Connection failed. Please check your proxy settings.');
                });
        }

        // Initialize page
        window.onload = function() {
            detectDevice();
            
            // Set proxy URLs (these would be dynamically set by the server)
            const currentHost = window.location.host;
            document.getElementById('proxyUrl').textContent = 'http://' + currentHost;
            document.getElementById('pacUrl').textContent = 'http://' + currentHost + '/proxy.pac';
        };
    </script>
</body>
</html>
        """.trimIndent()
    }
}
