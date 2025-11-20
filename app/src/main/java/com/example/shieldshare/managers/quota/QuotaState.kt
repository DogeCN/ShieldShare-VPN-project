package com.example.shieldshare.managers.quota

/**
 * Represents the quota state for a single client
 */
data class QuotaState(
    val clientIp: String,
    val macAddress: String,
    val allocatedQuotaBytes: Long,        // Total quota allocated to this client
    val usedBytes: Long = 0,               // Current usage
    val usageBaselineBytes: Long = 0,      // Baseline for usage offsets (for manual resets)
    val quotaExceededAt: Long? = null,     // Timestamp when quota was exceeded
    val isBlocked: Boolean = false,         // Whether client is currently blocked
    val blockedUntil: Long? = null,        // Timestamp until which client is blocked
    val lastWarningAt: Long? = null,       // Last time warning was sent (80% threshold)
    val resetAt: Long = System.currentTimeMillis() // When quota was last reset
) {
    /**
     * Get remaining quota in bytes
     */
    val remainingBytes: Long
        get() = maxOf(0, allocatedQuotaBytes - usedBytes)
    
    /**
     * Get quota usage percentage (0.0 to 1.0)
     */
    val usagePercentage: Double
        get() = if (allocatedQuotaBytes > 0) {
            minOf(1.0, usedBytes.toDouble() / allocatedQuotaBytes.toDouble())
        } else {
            0.0
        }
    
    /**
     * Check if quota is exceeded
     */
    val isExceeded: Boolean
        get() = usedBytes >= allocatedQuotaBytes && allocatedQuotaBytes > 0
    
    /**
     * Check if warning threshold (80%) is reached
     */
    fun isWarningThresholdReached(threshold: Double = 0.8): Boolean {
        return usagePercentage >= threshold && !isExceeded
    }
    
    /**
     * Check if client is currently blocked (considering time)
     */
    fun isCurrentlyBlocked(): Boolean {
        if (!isBlocked) return false
        val blockedUntil = blockedUntil ?: return false
        return System.currentTimeMillis() < blockedUntil
    }
}


