package com.example.shieldshare.managers.quota

/** Manages data usage quotas and limits for connected clients */
interface IQuotaManager {
    fun checkQuota(clientId: String, currentUsage: Long): QuotaStatus
    suspend fun updateQuota(clientId: String, limits: QuotaLimits): Result<Unit>
    fun getQuota(clientId: String): QuotaLimits?
}

data class QuotaLimits(val softLimit: Long, val hardLimit: Long, val resetPeriod: ResetPeriod)

enum class QuotaStatus {
    OK,
    WARNING,
    EXCEEDED
}

enum class ResetPeriod {
    DAILY,
    WEEKLY,
    MONTHLY
}
