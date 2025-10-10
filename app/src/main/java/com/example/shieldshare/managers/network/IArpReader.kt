package com.example.shieldshare.managers.network

/**
 * ARP Reader Interface
 * Based on the class diagram specification (IArpReader)
 */
interface IArpReader {
    fun getMacForIp(ip: String): String?
    fun readArpTable(): Map<String, String>
}
