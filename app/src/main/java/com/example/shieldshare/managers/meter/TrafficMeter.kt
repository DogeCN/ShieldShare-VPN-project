package com.example.shieldshare.managers.meter

import kotlinx.coroutines.flow.Flow

/**
 * Traffic Meter Interface
 * Based on the class diagram specification (ITrafficMeter)
 */
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
