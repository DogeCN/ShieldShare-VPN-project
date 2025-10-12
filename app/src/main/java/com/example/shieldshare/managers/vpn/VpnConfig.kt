package com.example.shieldshare.managers.vpn

import java.io.Serializable

/** VPN connection settings including server, protocol, credentials, and DNS configuration */
data class VpnConfig(
        val serverAddress: String = "",
        val protocol: VpnProtocol = VpnProtocol.OPENVPN,
        val credentials: Credentials = Credentials(),
        val dnsServers: List<String> = listOf("8.8.8.8", "8.8.4.4")
) : Serializable

data class Credentials(
        val username: String = "",
        val password: String = "",
        val certificate: String? = null
) : Serializable

enum class VpnProtocol : Serializable {
    OPENVPN,
    WIREGUARD,
    IPSEC
}

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED
}
