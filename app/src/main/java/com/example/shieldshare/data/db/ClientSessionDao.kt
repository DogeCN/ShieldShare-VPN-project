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
     * Delete all sessions.
     */
    @Query("DELETE FROM client_sessions")
    suspend fun deleteAll()
}

