package com.example.shieldshare.managers.proxy

import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import com.example.shieldshare.managers.quota.QuotaManager
import com.example.shieldshare.managers.quota.QuotaStatus
import com.example.shieldshare.managers.filter.TrafficFilterManager
import com.example.shieldshare.managers.filter.FilterResult
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import javax.net.SocketFactory

/** SOCKS5 Proxy Handler — VPN-bound outbound sockets */
class Socks5ProxyHandler(
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
        private const val TAG = "Socks5ProxyHandler"
        private const val BUFFER_SIZE = 65536 // 64KB buffer for better throughput (increased from 8KB)
        private const val SOCKS_VERSION = 0x05
        private const val AUTH_METHOD_NONE = 0x00
        private const val AUTH_METHOD_USERNAME_PASSWORD = 0x02
        private const val CMD_CONNECT = 0x01
        private const val ADDR_TYPE_IPV4 = 0x01
        private const val ADDR_TYPE_DOMAIN = 0x03
        private const val ADDR_TYPE_IPV6 = 0x04
        private const val REPLY_SUCCESS = 0x00
        private const val REPLY_GENERAL_FAILURE = 0x01
        private const val REPLY_CONNECTION_NOT_ALLOWED = 0x02
        private const val REPLY_NETWORK_UNREACHABLE = 0x03
        private const val REPLY_HOST_UNREACHABLE = 0x04
        private const val REPLY_CONNECTION_REFUSED = 0x05
        private const val REPLY_TTL_EXPIRED = 0x06
        private const val REPLY_COMMAND_NOT_SUPPORTED = 0x07
        private const val REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val clientIp: String = clientSocket.remoteSocketAddress.toString()
        .substringAfter("/").substringBefore(":")

    override fun handleConnectionInternal() {
        scope.launch {
            try {
                // Optimize client socket for better performance
                try {
                    socket.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
                    socket.receiveBufferSize = 131072 // 128KB for better throughput
                    socket.sendBufferSize = 131072 // 128KB for better throughput
                    socket.keepAlive = true // Enable keep-alive for connection reuse
                    socket.soTimeout = 10000 // 10 seconds timeout for initial request
                } catch (e: Exception) {
                    Log.w(TAG, "Error optimizing client socket", e)
                }
                
                // Validate connection before processing
                if (socket.isClosed || !socket.isConnected) {
                    Log.w(TAG, "Invalid socket connection, skipping request")
                    return@launch
                }
                
                handleSocks5Request()
            } catch (e: java.net.SocketTimeoutException) {
                Log.d(TAG, "Socket timeout handling SOCKS5 request")
            } catch (e: java.net.SocketException) {
                Log.d(TAG, "Socket closed or reset: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling SOCKS5 request", e)
            } finally {
                cleanup()
            }
        }
    }

    private suspend fun handleSocks5Request() =
            withContext(Dispatchers.IO) {
                val input = inOverride ?: socket.getInputStream()
                val output = socket.getOutputStream()

                // Step 1: Authentication negotiation
                if (!handleAuthentication(input, output)) {
                    Log.w(TAG, "Authentication failed")
                    return@withContext
                }

                // Step 2: Connection request
                val (targetHost, targetPort) = handleConnectionRequest(input, output)
                if (targetHost == null || targetPort == -1) {
                    Log.w(TAG, "Invalid connection request")
                    return@withContext
                }

                // NEW: Optional filter check (only if manager provided AND enabled)
                // SAFETY: Early return if disabled, zero overhead
                trafficFilterManager?.let { filter ->
                    if (filter.isFilteringEnabled()) {
                        val filterResult = filter.shouldAllowConnection(targetHost, targetPort, clientIp)
                        if (filterResult is FilterResult.BLOCK) {
                            Log.d(TAG, "SOCKS5 connection blocked by filter: $targetHost:$targetPort (reason: ${filterResult.reason})")
                            sendConnectionReply(output, REPLY_CONNECTION_NOT_ALLOWED)
                            return@withContext
                        }
                    }
                }

                // Step 3: Establish connection to target
                val targetSocket = establishTargetConnection(targetHost, targetPort, output)
                if (targetSocket == null) {
                    Log.w(TAG, "Failed to connect to target: $targetHost:$targetPort")
                    return@withContext
                }

                // Step 4: Start tunneling
                startTunneling(targetSocket)
            }

    private suspend fun handleAuthentication(input: InputStream, output: OutputStream): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    // Read authentication methods
                    val version = input.read()
                    if (version != SOCKS_VERSION) {
                        Log.w(TAG, "Unsupported SOCKS version: $version")
                        return@withContext false
                    }

                    val methodCount = input.read()
                    if (methodCount <= 0) return@withContext false
                    val methods = ByteArray(methodCount)
                    if (input.read(methods) != methods.size) return@withContext false

                    val supportsNoAuth = methods.contains(AUTH_METHOD_NONE.toByte())
                    val supportsUserPass = methods.contains(AUTH_METHOD_USERNAME_PASSWORD.toByte())
                    val authRequired = isAuthConfigured()

                    val selectedMethod =
                            when {
                                authRequired && supportsUserPass -> AUTH_METHOD_USERNAME_PASSWORD
                                !authRequired && supportsNoAuth -> AUTH_METHOD_NONE
                                !authRequired && supportsUserPass -> AUTH_METHOD_USERNAME_PASSWORD
                                else -> {
                                    Log.w(
                                            TAG,
                                            "No supported authentication methods (required=$authRequired)"
                                    )
                                    output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
                                    output.flush()
                                    return@withContext false
                                }
                            }

                    // Send selected method
                    output.write(byteArrayOf(SOCKS_VERSION.toByte(), selectedMethod.toByte()))
                    output.flush()

                    // Handle username/password authentication if selected
                    if (selectedMethod == AUTH_METHOD_USERNAME_PASSWORD) {
                        return@withContext handleUsernamePasswordAuth(input, output)
                    }

                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error during authentication", e)
                    false
                }
            }

    private suspend fun handleUsernamePasswordAuth(
        input: InputStream,
        output: OutputStream
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val version = input.read()
                if (version != 0x01) {
                    return@withContext false
                }

                val usernameLength = input.read()
                val username = ByteArray(usernameLength)
                input.read(username)

                val passwordLength = input.read()
                val password = ByteArray(passwordLength)
                input.read(password)

                val usernameStr = String(username)
                val passwordStr = String(password)
                val valid =
                        if (!isAuthConfigured()) {
                            true
                        } else {
                            val matches =
                                    usernameStr == authUsername && passwordStr == authPassword
                            if (!matches) {
                                Log.w(TAG, "SOCKS5 auth failed for $clientIp (user mismatch)")
                            }
                            matches
                        }

                val statusByte: Byte = if (valid) 0x00 else 0x01
                output.write(byteArrayOf(0x01, statusByte))
                output.flush()

                valid

            } catch (e: Exception) {
                Log.e(TAG, "Error during username/password auth", e)
                false
            }
        }

    private suspend fun handleConnectionRequest(
        input: InputStream,
        output: OutputStream
    ): Pair<String?, Int> =
        withContext(Dispatchers.IO) {
            try {
                val version = input.read()
                if (version != SOCKS_VERSION) {
                    sendConnectionReply(output, REPLY_GENERAL_FAILURE)
                    return@withContext Pair(null, -1)
                }

                val command = input.read()
                if (command != CMD_CONNECT) {
                    sendConnectionReply(output, REPLY_COMMAND_NOT_SUPPORTED)
                    return@withContext Pair(null, -1)
                }

                input.read() // Reserved byte

                val addressType = input.read()
                val (host, port) =
                    when (addressType) {
                        ADDR_TYPE_IPV4 -> {
                            val ipBytes = ByteArray(4)
                            input.read(ipBytes)
                            val ip = InetAddress.getByAddress(ipBytes)
                            val portBytes = ByteArray(2)
                            input.read(portBytes)
                            val port =
                                ((portBytes[0].toInt() and 0xFF) shl 8) or
                                        (portBytes[1].toInt() and 0xFF)
                            Pair(ip.hostAddress, port)
                        }
                        ADDR_TYPE_DOMAIN -> {
                            val domainLength = input.read()
                            val domainBytes = ByteArray(domainLength)
                            input.read(domainBytes)
                            val domain = String(domainBytes)
                            val portBytes = ByteArray(2)
                            input.read(portBytes)
                            val port =
                                ((portBytes[0].toInt() and 0xFF) shl 8) or
                                        (portBytes[1].toInt() and 0xFF)
                            Pair(domain, port)
                        }
                        ADDR_TYPE_IPV6 -> {
                            // IPv6 not implemented yet
                            sendConnectionReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                            return@withContext Pair(null, -1)
                        }
                        else -> {
                            sendConnectionReply(output, REPLY_ADDRESS_TYPE_NOT_SUPPORTED)
                            return@withContext Pair(null, -1)
                        }
                    }

                Log.d(TAG, "SOCKS5 connection request: $host:$port")
                Pair(host, port)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing connection request", e)
                sendConnectionReply(output, REPLY_GENERAL_FAILURE)
                Pair(null, -1)
            }
        }

    private fun isAuthConfigured(): Boolean {
        return authEnabled && !authUsername.isNullOrEmpty() && !authPassword.isNullOrEmpty()
    }


    private suspend fun establishTargetConnection(
        host: String,
        port: Int,
        output: OutputStream
    ): Socket? =
        withContext(Dispatchers.IO) {
            try {
                val targetSocket = socketFactory.createSocket()
                // Optimize for concurrent connections
                targetSocket.connect(InetSocketAddress(host, port), 8000) // 8 second connect timeout
                // Note: soTimeout will be set to 5s during tunneling for faster failure detection
                targetSocket.soTimeout = 10000 // 10 seconds initially - will be reduced to 5s during tunneling
                targetSocket.tcpNoDelay = true // Disable Nagle's algorithm for lower latency
                targetSocket.receiveBufferSize = 131072 // 128KB for better concurrent performance
                targetSocket.sendBufferSize = 131072 // 128KB for better concurrent performance
                targetSocket.keepAlive = true // Enable keep-alive for connection reuse

                // Send success reply
                sendConnectionReply(
                    output,
                    REPLY_SUCCESS,
                    targetSocket.localAddress,
                    targetSocket.localPort
                )

                targetSocket
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to target: $host:$port", e)
                sendConnectionReply(output, REPLY_CONNECTION_REFUSED)
                null
            }
        }

    private fun sendConnectionReply(
        output: OutputStream,
        replyCode: Int,
        bindAddress: InetAddress? = null,
        bindPort: Int = 0
    ) {
        try {
            val reply = ByteArray(4)
            reply[0] = SOCKS_VERSION.toByte()
            reply[1] = replyCode.toByte()
            reply[2] = 0x00 // Reserved

            if (bindAddress != null && bindAddress is Inet4Address) {
                reply[3] = ADDR_TYPE_IPV4.toByte()
                val fullReply =
                    reply +
                            bindAddress.address +
                            byteArrayOf((bindPort shr 8).toByte(), (bindPort and 0xFF).toByte())
                output.write(fullReply)
            } else {
                reply[3] = ADDR_TYPE_IPV4.toByte()
                val fullReply = reply + byteArrayOf(0, 0, 0, 0, 0, 0)
                output.write(fullReply)
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending connection reply", e)
        }
    }

    private suspend fun startTunneling(targetSocket: Socket) =
        withContext(Dispatchers.IO) {
            // Set shorter timeout for tunneling to fail fast on stuck connections
            val originalClientTimeout = try { socket.soTimeout } catch (e: Exception) { 0 }
            val originalTargetTimeout = try { targetSocket.soTimeout } catch (e: Exception) { 0 }
            try {
                socket.soTimeout = 5000 // 5 seconds - fail fast on stuck client connections
                targetSocket.soTimeout = 5000 // 5 seconds - fail fast on stuck target connections
            } catch (e: Exception) {
                Log.w(TAG, "Error setting socket timeouts for tunneling", e)
            }
            
            try {
                // Use coroutineScope to ensure both tunnels are cancelled together if one fails
                coroutineScope {
                    val t1 = launch {
                        tunnelData(
                            socket.getInputStream(),
                            targetSocket.getOutputStream(),
                            isUpload = true
                        )
                    }
                    val t2 = launch {
                        tunnelData(
                            targetSocket.getInputStream(),
                            socket.getOutputStream(),
                            isUpload = false
                        )
                    }
                    // Wait for both tunnels - if one fails, the other is automatically cancelled
                    t1.join()
                    t2.join()
                }
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
    private suspend fun tunnelData(
        input: InputStream,
        output: OutputStream,
        isUpload: Boolean
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var flushCounter = 0
        var readCount = 0
        try {
            while (true) {
                try {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    
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
                    Log.d(TAG, "[PERF] Read timeout in ${if (isUpload) "UPLOAD" else "DOWNLOAD"} tunnel after ${System.currentTimeMillis()}ms - closing direction")
                    break
                } catch (e: SocketException) {
                    break
                } catch (e: IOException) {
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
            // Record traffic statistics asynchronously to avoid blocking
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
     * Note: Under the current approach of using the system-level third-party VPN,
     * there is no need to "write into a VPN tunnel" here.
     * All outbound sockets are already bound to the VPN via activeNetwork.socketFactory.
     * This method is only for logging and policy control.
     */
    private suspend fun forwardThroughVpn(data: ByteArray, length: Int, output: OutputStream) {
        // No need for withContext - tunnelData is already running on Dispatchers.IO
        // Removing unnecessary context switch to reduce overhead
        try {
            output.write(data, 0, length) // output belongs to a VPN-bound target socket
        } catch (e: SocketException) {
            throw e // Re-throw to let caller handle connection cleanup
        } catch (e: IOException) {
            throw e // Re-throw to let caller handle connection cleanup
        }
    }

    private fun safeClose(s: Socket?) = try { s?.close() } catch (_: Throwable) {}

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
        
        // Call traffic callback
        trafficCallback(bytesUp.get(), bytesDown.get())
    }
}
