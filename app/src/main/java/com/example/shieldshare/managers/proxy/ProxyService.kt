package com.example.shieldshare.managers.proxy

import java.net.Socket

/**
 * Proxy Server Interface
 * Based on the class diagram specification (IProxyServer)
 */
interface ProxyServer {
    suspend fun startProxy(config: ProxyConfig): Result<ProxyInstance>
    suspend fun stopProxy(): Result<Unit>
    fun getProxyInfo(): ProxyInfo
    fun handleClientConnection(socket: Socket)
}
