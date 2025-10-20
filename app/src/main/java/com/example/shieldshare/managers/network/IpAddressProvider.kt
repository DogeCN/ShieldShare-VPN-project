package com.example.shieldshare.managers.network

interface IpAddressProvider {
    // return the IP
    suspend fun getPublicIp(): Result<String>
}