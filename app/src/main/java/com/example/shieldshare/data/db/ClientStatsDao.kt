package com.example.shieldshare.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for client statistics operations.
 * Handles upsert and querying of aggregated client statistics.
 */
@Dao
interface ClientStatsDao {
    
    /**
     * Insert or replace a client stats record.
     * Uses REPLACE strategy to handle upserts.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: ClientStatsEntity)
    
    /**
     * Update an existing client stats record.
     */
    @Update
    suspend fun update(stats: ClientStatsEntity)
    
    /**
     * Get stats for a specific client.
     */
    @Query("SELECT * FROM client_stats WHERE clientIp = :clientIp")
    suspend fun getClientStats(clientIp: String): ClientStatsEntity?
    
    /**
     * Get stats for a specific client as Flow.
     */
    @Query("SELECT * FROM client_stats WHERE clientIp = :clientIp")
    fun getClientStatsFlow(clientIp: String): Flow<ClientStatsEntity?>
    
    /**
     * Get all client stats.
     */
    @Query("SELECT * FROM client_stats ORDER BY lastSeen DESC")
    fun getAllClientStats(): Flow<List<ClientStatsEntity>>
    
    /**
     * Get top clients by total traffic (upload + download).
     */
    @Query("""
        SELECT * FROM client_stats
        ORDER BY (totalBytesUploaded + totalBytesDownloaded) DESC
        LIMIT :limit
    """)
    fun getTopClients(limit: Int = 10): Flow<List<ClientStatsEntity>>
    
    /**
     * Get clients seen within a time range.
     */
    @Query("""
        SELECT * FROM client_stats
        WHERE lastSeen >= :startTime
        AND lastSeen <= :endTime
        ORDER BY lastSeen DESC
    """)
    fun getClientsInRange(
        startTime: Long,
        endTime: Long
    ): Flow<List<ClientStatsEntity>>
    
    /**
     * Get total count of unique clients.
     */
    @Query("SELECT COUNT(*) FROM client_stats")
    suspend fun getClientCount(): Long
    
    /**
     * Delete all client stats.
     */
    @Query("DELETE FROM client_stats")
    suspend fun deleteAll()
    
    /**
     * Delete stats for clients not seen since specified timestamp.
     */
    @Query("DELETE FROM client_stats WHERE lastSeen < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int
}

