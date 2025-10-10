package com.example.shieldshare.managers.vpn

interface VpnManager {
    fun start(
        config: VpnConfig = VpnConfig(),
    )
    fun stop()
    val isRunning: Boolean
}
