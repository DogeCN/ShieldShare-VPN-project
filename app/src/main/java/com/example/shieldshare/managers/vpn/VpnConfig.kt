package com.example.shieldshare.managers.vpn

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VpnConfig(
    val serverAddress: String = "10.0.2.2", // my vpn server ip（deviec to server）
    val serverPort: Int = 5555,
    val sharedSecret: String = "shieldshare",
    val mtu: Int = 1500,
    val ipv4Address: String = "10.66.66.2",   // TUN local ip
    val prefixLength: Int = 24,
    val dnsServers: List<String> = listOf("1.1.1.1", "8.8.8.8"),
    val routes: List<String> = listOf(),
    val captureAll: Boolean = false
) : Parcelable
