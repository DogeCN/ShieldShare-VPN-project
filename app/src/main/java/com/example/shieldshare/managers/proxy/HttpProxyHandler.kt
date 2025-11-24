package com.example.shieldshare.managers.proxy

import android.util.Base64
import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import com.example.shieldshare.managers.quota.QuotaManager
import com.example.shieldshare.managers.quota.QuotaStatus
import com.example.shieldshare.managers.filter.TrafficFilterManager
import com.example.shieldshare.managers.filter.FilterResult
import java.io.*
import java.net.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import kotlinx.coroutines.*

/**
 * HTTP/HTTPS Proxy Handler with STAGE 2 Traffic Metering Handles HTTP CONNECT requests for HTTPS
 * tunneling and regular HTTP requests Collects detailed traffic data for Jialu's per-device
 * monitoring Ensures all outbound sockets are created via a VPN-bound SocketFactory
 */
class HttpProxyHandler(
        clientSocket: Socket,
        trafficMeter: TrafficMeter,
        private val socketFactory: SocketFactory,
        private val inOverride: InputStream? = null,
        private val quotaManager: QuotaManager? = null,
        private val authEnabled: Boolean = false,
        private val authUsername: String? = null,
        private val authPassword: String? = null,
        private val trafficFilterManager: TrafficFilterManager? = null,
        private val trafficCallback: (bytesUp: Long, bytesDown: Long) -> Unit = { _, _ -> }
) : ProxyHandler(clientSocket, trafficMeter) {
    companion object {
        private const val TAG = "HttpProxyHandler"
        private const val BUFFER_SIZE = 65536 // 64KB buffer for better throughput (increased from 8KB)
        private const val CONNECT_METHOD = "CONNECT"
        private const val HTTP_VERSION = "HTTP/1.1"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)

    // STAGE 2: Enhanced traffic tracking
    private val clientIp =
            clientSocket.remoteSocketAddress.toString().substringAfter("/").substringBefore(":")
    private var sessionId: String? = null
    private var userAgent: String? = null
    private val hostsAccessed = mutableSetOf<String>()

    /** Entry point from server */
    fun start() {
        scope.launch {
            try {
                // STAGE 2: Start traffic session
                sessionId = (trafficMeter as? TrafficMeterSimple)?.startSession(clientIp, "HTTP")
                Log.i(TAG, "HTTP session started for client: $clientIp (Session: $sessionId)")
                handleRequest()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling proxy request", e)
            } finally {
                cleanup()
            }
        }
    }

    /** If your base class calls this, keep it delegating to the same request handler. */
    override fun handleConnectionInternal() {
        val handlerStartTime = System.currentTimeMillis()
        scope.launch {
            try {
                // Wrap entire handler in try-catch to prevent crashes
                // Validate connection before processing
                if (socket.isClosed || !socket.isConnected) {
                    Log.w(TAG, "Invalid socket connection, skipping request")
                    return@launch
                }
                
                // STAGE 2: Start traffic session
                val sessionStartTime = System.currentTimeMillis()
                sessionId = (trafficMeter as? TrafficMeterSimple)?.startSession(
                    clientIp = clientIp,
                    protocolType = "HTTP"
                )
                val sessionDuration = System.currentTimeMillis() - sessionStartTime
                Log.i(TAG, "[PERF] HTTP handler START: $clientIp | Session: $sessionId | Setup: ${sessionDuration}ms | Time: $handlerStartTime")
                
                // Optimize client socket for better performance - same optimizations as target socket
                try {
                    socket.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
                    socket.receiveBufferSize = 131072 // 128KB for better throughput
                    socket.sendBufferSize = 131072 // 128KB for better throughput
                    socket.keepAlive = true // Enable keep-alive for connection reuse
                } catch (e: Exception) {
                    Log.w(TAG, "Error optimizing client socket", e)
                }
                
                // Set socket timeout to prevent hanging connections
                // Reduced from 30s to 10s for faster failure detection - if client doesn't send data quickly, fail fast
                socket.soTimeout = 10000 // 10 seconds timeout for initial request - fail fast for slow/stuck connections
                
                // Process requests - handle keep-alive for HTTP, but CONNECT requests close the connection
                var requestCount = 0
                val maxRequestsPerConnection = 50 // Increased for better connection reuse
                var isConnectRequest = false
                var shouldCloseConnection = false
                
                // Create shared reader/writer for the connection to handle keep-alive properly
                val clientIn = inOverride ?: socket.getInputStream()
                val clientOut = socket.getOutputStream()
                val reader = BufferedReader(InputStreamReader(clientIn, Charsets.US_ASCII))
                val writer = PrintWriter(OutputStreamWriter(clientOut, Charsets.US_ASCII), true)
                
                while (!socket.isClosed && socket.isConnected && 
                       !shouldCloseConnection && 
                       requestCount < maxRequestsPerConnection) {
                    try {
                        val requestStartTime = System.currentTimeMillis()
                        // For subsequent requests, use a reasonable timeout to detect idle connections
                        // Increased to 10s to allow concurrent requests to complete without premature timeouts
                        if (requestCount > 0) {
                            socket.soTimeout = 10000 // 10 seconds for keep-alive detection
                        }
                        
                        // Process the request - this will read the request line
                        // If no data is available, it will timeout
                        val requestResult = handleRequestWithStreams(reader, writer)
                        val requestDuration = System.currentTimeMillis() - requestStartTime
                        Log.i(TAG, "[PERF] Request #${requestCount + 1} processed: $requestResult | Duration: ${requestDuration}ms")
                        
                        // Reset timeout for next request processing
                        socket.soTimeout = 30000
                        
                        if (requestResult == RequestResult.CONNECT) {
                            // CONNECT request - connection is now dedicated to tunneling
                            // Don't try to process more requests - the tunnel will handle it
                            isConnectRequest = true
                            shouldCloseConnection = true
                            Log.d(TAG, "CONNECT request processed, connection dedicated to tunnel")
                            // Note: handleConnectRequest will keep the connection open for tunneling
                            // We break here so we don't try to process more requests
                            break
                        } else if (requestResult == RequestResult.CLOSE) {
                            // Request indicated connection should close
                            shouldCloseConnection = true
                            Log.d(TAG, "Request indicated connection should close")
                        } else if (requestResult == RequestResult.ERROR) {
                            // Error occurred, close connection
                            shouldCloseConnection = true
                            Log.d(TAG, "Error processing request, closing connection")
                        }
                        // If SUCCESS, we continue the loop to process more requests
                        
                        requestCount++
                        
                    } catch (e: java.net.SocketTimeoutException) {
                        if (requestCount == 0) {
                            // Timeout on first request - no data received, close connection
                            Log.d(TAG, "Timeout waiting for first request, closing connection")
                        } else {
                            // Timeout on subsequent request - connection idle, this is normal for keep-alive
                            Log.d(TAG, "Connection idle after $requestCount requests, closing")
                        }
                        break
                    } catch (e: java.net.SocketException) {
                        Log.d(TAG, "Socket closed/reset after $requestCount requests: ${e.message}")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error handling request #${requestCount + 1}: ${e.message}", e)
                        shouldCloseConnection = true
                        break
                    }
                }
                
                if (requestCount > 0) {
                    Log.d(TAG, "Processed $requestCount request(s) on connection (CONNECT: $isConnectRequest)")
                }
            } catch (e: CancellationException) {
                // Normal cancellation, just cleanup
                Log.d(TAG, "Connection handling cancelled for $clientIp")
                cleanup()
            } catch (e: java.net.SocketException) {
                // Socket errors are expected, just cleanup
                Log.d(TAG, "Socket exception in connection handling for $clientIp: ${e.message}")
                cleanup()
            } catch (e: Exception) {
                // Log unexpected errors but don't crash
                Log.e(TAG, "Unexpected error in connection handling for $clientIp", e)
                try {
                    cleanup()
                } catch (cleanupError: Exception) {
                    Log.e(TAG, "Error during cleanup", cleanupError)
                }
            }
        }
    }

    // Enum to indicate request processing result
    private enum class RequestResult {
        SUCCESS,      // Request processed successfully, connection can stay open
        CONNECT,      // CONNECT request - connection dedicated to tunnel
        CLOSE,        // Connection should close
        ERROR         // Error occurred
    }
    
    private suspend fun handleRequest() = withContext(Dispatchers.IO) {
        val clientIn = inOverride ?: socket.getInputStream()
        val clientOut = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(clientIn, Charsets.US_ASCII))
        val writer = PrintWriter(OutputStreamWriter(clientOut, Charsets.US_ASCII), true)
        handleRequestWithStreams(reader, writer)
    }
    
    private suspend fun handleRequestWithStreams(
        reader: BufferedReader,
        writer: PrintWriter
    ): RequestResult = withContext(Dispatchers.IO) {
        // Read the first request line with timeout handling
        val readStartTime = System.currentTimeMillis()
        val requestLine = try {
            reader.readLine() ?: return@withContext RequestResult.ERROR
        } catch (e: java.net.SocketTimeoutException) {
            val readDuration = System.currentTimeMillis() - readStartTime
            Log.d(TAG, "[PERF] Timeout reading request line after ${readDuration}ms")
            return@withContext RequestResult.ERROR
        } catch (e: Exception) {
            val readDuration = System.currentTimeMillis() - readStartTime
            Log.d(TAG, "[PERF] Error reading request line after ${readDuration}ms: ${e.message}")
            return@withContext RequestResult.ERROR
        }
        val readDuration = System.currentTimeMillis() - readStartTime
        Log.d(TAG, "[PERF] Request line read: $requestLine | Read time: ${readDuration}ms")

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            sendErrorResponse(writer, 400, "Bad Request")
            return@withContext RequestResult.CLOSE
        }

        val method = parts[0]
        val url = parts[1]
        val headers = readHeaders(reader)

        if (!validateProxyAuthentication(headers, writer)) {
            return@withContext RequestResult.CLOSE
        }

        if (method.equals(CONNECT_METHOD, ignoreCase = true)) {
            handleConnectRequest(writer, headers, url)
            RequestResult.CONNECT
        } else {
            val closeConnection = handleHttpRequest(writer, headers, method, url)
            if (closeConnection) RequestResult.CLOSE else RequestResult.SUCCESS
        }
    }

    private fun readHeaders(reader: BufferedReader): List<String> {
        val headers = mutableListOf<String>()
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            headers.add(line!!)
        }
        return headers
    }

    private fun validateProxyAuthentication(
        headers: List<String>,
        writer: PrintWriter
    ): Boolean {
        if (!isAuthConfigured()) {
            return true
        }

        val headerLine =
                headers.firstOrNull { it.startsWith("Proxy-Authorization", ignoreCase = true) }
        if (headerLine.isNullOrBlank()) {
            Log.w(TAG, "Proxy auth header missing for client $clientIp")
            sendProxyAuthRequired(writer)
            return false
        }

        val credentials = parseBasicCredentials(headerLine)
        if (credentials == null) {
            Log.w(TAG, "Proxy auth header invalid for client $clientIp")
            sendProxyAuthRequired(writer)
            return false
        }

        val (username, password) = credentials
        val valid = username == authUsername && password == authPassword
        if (!valid) {
            Log.w(TAG, "Proxy auth failed for client $clientIp (username mismatch)")
            sendProxyAuthRequired(writer)
        }
        return valid
    }

    private fun parseBasicCredentials(headerLine: String): Pair<String, String>? {
        val value = headerLine.substringAfter(":", "").trim()
        if (!value.startsWith("Basic", ignoreCase = true)) {
            return null
        }
        val token = value.substringAfter(" ", missingDelimiterValue = "").trim()
        if (token.isEmpty()) {
            return null
        }
        return try {
            val decoded = Base64.decode(token, Base64.NO_WRAP)
            val credentialPair = String(decoded, Charsets.UTF_8)
            val separatorIndex = credentialPair.indexOf(':')
            if (separatorIndex == -1) {
                null
            } else {
                val username = credentialPair.substring(0, separatorIndex)
                val password = credentialPair.substring(separatorIndex + 1)
                username to password
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isAuthConfigured(): Boolean {
        return authEnabled && !authUsername.isNullOrEmpty() && !authPassword.isNullOrEmpty()
    }

    /** CONNECT method: establish a TCP tunnel and pump bytes both ways */
    private suspend fun handleConnectRequest(
        writer: PrintWriter,
        headers: List<String>,
        url: String
    ) = withContext(Dispatchers.IO) {
        val connectStartTime = System.currentTimeMillis()
        Log.i(TAG, "[PERF] HTTPS CONNECT START: $url from $clientIp | Time: $connectStartTime")

        val (host, port) = parseHostPort(url)
        if (host == null || port == -1) {
            sendErrorResponse(writer, 400, "Bad Request - Invalid host:port")
            return@withContext
        }

        // NEW: Optional filter check (only if manager provided AND enabled)
        // SAFETY: Early return if disabled, zero overhead
        trafficFilterManager?.let { filter ->
            if (filter.isFilteringEnabled()) {
                val filterResult = filter.shouldAllowConnection(host, port, clientIp)
                if (filterResult is FilterResult.BLOCK) {
                    Log.d(TAG, "Connection blocked by filter: $host:$port (reason: ${filterResult.reason})")
                    sendErrorResponse(writer, 403, filterResult.reason)
                    return@withContext
                }
            }
        }

        headers.firstOrNull { it.startsWith("User-Agent:", ignoreCase = true) }?.let { header ->
            userAgent = header.substringAfter(":").trim()
        }

        // Track target host for session
        hostsAccessed.add(host)
        // (trafficMeter as? TrafficMeterSimple)?.updateSessionTarget(sessionId, host) //
        // 如果你实现了的话

        // 200 Established, then raw tunnel
        val responseStartTime = System.currentTimeMillis()
        writer.println("$HTTP_VERSION 200 Connection Established")
        writer.println("Proxy-Agent: ShieldShare/1.0")
        writer.println()
        writer.flush()
        val responseDuration = System.currentTimeMillis() - responseStartTime
        Log.d(TAG, "[PERF] CONNECT response sent: ${responseDuration}ms")

        // Create target socket via VPN-bound factory
        val connectTargetStartTime = System.currentTimeMillis()
        val targetSocket = try {
            connectTarget(host, port)
        } catch (e: Exception) {
            val connectDuration = System.currentTimeMillis() - connectTargetStartTime
            Log.e(TAG, "[PERF] Failed to connect target $host:$port after ${connectDuration}ms", e)
            sendErrorResponse(writer, 502, "Bad Gateway - Failed to connect")
            return@withContext
        }
        val connectDuration = System.currentTimeMillis() - connectTargetStartTime
        Log.i(TAG, "[PERF] Target connected: $host:$port | Connect time: ${connectDuration}ms")
        
        val tunnelStartTime = System.currentTimeMillis()
        // Set shorter timeout for tunneling to fail fast on stuck connections
        // Use 5 seconds - if no data arrives in 5s, the connection is likely stuck
        val originalClientTimeout = try { socket.soTimeout } catch (e: Exception) { 0 }
        val originalTargetTimeout = try { targetSocket.soTimeout } catch (e: Exception) { 0 }
        try {
            socket.soTimeout = 5000 // 5 seconds - fail fast on stuck client connections
            targetSocket.soTimeout = 5000 // 5 seconds - fail fast on stuck target connections
        } catch (e: Exception) {
            Log.w(TAG, "Error setting socket timeouts for tunneling", e)
        }
        
        // Log socket states before starting tunnels
        val clientSocketState = try {
            "connected=${socket.isConnected}, closed=${socket.isClosed}, timeout=${socket.soTimeout}, tcpNoDelay=${socket.tcpNoDelay}, recvBuf=${socket.receiveBufferSize}, sendBuf=${socket.sendBufferSize}"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
        val targetSocketState = try {
            "connected=${targetSocket.isConnected}, closed=${targetSocket.isClosed}, timeout=${targetSocket.soTimeout}, tcpNoDelay=${targetSocket.tcpNoDelay}, recvBuf=${targetSocket.receiveBufferSize}, sendBuf=${targetSocket.sendBufferSize}"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
        Log.i(TAG, "[PERF] Starting tunnels for $host:$port | Client socket: $clientSocketState | Target socket: $targetSocketState")
        
        try {
            // Use coroutineScope to ensure both tunnels are cancelled together if one fails
            coroutineScope {
                val t1 = launch {
                    try {
                        val uploadStartTime = System.currentTimeMillis()
                        val uploadThread = Thread.currentThread().name
                        Log.d(TAG, "[PERF] Starting UPLOAD tunnel for $host:$port | Thread: $uploadThread")
                        // client -> target (upload)
                        tunnelData(
                            input = socket.getInputStream(),
                            output = targetSocket.getOutputStream(),
                            isUpload = true
                        )
                        val uploadDuration = System.currentTimeMillis() - uploadStartTime
                        Log.d(TAG, "[PERF] UPLOAD tunnel completed for $host:$port in ${uploadDuration}ms | Thread: $uploadThread")
                    } catch (e: CancellationException) {
                        // Normal cancellation - don't log, just rethrow
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in upload tunnel for $host:$port | Thread: ${Thread.currentThread().name}", e)
                        throw e
                    }
                }
                val t2 = launch {
                    try {
                        val downloadStartTime = System.currentTimeMillis()
                        val downloadThread = Thread.currentThread().name
                        Log.d(TAG, "[PERF] Starting DOWNLOAD tunnel for $host:$port | Thread: $downloadThread")
                        // target -> client (download)
                        tunnelData(
                            input = targetSocket.getInputStream(),
                            output = socket.getOutputStream(),
                            isUpload = false
                        )
                        val downloadDuration = System.currentTimeMillis() - downloadStartTime
                        Log.d(TAG, "[PERF] DOWNLOAD tunnel completed for $host:$port in ${downloadDuration}ms | Thread: $downloadThread")
                    } catch (e: CancellationException) {
                        // Normal cancellation - don't log, just rethrow
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in download tunnel for $host:$port | Thread: ${Thread.currentThread().name}", e)
                        throw e
                    }
                }
                
                // Wait for both tunnels - coroutineScope will cancel both if one fails
                // This prevents the barrier issue by ensuring proper cancellation propagation
                try {
                    t1.join()
                    t2.join()
                } catch (e: CancellationException) {
                    // One or both cancelled - coroutineScope handles cleanup
                    throw e
                }
            }
            
            val totalTunnelDuration = System.currentTimeMillis() - tunnelStartTime
            val totalConnectDuration = System.currentTimeMillis() - connectStartTime
            Log.i(TAG, "[PERF] CONNECT complete: $host:$port | Tunnel: ${totalTunnelDuration}ms | Total: ${totalConnectDuration}ms")
        } catch (e: CancellationException) {
            // Normal cancellation, just cleanup
            Log.d(TAG, "Tunnel cancelled for $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed CONNECT tunnel to $host:$port", e)
        } finally {
            // Restore original socket timeouts
            try {
                socket.soTimeout = originalClientTimeout
            } catch (e: Exception) {
                // Ignore
            }
            try {
                targetSocket.soTimeout = originalTargetTimeout
            } catch (e: Exception) {
                // Ignore
            }
            safeClose(targetSocket)
        }
    }

    /** Plain HTTP proxying (no TLS termination)
     * @return true if connection should close, false if keep-alive is possible */
    private suspend fun handleHttpRequest(
        writer: PrintWriter,
        headers: List<String>,
        method: String,
        url: String
    ): Boolean = withContext(Dispatchers.IO) {
        val requestStartTime = System.currentTimeMillis()
        Log.i(TAG, "[PERF] HTTP request START: $method $url | Time: $requestStartTime")

        var contentLength: Int? = null
        headers.forEach { header ->
            if (header.startsWith("user-agent:", ignoreCase = true)) {
                userAgent = header.substringAfter(":").trim()
            }
            if (header.startsWith("content-length:", ignoreCase = true)) {
                contentLength = header.substringAfter(":").trim().toIntOrNull()
            }
        }

        // Parse URL and fall back to Host header if the request line was relative-form
        val (hostFromUrl, portFromUrl, pathFromUrl) = parseHttpUrl(url)
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val (host, port, path) = when {
            hostFromUrl != null -> Triple(hostFromUrl, portFromUrl, pathFromUrl)
            hostHeader != null -> {
                val (hh, hp) = splitHostPort(hostHeader, defaultPort = 80)
                Triple(hh, hp, url) // url was likely relative path
            }
            else -> Triple(null, -1, url)
        }

        if (host == null || port <= 0) {
            sendErrorResponse(writer, 400, "Bad Request - Invalid URL/Host")
            return@withContext true // Close connection on error
        }

        // NEW: Optional filter check (only if manager provided AND enabled)
        // SAFETY: Early return if disabled, zero overhead
        trafficFilterManager?.let { filter ->
            if (filter.isFilteringEnabled()) {
                val filterResult = filter.shouldAllowConnection(host, port, clientIp)
                if (filterResult is FilterResult.BLOCK) {
                    Log.d(TAG, "HTTP request blocked by filter: $host:$port (reason: ${filterResult.reason})")
                    sendErrorResponse(writer, 403, filterResult.reason)
                    return@withContext true // Close connection on error
                }
            }
        }

        hostsAccessed.add(host)

        // Check for Connection header to determine if we should keep connection alive
        val connectionHeader = headers.firstOrNull { 
            it.startsWith("Connection:", ignoreCase = true) 
        }?.substringAfter(":")?.trim()?.lowercase()
        val shouldClose = connectionHeader == "close"
        // HTTP/1.1 defaults to keep-alive unless Connection: close is specified
        val isKeepAlive = !shouldClose && (connectionHeader == "keep-alive" || HTTP_VERSION == "HTTP/1.1")
        
        // Build request preface to forward to target
        val sb = StringBuilder()
        sb.append("$method $path $HTTP_VERSION\r\n")
        headers.filterNot { it.startsWith("Proxy-Authorization", ignoreCase = true) }
                .forEach { h -> sb.append(h).append("\r\n") }
        // If client wants keep-alive, we'll handle it, but always add Connection header for clarity
        if (!headers.any { it.startsWith("Connection:", ignoreCase = true) }) {
            sb.append("Connection: ${if (isKeepAlive) "keep-alive" else "close"}\r\n")
        }
        sb.append("\r\n")
        val preface = sb.toString().toByteArray(Charsets.US_ASCII)

        // Forward to target via VPN-bound socket
        val connectStartTime = System.currentTimeMillis()
        val targetSocket = connectTarget(host, port)
        val connectDuration = System.currentTimeMillis() - connectStartTime
        Log.i(TAG, "[PERF] HTTP target connected: $host:$port | Connect time: ${connectDuration}ms")
        try {
            // Use larger buffer sizes for better throughput with concurrent connections
            val targetOut = BufferedOutputStream(targetSocket.getOutputStream(), 65536) // 64KB buffer
            val targetIn = BufferedInputStream(targetSocket.getInputStream(), 65536) // 64KB buffer
            val clientOut = BufferedOutputStream(socket.getOutputStream(), 65536) // 64KB buffer
            val clientIn = socket.getInputStream()

            // Send request line + headers
            var connectionOk = true
            var totalUp = 0L
            try {
                forwardThroughVpn(preface, preface.size, targetOut)
                
                // Read and forward request body for POST/PUT/PATCH requests
                if (contentLength != null && contentLength!! > 0 && 
                    (method.equals("POST", ignoreCase = true) || 
                     method.equals("PUT", ignoreCase = true) || 
                     method.equals("PATCH", ignoreCase = true))) {
                    Log.d(TAG, "Reading request body: $contentLength bytes for $method request")
                    val bodyBuffer = ByteArray(BUFFER_SIZE)
                    var remaining = contentLength!!
                    
                    while (remaining > 0) {
                        val toRead = minOf(remaining, BUFFER_SIZE)
                        val read = clientIn.read(bodyBuffer, 0, toRead)
                        if (read <= 0) break
                        
                        forwardThroughVpn(bodyBuffer, read, targetOut)
                        totalUp += read
                        remaining -= read
                    }
                }

                targetOut.flush()
            } catch (e: SocketException) {
                Log.w(TAG, "Connection reset while sending request: ${e.message}")
                connectionOk = false
            } catch (e: IOException) {
                Log.w(TAG, "IO error while sending request: ${e.message}")
                connectionOk = false
            }

            // Stream response bytes back to client only if connection is still ok
            var totalDown = 0L
            var flushCounter = 0
            if (connectionOk) {
                val buffer = ByteArray(BUFFER_SIZE)
                try {
                    while (true) {
                        val n =
                                try {
                                    targetIn.read(buffer)
                                } catch (e: SocketException) {
                                    Log.w(
                                            TAG,
                                            "Connection reset while reading response: ${e.message}"
                                    )
                                    break
                                } catch (e: IOException) {
                                    Log.w(
                                            TAG,
                                            "IO error while reading response: ${e.message}"
                                    )
                                    break
                                }

                        if (n <= 0) break

                        try {
                            clientOut.write(buffer, 0, n)
                            totalDown += n
                            
                            // Flush every 4 reads (256KB) instead of every read for better performance
                            // Only flush frequently for small responses, less frequently for large ones
                            flushCounter++
                            if (flushCounter >= 4 || totalDown < 65536) {
                                clientOut.flush()
                                flushCounter = 0
                            }
                        } catch (e: SocketException) {
                            Log.w(
                                    TAG,
                                    "Connection reset while writing response to client: ${e.message}"
                            )
                            break
                        } catch (e: IOException) {
                            Log.w(
                                    TAG,
                                    "IO error while writing response to client: ${e.message}"
                            )
                            break
                        }
                    }
                    
                    // Final flush
                    try {
                        clientOut.flush()
                    } catch (e: SocketException) {
                        Log.w(TAG, "Connection reset during response flush: ${e.message}")
                    } catch (e: IOException) {
                        Log.w(TAG, "IO error during response flush: ${e.message}")
                    }
                } finally {
                    // traffic accounting (include request body in upload) - do this asynchronously to not block
                    val finalUp = totalUp
                    val finalDown = totalDown
                    scope.launch {
                        bytesUp.addAndGet(finalUp)
                        bytesDown.addAndGet(finalDown)
                        trafficMeter.recordTraffic(clientIp, finalUp, finalDown)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle HTTP request to $host:$port", e)
            // Only send error response if connection is still valid
            if (!socket.isClosed && socket.isConnected) {
                try {
                    sendErrorResponse(writer, 502, "Bad Gateway")
                } catch (ignored: Exception) {
                    // Connection might be broken, ignore
                }
            }
            return@withContext true // Close connection on error
        } finally {
            safeClose(targetSocket)
            val requestDuration = System.currentTimeMillis() - requestStartTime
            Log.i(TAG, "[PERF] HTTP request COMPLETE: $method $url | Total duration: ${requestDuration}ms")
        }
        
        // Return whether connection should close
        shouldClose
    }

    /**
     * Copy bytes from input to output. If isUpload = true, bytes are accounted as client -> target
     * (↑). If false, as target -> client (↓).
     */
    private suspend fun tunnelData(
        input: InputStream,
        output: OutputStream,
        isUpload: Boolean
    ) = withContext(Dispatchers.IO) {
        val tunnelStartTime = System.currentTimeMillis()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var flushCounter = 0
        var readCount = 0
        var lastLogTime = System.currentTimeMillis()
        val threadName = Thread.currentThread().name
        try {
            while (true) {
                try {
                    val n = input.read(buffer)
                    
                    if (n <= 0) {
                        break
                    }
                    
                    readCount++
                    total += n
                    
                    // Check quota more frequently during transfer (every 10 reads or every 8KB to catch quota exceeded earlier)
                    // We check if current quota state + running total of this connection exceeds quota
                    // Quota usage is recorded incrementally in tunnelData finally block, same as traffic metering
                    if (quotaManager != null && (readCount % 10 == 0 || total % 8192 == 0L)) {
                        try {
                            // Check if adding the current connection's total would exceed quota
                            val quotaStatus = quotaManager.checkQuota(clientIp, total)
                            when (quotaStatus) {
                                is QuotaStatus.Blocked -> {
                                    Log.w(TAG, "Client $clientIp is blocked due to quota - closing connection")
                                    break
                                }
                                is QuotaStatus.Exceeded -> {
                                    Log.w(TAG, "Client $clientIp has exceeded quota (used $total bytes in this connection) - closing connection")
                                    break
                                }
                                else -> {
                                    // Quota OK, continue
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error checking quota during transfer", e)
                        }
                    }
                    
                    // Reduced logging - only log progress every 5 seconds or every 500 reads
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 5000 || readCount % 500 == 0) {
                        val elapsed = currentTime - tunnelStartTime
                        val speed = if (elapsed > 0) (total * 1000 / elapsed / 1024) else 0
                        Log.d(TAG, "[PERF] Tunnel ${if (isUpload) "UPLOAD" else "DOWNLOAD"} progress: ${total} bytes, ${readCount} reads, ${elapsed}ms, ${speed} KB/s")
                        lastLogTime = currentTime
                    }
                    
                    try {
                        forwardThroughVpn(buffer, n, output)
                        
                        // Flush every 4 reads for better performance
                        flushCounter++
                        if (flushCounter >= 4) {
                            output.flush()
                            flushCounter = 0
                        }
                    } catch (e: SocketException) {
                        break
                    } catch (e: IOException) {
                        break
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout on read - connection is idle or stuck
                    // For tunneling, if we timeout, close this direction
                    // The other direction may still be active
                    Log.d(TAG, "[PERF] Read timeout in ${if (isUpload) "UPLOAD" else "DOWNLOAD"} tunnel after ${System.currentTimeMillis() - tunnelStartTime}ms - closing direction")
                    break
                } catch (e: SocketException) {
                    Log.w(TAG, "Connection reset during data tunneling: ${e.message}")
                    break
                } catch (e: IOException) {
                    Log.w(TAG, "Error reading data: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during read: ${e.message}", e)
                    break
                }
            }
            
            // Final flush
            try {
                output.flush()
            } catch (e: SocketException) {
                Log.w(TAG, "Connection reset during flush: ${e.message}")
            } catch (e: IOException) {
                Log.w(TAG, "IO error during flush: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in tunnelData: ${e.message}", e)
        } finally {
            val tunnelDuration = System.currentTimeMillis() - tunnelStartTime
            val direction = if (isUpload) "UPLOAD" else "DOWNLOAD"
            Log.i(TAG, "[PERF] Tunnel $direction completed: ${total} bytes | Duration: ${tunnelDuration}ms | Speed: ${if (tunnelDuration > 0) (total * 1000 / tunnelDuration / 1024) else 0} KB/s")
            // Record traffic statistics asynchronously to not block
            val finalTotal = total
            scope.launch {
                // Get MAC address from TrafficMeter (same way we do for other features)
                val macAddress = try {
                    (trafficMeter as? TrafficMeterSimple)?.mapIpToMac()?.get(clientIp) ?: null
                } catch (e: Exception) {
                    null
                }
                
                if (isUpload) {
                    bytesUp.addAndGet(finalTotal)
                    trafficMeter.recordTraffic(clientIp, finalTotal, 0)
                    Log.d(TAG, "↑ Upload: $finalTotal bytes from $clientIp")
                    // Record quota usage incrementally (same as traffic metering) with MAC address
                    quotaManager?.recordUsage(clientIp, finalTotal, 0, macAddress)
                } else {
                    bytesDown.addAndGet(finalTotal)
                    trafficMeter.recordTraffic(clientIp, 0, finalTotal)
                    Log.d(TAG, "↓ Download: $finalTotal bytes to $clientIp")
                    // Record quota usage incrementally (same as traffic metering) with MAC address
                    quotaManager?.recordUsage(clientIp, 0, finalTotal, macAddress)
                }
            }
        }
    }

    /**
     * Note: Under the current approach of using the system-level third-party VPN, there is no need
     * to "write into a VPN tunnel" here. All outbound sockets are already bound to the VPN via
     * activeNetwork.socketFactory. This method is only for logging and policy control.
     */
    private suspend fun forwardThroughVpn(data: ByteArray, length: Int, output: OutputStream) {
        // No need for withContext - tunnelData is already running on Dispatchers.IO
        // Removing unnecessary context switch to reduce overhead
        try {
            output.write(data, 0, length) // output belongs to a VPN-bound target socket
        } catch (e: SocketException) {
            Log.w(TAG, "Socket closed during VPN forward: ${e.message}")
            throw e // Re-throw to let caller handle connection cleanup
        } catch (e: IOException) {
            Log.w(TAG, "IO error during VPN forward: ${e.message}")
            throw e // Re-throw to let caller handle connection cleanup
        }
    }

    /** Create a target connection using the VPN-bound SocketFactory */
    private fun connectTarget(host: String, port: Int): Socket {
        Log.d(TAG, "Creating socket via VPN-bound factory for $host:$port")
        return socketFactory.createSocket().apply {
            // Optimize for concurrent connections - faster connect timeout
            // Multiple connections can establish in parallel
            connect(InetSocketAddress(host, port), /* connect timeout */ 8_000)
            // Note: soTimeout will be set to 5s during tunneling for faster failure detection
            // Initial timeout is longer for connection establishment
            soTimeout = 10_000 // 10 seconds initially - will be reduced to 5s during tunneling
            tcpNoDelay = true // Disable Nagle's algorithm for lower latency
            // Set receive buffer size for better throughput
            receiveBufferSize = 131072 // 128KB for better concurrent performance
            sendBufferSize = 131072 // 128KB for better concurrent performance
            // Set keep-alive for better connection reuse
            keepAlive = true
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

    private fun sendProxyAuthRequired(writer: PrintWriter) {
        val message = "Proxy authentication required"
        writer.println("$HTTP_VERSION 407 Proxy Authentication Required")
        writer.println("Proxy-Agent: ShieldShare/1.0")
        writer.println("Proxy-Authenticate: Basic realm=\"ShieldShare Proxy\"")
        writer.println("Content-Type: text/plain")
        writer.println("Content-Length: ${message.length}")
        writer.println("Connection: close")
        writer.println()
        writer.println(message)
        writer.flush()
    }

    private fun cleanup() {
        try {
            // Cancel all coroutines in scope first
            scope.cancel()
            
            // Close socket
            if (!socket.isClosed) {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }

        // STAGE 2: End traffic session and log summary
        try {
            sessionId?.let { sid ->
                (trafficMeter as? TrafficMeterSimple)?.endSession(sid)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error ending session", e)
        }

        val totalUp = bytesUp.get()
        val totalDown = bytesDown.get()
        val macAddress = try {
            (trafficMeter as? TrafficMeterSimple)?.mapIpToMac()?.get(clientIp) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        Log.i(TAG, "**HTTP session ended** for $clientIp ($macAddress)")
        Log.i(TAG, "   ↑ **Total Upload**: ${formatBytes(totalUp)}")
        Log.i(TAG, "   ↓ **Total Download**: ${formatBytes(totalDown)}")
        Log.i(
                TAG,
                "   **Hosts Accessed**: ${hostsAccessed.size} - ${hostsAccessed.joinToString(", ")}"
        )
        Log.i(TAG, "   **User Agent**: ${userAgent ?: "Unknown"}")
        
        // Call traffic callback - THIS REMOVES THE HANDLER FROM THE MAP
        // This is critical - the callback must be called to free up the handler slot
        try {
            trafficCallback(totalUp, totalDown)
        } catch (e: Exception) {
            Log.e(TAG, "Error in traffic callback", e)
            // If callback fails, handler won't be removed - this is a problem
            // But we can't remove it here because we don't have access to the map
        }
    }

    private fun parseHostPort(url: String): Pair<String?, Int> =
            try {
                val parts = url.split(":")
                if (parts.size == 2) parts[0] to parts[1].toInt() else null to -1
            } catch (_: Throwable) {
                null to -1
            }

    /** For absolute-form proxy requests (http://host:port/path). */
    private fun parseHttpUrl(url: String): Triple<String?, Int, String> =
            try {
                val uri = URI(url)
                val host = uri.host
                val port = if (uri.port != -1) uri.port else 80
                val path =
                        (uri.rawPath
                                ?: "/") + (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                Triple(host, port, path)
            } catch (_: Throwable) {
                Triple(null, -1, url)
            }

    private fun splitHostPort(hostHeader: String, defaultPort: Int): Pair<String, Int> {
        val idx = hostHeader.lastIndexOf(':')
        return if (idx > 0 && idx < hostHeader.length - 1 && hostHeader.indexOf(']') == -1) {
            // simple host:port (no IPv6 bracket form)
            val h = hostHeader.substring(0, idx).trim()
            val p = hostHeader.substring(idx + 1).trim().toIntOrNull() ?: defaultPort
            h to p
        } else {
            hostHeader.trim() to defaultPort
        }
    }

    private fun safeClose(s: Socket?) =
            try {
                s?.close()
            } catch (_: Throwable) {}

    private fun formatBytes(bytes: Long): String =
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
}
