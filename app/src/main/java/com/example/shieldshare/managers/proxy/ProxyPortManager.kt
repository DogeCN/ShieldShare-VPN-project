package com.example.shieldshare.managers.proxy

import android.util.Log

/**
 * Manages proxy port configuration to prevent conflicts and crashes Based on team feedback,
 * simplifies port selection to avoid complexity
 */
object ProxyPortManager {
    private const val TAG = "ProxyPortManager"

    // Standard ports for different protocols
    const val HTTP_PORT = 8080
    const val SOCKS5_PORT = 1080
    const val HTTPS_PORT = 443

    // Alternative ports if standard ones are busy
    private val HTTP_ALTERNATIVES = listOf(8081, 8082, 8083)
    private val SOCKS5_ALTERNATIVES = listOf(1081, 1082, 1083)

    /** Get recommended port for HTTP proxy Returns standard port or first available alternative */
    fun getHttpPort(): Int {
        return if (isPortAvailable(HTTP_PORT)) {
            HTTP_PORT
        } else {
            HTTP_ALTERNATIVES.firstOrNull { isPortAvailable(it) } ?: HTTP_PORT
        }
    }

    /**
     * Get recommended port for SOCKS5 proxy Returns standard port or first available alternative
     */
    fun getSocks5Port(): Int {
        return if (isPortAvailable(SOCKS5_PORT)) {
            SOCKS5_PORT
        } else {
            SOCKS5_ALTERNATIVES.firstOrNull { isPortAvailable(it) } ?: SOCKS5_PORT
        }
    }

    /** Get port configuration for both protocols Ensures different ports to prevent conflicts */
    fun getDualProtocolPorts(): Pair<Int, Int> {
        val httpPort = getHttpPort()
        val socks5Port =
                if (httpPort == HTTP_PORT) {
                    getSocks5Port()
                } else {
                    // If HTTP is using alternative port, use standard SOCKS5
                    SOCKS5_PORT
                }

        Log.d(TAG, "Recommended ports - HTTP: $httpPort, SOCKS5: $socks5Port")
        return Pair(httpPort, socks5Port)
    }

    /** Check if a port is available (simplified check) */
    private fun isPortAvailable(port: Int): Boolean {
        return try {
            val socket = java.net.ServerSocket()
            socket.bind(java.net.InetSocketAddress("127.0.0.1", port))
            socket.close()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Port $port is not available: ${e.message}")
            false
        }
    }

    /** Validate port configuration to prevent crashes */
    fun validatePortConfiguration(config: ProxyConfig): Boolean {
        return when (config.proxyType) {
            ProxyType.HTTP_HTTPS -> {
                config.port == HTTP_PORT || HTTP_ALTERNATIVES.contains(config.port)
            }
            ProxyType.SOCKS5 -> {
                config.port == SOCKS5_PORT || SOCKS5_ALTERNATIVES.contains(config.port)
            }
            ProxyType.BOTH -> {
                // For dual protocol, recommend using separate ports
                Log.w(TAG, "Dual protocol mode - consider using separate HTTP and SOCKS5 servers")
                true
            }
        }
    }

    /** Get user-friendly port recommendations */
    fun getPortRecommendations(): Map<String, String> {
        return mapOf(
                "HTTP/HTTPS Proxy" to
                        "Use port $HTTP_PORT (or ${HTTP_ALTERNATIVES.joinToString(", ")})",
                "SOCKS5 Proxy" to
                        "Use port $SOCKS5_PORT (or ${SOCKS5_ALTERNATIVES.joinToString(", ")})",
                "Both Protocols" to
                        "Use separate ports: HTTP on $HTTP_PORT, SOCKS5 on $SOCKS5_PORT",
                "Note" to "Avoid custom ports to prevent configuration conflicts"
        )
    }
}

