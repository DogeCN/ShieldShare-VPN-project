package com.example.shieldshare.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for client session operations.
 * Handles insertion, updates, and querying of connection sessions.
 */
@Dao
interface ClientSessionDao {
    
    /**
     * Insert a new session.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ClientSessionEntity)
    
    /**
     * Update an existing session.
     */
    @Update
    suspend fun update(session: ClientSessionEntity)
    
    /**
     * Get a session by session ID.
     */
    @Query("SELECT * FROM client_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): ClientSessionEntity?
    
    /**
     * Get all sessions for a specific client.
     */
    @Query("""
        SELECT * FROM client_sessions
        WHERE clientIp = :clientIp
        ORDER BY startTime DESC
        LIMIT :limit
    """)
    fun getSessionsForClient(
        clientIp: String,
        limit: Int = 100
    ): Flow<List<ClientSessionEntity>>
    
    /**
     * Get all active sessions.
     */
    @Query("""
        SELECT * FROM client_sessions
        WHERE isActive = 1
        ORDER BY startTime DESC
    """)
    fun getActiveSessions(): Flow<List<ClientSessionEntity>>
    
    /**
     * Get all sessions within a time range.
     */
    @Query("""
        SELECT * FROM client_sessions
        WHERE startTime >= :startTime
        AND startTime <= :endTime
        ORDER BY startTime DESC
        LIMIT :limit
    """)
    fun getSessionsInRange(
        startTime: Long,
        endTime: Long,
        limit: Int = 1000
    ): Flow<List<ClientSessionEntity>>
    
    /**
     * Get total count of sessions.
     */
    @Query("SELECT COUNT(*) FROM client_sessions")
    suspend fun getSessionCount(): Long
    
    /**
     * Get total count of sessions for a specific client.
     */
    @Query("SELECT COUNT(*) FROM client_sessions WHERE clientIp = :clientIp")
    suspend fun getSessionCountForClient(clientIp: String): Long
    
    /**
     * Delete all sessions.
     */
    @Query("DELETE FROM client_sessions")
    suspend fun deleteAll()
    
    /**
     * Delete sessions older than specified timestamp.
     */
    @Query("DELETE FROM client_sessions WHERE startTime < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int
}

