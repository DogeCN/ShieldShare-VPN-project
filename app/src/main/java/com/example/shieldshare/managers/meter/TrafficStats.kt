package com.example.shieldshare.managers.meter

/**
 * Traffic Statistics data models
 * Based on the class diagram specification
 */
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
    val lastSeen: Long = System.currentTimeMillis()
)

data class TimeRange(
    val startTime: Long,
    val endTime: Long
)

data class StatsQuery(
    val clientId: String? = null,
    val timeRange: TimeRange? = null,
    val limit: Int = 100
)
