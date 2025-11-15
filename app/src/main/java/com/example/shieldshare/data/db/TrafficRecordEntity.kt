package com.example.shieldshare.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for storing individual traffic records.
 * Each record represents a single traffic event from recordTraffic() calls.
 */
@Entity(
    tableName = "traffic_records",
    indices = [
        Index(value = ["clientIp"]),
        Index(value = ["timestamp"])
    ]
)
data class TrafficRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val clientIp: String,
    
    val macAddress: String,
    
    val bytesUploaded: Long,
    
    val bytesDownloaded: Long,
    
    val timestamp: Long,
    
    val protocol: String, // "HTTP", "HTTPS", "SOCKS5"
    
    val sessionId: String? = null
)

