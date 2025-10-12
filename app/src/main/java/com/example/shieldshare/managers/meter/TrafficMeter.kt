package com.example.shieldshare.managers.meter

/** Records and tracks network traffic statistics for connected clients */
interface TrafficMeter {
    fun recordTraffic(clientIp: String, bytesUp: Long, bytesDown: Long)
    fun getCurrentStats(): List<ClientTrafficStats>
    fun getHistoricalStats(timeRange: TimeRange): List<ClientTrafficStats>
    fun mapIpToMac(): Map<String, String>
}

class TrafficMeterNoop : TrafficMeter {
    override fun recordTraffic(clientIp: String, bytesUp: Long, bytesDown: Long) {}
    override fun getCurrentStats(): List<ClientTrafficStats> = emptyList()
    override fun getHistoricalStats(timeRange: TimeRange): List<ClientTrafficStats> = emptyList()
    override fun mapIpToMac(): Map<String, String> = emptyMap()
}
