package com.example.shieldshare.managers.filter

/**
 * Result of a traffic filter check
 */
sealed class FilterResult {
    /**
     * Connection is allowed to proceed
     */
    object ALLOW : FilterResult()
    
    /**
     * Connection should be blocked
     * @param reason Human-readable reason for blocking
     */
    data class BLOCK(val reason: String = "Connection blocked by host policy") : FilterResult()
}

