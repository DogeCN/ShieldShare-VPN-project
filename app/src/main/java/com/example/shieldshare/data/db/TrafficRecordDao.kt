package com.example.shieldshare.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for traffic record operations.
 * Handles insertion and querying of individual traffic records.
 */
@Dao
interface TrafficRecordDao {
    
    /**
     * Insert multiple traffic records in a single transaction.
     * Used for batch inserts for better performance.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TrafficRecordEntity>)
    
    /**
     * Get all traffic records within a time range.
     */
    @Query("""
        SELECT * FROM traffic_records
        WHERE timestamp >= :startTime
        AND timestamp <= :endTime
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getTrafficInRange(
        startTime: Long,
        endTime: Long,
        limit: Int = 1000
    ): Flow<List<TrafficRecordEntity>>
    
    /**
     * Delete all traffic records.
     */
    @Query("DELETE FROM traffic_records")
    suspend fun deleteAll()
}

