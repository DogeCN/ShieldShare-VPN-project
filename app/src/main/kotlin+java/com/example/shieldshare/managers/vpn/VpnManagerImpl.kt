package com.example.shieldshare.managers.vpn

import android.content.Context
import android.content.Intent

class VpnManagerImpl(private val context: Context) : VpnManager {
    @Volatile private var running = false
    override fun prepareAndStart() {
        // TODO
        context.startService(Intent(context, VpnTunnelService::class.java))
        running = true
    }
    override fun stop() {
        context.stopService(Intent(context, VpnTunnelService::class.java))
        running = false
    }
    override val isRunning: Boolean get() = running
}
