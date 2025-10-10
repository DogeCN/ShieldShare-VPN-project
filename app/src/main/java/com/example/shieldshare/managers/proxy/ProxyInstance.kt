package com.example.shieldshare.managers.proxy

/**
 * Proxy Instance data class
 * Based on the CSV specification
 */
data class ProxyInstance(
    val instanceId: String,
    val config: ProxyConfig,
    val startTime: Long = System.currentTimeMillis()
)
