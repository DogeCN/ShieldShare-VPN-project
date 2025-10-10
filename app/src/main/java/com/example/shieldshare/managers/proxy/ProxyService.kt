package com.example.shieldshare.managers.proxy

interface ProxyServer {
    fun start()
    fun stop()
    val isRunning: Boolean
}
