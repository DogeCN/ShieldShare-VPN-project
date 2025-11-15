package com.example.shieldshare.data.repository

import android.util.Log
import com.example.shieldshare.data.db.ClientSessionDao
import com.example.shieldshare.data.db.ClientSessionEntity
import com.example.shieldshare.data.db.ClientStatsDao
import com.example.shieldshare.data.db.ClientStatsEntity
import com.example.shieldshare.data.db.TrafficRecordDao
import com.example.shieldshare.data.db.TrafficRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for traffic data operations.
 * Provides high-level methods for recording and querying traffic data,
 * wrapping the underlying DAOs and handling aggregation logic.
 */
@Singleton
class TrafficRepository @Inject constructor(
    private val trafficRecordDao: TrafficRecordDao,
    private val clientSessionDao: ClientSessionDao,
    private val clientStatsDao: ClientStatsDao
) {
    companion object {
        private const val TAG = "TrafficRepository"
        private const val BATCH_SIZE = 50 // Batch size for inserts
    }

    // Batch buffer for traffic records
    private val trafficRecordBuffer = mutableListOf<TrafficRecordEntity>()
    private var lastBatchTime = System.currentTimeMillis()
    private val BATCH_TIMEOUT_MS = 5_000L // 5 seconds

    // ==================== Traffic Record Operations ====================

    /**
     * Record a single traffic event.
     * This method buffers records and inserts them in batches for better performance.
     */
    suspend fun recordTraffic(
        clientIp: String,
        macAddress: String,
        bytesUploaded: Long,
        bytesDownloaded: Long,
        protocol: String,
        sessionId: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val record = TrafficRecordEntity(
                    clientIp = clientIp,
                    macAddress = macAddress,
                    bytesUploaded = bytesUploaded,
                    bytesDownloaded = bytesDownloaded,
                    timestamp = System.currentTimeMillis(),
                    protocol = protocol,
                    sessionId = sessionId
                )

                val shouldFlush = synchronized(trafficRecordBuffer) {
                    trafficRecordBuffer.add(record)
                    val size = trafficRecordBuffer.size
                    val timeSinceLastBatch = System.currentTimeMillis() - lastBatchTime
                    size >= BATCH_SIZE || timeSinceLastBatch >= BATCH_TIMEOUT_MS
                }

                // Flush outside synchronized block if needed
                if (shouldFlush) {
                    flushTrafficRecords()
                }

                // Update client stats
                updateClientStats(clientIp, macAddress, bytesUploaded, bytesDownloaded, protocol)
            } catch (e: Exception) {
                Log.e(TAG, "Error recording traffic", e)
            }
        }
    }

    /**
     * Insert traffic records in batch.
     * Useful when you already have a collection of records ready.
     */
    suspend fun insertTrafficRecords(records: List<TrafficRecordEntity>) {
        withContext(Dispatchers.IO) {
            try {
                if (records.isNotEmpty()) {
                    trafficRecordDao.insertAll(records)
                    Log.d(TAG, "Inserted ${records.size} traffic records")
                } else {
                    // No-op when records list is empty
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting traffic records", e)
            }
        }
    }

    /**
     * Flush buffered traffic records to database.
     * Called automatically when buffer is full or timeout is reached.
     */
    suspend fun flushTrafficRecords() {
        withContext(Dispatchers.IO) {
            try {
                val recordsToFlush = synchronized(trafficRecordBuffer) {
                    if (trafficRecordBuffer.isNotEmpty()) {
                        val records = trafficRecordBuffer.toList()
                        val count = trafficRecordBuffer.size
                        trafficRecordBuffer.clear()
                        lastBatchTime = System.currentTimeMillis()
                        Pair(records, count)
                    } else {
                        null
                    }
                }

                // Insert outside synchronized block
                recordsToFlush?.let { (records, count) ->
                    trafficRecordDao.insertAll(records)
                    Log.d(TAG, "Flushed $count traffic records to database")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing traffic records", e)
            }
        }
    }

    /**
     * Get traffic records for a specific client within a time range.
     */
    fun getTrafficForClient(
        clientIp: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<TrafficRecordEntity>> {
        return trafficRecordDao.getTrafficForClient(clientIp, startTime, endTime)
    }

    /**
     * Get all traffic records within a time range.
     */
    fun getTrafficInRange(
        startTime: Long,
        endTime: Long,
        limit: Int = 1000
    ): Flow<List<TrafficRecordEntity>> {
        return trafficRecordDao.getTrafficInRange(startTime, endTime, limit)
    }

    /**
     * Get traffic records for a client (all time).
     */
    fun getTrafficForClientAllTime(
        clientIp: String,
        limit: Int = 1000
    ): Flow<List<TrafficRecordEntity>> {
        return trafficRecordDao.getTrafficForClientAllTime(clientIp, limit)
    }

    // ==================== Session Operations ====================

    /**
     * Start a new session.
     */
    suspend fun startSession(
        sessionId: String,
        clientIp: String,
        macAddress: String,
        protocolType: String,
        deviceName: String? = null,
        userAgent: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val session = ClientSessionEntity(
                    sessionId = sessionId,
                    clientIp = clientIp,
                    macAddress = macAddress,
                    protocolType = protocolType,
                    startTime = System.currentTimeMillis(),
                    endTime = null,
                    totalBytesUploaded = 0,
                    totalBytesDownloaded = 0,
                    connectionCount = 0,
                    hostsAccessed = null,
                    userAgent = userAgent,
                    deviceName = deviceName,
                    isActive = true
                )
                clientSessionDao.insert(session)
                Log.d(TAG, "Started session: $sessionId for client $clientIp")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session", e)
            }
        }
    }

    /**
     * End a session and update its statistics.
     */
    suspend fun endSession(
        sessionId: String,
        totalBytesUploaded: Long,
        totalBytesDownloaded: Long,
        connectionCount: Int,
        hostsAccessed: List<String>? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                val session = clientSessionDao.getSession(sessionId)
                if (session != null) {
                    val updatedSession = session.copy(
                        endTime = System.currentTimeMillis(),
                        totalBytesUploaded = totalBytesUploaded,
                        totalBytesDownloaded = totalBytesDownloaded,
                        connectionCount = connectionCount,
                        hostsAccessed = hostsAccessed?.joinToString(","),
                        isActive = false
                    )
                    clientSessionDao.update(updatedSession)
                    Log.d(TAG, "Ended session: $sessionId")
                } else {
                    Log.w(TAG, "Session not found: $sessionId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ending session", e)
            }
        }
    }

    /**
     * Update session traffic during an active session.
     */
    suspend fun updateSessionTraffic(
        sessionId: String,
        bytesUploaded: Long,
        bytesDownloaded: Long
    ) {
        withContext(Dispatchers.IO) {
            try {
                val session = clientSessionDao.getSession(sessionId)
                if (session != null && session.isActive) {
                    val updatedSession = session.copy(
                        totalBytesUploaded = session.totalBytesUploaded + bytesUploaded,
                        totalBytesDownloaded = session.totalBytesDownloaded + bytesDownloaded,
                        connectionCount = session.connectionCount + 1
                    )
                    clientSessionDao.update(updatedSession)
                } else {
                    // Session not found or not active - no-op
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating session traffic", e)
            }
        }
    }

    /**
     * Get sessions for a specific client.
     */
    fun getSessionsForClient(
        clientIp: String,
        limit: Int = 100
    ): Flow<List<ClientSessionEntity>> {
        return clientSessionDao.getSessionsForClient(clientIp, limit)
    }

    /**
     * Get all active sessions.
     */
    fun getActiveSessions(): Flow<List<ClientSessionEntity>> {
        return clientSessionDao.getActiveSessions()
    }

    /**
     * Get sessions within a time range.
     */
    fun getSessionsInRange(
        startTime: Long,
        endTime: Long,
        limit: Int = 1000
    ): Flow<List<ClientSessionEntity>> {
        return clientSessionDao.getSessionsInRange(startTime, endTime, limit)
    }

    // ==================== Client Stats Operations ====================

    /**
     * Update or insert client statistics.
     * Called automatically when traffic is recorded.
     */
    private suspend fun updateClientStats(
        clientIp: String,
        macAddress: String,
        bytesUploaded: Long,
        bytesDownloaded: Long,
        protocol: String
    ) {
        try {
            val existingStats = clientStatsDao.getClientStats(clientIp)
            val currentTime = System.currentTimeMillis()

            val updatedStats = if (existingStats != null) {
                existingStats.copy(
                    macAddress = macAddress, // Update MAC in case it changed
                    lastSeen = currentTime,
                    totalBytesUploaded = existingStats.totalBytesUploaded + bytesUploaded,
                    totalBytesDownloaded = existingStats.totalBytesDownloaded + bytesDownloaded,
                    totalConnections = existingStats.totalConnections + 1,
                    lastProtocol = protocol,
                    lastUpdated = currentTime
                )
            } else {
                ClientStatsEntity(
                    clientIp = clientIp,
                    macAddress = macAddress,
                    deviceAlias = null,
                    firstSeen = currentTime,
                    lastSeen = currentTime,
                    totalBytesUploaded = bytesUploaded,
                    totalBytesDownloaded = bytesDownloaded,
                    totalConnections = 1,
                    totalSessions = 0, // Will be updated when session ends
                    lastProtocol = protocol,
                    lastUpdated = currentTime
                )
            }

            clientStatsDao.upsert(updatedStats)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating client stats", e)
        }
    }

    /**
     * Get stats for a specific client.
     */
    suspend fun getClientStats(clientIp: String): ClientStatsEntity? {
        return withContext(Dispatchers.IO) {
            try {
                clientStatsDao.getClientStats(clientIp)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting client stats", e)
                null
            }
        }
    }

    /**
     * Get stats for a specific client as Flow.
     */
    fun getClientStatsFlow(clientIp: String): Flow<ClientStatsEntity?> {
        return clientStatsDao.getClientStatsFlow(clientIp)
    }

    /**
     * Get all client stats.
     */
    fun getAllClientStats(): Flow<List<ClientStatsEntity>> {
        return clientStatsDao.getAllClientStats()
    }

    /**
     * Get top clients by total traffic.
     */
    fun getTopClients(limit: Int = 10): Flow<List<ClientStatsEntity>> {
        return clientStatsDao.getTopClients(limit)
    }

    /**
     * Get clients seen within a time range.
     */
    fun getClientsInRange(
        startTime: Long,
        endTime: Long
    ): Flow<List<ClientStatsEntity>> {
        return clientStatsDao.getClientsInRange(startTime, endTime)
    }

    /**
     * Update device alias for a client.
     */
    suspend fun updateDeviceAlias(clientIp: String, alias: String?) {
        withContext(Dispatchers.IO) {
            try {
                val stats = clientStatsDao.getClientStats(clientIp)
                if (stats != null) {
                    val updatedStats = stats.copy(
                        deviceAlias = alias,
                        lastUpdated = System.currentTimeMillis()
                    )
                    clientStatsDao.update(updatedStats)
                } else {
                    // Stats not found - no-op
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating device alias", e)
            }
        }
    }

    /**
     * Increment session count for a client.
     * Called when a session ends.
     */
    suspend fun incrementSessionCount(clientIp: String) {
        withContext(Dispatchers.IO) {
            try {
                val stats = clientStatsDao.getClientStats(clientIp)
                if (stats != null) {
                    val updatedStats = stats.copy(
                        totalSessions = stats.totalSessions + 1,
                        lastUpdated = System.currentTimeMillis()
                    )
                    clientStatsDao.update(updatedStats)
                } else {
                    // Stats not found - no-op
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error incrementing session count", e)
            }
        }
    }

    // ==================== Database Management ====================

    /**
     * Get database statistics.
     */
    suspend fun getDatabaseStats(): DatabaseStats {
        return withContext(Dispatchers.IO) {
            try {
                val recordCount = trafficRecordDao.getRecordCount()
                val sessionCount = clientSessionDao.getSessionCount()
                val clientCount = clientStatsDao.getClientCount()

                DatabaseStats(
                    totalRecords = recordCount,
                    totalSessions = sessionCount,
                    uniqueClients = clientCount
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting database stats", e)
                DatabaseStats(0, 0, 0)
            }
        }
    }

    /**
     * Clear all traffic data from the database.
     */
    suspend fun clearAllTrafficData() {
        withContext(Dispatchers.IO) {
            try {
                // Clear buffer first
                synchronized(trafficRecordBuffer) {
                    trafficRecordBuffer.clear()
                }

                // Clear all tables
                trafficRecordDao.deleteAll()
                clientSessionDao.deleteAll()
                clientStatsDao.deleteAll()

                Log.i(TAG, "All traffic data cleared from database")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing traffic data", e)
                throw e
            }
        }
    }

    /**
     * Delete old records before a specified timestamp.
     * Useful for implementing data retention policies.
     */
    suspend fun deleteOldRecords(beforeTimestamp: Long): Int {
        return withContext(Dispatchers.IO) {
            try {
                val recordsDeleted = trafficRecordDao.deleteOlderThan(beforeTimestamp)
                val sessionsDeleted = clientSessionDao.deleteOlderThan(beforeTimestamp)
                val statsDeleted = clientStatsDao.deleteOlderThan(beforeTimestamp)

                Log.i(TAG, "Deleted old records: $recordsDeleted records, $sessionsDeleted sessions, $statsDeleted stats")
                recordsDeleted + sessionsDeleted + statsDeleted
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting old records", e)
                0
            }
        }
    }

    /**
     * Get count of traffic records for a specific client.
     */
    suspend fun getRecordCountForClient(clientIp: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                trafficRecordDao.getRecordCountForClient(clientIp)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting record count for client", e)
                0L
            }
        }
    }

    /**
     * Get count of sessions for a specific client.
     */
    suspend fun getSessionCountForClient(clientIp: String): Long {
        return withContext(Dispatchers.IO) {
            try {
                clientSessionDao.getSessionCountForClient(clientIp)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting session count for client", e)
                0L
            }
        }
    }
}

/**
 * Data class for database statistics.
 */
data class DatabaseStats(
    val totalRecords: Long,
    val totalSessions: Long,
    val uniqueClients: Long
)

