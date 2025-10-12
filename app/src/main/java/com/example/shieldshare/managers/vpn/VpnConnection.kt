package com.example.shieldshare.managers.vpn

/** Represents an active VPN connection with status and metadata */
data class VpnConnection(
        val connectionId: String,
        val status: VpnStatus,
        val connectedAt: Long = System.currentTimeMillis(),
        val serverAddress: String
)
