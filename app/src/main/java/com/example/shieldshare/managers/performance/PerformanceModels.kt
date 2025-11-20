package com.example.shieldshare.managers.performance

/**
 * Represents a single performance sample captured during a ShieldShare session.
 */
data class PerformanceSample(
    val timestamp: Long,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val cpuPercent: Double,
    val activeConnections: Int,
    val totalUploadBytes: Long,
    val totalDownloadBytes: Long,
    val uploadRateBps: Double,
    val downloadRateBps: Double
)

/**
 * Aggregated session summary derived from captured samples.
 */
data class PerformanceSummary(
    val sessionStartTime: Long,
    val sessionStartBattery: Int,
    val latestSample: PerformanceSample,
    val averageCpuPercent: Double,
    val peakCpuPercent: Double,
    val estimatedBatteryDropPerHour: Double,
    val sampleCount: Int
)

