package com.example.shieldshare.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing service sessions.
 * A service session represents the period when both proxy server and VPN are active.
 */
@Entity(
    tableName = "service_sessions",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["endTime"])
    ]
)
data class ServiceSessionEntity(
    @PrimaryKey
    val sessionId: String, // UUID
    
    val startTime: Long, // Unix timestamp in milliseconds
    
    val endTime: Long? = null, // null if session is still active
    
    val totalBytesUploaded: Long = 0, // Total bytes uploaded during this service session
    
    val totalBytesDownloaded: Long = 0, // Total bytes downloaded during this service session
    
    val uniqueClients: Int = 0, // Number of unique clients that connected during this session
    
    val isActive: Boolean = true // true if session is still ongoing
)

