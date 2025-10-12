package com.example.shieldshare.managers.network

/** Monitors network quality, latency, and bandwidth statistics */
interface INetworkMonitor {
    fun getConnectionQuality(): NetworkQuality
    fun getLatency(): Long
    fun getBandwidth(): BandwidthStats
    fun subscribeToQualityChanges(): kotlinx.coroutines.flow.Flow<NetworkQuality>
}

data class NetworkQuality(
        val quality: QualityLevel,
        val latency: Long,
        val bandwidth: Long,
        val packetLoss: Double
)

data class BandwidthStats(
        val downloadSpeed: Long,
        val uploadSpeed: Long,
        val timestamp: Long = System.currentTimeMillis(),
        val measurementDuration: Long
)

enum class QualityLevel {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNKNOWN
}
