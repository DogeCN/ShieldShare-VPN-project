package com.example.shieldshare.managers.proxy

/** Represents a running proxy server instance with configuration and metadata */
data class ProxyInstance(
        val instanceId: String,
        val config: ProxyConfig,
        val startTime: Long = System.currentTimeMillis()
)
