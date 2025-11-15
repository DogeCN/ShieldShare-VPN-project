package com.example.shieldshare.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing connection sessions.
 * Tracks when connections start and end, along with session-level statistics.
 */
@Entity(
    tableName = "client_sessions",
    indices = [
        Index(value = ["clientIp"]),
        Index(value = ["startTime"])
    ]
)
data class ClientSessionEntity(
    @PrimaryKey
    val sessionId: String, // UUID
    
    val clientIp: String,
    
    val macAddress: String,
    
    val protocolType: String, // "HTTP", "HTTPS", "SOCKS5"
    
    val startTime: Long, // Unix timestamp in milliseconds
    
    val endTime: Long? = null, // null if session is still active
    
    val totalBytesUploaded: Long = 0,
    
    val totalBytesDownloaded: Long = 0,
    
    val connectionCount: Int = 0,
    
    val hostsAccessed: String? = null, // Comma-separated list
    
    val userAgent: String? = null,
    
    val deviceName: String? = null,
    
    val isActive: Boolean = true
)

