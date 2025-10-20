package com.example.shieldshare.managers.proxy

import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * PAC (Proxy Auto Configuration) File Generator Generates PAC files to automatically configure
 * clients to use the proxy
 */
class PacFileGenerator {
    companion object {
        private const val TAG = "PacFileGenerator"
        private const val HTTP_PROXY_PORT = 8080
        private const val SOCKS5_PROXY_PORT = 1080
    }

    /** Generate PAC file content for the current hotspot network */
    fun generatePacFile(): String {
        val gatewayIp = getHotspotGatewayIp()
        val httpProxy = "$gatewayIp:$HTTP_PROXY_PORT"
        val socksProxy = "SOCKS5 $gatewayIp:$SOCKS5_PROXY_PORT"

        return """
function FindProxyForURL(url, host) {
    // ShieldShare PAC Configuration
    // Generated automatically for hotspot clients
    
    // Direct access for local networks (dynamically detected)
    if (isLocalNetwork(host)) {
        return "DIRECT";
    }
    
    // Direct access for localhost
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
        return "DIRECT";
    }
    
    // Use HTTP proxy for HTTP/HTTPS traffic
    if (url.substring(0, 5) == "http:" || url.substring(0, 6) == "https:") {
        return "PROXY $httpProxy";
    }
    
    // Use SOCKS5 proxy for other protocols (FTP, etc.)
    return "$socksProxy";
}
""".trimIndent()
    }

    /** Generate PAC file URL for clients to use */
    fun getPacFileUrl(): String {
        val gatewayIp = getHotspotGatewayIp()
        return if (gatewayIp != null) {
            "http://$gatewayIp:$HTTP_PROXY_PORT/proxy.pac"
        } else {
            "Not available"
        }
    }

    /** Get the hotspot gateway IP address dynamically */
    private fun getHotspotGatewayIp(): String? {
        return try {
            // Try to get the hotspot gateway IP
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is InetAddress && !address.isLoopbackAddress) {
                            val hostAddress = address.hostAddress
                            if (hostAddress != null && isLocalNetwork(hostAddress)) {
                                // Local network interface detected
                                // Return the gateway IP (typically .1)
                                val parts = hostAddress.split(".")
                                if (parts.size == 4) {
                                    return "${parts[0]}.${parts[1]}.${parts[2]}.1"
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot gateway IP", e)
            null
        }
    }
    
    /** Check if an IP address is in a local network range */
    private fun isLocalNetwork(host: String): Boolean {
        return try {
            val ip = java.net.InetAddress.getByName(host)
            val address = ip.address
            when {
                // 192.168.x.x
                address[0] == 192.toByte() && address[1] == 168.toByte() -> true
                // 10.x.x.x
                address[0] == 10.toByte() -> true
                // 172.16.x.x - 172.31.x.x
                address[0] == 172.toByte() && address[1] in 16..31 -> true
                // 127.x.x.x (localhost)
                address[0] == 127.toByte() -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Generate a simple PAC file for testing */
    fun generateSimplePacFile(): String {
        val gatewayIp = getHotspotGatewayIp()
        if (gatewayIp == null) {
            return "// PAC file not available - no local network detected"
        }
        val httpProxy = "$gatewayIp:$HTTP_PROXY_PORT"

        return """
function FindProxyForURL(url, host) {
    // Simple PAC - route all traffic through proxy
    return "PROXY $httpProxy";
}
""".trimIndent()
    }

    /** Generate PAC file with authentication */
    fun generatePacFileWithAuth(username: String, password: String): String {
        val gatewayIp = getHotspotGatewayIp()
        val httpProxy = "$gatewayIp:$HTTP_PROXY_PORT"

        return """
function FindProxyForURL(url, host) {
    // PAC with authentication
    // Note: PAC files cannot handle authentication directly
    // Clients must be configured with username/password separately
    
    if (isLocalNetwork(host)) {
        return "DIRECT";
    }
    
    return "PROXY $httpProxy";
}
""".trimIndent()
    }
}
