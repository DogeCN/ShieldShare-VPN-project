package com.example.shieldshare.managers.proxy

/** Configuration settings for proxy server (port, auth, type, allowed clients) */
data class ProxyConfig(
        val port: Int = 8080,
        val authEnabled: Boolean = false,
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
        val port: Int,
        val proxyType: ProxyType,
        val activeConnections: Int = 0,
        val pacFileUrl: String? = null
)
