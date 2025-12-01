package com.example.shieldshare.managers.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.example.shieldshare.R
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.hotspot.HotspotManager
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.quota.QuotaManager
import com.example.shieldshare.managers.quota.QuotaStatus
import com.example.shieldshare.managers.filter.TrafficFilterManager
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import com.example.shieldshare.managers.vpn.isVpnConnected
import com.example.shieldshare.managers.vpn.vpnAwareSocketFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/** Main proxy server implementation handling HTTP/HTTPS and SOCKS5 protocols */
class ProxyServerImpl(
        private val context: Context,
        private val trafficMeter: TrafficMeter,
        private val vpnManager: VpnManager,
        private val hotspotManager: HotspotManager,
        private val appPrefs: AppPrefs,
        private val quotaManager: QuotaManager,
        private val trafficFilterManager: TrafficFilterManager? = null
) : ProxyServer {
    companion object {
        private const val TAG = "ProxyServerImpl"
        private const val CLIENT_NOTIFICATION_CHANNEL_ID = "client_notifications"
        private const val ABUSE_NOTIFICATION_CHANNEL_ID = "abuse_notifications"
        private const val CLIENT_NOTIFICATION_ID_BASE = 1000
        private const val ABUSE_NOTIFICATION_ID_BASE = 3000
        private val notificationCounter = AtomicInteger(0)
    }

    private var httpServerSocket: ServerSocket? = null
    private var socksServerSocket: ServerSocket? = null
    private var webServerSocket: ServerSocket? = null
    private val proxyHandlers = ConcurrentHashMap<String, ProxyHandler>()
    private val handlerTimestamps = ConcurrentHashMap<String, Long>() // Track when handlers were created
    private val handlersPerClient = ConcurrentHashMap<String, Int>() // Track handler count per client for O(1) lookup
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientDetectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentInstance: ProxyInstance? = null
    
    // Connection limits to prevent resource exhaustion
    // Modern browsers open 6-8 concurrent connections per domain, so we need reasonable limits
    // With 7 pages × 8 connections = 56 connections, we need higher limits
    private val MAX_CONCURRENT_HANDLERS = 200 // Maximum concurrent proxy handlers (increased for multiple pages)
    private val MAX_HANDLERS_PER_CLIENT = 60 // Maximum handlers per client IP (increased - browsers use many connections)
    private val HANDLER_TIMEOUT_MS = 120_000L // 2 minutes - remove handlers that are stuck

    private fun createServerSocket(port: Int, hotspotIp: String?): ServerSocket {
        // Always bind to 0.0.0.0 to accept connections from any interface
        // This ensures compatibility with both 2.4GHz and 5GHz hotspots
        // The hotspotIp parameter is kept for logging purposes but not used for binding
        val bindAddress = InetSocketAddress("0.0.0.0", port)
        if (hotspotIp != null) {
            Log.d(TAG, "Binding proxy server to all interfaces (0.0.0.0:$port) - hotspot IP: $hotspotIp")
        } else {
            Log.d(TAG, "Binding proxy server to all interfaces (0.0.0.0:$port) - no hotspot IP detected")
        }
        return ServerSocket().apply {
            reuseAddress = true
            soTimeout = 1000 // 1 second timeout for accept() - fail fast if no connections, allows checking isActive
            // Set backlog to allow many pending connections - browsers open many connections quickly
            // With 8 pages × 8 connections/page = 64 connections, plus burst traffic
            bind(bindAddress, 500) // Allow up to 500 pending connections (significantly increased)
        }
    }

    private fun closeServer(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing server socket", t)
        }
    }

    // Track unique client IPs for counting
    private val connectedClients = ConcurrentHashMap<String, Long>() // IP -> timestamp
    private val CLIENT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes timeout

    override suspend fun startProxy(config: ProxyConfig): Result<ProxyInstance> =
            withContext(Dispatchers.IO) {
                try {
                    if (httpServerSocket != null || socksServerSocket != null) {
                        return@withContext Result.failure(Exception("Proxy server already running"))
                    }

                    if (!ProxyPortManager.validatePortConfiguration(config)) {
                        Log.w(
                                TAG,
                                "Custom proxy ports detected; ShieldShare enforces dedicated defaults."
                        )
                    }

                    val hotspotIp = hotspotManager.getHotspotIpAddress()

                    try {
                        // Conditionally start servers based on proxyType
                        when (config.proxyType) {
                            ProxyType.HTTP_HTTPS -> {
                                httpServerSocket = createServerSocket(config.httpPort, hotspotIp)
                                Log.i(
                                        TAG,
                                        "Starting HTTP/HTTPS proxy server on port ${config.httpPort}"
                                )
                            }
                            ProxyType.SOCKS5 -> {
                                socksServerSocket = createServerSocket(config.socks5Port, hotspotIp)
                                Log.i(
                                        TAG,
                                        "Starting SOCKS5 proxy server on port ${config.socks5Port}"
                                )
                            }
                            ProxyType.BOTH -> {
                                httpServerSocket = createServerSocket(config.httpPort, hotspotIp)
                                socksServerSocket = createServerSocket(config.socks5Port, hotspotIp)
                                Log.i(
                                        TAG,
                                        "Starting both proxy servers (HTTP/HTTPS ${config.httpPort}, SOCKS5 ${config.socks5Port})"
                                )
                            }
                        }
                    } catch (bindError: Exception) {
                        Log.e(TAG, "Failed to bind proxy sockets", bindError)
                        closeServer(httpServerSocket)
                        closeServer(socksServerSocket)
                        httpServerSocket = null
                        socksServerSocket = null
                        return@withContext Result.failure(bindError)
                    }

                    val instance =
                            ProxyInstance(
                                    instanceId = "proxy_${System.currentTimeMillis()}",
                                    config = config
                            )
                    currentInstance = instance

                    // Start acceptor coroutines only for enabled protocols
                    when (config.proxyType) {
                        ProxyType.HTTP_HTTPS -> {
                            serviceScope.launch {
                                httpServerSocket?.let { socket ->
                                    Log.d(
                                            TAG,
                                            "Starting HTTP/HTTPS acceptor coroutine on port ${socket.localPort}"
                                    )
                                    acceptConnections(socket, "HTTP/HTTPS")
                                }
                            }
                        }
                        ProxyType.SOCKS5 -> {
                            serviceScope.launch {
                                socksServerSocket?.let { socket ->
                                    Log.d(
                                            TAG,
                                            "Starting SOCKS5 acceptor coroutine on port ${socket.localPort}"
                                    )
                                    acceptConnections(socket, "SOCKS5")
                                }
                            }
                        }
                        ProxyType.BOTH -> {
                            serviceScope.launch {
                                httpServerSocket?.let { socket ->
                                    Log.d(
                                            TAG,
                                            "Starting HTTP/HTTPS acceptor coroutine on port ${socket.localPort}"
                                    )
                                    acceptConnections(socket, "HTTP/HTTPS")
                                }
                            }
                            serviceScope.launch {
                                socksServerSocket?.let { socket ->
                                    Log.d(
                                            TAG,
                                            "Starting SOCKS5 acceptor coroutine on port ${socket.localPort}"
                                    )
                                    acceptConnections(socket, "SOCKS5")
                                }
                            }
                        }
                    }

                    // Initialize notification channel proactively for instant notifications
                    createClientNotificationChannel()
                    
                    // Setup quota exhaustion notification callback
                    quotaManager.onQuotaExceeded = { clientIp, macAddress ->
                        showQuotaExceededNotification(clientIp, macAddress)
                    }
                    
                    // Load quota configuration
                    quotaManager.loadConfig()
                    
                    serviceScope.launch { startWebServer(config) }
                    serviceScope.launch { startClientCleanup() }
                    serviceScope.launch { startHandlerCleanup() }
                    serviceScope.launch {
                        Log.i(TAG, "Starting proactive client detection")
                        startClientScanning()
                    }

                    Result.success(instance)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start proxy servers", e)
                    Result.failure(e)
                }
            }

    override suspend fun stopProxy(): Result<Unit> =
            withContext(Dispatchers.IO) {
                try {
                    closeServer(httpServerSocket)
                    closeServer(socksServerSocket)
                    httpServerSocket = null
                    socksServerSocket = null

                    webServerSocket?.close()
                    webServerSocket = null

                    // Stop all active handlers
                    proxyHandlers.values.forEach { _ ->
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

        // Filter out host device IP from client count
        val hostIp = hotspotManager.getHotspotIpAddress()
        val clientCount =
                if (hostIp != null) {
                    connectedClients.keys.count { it != hostIp }
                } else {
                    connectedClients.size
                }

        Log.d(
                TAG,
                "getProxyInfo() called - Connected clients count: $clientCount (host IP: $hostIp)"
        )

        // More robust proxy status detection:
        // Check if sockets exist and are not closed
        val isRunning =
                try {
                    val httpRunning = httpServerSocket != null && !httpServerSocket!!.isClosed
                    val socksRunning = socksServerSocket != null && !socksServerSocket!!.isClosed

                    // Proxy is running if at least one socket is open
                    // Also check instance to handle edge cases during startup/shutdown
                    val socketsRunning = httpRunning || socksRunning
                    val hasInstance = instance != null

                    // If instance exists, we trust it (sockets might be temporarily null during
                    // transitions)
                    // Otherwise, rely on socket state
                    if (hasInstance) {
                        socketsRunning || (httpServerSocket != null || socksServerSocket != null)
                    } else {
                        socketsRunning
                    }
                } catch (e: Exception) {
                    // If there's an exception checking socket state, fall back to instance check
                    Log.w(TAG, "Error checking proxy status, using instance check: ${e.message}")
                    instance != null
                }

        return if (instance != null) {
            ProxyInfo(
                    isRunning = isRunning,
                    httpPort = instance.config.httpPort,
                    httpsPort = instance.config.httpsPort,
                    socks5Port = instance.config.socks5Port,
                    proxyType = instance.config.proxyType,
                    activeConnections = clientCount, // Use filtered client count (excluding host)
                    pacFileUrl =
                            hotspotManager.getHotspotIpAddress()?.let { hotspotIp ->
                                "http://$hotspotIp:${ProxyPortManager.CONFIG_PORT}/proxy.pac"
                            }
                                    ?: "Not available"
            )
        } else {
            ProxyInfo(
                    isRunning = isRunning, // Still check sockets even if instance is null (might be
                    // during shutdown)
                    httpPort = ProxyPortManager.HTTP_PORT,
                    httpsPort = ProxyPortManager.HTTPS_PORT,
                    socks5Port = ProxyPortManager.SOCKS5_PORT,
                    proxyType = ProxyType.BOTH,
                    activeConnections = clientCount
            )
        }
    }

    override fun handleClientConnection(socket: Socket, expectedProtocol: ProxyType?) {
        val connectionStartTime = System.currentTimeMillis()
        val clientId = "${socket.remoteSocketAddress}_${System.currentTimeMillis()}"
        val clientAddress = socket.remoteSocketAddress.toString()
        Log.i(TAG, "[PERF] handleClientConnection START: $clientAddress | Time: $connectionStartTime")

        val instanceConfig = currentInstance?.config
        val authConfigured =
                instanceConfig?.authEnabled == true &&
                        !instanceConfig.authUsername.isNullOrEmpty() &&
                        !instanceConfig.authPassword.isNullOrEmpty()
        val authUsername = if (authConfigured) instanceConfig?.authUsername else null
        val authPassword = if (authConfigured) instanceConfig?.authPassword else null

        // Validate socket connection to prevent crashes
        if (socket.isClosed || !socket.isConnected) {
            Log.w(TAG, "Invalid socket connection, closing")
            try {
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing invalid socket", e)
            }
            return
        }

        // Track unique client IP for counting - ENHANCED LOGGING
        val clientIp =
                socket.remoteSocketAddress.toString().substringAfter("/").substringBefore(':')

        // Filter out host device IP - don't count it as a client
        val hostIp = hotspotManager.getHotspotIpAddress()
        
        // Check quota before accepting connection (for non-host clients)
        // Use HTTP response as default
        if (hostIp == null || clientIp != hostIp) {
            if (quotaManager.isClientBlocked(clientIp)) {
                Log.w(TAG, "Connection rejected: Client $clientIp is blocked due to quota exhaustion")
                try {
                    // Try to detect protocol quickly for proper error response
                    socket.soTimeout = 1000 // Short timeout
                    val peek = socket.getInputStream().read()
                    socket.soTimeout = 0
                    if (peek == 0x05) {
                        // SOCKS5
                        sendSocks5GeneralFailure(socket)
                    } else {
                        // HTTP/HTTPS
                        sendHttp503QuotaExceeded(socket)
                    }
                } catch (e: Exception) {
                    // If detection fails, default to HTTP response
                    try {
                        sendHttp503QuotaExceeded(socket)
                    } catch (ignored: Exception) {}
                } finally {
                    safeClose(socket)
                }
                return
            }
        }
        
        if (hostIp != null && clientIp == hostIp) {
            Log.d(TAG, "Ignoring connection from host device IP: $clientIp")
            // Still process the connection, just don't count it as a client
        } else {
            val isNewClient = !connectedClients.containsKey(clientIp)
            connectedClients[clientIp] = System.currentTimeMillis()

            if (isNewClient) {
                Log.i(
                        TAG,
                        "NEW CLIENT connected via proxy: $clientIp (Total unique clients: ${connectedClients.size})"
                )
                // Show notification for new client
                showNewClientNotification(clientIp)
                // Try to get device name for new clients
                serviceScope.launch {
                    val deviceName = tryGetDeviceName(clientIp)
                    if (deviceName != null && deviceName != clientIp) {
                        Log.i(TAG, "Device name for new client $clientIp: $deviceName")
                    }
                }
                
                // Initialize quotas when new client connects (synchronously to ensure quotas are set before traffic starts)
                val connectedClientList = connectedClients.keys.toList()
                quotaManager.initializeQuotas(connectedClientList, hostIp)
            } else {
                Log.d(
                        TAG,
                        "Existing client reconnected: $clientIp (Total unique clients: ${connectedClients.size})"
                )
            }
        }

        // Strict mode: ensure all outbound sockets go via VPN; if not connected, reject early
        // to avoid Traffic leakage
        val strictVpn = true

        // Determine protocol type - use expectedProtocol if provided, otherwise detect
        // Set short timeout for protocol detection to prevent blocking
        val protocolDetectStart = System.currentTimeMillis()
        val originalTimeout = try { socket.soTimeout } catch (e: Exception) { 0 }
        val (ptype, inOverride) = when {
            expectedProtocol != null -> {
                val duration = System.currentTimeMillis() - protocolDetectStart
                Log.d(TAG, "[PERF] Protocol from expected: $expectedProtocol | Duration: ${duration}ms")
                expectedProtocol to null
            }
            currentInstance?.config?.proxyType == ProxyType.BOTH -> {
                try {
                    // Set short timeout for protocol detection to prevent long blocking
                    socket.soTimeout = 2000 // 2 seconds max for protocol detection
                } catch (e: Exception) {
                    Log.w(TAG, "Error setting timeout for protocol detection", e)
                }
                try {
                    val detectResult = detectProtocolAndWrap(socket)
                    val duration = System.currentTimeMillis() - protocolDetectStart
                    Log.i(TAG, "[PERF] Protocol detection: ${detectResult.first} | Duration: ${duration}ms")
                    detectResult
                } finally {
                    // Restore original timeout
                    try {
                        socket.soTimeout = originalTimeout
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
            currentInstance?.config?.proxyType == ProxyType.HTTP_HTTPS -> {
                val duration = System.currentTimeMillis() - protocolDetectStart
                Log.d(TAG, "[PERF] Protocol from config: HTTP_HTTPS | Duration: ${duration}ms")
                ProxyType.HTTP_HTTPS to null
            }
            currentInstance?.config?.proxyType == ProxyType.SOCKS5 -> {
                val duration = System.currentTimeMillis() - protocolDetectStart
                Log.d(TAG, "[PERF] Protocol from config: SOCKS5 | Duration: ${duration}ms")
                ProxyType.SOCKS5 to null
            }
            else -> {
                Log.w(TAG, "Unknown proxy type, closing connection")
                safeClose(socket)
                return
            }
        }

        // If strict and no VPN, send appropriate error reply and close
        if (strictVpn && !context.isVpnConnected()) {
            Log.w(TAG, "VPN not connected; rejecting client $clientIp for protocol $ptype")
            try {
                when (ptype) {
                    ProxyType.SOCKS5 -> sendSocks5GeneralFailure(socket)
                    else -> sendHttp502(socket)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to send rejection response", t)
            } finally {
                safeClose(socket)
            }
            return
        }

        // Obtain VPN-aware SocketFactory (if not VPN, will fall back to default unless strict=true)
        val socketFactory = context.vpnAwareSocketFactory(strict = strictVpn)

        // Check connection limits (with thread-safe counting)
        val limitCheckStart = System.currentTimeMillis()
        val activeHandlerCount = proxyHandlers.size
        if (activeHandlerCount >= MAX_CONCURRENT_HANDLERS) {
            val duration = System.currentTimeMillis() - limitCheckStart
            Log.w(TAG, "[PERF] Rejected: Max handlers ($activeHandlerCount/${MAX_CONCURRENT_HANDLERS}) | Limit check: ${duration}ms")
            try {
                when (ptype) {
                    ProxyType.SOCKS5 -> sendSocks5GeneralFailure(socket)
                    else -> sendHttp502(socket)
                }
            } catch (ignored: Exception) {
            } finally {
                safeClose(socket)
            }
            return
        }
        
        // Check per-client handler limit (O(1) lookup using handlersPerClient map)
        val clientAddressStr = socket.remoteSocketAddress.toString()
        val clientHandlers = handlersPerClient.getOrDefault(clientAddressStr, 0)
        val limitCheckDuration = System.currentTimeMillis() - limitCheckStart
        Log.d(TAG, "[PERF] Limit check: Total: $activeHandlerCount, Client: $clientHandlers | Duration: ${limitCheckDuration}ms")
        
        if (clientHandlers >= MAX_HANDLERS_PER_CLIENT) {
            Log.w(TAG, "[PERF] Rejected: Max per-client ($clientHandlers/${MAX_HANDLERS_PER_CLIENT}) | Limit check: ${limitCheckDuration}ms")
            try {
                when (ptype) {
                    ProxyType.SOCKS5 -> sendSocks5GeneralFailure(socket)
                    else -> sendHttp502(socket)
                }
            } catch (ignored: Exception) {
            } finally {
                safeClose(socket)
            }
            return
        }
        
        // Log when we're getting close to limits for debugging
        if (activeHandlerCount > MAX_CONCURRENT_HANDLERS * 0.7) {
            Log.d(TAG, "Handler count is high: $activeHandlerCount/${MAX_CONCURRENT_HANDLERS}, client $clientIp has $clientHandlers handlers")
        }

        // Create appropriate handler
        val handler: ProxyHandler = when (ptype) {
            ProxyType.HTTP_HTTPS ->
                HttpProxyHandler(
                    clientSocket = socket,
                    trafficMeter = trafficMeter,
                    socketFactory = socketFactory,
                    inOverride = inOverride,
                    quotaManager = quotaManager,
                    trafficFilterManager = trafficFilterManager,
                    authEnabled = authConfigured,
                    authUsername = authUsername,
                    authPassword = authPassword
                ) { bytesUp, bytesDown ->
                    try {
                        val clientIpStr = socket.remoteSocketAddress.toString()
                        trafficMeter.recordTraffic(
                            clientIpStr,
                            bytesUp,
                            bytesDown
                        )
                        // Quota usage is recorded incrementally in tunnelData (same as traffic metering)
                        // No need to record here to avoid double-counting
                    } catch (e: Exception) {
                        Log.w(TAG, "Error recording traffic", e)
                    }
                    // Always remove handler when callback is called
                    removeHandler(clientId, clientAddressStr)
                    Log.d(TAG, "Handler removed via callback: $clientId (Remaining: ${proxyHandlers.size})")
                }

            ProxyType.SOCKS5 ->
                Socks5ProxyHandler(
                    clientSocket = socket,
                    trafficMeter = trafficMeter,
                    socketFactory = socketFactory,
                    inOverride = inOverride,
                    quotaManager = quotaManager,
                    trafficFilterManager = trafficFilterManager,
                    authEnabled = authConfigured,
                    authUsername = authUsername,
                    authPassword = authPassword
                ) { bytesUp, bytesDown ->
                    try {
                        val clientIpStr = socket.remoteSocketAddress.toString()
                        trafficMeter.recordTraffic(
                            clientIpStr,
                            bytesUp,
                            bytesDown
                        )
                        // Quota usage is recorded incrementally in tunnelData (same as traffic metering)
                        // No need to record here to avoid double-counting
                    } catch (e: Exception) {
                        Log.w(TAG, "Error recording traffic", e)
                    }
                    // Always remove handler when callback is called
                    removeHandler(clientId, clientAddressStr)
                    Log.d(TAG, "Handler removed via callback: $clientId (Remaining: ${proxyHandlers.size})")
                }

            else -> {
                // Should not happen
                safeClose(socket)
                return
            }
        }

        val handlerCreateStart = System.currentTimeMillis()
        proxyHandlers[clientId] = handler
        handlerTimestamps[clientId] = System.currentTimeMillis()
        // Update per-client handler count atomically
        handlersPerClient.compute(clientAddressStr) { _, count -> (count ?: 0) + 1 }
        val handlerCreateDuration = System.currentTimeMillis() - handlerCreateStart
        val totalDuration = System.currentTimeMillis() - connectionStartTime
        Log.i(TAG, "[PERF] Handler created: $clientId | Total: ${proxyHandlers.size}, Client: ${handlersPerClient.getOrDefault(clientAddressStr, 0)} | Create: ${handlerCreateDuration}ms | Total setup: ${totalDuration}ms")

        // Start the handler in a coroutine with proper exception handling
        serviceScope.launch {
            try {
                handler.handleConnection()
                // Handler should remove itself via callback, but ensure it's removed if callback wasn't called
                // This is a safety net - the callback should handle removal
            } catch (e: CancellationException) {
                // Normal cancellation, ensure handler is removed
                Log.d(TAG, "Handler cancelled for $clientId")
                removeHandler(clientId, clientAddressStr)
                Log.d(TAG, "Handler removed after cancellation: $clientId (Remaining: ${proxyHandlers.size})")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client connection for $clientId", e)
                // Ensure cleanup happens even on error - handler MUST be removed
                try {
                    removeHandler(clientId, clientAddressStr)
                    Log.d(TAG, "Handler removed after error: $clientId (Remaining: ${proxyHandlers.size})")
                } catch (cleanupError: Exception) {
                    Log.e(TAG, "Error removing handler from map", cleanupError)
                }
                try {
                    safeClose(socket)
                } catch (closeError: Exception) {
                    Log.e(TAG, "Error closing socket", closeError)
                }
            } finally {
                // Final safety net - ensure handler is removed even if everything else fails
                // Only remove if still present (callback might have already removed it)
                if (proxyHandlers.containsKey(clientId)) {
                    removeHandler(clientId, clientAddressStr)
                    Log.w(TAG, "Handler $clientId removed in finally block (safety net)")
                }
            }
        }
    }

    private suspend fun acceptConnections(
            serverSocket: ServerSocket,
            protocolLabel: String
    ) = withContext(Dispatchers.IO) {
        Log.d(
                TAG,
                "$protocolLabel acceptor started, waiting for connections on port ${serverSocket.localPort}"
        )

        // Determine protocol type from label
        val expectedProtocol = when (protocolLabel) {
            "HTTP/HTTPS" -> ProxyType.HTTP_HTTPS
            "SOCKS5" -> ProxyType.SOCKS5
            else -> null // Will use detection if null
        }

        while (isActive && !serverSocket.isClosed) {
            try {
                val acceptStartTime = System.currentTimeMillis()
                val clientSocket = serverSocket.accept()
                val acceptDuration = System.currentTimeMillis() - acceptStartTime
                val connectionTime = System.currentTimeMillis()
                Log.i(TAG, "[PERF] [$protocolLabel] Connection accepted: ${clientSocket.remoteSocketAddress} | Accept wait: ${acceptDuration}ms | Time: $connectionTime")
                // Launch handler in separate coroutine immediately to not block accept loop
                // This ensures connections are processed truly concurrently
                // Use Dispatchers.IO with unlimited parallelism for maximum concurrency
                serviceScope.launch(Dispatchers.IO) {
                    val startTime = System.currentTimeMillis()
                    try {
                        handleClientConnection(clientSocket, expectedProtocol)
                        val duration = System.currentTimeMillis() - startTime
                        Log.i(TAG, "[PERF] handleClientConnection completed: ${clientSocket.remoteSocketAddress} | Total: ${duration}ms")
                    } catch (e: Exception) {
                        val duration = System.currentTimeMillis() - startTime
                        Log.e(TAG, "[PERF] Error in handleClientConnection after ${duration}ms: ${clientSocket.remoteSocketAddress}", e)
                        try {
                            clientSocket.close()
                        } catch (closeError: Exception) {
                            Log.w(TAG, "Error closing socket after handler error", closeError)
                        }
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Normal timeout - accept() timed out waiting for connection, just continue loop
                // This allows us to check isActive and exit gracefully
            } catch (e: Exception) {
                if (!serverSocket.isClosed) {
                    Log.e(TAG, "Error accepting $protocolLabel connection", e)
                }
            }
        }
        Log.d(TAG, "$protocolLabel acceptor stopped")
    }

    /**
     * Note: Under the current approach of using the system-level third-party VPN, there is no need
     * to "write into a VPN tunnel" here. All outbound sockets are already bound to the VPN via
     * activeNetwork.socketFactory. This method is only for logging and policy control.
     */
    private suspend fun forwardThroughVpn() {
        val vpnStatus = vpnManager.getConnectionStatus()
        Log.d(TAG, "VPN Status = $vpnStatus")
        when (vpnStatus) {
            VpnStatus.CONNECTED -> {
                // Go through socketFactory to VPN，No extra work needed
                Log.d(TAG, "VPN connected; outbound sockets are created via VPN Network.")
            }
            VpnStatus.DISCONNECTED -> {
                Log.w(TAG, "VPN not connected. (strict gating is enforced at connection time)")
            }
            else -> {
                Log.w(TAG, "VPN status unknown: $vpnStatus")
            }
        }
    }

    /** Start web server for auto-configuration page */
    private suspend fun startWebServer(config: ProxyConfig) {
        try {
            val hotspotIp = hotspotManager.getHotspotIpAddress()
            val portalPort = ProxyPortManager.CONFIG_PORT
            Log.d(
                    TAG,
                    "Attempting to start web server on port $portalPort (hotspot IP: $hotspotIp)"
            )

            // Always bind to 0.0.0.0 to accept connections from any interface
            // Binding to hotspot IP might fail if the interface isn't ready
            val bindAddress = java.net.InetSocketAddress("0.0.0.0", portalPort)

            Log.d(TAG, "Binding web server to: $bindAddress")
            webServerSocket =
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(bindAddress)
                    }

            Log.i(
                    TAG,
                    "✅ Web server started successfully on port $portalPort (bound to: ${webServerSocket?.localSocketAddress})"
            )

            while (webServerSocket?.isClosed == false) {
                try {
                    val clientSocket = webServerSocket?.accept()
                    if (clientSocket != null) {
                        val clientIp = clientSocket.remoteSocketAddress.toString()
                        Log.d(TAG, "Web server: New connection from $clientIp")
                        serviceScope.launch { handleWebRequest(clientSocket, config) }
                    }
                } catch (e: Exception) {
                    if (webServerSocket?.isClosed == false) {
                        Log.e(TAG, "Error accepting web connection", e)
                    }
                }
            }
            Log.w(TAG, "Web server loop exited (socket closed: ${webServerSocket?.isClosed})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start web server on port ${ProxyPortManager.CONFIG_PORT}", e)
            e.printStackTrace()
        }
    }

    /** Handle web requests for auto-configuration */
    private suspend fun handleWebRequest(socket: Socket, config: ProxyConfig) {
        val clientIp = socket.remoteSocketAddress.toString()
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val request = input.bufferedReader().readLine()
            Log.i(TAG, "📥 Web request from $clientIp: $request")

            val hotspotIp = hotspotManager.getHotspotIpAddress() ?: "192.168.43.1"

            // Check if this is a PAC file request
            val isPacRequest = request.contains("/proxy.pac", ignoreCase = true)

            if (isPacRequest) {
                Log.i(TAG, "📄 PAC file requested by $clientIp")
            }

            if (isPacRequest) {
                // Generate and serve PAC file
                val pacContent = generatePacFile(hotspotIp, config)
                val pacBytes = pacContent.toByteArray(Charsets.UTF_8)

                Log.i(TAG, "📤 Serving PAC file to $clientIp (${pacBytes.size} bytes)")
                Log.d(TAG, "PAC file content:\n$pacContent")

                // Check if this is a download request (has ?download parameter)
                val isDownload =
                        request.contains("?download", ignoreCase = true) ||
                                request.contains("download=1", ignoreCase = true)

                val response = StringBuilder()
                response.append("HTTP/1.1 200 OK\r\n")
                response.append(
                        "Content-Type: application/x-ns-proxy-autoconfig; charset=UTF-8\r\n"
                )
                if (isDownload) {
                    response.append("Content-Disposition: attachment; filename=\"proxy.pac\"\r\n")
                }
                response.append("Content-Length: ${pacBytes.size}\r\n")
                response.append("Connection: close\r\n")
                response.append("\r\n")
                response.append(pacContent)

                output.write(response.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                Log.i(TAG, "✅ PAC file sent successfully to $clientIp")
            } else {
                // Generate and serve HTML configuration page
                val htmlContent = generateAutoConfigPage(hotspotIp, config)
                val contentBytes = htmlContent.toByteArray(Charsets.UTF_8)

                val response = StringBuilder()
                response.append("HTTP/1.1 200 OK\r\n")
                response.append("Content-Type: text/html; charset=UTF-8\r\n")
                response.append("Content-Length: ${contentBytes.size}\r\n")
                response.append("Connection: close\r\n")
                response.append("\r\n")
                response.append(htmlContent)

                output.write(response.toString().toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling web request", e)
        } finally {
            socket.close()
        }
    }

    /** Generate PAC file based on enabled protocols */
    private fun generatePacFile(hotspotIp: String, config: ProxyConfig): String {
        val httpPort = config.httpPort
        val socksPort = config.socks5Port
        val authNotice =
                if (config.authEnabled &&
                        !config.authUsername.isNullOrEmpty() &&
                        !config.authPassword.isNullOrEmpty()) {
                    "\n    // Authentication enabled - configure proxy username/password on this device"
                } else {
                    ""
                }

        val proxyList = buildList {
            when (config.proxyType) {
                ProxyType.HTTP_HTTPS -> {
                    add("PROXY $hotspotIp:$httpPort")
                }
                ProxyType.SOCKS5 -> {
                    add("SOCKS5 $hotspotIp:$socksPort")
                }
                ProxyType.BOTH -> {
                    add("PROXY $hotspotIp:$httpPort")
                    add("SOCKS5 $hotspotIp:$socksPort")
                }
            }
            add("DIRECT") // Fallback
        }

        return """
function FindProxyForURL(url, host) {
    // ShieldShare PAC Configuration
    // Generated automatically for hotspot clients
$authNotice
    
    // Direct access for local networks
    if (isLocalNetwork(host)) {
        return "DIRECT";
    }
    
    // Direct access for localhost
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
        return "DIRECT";
    }
    
    // Use configured proxy based on enabled protocols
    return "${proxyList.joinToString("; ")}";
}

function isLocalNetwork(host) {
    // Check if host is in local network ranges
    var parts = host.split(".");
    if (parts.length == 4) {
        var first = parseInt(parts[0]);
        var second = parseInt(parts[1]);
        // 192.168.x.x
        if (first == 192 && second == 168) return true;
        // 10.x.x.x
        if (first == 10) return true;
        // 172.16.x.x - 172.31.x.x
        if (first == 172 && second >= 16 && second <= 31) return true;
        // 127.x.x.x
        if (first == 127) return true;
    }
    return false;
}
        """.trimIndent()
    }

    /** Generate auto-configuration HTML page */
    private fun generateAutoConfigPage(hotspotIp: String, config: ProxyConfig): String {
        val httpPort = config.httpPort
        val socksPort = config.socks5Port
        val portalPort = ProxyPortManager.CONFIG_PORT
        val pacUrl = "http://$hotspotIp:$portalPort/proxy.pac"
        val authConfigured =
                config.authEnabled &&
                        !config.authUsername.isNullOrEmpty() &&
                        !config.authPassword.isNullOrEmpty()

        // Build manual proxy settings list based on enabled protocols
        val manualProxySettings = buildList {
            when (config.proxyType) {
                ProxyType.HTTP_HTTPS -> {
                    add(
                            "<li>HTTP/HTTPS Proxy: <span class=\"mini-code\">$hotspotIp:$httpPort</span></li>"
                    )
                }
                ProxyType.SOCKS5 -> {
                    add(
                            "<li>SOCKS5 Proxy: <span class=\"mini-code\">$hotspotIp:$socksPort</span></li>"
                    )
                }
                ProxyType.BOTH -> {
                    add(
                            "<li>HTTP/HTTPS Proxy: <span class=\"mini-code\">$hotspotIp:$httpPort</span></li>"
                    )
                    add(
                            "<li>SOCKS5 Proxy: <span class=\"mini-code\">$hotspotIp:$socksPort</span></li>"
                    )
                }
            }
        }

        val authSection =
                if (authConfigured) {
                    """
        <div class="config-section">
            <h3>Proxy Credentials</h3>
            <p>Enter these credentials when prompted by ShieldShare proxy authentication.</p>
            <ul>
                <li>Username: <span class="mini-code">${config.authUsername}</span></li>
                <li>Password: <span class="mini-code">${config.authPassword}</span></li>
            </ul>
        </div>
                    """.trimIndent()
                } else {
                    ""
                }

        // Build alert message for auto-configure based on enabled protocols
        val alertMessage =
                when (config.proxyType) {
                    ProxyType.HTTP_HTTPS -> "HTTP/HTTPS: $hotspotIp:$httpPort"
                    ProxyType.SOCKS5 -> "SOCKS5: $hotspotIp:$socksPort"
                    ProxyType.BOTH ->
                            "HTTP/HTTPS: $hotspotIp:$httpPort\\nSOCKS5: $hotspotIp:$socksPort"
                }

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
            <strong>Gateway:</strong> $hotspotIp<br>
            <strong>PAC URL:</strong> $pacUrl<br>
            <strong>Authentication:</strong> ${if (authConfigured) "Enabled" else "Disabled"}
        </div>
        
        <div class="config-section">
            <h3>One-Click Auto-Configuration</h3>
            <p>Click the button below to automatically configure your device:</p>
            <button class="button" onclick="autoConfigure()">Auto-Configure Proxy</button>
            <button class="button success" onclick="downloadPAC()">Download PAC File</button>
        </div>
        
        <div class="manual-config">
            <h3>Manual Proxy Settings</h3>
            <p>Configure the following on your client device:</p>
            <ul>
                ${manualProxySettings.joinToString("\n                ")}
            </ul>
        </div>
        
        <div class="config-section">
            <h3>PAC Auto-Configuration</h3>
            <p>Use PAC file for automatic proxy routing:</p>
            <div class="code">$pacUrl</div>
            <p><small>Copy this URL to your device's Proxy Auto-Configuration settings</small></p>
        </div>
        $authSection
    </div>
    
    <script>
        function autoConfigure() {
            // Try to open system proxy settings
            if (navigator.userAgent.includes('Android')) {
                alert('Please manually configure proxy in Android Wi-Fi settings:\\n\\n$alertMessage');
            } else if (navigator.userAgent.includes('iPhone') || navigator.userAgent.includes('iPad')) {
                alert('Please manually configure proxy in iOS Wi-Fi settings:\\n\\n$alertMessage');
            } else {
                alert('Auto-configuration not supported on this device.\\nPlease use manual configuration.');
            }
        }
        
        function downloadPAC() {
            // Force download by adding ?download parameter
            window.location.href = '$pacUrl?download=1';
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
                    var removedClients = mutableListOf<String>()

                    while (iterator.hasNext()) {
                        val (ip, timestamp) = iterator.next()
                        if (currentTime - timestamp > CLIENT_TIMEOUT_MS) {
                            iterator.remove()
                            removedClients.add(ip)
                            Log.d(TAG, "Removed stale client IP: $ip")
                        }
                    }

                    // Recalculate quotas if any clients were removed
                    if (removedClients.isNotEmpty()) {
                        val hostIp = hotspotManager.getHotspotIpAddress()
                        val connectedClientList = connectedClients.keys.toList()
                        quotaManager.initializeQuotas(connectedClientList, hostIp)
                        Log.i(TAG, "Quotas recalculated after cleanup: removed ${removedClients.size} stale client(s), ${connectedClientList.size} clients remaining")
                    }

                    delay(30_000) // Clean up every 30 seconds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in client cleanup", e)
                    delay(60_000) // Wait longer on error
                }
            }
        }
    }

    /** Clean up stale proxy handlers that may have leaked */
    private suspend fun startHandlerCleanup() {
        coroutineScope {
            while (isActive) {
                try {
                    val handlerCount = proxyHandlers.size
                    if (handlerCount > 0) {
                        Log.d(TAG, "Active proxy handlers: $handlerCount")
                        
                        val currentTime = System.currentTimeMillis()
                        val iterator = proxyHandlers.iterator()
                        var removedCount = 0
                        
                        while (iterator.hasNext()) {
                            val (clientId, handler) = iterator.next()
                            try {
                                // Check if handler has timed out (stuck for too long)
                                val timestamp = handlerTimestamps[clientId]
                                val isTimedOut = timestamp != null && (currentTime - timestamp) > HANDLER_TIMEOUT_MS
                                
                                // Check if the handler's socket is still valid
                                val isValid = handler.isSocketValid()
                                
                                if (isTimedOut || !isValid) {
                                    // Extract client address from clientId (format: "address_timestamp")
                                    val clientAddressStr = clientId.substringBeforeLast('_')
                                    iterator.remove()
                                    handlerTimestamps.remove(clientId)
                                    decrementClientHandlerCount(clientAddressStr)
                                    removedCount++
                                    if (isTimedOut) {
                                        Log.w(TAG, "Removed timed-out handler: $clientId (age: ${(currentTime - timestamp!!) / 1000}s)")
                                    } else {
                                        Log.d(TAG, "Removed stale handler: $clientId")
                                    }
                                }
                            } catch (e: Exception) {
                                // Handler or socket might be in invalid state, remove it
                                val clientAddressStr = try { clientId.substringBeforeLast('_') } catch (_: Exception) { "" }
                                iterator.remove()
                                handlerTimestamps.remove(clientId)
                                decrementClientHandlerCount(clientAddressStr)
                                removedCount++
                                Log.d(TAG, "Removed invalid handler: $clientId (${e.message})")
                            }
                        }
                        
                        if (removedCount > 0) {
                            Log.i(TAG, "Cleaned up $removedCount stale handler(s), remaining: ${proxyHandlers.size}")
                        }
                        
                        // If we're approaching the limit (90% instead of 80%), be more aggressive about closing connections
                        // Only clean up invalid sockets, don't close active connections
                        if (handlerCount > MAX_CONCURRENT_HANDLERS * 0.9) {
                            Log.w(TAG, "Handler count is very high ($handlerCount/${MAX_CONCURRENT_HANDLERS}), cleaning up invalid handlers")
                            // Only close handlers with closed/invalid sockets - don't close active ones
                            val iterator2 = proxyHandlers.iterator()
                            var forceRemoved = 0
                            while (iterator2.hasNext()) {
                                val (clientId2, handler2) = iterator2.next()
                                try {
                                    // Only remove if socket is definitely invalid
                                    if (!handler2.isSocketValid()) {
                                        val clientAddressStr = clientId2.substringBeforeLast('_')
                                        iterator2.remove()
                                        handlerTimestamps.remove(clientId2)
                                        decrementClientHandlerCount(clientAddressStr)
                                        forceRemoved++
                                    }
                                } catch (e: Exception) {
                                    // Remove invalid handlers
                                    val clientAddressStr = try { clientId2.substringBeforeLast('_') } catch (_: Exception) { "" }
                                    iterator2.remove()
                                    handlerTimestamps.remove(clientId2)
                                    decrementClientHandlerCount(clientAddressStr)
                                    forceRemoved++
                                }
                            }
                            if (forceRemoved > 0) {
                                Log.i(TAG, "Force removed $forceRemoved invalid handler(s) due to high handler count")
                            }
                        }
                    }
                    
                    delay(30_000) // Clean up every 30 seconds (more frequent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in handler cleanup", e)
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

        // Scan client address
        val scanJobs =
                (2..10).map { i ->
                    clientDetectionScope.async {
                        val targetIp = "$subnet.$i"
                        Log.d(TAG, "Pinging $targetIp...")
                        if (pingDevice(targetIp)) {
                            Log.i(TAG, "Ping successful for $targetIp!")
                            // record new devices
                            if (!connectedClients.containsKey(targetIp)) {
                                connectedClients[targetIp] = System.currentTimeMillis()
                                Log.i(
                                        TAG,
                                        "Discovered new device via ping: $targetIp (Total clients: ${connectedClients.size})"
                                )
                            }
                            //
                            clientDetectionScope.launch {
                                val deviceName = tryGetDeviceName(targetIp)
                                if (deviceName != null && deviceName != targetIp) {
                                    Log.i(TAG, "Device name for $targetIp: $deviceName")
                                }
                            }
                        } else {
                            Log.d(TAG, "No response from $targetIp")
                        }
                    }
                }

        // Waiting ping
        withTimeoutOrNull(3000) { scanJobs.awaitAll() }

        Log.i(TAG, "Scan completed. Total connected clients: ${connectedClients.size}")
        if (connectedClients.isNotEmpty()) {
            Log.i(TAG, "Connected client IPs: ${connectedClients.keys.joinToString(", ")}")
        }
    }

    /** Fast ping check for device availability */
    private suspend fun pingDevice(ip: String): Boolean =
            withTimeoutOrNull(800) {
                try {
                    val address = InetAddress.getByName(ip)
                    address.isReachable(500) // 500ms timeout per ping
                } catch (e: Exception) {
                    false
                }
            }
                    ?: false

    /** Try to get device name via reverse DNS lookup */
    private suspend fun tryGetDeviceName(ip: String): String? =
            withTimeoutOrNull(1000) {
                try {
                    InetAddress.getByName(ip).hostName.takeIf { it != ip }
                } catch (e: Exception) {
                    null
                }
            }

    /**
     * Inspect the first bytes: detect SOCKS5 (0x05) or HTTP method/CONNECT; return the detected
     * protocol and a replayable (pushback) InputStream.
     */
    private fun detectProtocolAndWrap(socket: Socket): Pair<ProxyType, InputStream?> {
        return try {
            val pb = PushbackInputStream(BufferedInputStream(socket.getInputStream()), 32)
            val peek = ByteArray(32)
            val n = pb.read(peek)
            if (n > 0) pb.unread(peek, 0, n)

            val first = if (n > 0) peek[0].toInt() and 0xFF else -1
            val head = if (n > 0) String(peek, 0, n, Charsets.US_ASCII).uppercase() else ""

            val isHttp =
                    head.startsWith("CONNECT") ||
                            head.startsWith("GET") ||
                            head.startsWith("POST") ||
                            head.startsWith("HEAD") ||
                            head.startsWith("PUT") ||
                            head.startsWith("DELETE") ||
                            head.startsWith("OPTIONS") ||
                            head.startsWith("TRACE") ||
                            head.startsWith("PATCH")

            when {
                first == 0x05 -> ProxyType.SOCKS5 to pb
                isHttp -> ProxyType.HTTP_HTTPS to pb
                else -> ProxyType.HTTP_HTTPS to pb // 兜底
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Protocol detection failed, defaulting to HTTP: ${t.message}")
            ProxyType.HTTP_HTTPS to null
        }
    }

    /** In strict mode, reject HTTP: send 502 Bad Gateway and close the connection. */
    private fun sendHttp502(socket: Socket) {
        try {
            val msg = "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
            socket.getOutputStream().write(msg.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        } catch (_: Throwable) {}
    }
    
    private fun sendHttp503QuotaExceeded(socket: Socket) {
        try {
            val msg = "HTTP/1.1 503 Service Unavailable\r\n" +
                    "Connection: close\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: 45\r\n\r\n" +
                    "Quota exceeded. Please try again later."
            socket.getOutputStream().write(msg.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
        } catch (_: Throwable) {}
    }

    /**
     * In strict mode, reject SOCKS5: return GENERAL_FAILURE (VER=0x05, REP=0x01, RSV=0x00,
     * ATYP=0x01, addr=0.0.0.0, port=0) and close.
     */
    private fun sendSocks5GeneralFailure(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            out.write(
                    byteArrayOf(
                            0x05, // VER
                            0x01, // REP = general failure
                            0x00, // RSV
                            0x01, // ATYP = IPv4
                            0x00,
                            0x00,
                            0x00,
                            0x00, // BND.ADDR = 0.0.0.0
                            0x00,
                            0x00 // BND.PORT = 0
                    )
            )
            out.flush()
        } catch (_: Throwable) {}
    }

    private fun safeClose(s: Socket?) = try { s?.close() } catch (_: Throwable) {}
    
    /**
     * Remove handler and update per-client counter atomically
     * Also checks if client should be removed from connectedClients and quotas recalculated
     */
    private fun removeHandler(clientId: String, clientAddressStr: String) {
        val removed = proxyHandlers.remove(clientId)
        handlerTimestamps.remove(clientId)
        if (removed != null) {
            // Decrement per-client counter atomically
            val hadHandlers = handlersPerClient.containsKey(clientAddressStr)
            handlersPerClient.compute(clientAddressStr) { _, count ->
                val newCount = (count ?: 1) - 1
                if (newCount <= 0) null else newCount // Remove entry if count reaches 0
            }
            
            // Check if client now has no handlers and should be removed from connectedClients
            val hasNoHandlers = !handlersPerClient.containsKey(clientAddressStr)
            if (hadHandlers && hasNoHandlers) {
                // Client had handlers before, but now has none - check if we should remove from connectedClients
                // Only remove if they've been inactive for a reasonable time (30 seconds) to avoid removing active clients
                val clientTimestamp = connectedClients[clientAddressStr]
                if (clientTimestamp != null) {
                    val timeSinceLastConnection = System.currentTimeMillis() - clientTimestamp
                    if (timeSinceLastConnection > 30_000) { // 30 seconds grace period
                        connectedClients.remove(clientAddressStr)
                        Log.d(TAG, "Client $clientAddressStr removed from connectedClients (no active handlers for ${timeSinceLastConnection / 1000}s)")
                        
                        // Recalculate quotas immediately when client disconnects (launch in coroutine to avoid blocking)
                        serviceScope.launch {
                            val hostIp = hotspotManager.getHotspotIpAddress()
                            val connectedClientList = connectedClients.keys.toList()
                            quotaManager.initializeQuotas(connectedClientList, hostIp)
                            Log.i(TAG, "Quotas recalculated after client disconnect: ${connectedClientList.size} clients remaining")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Update per-client counter when removing via iterator (for cleanup)
     */
    private fun decrementClientHandlerCount(clientAddressStr: String) {
        handlersPerClient.compute(clientAddressStr) { _, count ->
            val newCount = (count ?: 1) - 1
            if (newCount <= 0) null else newCount // Remove entry if count reaches 0
        }
    }
    
    /**
     * Create notification channel for abuse detection notifications
     */
    private fun createAbuseNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Check if channel already exists
            if (notificationManager.getNotificationChannel(ABUSE_NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    ABUSE_NOTIFICATION_CHANNEL_ID,
                    "Abuse Detection",
                    NotificationManager.IMPORTANCE_HIGH // Use HIGH importance so notifications show up
                ).apply {
                    description = "Notifications when clients are flagged for bandwidth abuse"
                    enableVibration(true)
                    enableLights(true)
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Created abuse notification channel: $ABUSE_NOTIFICATION_CHANNEL_ID")
            }
        }
    }
    
    /**
     * Create notification channel for client notifications
     */
    private fun createClientNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Check if channel already exists
            if (notificationManager.getNotificationChannel(CLIENT_NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CLIENT_NOTIFICATION_CHANNEL_ID,
                    "Client Notifications",
                    NotificationManager.IMPORTANCE_HIGH // Use HIGH importance so notifications show up
                ).apply {
                    description = "Notifications when new clients connect to the proxy"
                    enableVibration(true)
                    enableLights(true)
                }
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Created notification channel: $CLIENT_NOTIFICATION_CHANNEL_ID")
            }
        }
    }
    
    /**
     * Check if we have permission to show notifications (Android 13+)
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Permission not required for Android < 13
            true
        }
    }
    
    /**
     * Show a notification when a new client is detected
     */
    private fun showNewClientNotification(clientIp: String) {
        try {
            Log.i(TAG, "Attempting to show notification for new client: $clientIp")
            
            // Check if notifications are enabled in settings
            val notificationsEnabled = appPrefs.getBoolean("notifications_enabled", true)
            if (!notificationsEnabled) {
                Log.d(TAG, "Notifications disabled in settings, skipping notification for client: $clientIp")
                return
            }
            
            // Check notification permission (Android 13+)
            if (!hasNotificationPermission()) {
                Log.w(TAG, "Notification permission not granted. Cannot show notification for client: $clientIp")
                Log.w(TAG, "Please grant notification permission in app settings")
                return
            }
            
            // Ensure notification channel exists
            createClientNotificationChannel()
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if notifications are enabled for this channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = notificationManager.getNotificationChannel(CLIENT_NOTIFICATION_CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(TAG, "Notification channel is disabled by user")
                    return
                }
            }
            
            // Generate unique notification ID for each client
            val notificationId = CLIENT_NOTIFICATION_ID_BASE + (notificationCounter.getAndIncrement() % 1000)
            
            val notification = NotificationCompat.Builder(context, CLIENT_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("New Client Connected")
                .setContentText("Client IP: $clientIp")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("A new client has connected to the proxy server.\nClient IP: $clientIp"))
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Use HIGH priority
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, vibration, lights
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(notificationId, notification)
            Log.i(TAG, "Notification shown successfully for new client: $clientIp (ID: $notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification for new client: $clientIp", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Show a notification when a client exhausts their quota
     */
    private fun showQuotaExceededNotification(clientIp: String, macAddress: String) {
        try {
            Log.i(TAG, "Attempting to show quota exceeded notification for client: $clientIp")
            
            // Check if notifications are enabled in settings
            val notificationsEnabled = appPrefs.getBoolean("notifications_enabled", true)
            if (!notificationsEnabled) {
                Log.d(TAG, "Notifications disabled in settings, skipping quota notification for client: $clientIp")
                return
            }
            
            // Check notification permission (Android 13+)
            if (!hasNotificationPermission()) {
                Log.w(TAG, "Notification permission not granted. Cannot show quota notification for client: $clientIp")
                return
            }
            
            // Ensure notification channel exists
            createClientNotificationChannel()
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if notifications are enabled for this channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = notificationManager.getNotificationChannel(CLIENT_NOTIFICATION_CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(TAG, "Notification channel is disabled by user")
                    return
                }
            }
            
            // Generate unique notification ID
            val notificationId = CLIENT_NOTIFICATION_ID_BASE + (notificationCounter.getAndIncrement() % 1000) + 2000 // Offset to avoid conflicts
            
            val notification = NotificationCompat.Builder(context, CLIENT_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Client Quota Exceeded")
                .setContentText("Client IP: $clientIp (MAC/ID: ${macAddress})")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("A client has exhausted their traffic quota.\n\nClient IP: $clientIp\nMAC/ID: $macAddress\n\nThe client has been blocked for 1 hour."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, vibration, lights
                .setAutoCancel(true)
                .build()
            
            notificationManager.notify(notificationId, notification)
            Log.i(TAG, "Quota exceeded notification shown successfully for client: $clientIp (ID: $notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show quota exceeded notification for client: $clientIp", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Show a notification when abuse is detected
     */
    fun showAbuseDetectedNotification(clientIp: String, abuseRatio: Float, globalAverage: Long) {
        try {
            Log.i(TAG, "Attempting to show abuse detection notification for client: $clientIp")
            
            // Check if notifications are enabled in settings
            val notificationsEnabled = appPrefs.getBoolean("notifications_enabled", true)
            if (!notificationsEnabled) {
                Log.d(TAG, "Notifications disabled in settings, skipping abuse notification for client: $clientIp")
                return
            }
            
            // Check notification permission (Android 13+)
            if (!hasNotificationPermission()) {
                Log.w(TAG, "Notification permission not granted. Cannot show abuse notification for client: $clientIp")
                return
            }
            
            // Ensure notification channel exists
            createAbuseNotificationChannel()
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Check if notifications are enabled for this channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = notificationManager.getNotificationChannel(ABUSE_NOTIFICATION_CHANNEL_ID)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                    Log.w(TAG, "Abuse notification channel is disabled by user")
                    return
                }
            }
            
            // Generate unique notification ID
            val notificationId = ABUSE_NOTIFICATION_ID_BASE + (notificationCounter.getAndIncrement() % 1000)
            
            // Create intent to open Advanced Traffic Regulation screen
            val intent = android.content.Intent().apply {
                setClassName(context, "com.example.shieldshare.MainActivity")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "advanced-traffic-regulation")
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val notification = NotificationCompat.Builder(context, ABUSE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("⚠️ Data Leak Detected")
                .setContentText("Client $clientIp shows sustained high-rate activity (${String.format("%.1f", abuseRatio)}× baseline rate)")
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText("A client has been flagged for data leak pattern.\n\nClient IP: $clientIp\nCurrent Rate: ${String.format("%.1f", abuseRatio)}× baseline rate\nBaseline Rate: ${formatBytes(globalAverage)}/hour\n\nSustained high-rate transfer detected for 15+ minutes.\n\nThis may indicate:\n• Data exfiltration or leak\n• Unauthorized access\n• Compromised device\n\nTap to investigate and block in Advanced Traffic Regulation."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Sound, vibration, lights
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "View Details",
                    pendingIntent
                )
                .build()
            
            notificationManager.notify(notificationId, notification)
            Log.i(TAG, "Abuse detection notification shown successfully for client: $clientIp (ID: $notificationId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show abuse detection notification for client: $clientIp", e)
            e.printStackTrace()
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
