package com.example.shieldshare.managers.data

import com.example.shieldshare.managers.meter.TrafficStats
import com.example.shieldshare.managers.meter.StatsQuery

/**
 * Data Repository Interface
 * Based on the class diagram specification (IDataRepository)
 */
interface IDataRepository {
    suspend fun saveTrafficStats(stats: TrafficStats): Result<Unit>
    suspend fun queryStats(query: StatsQuery): Result<List<TrafficStats>>
    suspend fun deleteOldStats(cutoffDate: Long): Result<Unit>
}
