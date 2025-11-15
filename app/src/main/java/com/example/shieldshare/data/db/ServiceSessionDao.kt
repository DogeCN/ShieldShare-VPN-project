package com.example.shieldshare.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for service session operations.
 */
@Dao
interface ServiceSessionDao {
    
    /**
     * Insert a new service session.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ServiceSessionEntity)
    
    /**
     * Update an existing service session.
     */
    @Update
    suspend fun update(session: ServiceSessionEntity)
    
    /**
     * Get a service session by ID.
     */
    @Query("SELECT * FROM service_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): ServiceSessionEntity?
    
    /**
     * Get all service sessions, ordered by start time (newest first).
     */
    @Query("SELECT * FROM service_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ServiceSessionEntity>>
    
    /**
     * Get active service session (if any).
     */
    @Query("SELECT * FROM service_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): ServiceSessionEntity?
    
    /**
     * Delete all service sessions.
     */
    @Query("DELETE FROM service_sessions")
    suspend fun deleteAll()
}

