package com.example.shieldshare.managers.proxy

import java.net.Socket

/** Manages HTTP/HTTPS and SOCKS5 proxy server operations and client connections */
interface ProxyServer {
    suspend fun startProxy(config: ProxyConfig): Result<ProxyInstance>
    suspend fun stopProxy(): Result<Unit>
    fun getProxyInfo(): ProxyInfo
    fun handleClientConnection(socket: Socket)
}
