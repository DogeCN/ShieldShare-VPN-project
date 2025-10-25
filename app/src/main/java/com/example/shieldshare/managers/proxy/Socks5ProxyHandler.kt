package com.example.shieldshare.managers.proxy

import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** SOCKS5 Proxy Handler Handles SOCKS5 protocol for tunneling various types of traffic */
class Socks5ProxyHandler(
        clientSocket: Socket,
        trafficMeter: TrafficMeter,
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)

    fun start() {
        scope.launch {
            try {
                handleConnection()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling SOCKS5 request", e)
            } finally {
                cleanup()
            }
        }
    }

    override fun handleConnectionInternal() {
        scope.launch { handleSocks5Request() }
    }

    private suspend fun handleSocks5Request() =
            withContext(Dispatchers.IO) {
                val input = socket.getInputStream()
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
                    val methods = ByteArray(methodCount)
                    input.read(methods)

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
                try {
                    val targetSocket = Socket()
                    targetSocket.connect(InetSocketAddress(host, port), 10000)
                    targetSocket.soTimeout = 30000 // 30 second read timeout

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

                targetSocket.close()
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
                            bytesCounter.addAndGet(bytesRead.toLong())
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

    private fun cleanup() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }

        // Report traffic statistics
        trafficCallback(bytesUp.get(), bytesDown.get())

        scope.cancel()
    }
}
