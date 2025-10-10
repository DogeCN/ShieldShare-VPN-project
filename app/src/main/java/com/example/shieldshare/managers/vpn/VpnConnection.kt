package com.example.shieldshare.managers.vpn

/**
 * VPN Connection data class
 * Based on the CSV specification
 */
data class VpnConnection(
    val connectionId: String,
    val status: VpnStatus,
    val connectedAt: Long = System.currentTimeMillis(),
    val serverAddress: String
)
