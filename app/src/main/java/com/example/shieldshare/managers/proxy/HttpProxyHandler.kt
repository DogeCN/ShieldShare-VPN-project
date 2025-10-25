package com.example.shieldshare.managers.proxy

import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/**
 * HTTP/HTTPS Proxy Handler with STAGE 2 Traffic Metering
 * Handles HTTP CONNECT requests for HTTPS tunneling and regular HTTP requests
 * Collects detailed traffic data for Jialu's per-device monitoring
 */
class HttpProxyHandler(
        clientSocket: Socket,
        trafficMeter: TrafficMeter,
        private val trafficCallback: (bytesUp: Long, bytesDown: Long) -> Unit = { _, _ -> }
) : ProxyHandler(clientSocket, trafficMeter) {
    companion object {
        private const val TAG = "HttpProxyHandler"
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_METHOD = "CONNECT"
        private const val HTTP_VERSION = "HTTP/1.1"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    
    // STAGE 2: Enhanced traffic tracking
    private val clientIp = clientSocket.remoteSocketAddress.toString().substringAfter("/").substringBefore(":")
    private var sessionId: String? = null
    private var userAgent: String? = null
    private val hostsAccessed = mutableSetOf<String>()

    fun start() {
        scope.launch {
            try {
                // STAGE 2: Start traffic session
                sessionId = (trafficMeter as? TrafficMeterSimple)?.startSession(
                    clientIp = clientIp,
                    protocolType = "HTTP"
                )
                
                Log.i(TAG, "HTTP session started for client: $clientIp (Session: $sessionId)")
                
                handleConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling proxy request", e)
            } finally {
                cleanup()
            }
        }
    }

    override fun handleConnectionInternal() {
        scope.launch { handleRequest() }
    }

    private suspend fun handleRequest() =
            withContext(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // Read the first line to determine request type
                val requestLine = reader.readLine() ?: return@withContext
                Log.d(TAG, "Proxy request: $requestLine")

                val parts = requestLine.split(" ")
                if (parts.size < 3) {
                    sendErrorResponse(writer, 400, "Bad Request")
                    return@withContext
                }

                val method = parts[0]
                val url = parts[1]

                when (method) {
                    CONNECT_METHOD -> handleConnectRequest(reader, writer, url)
                    else -> handleHttpRequest(reader, writer, method, url)
                }
            }

    private suspend fun handleConnectRequest(
            reader: BufferedReader,
            writer: PrintWriter,
            url: String
    ) =
            withContext(Dispatchers.IO) {
                Log.i(TAG, "HTTPS CONNECT request to: $url from $clientIp")

                // Parse host and port from URL
                val (host, port) = parseHostPort(url)
                if (host == null || port == -1) {
                    sendErrorResponse(writer, 400, "Bad Request - Invalid host:port")
                    return@withContext
                }
                
                // STAGE 2: Track target host
                hostsAccessed.add(host)
                sessionId?.let { sessionId ->
                    // TODO: Add updateSessionTarget method to TrafficMeterSimple if needed
                    // (trafficMeter as? TrafficMeterSimple)?.updateSessionTarget(sessionId, host)
                }
                Log.i(TAG, "Client $clientIp accessing: $host:$port")

                // Send 200 Connection Established response
                writer.println("$HTTP_VERSION 200 Connection Established")
                writer.println("Proxy-Agent: ShieldShare/1.0")
                writer.println()
                writer.flush()

                // Create tunnel to target server
                val targetSocket = Socket()
                try {
                    targetSocket.connect(InetSocketAddress(host, port), 10000)
                    targetSocket.soTimeout = 30000 // 30 second read timeout
                    Log.d(TAG, "Connected to target: $host:$port")

                    // Start bidirectional tunneling
                    val tunnelJob1 =
                            scope.launch {
                                try {
                                    tunnelData(
                                            socket.getInputStream(),
                                            targetSocket.getOutputStream(),
                                            bytesUp
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Tunnel job 1 failed: ${e.message}")
                                }
                            }
                    val tunnelJob2 =
                            scope.launch {
                                try {
                                    tunnelData(
                                            targetSocket.getInputStream(),
                                            socket.getOutputStream(),
                                            bytesDown
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Tunnel job 2 failed: ${e.message}")
                                }
                            }

                    // Wait for either tunnel to complete
                    joinAll(tunnelJob1, tunnelJob2)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to target: $host:$port", e)
                    sendErrorResponse(writer, 502, "Bad Gateway")
                } finally {
                    targetSocket.close()
                }
            }

    private suspend fun handleHttpRequest(
            reader: BufferedReader,
            writer: PrintWriter,
            method: String,
            url: String
    ) =
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Handling HTTP request: $method $url")

                // Parse URL to get host and port
                val (host, port, path) = parseHttpUrl(url)
                if (host == null) {
                    sendErrorResponse(writer, 400, "Bad Request - Invalid URL")
                    return@withContext
                }

                // Read all headers
                val headers = mutableListOf<String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    headers.add(line!!)
                    
                    // STAGE 2: Extract User-Agent for device identification
                    if (line!!.lowercase().startsWith("user-agent:")) {
                        userAgent = line!!.substringAfter(":").trim()
                        Log.i(TAG, "User-Agent detected: $userAgent")
                    }
                }

                // Forward request to target server
                val targetSocket = Socket()
                try {
                    targetSocket.connect(InetSocketAddress(host, port), 10000)
                    targetSocket.soTimeout = 30000 // 30 second read timeout
                    val targetWriter = PrintWriter(targetSocket.getOutputStream(), true)
                    val targetReader =
                            BufferedReader(InputStreamReader(targetSocket.getInputStream()))

                    // Send request to target
                    targetWriter.println("$method $path $HTTP_VERSION")
                    headers.forEach { header -> targetWriter.println(header) }
                    targetWriter.println()
                    targetWriter.flush()

                    // Forward response back to client
                    var responseLine: String?
                    while (targetReader.readLine().also { responseLine = it } != null) {
                        writer.println(responseLine)
                    }
                    writer.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle HTTP request", e)
                    sendErrorResponse(writer, 502, "Bad Gateway")
                } finally {
                    targetSocket.close()
                }
            }

    private suspend fun tunnelData(
            input: InputStream,
            output: OutputStream,
            bytesCounter: AtomicLong
    ) =
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                
                try {
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        try {
                            // TODO: HANCHEN - Replace this direct forwarding with VPN tunnel forwarding
                            // Currently: Direct forwarding to target
                            // Should be: Forward through VPN tunnel for encryption
                            forwardThroughVpn(buffer, bytesRead, output)
                            output.flush()
                            
                            // STAGE 2: Track bytes and record traffic
                            val bytes = bytesRead.toLong()
                            bytesCounter.addAndGet(bytes)
                            
                            // Determine if this is upload or download
                            val isUpload = bytesCounter == bytesUp
                            
                            if (isUpload) {
                                trafficMeter.recordTraffic(clientIp, bytes, 0)
                                Log.d(TAG, "↑ Upload: $bytes bytes from $clientIp")
                            } else {
                                trafficMeter.recordTraffic(clientIp, 0, bytes)
                                Log.d(TAG, "↓ Download: $bytes bytes to $clientIp")
                            }
                        } catch (e: IOException) {
                            Log.w(TAG, "Error forwarding data: ${e.message}")
                            break // Exit the loop on I/O errors
                        }
                    }
                } catch (e: SocketException) {
                    Log.i(TAG, "Connection reset by client: ${e.message}")
                    // This is normal - client disconnected
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in tunnelData: ${e.message}", e)
                } finally {
                    try {
                        input.close()
                        output.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing streams: ${e.message}")
                    }
                }
            }

    /**
     * VPN Integration Point for Hanchen
     *
     * HANCHEN: This is where you need to integrate your VPN tunnel. Instead of writing directly to
     * the output stream, you should:
     * 1. Check if VPN is connected
     * 2. Forward the data through your VPN tunnel
     * 3. Let the VPN tunnel handle the actual internet communication
     *
     * @param data The data to forward through VPN
     * @param length Number of bytes to forward
     * @param output The output stream (currently used for direct forwarding)
     */
    private suspend fun forwardThroughVpn(data: ByteArray, length: Int, output: OutputStream) {
        // TODO: HANCHEN - Implement VPN forwarding here
        //
        // Example integration:
        // 1. Check VPN status: vpnManager.getConnectionStatus()
        // 2. If VPN is connected, forward through VPN tunnel
        // 3. If VPN is not connected, either:
        //    - Block the traffic (secure option)
        //    - Forward directly (current behavior)
        //    - Queue for later when VPN connects
        //
        // For now, we're forwarding directly
        output.write(data, 0, length)

        Log.d(TAG, "HANCHEN: Data forwarded directly (VPN integration pending)")
    }

    private fun parseHostPort(url: String): Pair<String?, Int> {
        return try {
            val parts = url.split(":")
            if (parts.size == 2) {
                Pair(parts[0], parts[1].toInt())
            } else {
                Pair(null, -1)
            }
        } catch (e: Exception) {
            Pair(null, -1)
        }
    }

    private fun parseHttpUrl(url: String): Triple<String?, Int, String> {
        return try {
            val uri = URI(url)
            val host = uri.host
            val port = if (uri.port != -1) uri.port else 80
            val path = uri.path + if (uri.query != null) "?${uri.query}" else ""
            Triple(host, port, path)
        } catch (e: Exception) {
            Triple(null, -1, "")
        }
    }

    private fun sendErrorResponse(writer: PrintWriter, code: Int, message: String) {
        writer.println("$HTTP_VERSION $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Content-Length: ${message.length}")
        writer.println()
        writer.println(message)
        writer.flush()
    }

    private fun cleanup() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }

        // STAGE 2: End traffic session and log summary
        sessionId?.let { sessionId ->
            (trafficMeter as? TrafficMeterSimple)?.endSession(sessionId)
        }
        
        val totalUp = bytesUp.get()
        val totalDown = bytesDown.get()
        
        // Get MAC address for enhanced logging
        val macAddress = (trafficMeter as? TrafficMeterSimple)?.mapIpToMac()?.get(clientIp) ?: "unknown"
        
        Log.i(TAG, "**HTTP session ended** for $clientIp ($macAddress)")
        Log.i(TAG, "   ↑ **Total Upload**: ${formatBytes(totalUp)}")
        Log.i(TAG, "   ↓ **Total Download**: ${formatBytes(totalDown)}")
        Log.i(TAG, "   **Hosts Accessed**: ${hostsAccessed.size} - ${hostsAccessed.joinToString(", ")}")
        Log.i(TAG, "   **User Agent**: ${userAgent ?: "Unknown"}")

        // Report traffic statistics
        trafficCallback(totalUp, totalDown)

        scope.cancel()
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
