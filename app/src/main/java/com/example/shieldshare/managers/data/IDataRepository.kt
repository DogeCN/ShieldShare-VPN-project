package com.example.shieldshare.managers.data

import com.example.shieldshare.managers.meter.StatsQuery
import com.example.shieldshare.managers.meter.TrafficStats

/** Persists and retrieves traffic statistics and application data */
interface IDataRepository {
    suspend fun saveTrafficStats(stats: TrafficStats): Result<Unit>
    suspend fun queryStats(query: StatsQuery): Result<List<TrafficStats>>
    suspend fun deleteOldStats(cutoffDate: Long): Result<Unit>
}
