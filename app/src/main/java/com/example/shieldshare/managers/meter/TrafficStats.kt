package com.example.shieldshare.managers.meter

import java.util.Date

/** Data models for traffic statistics, client stats, and query parameters */
data class TrafficStats(
        val statId: Long = 0,
        val clientId: String,
        val sessionId: String,
        val timestamp: Long = System.currentTimeMillis(),
        val bytesUploaded: Long = 0,
        val bytesDownloaded: Long = 0,
        val connectionCount: Int = 1
)

data class ClientTrafficStats(
        val clientId: String,
        val macAddress: String,
        val ipAddress: String,
        val deviceAlias: String? = null,
        val totalBytesUp: Long = 0,
        val totalBytesDown: Long = 0,
        val currentRateUp: Double = 0.0,
        val currentRateDown: Double = 0.0,
        val lastSeen: Long = System.currentTimeMillis(),
        val connectionCount: Int = 0,
        val activeConnections: Int = 0,
        val firstSeen: Date = Date(),
        val bytesUpload: Long = totalBytesUp,
        val bytesDownload: Long = totalBytesDown
)

data class TimeRange(val startTime: Long, val endTime: Long)

data class StatsQuery(
        val clientId: String? = null,
        val timeRange: TimeRange? = null,
        val limit: Int = 100
)

/**
 * STAGE 2: Additional data classes for comprehensive traffic monitoring
 */
data class TrafficSession(
    val sessionId: String,
    val clientIp: String,
    val startTime: Date,
    var endTime: Date? = null,
    val protocolType: String, // "HTTP", "SOCKS5", etc.
    var bytesUploaded: Long = 0,
    var bytesDownloaded: Long = 0,
    var connectionCount: Int = 0,
    val hostsAccessed: MutableSet<String> = mutableSetOf(),
    var userAgent: String? = null,
    var deviceName: String? = null,
    var macAddress: String? = null,
    var isActive: Boolean = true
)

data class NetworkEvent(
    val eventId: String,
    val timestamp: Date,
    val clientIp: String,
    val eventType: String, // "CONNECTION_START", "CONNECTION_END", "DATA_TRANSFER", "HOST_ACCESS"
    val details: String,
    val bytesTransferred: Long = 0,
    val hostAccessed: String? = null,
    val sessionId: String? = null
)
