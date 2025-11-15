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
     * Delete all client stats.
     */
    @Query("DELETE FROM client_stats")
    suspend fun deleteAll()
}

