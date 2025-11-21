package com.example.shieldshare.managers.consumption

/**
 * Result of abuse detection check
 */
data class AbuseCheckResult(
    val isAbusing: Boolean,
    val clientAverage: Long,
    val globalAverage: Long,
    val threshold: Long,
    val abuseRatio: Float // How many times the client's average is compared to global average
)

