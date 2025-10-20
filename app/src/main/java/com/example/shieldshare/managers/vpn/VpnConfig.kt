package com.example.shieldshare.managers.vpn

import java.io.Serializable

/** to third party VPN  */
data class VpnConfig(
    val thirdPartyPackage: String? = null, // like "com.vpnmaster.android"
    val note: String? = null               // what it shows
) : Serializable

data class VpnConnection(
    val instanceId: String,
    val config: VpnConfig
) : Serializable

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}
