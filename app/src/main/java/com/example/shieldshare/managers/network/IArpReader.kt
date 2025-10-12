package com.example.shieldshare.managers.network

/** Reads ARP table to map IP addresses to MAC addresses for client identification */
interface IArpReader {
    fun getMacForIp(ip: String): String?
    fun readArpTable(): Map<String, String>
}
