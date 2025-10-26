package com.example.shieldshare.managers.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import javax.net.SocketFactory

/** Whether a system-level VPN is connected (activeNetwork has TRANSPORT_VPN when a third-party VPN is active). */
fun Context.isVpnConnected(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

/**
 * return a known VPN SocketFactory。
 * When system shown TRANSPORT_VPN，return socketFactory（all connection go VPN）；
 * otherwise ：
 *  - strict=true: throw exception
 *  - strict=false: return SocketFactory
 */
fun Context.vpnAwareSocketFactory(strict: Boolean = true): SocketFactory {
    val cm = getSystemService(ConnectivityManager::class.java)
    val net = cm.activeNetwork ?: return if (strict)
        throw IllegalStateException("VPN is not connected; refusing to create non-VPN sockets.")
    else
        SocketFactory.getDefault()

    val caps = cm.getNetworkCapabilities(net)
    val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    if (isVpn) return net.socketFactory
    if (strict) throw IllegalStateException("VPN is not connected; refusing to create non-VPN sockets.")
    return SocketFactory.getDefault()
}


