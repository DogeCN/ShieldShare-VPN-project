package com.example.shieldshare.managers.quota

/**
 * Configuration for quota management system
 */
data class QuotaConfig(
    val totalBandwidthBytes: Long = 0,         // Total available bandwidth (0 = unlimited)
    val quotaResetIntervalMs: Long = 86400000,  // Reset interval in milliseconds (24 hours default)
    val blockDurationMs: Long = 3600000,        // Block duration after quota exceeded (1 hour default)
    val warningThreshold: Double = 0.8,         // Warn at 80% of quota
    val throttleSpeedBps: Long = 1024,         // Throttle speed when exceeded (1 KB/s)
    val allowMinimalTraffic: Boolean = true,    // Allow minimal traffic after quota exceeded
    val enabled: Boolean = false                // Whether quota system is enabled
) {
    companion object {
        /**
         * Default configuration (quota disabled)
         */
        fun default(): QuotaConfig = QuotaConfig()
        
        /**
         * Create config from preferences values
         */
        fun fromPreferences(
            enabled: Boolean,
            totalBandwidthMb: Long,
            resetIntervalHours: Int,
            blockDurationHours: Int,
            warningThreshold: Double,
            throttleSpeedKbps: Int
        ): QuotaConfig {
            return QuotaConfig(
                enabled = enabled,
                totalBandwidthBytes = totalBandwidthMb * 1024 * 1024,
                quotaResetIntervalMs = resetIntervalHours * 3600 * 1000L,
                blockDurationMs = blockDurationHours * 3600 * 1000L,
                warningThreshold = warningThreshold,
                throttleSpeedBps = throttleSpeedKbps * 1024L
            )
        }
    }
}


