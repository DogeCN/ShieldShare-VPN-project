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



}
