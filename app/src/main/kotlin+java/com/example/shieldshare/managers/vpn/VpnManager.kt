package com.example.shieldshare.managers.vpn

interface VpnManager {
    fun prepareAndStart()
    fun stop()
    val isRunning: Boolean
}
