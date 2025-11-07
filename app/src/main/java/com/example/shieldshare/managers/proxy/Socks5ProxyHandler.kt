package com.example.shieldshare.managers.proxy

import android.net.Network
import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
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
        private val vpnNetwork: Network? = null,
        private val inOverride: InputStream? = null,
        private val trafficCallback: (bytesUp: Long, bytesDown: Long) -> Unit = { _, _ -> }
) : ProxyHandler(clientSocket, trafficMeter) {
    companion object {
        private const val TAG = "Socks5ProxyHandler"
        private const val BUFFER_SIZE = 8192
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
        
        // DNS cache with TTL: hostname -> Pair(IP address, expiration timestamp)
        private const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L
        private val dnsCache = mutableMapOf<String, Pair<InetAddress, Long>>()
        private val dnsCacheLock = Any()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val clientIp: String = clientSocket.remoteSocketAddress.toString()
        .substringAfter("/").substringBefore(":")

    override fun handleConnectionInternal() {
        scope.launch {
            try {
                handleSocks5Request()
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

                    // Check if no authentication is supported
                    val supportsNoAuth = methods.contains(AUTH_METHOD_NONE.toByte())
                    val supportsUserPass = methods.contains(AUTH_METHOD_USERNAME_PASSWORD.toByte())

                    val selectedMethod =
                            when {
                                supportsNoAuth -> AUTH_METHOD_NONE
                                supportsUserPass -> AUTH_METHOD_USERNAME_PASSWORD
                                else -> {
                                    Log.w(TAG, "No supported authentication methods")
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

                // For now, accept any username/password (implement proper auth later)
                val usernameStr = String(username)
                val passwordStr = String(password)
                Log.d(TAG, "Authentication attempt: user=$usernameStr")

                // Send success response
                output.write(byteArrayOf(0x01, 0x00))
                output.flush()

                true
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


    private suspend fun establishTargetConnection(
        host: String,
        port: Int,
        output: OutputStream
    ): Socket? =
        withContext(Dispatchers.IO) {
            val socket = socketFactory.createSocket()
            var resolvedAddress: InetAddress? = null
            
            try {
                // Check DNS cache first (with TTL validation)
                var cachedIp: InetAddress? = null
                synchronized(dnsCacheLock) {
                    val cached = dnsCache[host]
                    if (cached != null) {
                        val (ip, expirationTime) = cached
                        if (System.currentTimeMillis() < expirationTime) {
                            cachedIp = ip
                            Log.d(TAG, "Using cached DNS for $host -> ${ip.hostAddress}")
                        } else {
                            dnsCache.remove(host)
                            Log.d(TAG, "DNS cache expired for $host, will resolve fresh")
                        }
                    }
                }
                
                // Resolve DNS using VPN network if available
                resolvedAddress = if (cachedIp != null) {
                    cachedIp
                } else if (vpnNetwork != null) {
                    // Use VPN network's DNS resolver
                    try {
                        Log.d(TAG, "Resolving DNS for $host via VPN network")
                        val addresses = vpnNetwork.getAllByName(host)
                        if (addresses.isNotEmpty()) {
                            val ip = addresses[0]
                            Log.d(TAG, "DNS resolved via VPN: $host -> ${ip.hostAddress}")
                            
                            // Cache the resolved IP with TTL
                            synchronized(dnsCacheLock) {
                                dnsCache[host] = Pair(ip, System.currentTimeMillis() + DNS_CACHE_TTL_MS)
                            }
                            ip
                        } else {
                            throw UnknownHostException("No addresses found for $host")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "VPN DNS resolution failed for $host, falling back to socket DNS: ${e.message}")
                        null
                    }
                } else {
                    Log.d(TAG, "No VPN network available, using socket DNS for $host")
                    null
                }
                
                // Create socket address
                val address = if (resolvedAddress != null) {
                    InetSocketAddress(resolvedAddress, port)
                } else {
                    InetSocketAddress(host, port)
                }
                
                // Optimized connection timeout: 10 seconds
                socket.connect(address, 10_000)
                
                // Cache the final resolved IP if we didn't cache it already
                val connectedAddress = socket.remoteSocketAddress as? InetSocketAddress
                if (connectedAddress != null && resolvedAddress == null) {
                    synchronized(dnsCacheLock) {
                        dnsCache[host] = Pair(connectedAddress.address, System.currentTimeMillis() + DNS_CACHE_TTL_MS)
                        Log.d(TAG, "Cached DNS resolution from socket: $host -> ${connectedAddress.address.hostAddress}")
                    }
                }
                
                socket.soTimeout = 30_000
                socket.tcpNoDelay = true
                socket.keepAlive = true

                // Send success reply
                sendConnectionReply(
                    output,
                    REPLY_SUCCESS,
                    socket.localAddress,
                    socket.localPort
                )

                socket
            } catch (e: UnknownHostException) {
                synchronized(dnsCacheLock) {
                    dnsCache.remove(host)
                }
                Log.e(TAG, "DNS resolution failed for $host:$port - ${e.message}", e)
                try {
                    socket.close()
                } catch (_: Exception) {}
                sendConnectionReply(output, REPLY_HOST_UNREACHABLE)
                null
            } catch (e: SocketTimeoutException) {
                synchronized(dnsCacheLock) {
                    dnsCache.remove(host)
                }
                Log.e(TAG, "Connection timeout to $host:$port - ${e.message}", e)
                try {
                    socket.close()
                } catch (_: Exception) {}
                sendConnectionReply(output, REPLY_NETWORK_UNREACHABLE)
                null
            } catch (e: ConnectException) {
                synchronized(dnsCacheLock) {
                    dnsCache.remove(host)
                }
                Log.e(TAG, "Connection refused to $host:$port - ${e.message}", e)
                try {
                    socket.close()
                } catch (_: Exception) {}
                sendConnectionReply(output, REPLY_CONNECTION_REFUSED)
                null
            } catch (e: Exception) {
                synchronized(dnsCacheLock) {
                    dnsCache.remove(host)
                }
                Log.e(TAG, "Failed to connect to target: $host:$port - ${e.javaClass.simpleName}: ${e.message}", e)
                try {
                    socket.close()
                } catch (_: Exception) {}
                sendConnectionReply(output, REPLY_GENERAL_FAILURE)
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
            // Start bidirectional tunneling
            val tunnelJob1 =
                scope.launch {
                    tunnelData(
                        socket.getInputStream(),
                        targetSocket.getOutputStream(),
                        isUpload = true
                    )
                }
            val tunnelJob2 =
                scope.launch {
                    tunnelData(
                        targetSocket.getInputStream(),
                        socket.getOutputStream(),
                        isUpload = false
                    )
                }

            // Wait for either tunnel to complete
            joinAll(tunnelJob1, tunnelJob2)

            targetSocket.close()
        }
    private suspend fun tunnelData(
        input: InputStream,
        output: OutputStream,
        isUpload: Boolean
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        try {
            while (true) {
                try {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    
                    try {
                        // Note: using VPN-bound target output stream, no extra "tunnel API" needed
                        forwardThroughVpn(buffer, n, output)
                        total += n
                    } catch (e: SocketException) {
                        Log.w(TAG, "Connection reset during VPN forward: ${e.message}")
                        break
                    } catch (e: IOException) {
                        Log.w(TAG, "IO error during VPN forward: ${e.message}")
                        break
                    }
                } catch (e: SocketException) {
                    Log.w(TAG, "Connection reset during data tunneling: ${e.message}")
                    break // Exit the loop on connection reset
                } catch (e: IOException) {
                    Log.w(TAG, "Error reading data: ${e.message}")
                    break // Exit the loop on I/O errors
                }
            }
            
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
            // Record traffic statistics
            if (isUpload) {
                bytesUp.addAndGet(total)
                trafficMeter.recordTraffic(clientIp, total, 0)
                Log.d(TAG, "↑ Upload: $total bytes from $clientIp")
            } else {
                bytesDown.addAndGet(total)
                trafficMeter.recordTraffic(clientIp, 0, total)
                Log.d(TAG, "↓ Download: $total bytes to $clientIp")
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
        withContext(Dispatchers.IO) {
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
    }

    private fun safeClose(s: Socket?) = try { s?.close() } catch (_: Throwable) {}

    private fun cleanup() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }
        trafficCallback(bytesUp.get(), bytesDown.get())
        scope.cancel()
    }
}
