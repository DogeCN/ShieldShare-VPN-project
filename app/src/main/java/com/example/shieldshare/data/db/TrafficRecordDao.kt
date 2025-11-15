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
     * Insert a single traffic record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TrafficRecordEntity): Long
    
    /**
     * Insert multiple traffic records in a single transaction.
     * Used for batch inserts for better performance.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TrafficRecordEntity>)
    
    /**
     * Get all traffic records for a specific client within a time range.
     */
    @Query("""
        SELECT * FROM traffic_records
        WHERE clientIp = :clientIp
        AND timestamp >= :startTime
        AND timestamp <= :endTime
        ORDER BY timestamp DESC
    """)
    fun getTrafficForClient(
        clientIp: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<TrafficRecordEntity>>
    
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
     * Get traffic records for a specific client (all time).
     */
    @Query("""
        SELECT * FROM traffic_records
        WHERE clientIp = :clientIp
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun getTrafficForClientAllTime(
        clientIp: String,
        limit: Int = 1000
    ): Flow<List<TrafficRecordEntity>>
    
    /**
     * Get total count of traffic records.
     */
    @Query("SELECT COUNT(*) FROM traffic_records")
    suspend fun getRecordCount(): Long
    
    /**
     * Get total count of traffic records for a specific client.
     */
    @Query("SELECT COUNT(*) FROM traffic_records WHERE clientIp = :clientIp")
    suspend fun getRecordCountForClient(clientIp: String): Long
    
    /**
     * Delete all traffic records.
     */
    @Query("DELETE FROM traffic_records")
    suspend fun deleteAll()
    
    /**
     * Delete traffic records older than specified timestamp.
     */
    @Query("DELETE FROM traffic_records WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int
}

