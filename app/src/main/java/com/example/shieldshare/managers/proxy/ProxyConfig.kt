package com.example.shieldshare.managers.proxy

/** Configuration settings for proxy server (ports, auth, type, allowed clients) */
data class ProxyConfig(
        val httpPort: Int = ProxyPortManager.HTTP_PORT,
        val httpsPort: Int = ProxyPortManager.HTTPS_PORT,
        val socks5Port: Int = ProxyPortManager.SOCKS5_PORT,
        val authEnabled: Boolean = false,
        val authUsername: String? = null,
        val authPassword: String? = null,
        val allowedClients: List<String> = emptyList(),
        val proxyType: ProxyType = ProxyType.BOTH
)

enum class ProxyType {
        HTTP_HTTPS,
        SOCKS5,
        BOTH
}

data class ProxyInfo(
        val isRunning: Boolean,
        val httpPort: Int,
        val httpsPort: Int,
        val socks5Port: Int,
        val proxyType: ProxyType,
        val activeConnections: Int = 0,
        val pacFileUrl: String? = null
)
