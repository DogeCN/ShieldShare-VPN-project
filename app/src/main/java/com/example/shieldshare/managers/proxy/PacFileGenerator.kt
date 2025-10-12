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
    
    // Direct access for local network
    if (isInNet(host, "192.168.43.0", "255.255.255.0") ||
        isInNet(host, "10.0.0.0", "255.0.0.0") ||
        isInNet(host, "172.16.0.0", "255.240.0.0") ||
        isInNet(host, "127.0.0.0", "255.0.0.0")) {
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
        return "http://$gatewayIp:$HTTP_PROXY_PORT/proxy.pac"
    }

    /** Get the hotspot gateway IP address This is typically 192.168.43.1 for Android hotspots */
    private fun getHotspotGatewayIp(): String {
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
                            if (hostAddress != null && hostAddress.startsWith("192.168.43.")) {
                                // Hotspot interface detected
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
            // Fallback to default hotspot gateway
            "192.168.43.1"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot gateway IP", e)
            "192.168.43.1"
        }
    }

    /** Generate a simple PAC file for testing */
    fun generateSimplePacFile(): String {
        val gatewayIp = getHotspotGatewayIp()
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
    
    if (isInNet(host, "192.168.43.0", "255.255.255.0") ||
        isInNet(host, "127.0.0.0", "255.0.0.0")) {
        return "DIRECT";
    }
    
    return "PROXY $httpProxy";
}
""".trimIndent()
    }
}
