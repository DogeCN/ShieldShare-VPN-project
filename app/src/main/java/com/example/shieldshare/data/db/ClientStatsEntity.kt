package com.example.shieldshare.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing aggregated client statistics.
 * Pre-aggregated stats per client for fast queries without aggregating all traffic records.
 */
@Entity(
    tableName = "client_stats",
    indices = [
        Index(value = ["lastSeen"])
    ]
)
data class ClientStatsEntity(
    @PrimaryKey
    val clientIp: String,
    
    val macAddress: String,
    
    val deviceAlias: String? = null, // User-assigned device name/alias
    
    val firstSeen: Long, // Unix timestamp in milliseconds
    
    val lastSeen: Long, // Unix timestamp in milliseconds
    
    val totalBytesUploaded: Long = 0,
    
    val totalBytesDownloaded: Long = 0,
    
    val totalConnections: Int = 0,
    
    val totalSessions: Int = 0,
    
    val lastProtocol: String? = null, // "HTTP", "HTTPS", "SOCKS5"
    
    val lastUpdated: Long = System.currentTimeMillis() // Last time this record was updated
)

