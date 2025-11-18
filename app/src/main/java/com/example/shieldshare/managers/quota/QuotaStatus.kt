package com.example.shieldshare.managers.quota

/**
 * Result of a quota check operation
 */
sealed class QuotaStatus {
    /**
     * Quota is OK, proceed normally
     */
    object OK : QuotaStatus()
    
    /**
     * Quota is approaching limit (warning threshold reached)
     */
    data class Warning(
        val usagePercentage: Double,
        val remainingBytes: Long
    ) : QuotaStatus()
    
    /**
     * Quota is exceeded
     */
    data class Exceeded(
        val usedBytes: Long,
        val allocatedBytes: Long,
        val allowMinimal: Boolean = false  // Whether to allow minimal traffic
    ) : QuotaStatus()
    
    /**
     * Client is blocked (quota exceeded and blocking period active)
     */
    data class Blocked(
        val blockedUntil: Long,
        val reason: String = "Quota exceeded"
    ) : QuotaStatus()
}


