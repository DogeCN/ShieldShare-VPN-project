package com.example.shieldshare.managers.meter

/** Records and tracks network traffic statistics for connected clients */
interface TrafficMeter { 
    fun recordTraffic(clientIp: String, bytesUp: Long, bytesDown: Long)
    fun getCurrentStats(): List<ClientTrafficStats>
    fun getHistoricalStats(timeRange: TimeRange): List<ClientTrafficStats>
    fun mapIpToMac(): Map<String, String>
    /** Cumulative totals for throughput sampling (bytes up/down). */
    fun getTotals(): TrafficTotals = TrafficTotals(0L, 0L)
    /**
     * Reset/clear current session statistics.
     * Called when a new service session starts to ensure clean state.
     */
    fun resetCurrentSessionStats()
}

data class TrafficTotals(
    val totalBytesUp: Long,
    val totalBytesDown: Long
)

class TrafficMeterNoop : TrafficMeter {
    override fun recordTraffic(clientIp: String, bytesUp: Long, bytesDown: Long) {}
    override fun getCurrentStats(): List<ClientTrafficStats> = emptyList()
    override fun getHistoricalStats(timeRange: TimeRange): List<ClientTrafficStats> = emptyList()
    override fun mapIpToMac(): Map<String, String> = emptyMap()
    override fun getTotals(): TrafficTotals = TrafficTotals(0L, 0L)
    override fun resetCurrentSessionStats() {}
}
