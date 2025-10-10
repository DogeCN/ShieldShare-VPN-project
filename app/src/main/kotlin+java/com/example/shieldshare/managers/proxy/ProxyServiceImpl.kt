package com.example.shieldshare.managers.proxy

import android.content.Context
import android.content.Intent

class ProxyServerImpl(private val context: Context) : ProxyServer {
    @Volatile private var running = false
    override fun start() {
        context.startService(Intent(context, ProxyForegroundService::class.java))
        running = true
    }
    override fun stop() {
        context.stopService(Intent(context, ProxyForegroundService::class.java))
        running = false
    }
    override val isRunning: Boolean get() = running
}
