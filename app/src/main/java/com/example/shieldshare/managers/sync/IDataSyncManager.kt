package com.example.shieldshare.managers.sync

/** Manages data synchronization operations and auto-sync functionality */
interface IDataSyncManager {
    fun enqueueSyncOperation(operation: SyncOperation)
    suspend fun syncNow(): Result<SyncStatus>
    fun enableAutoSync(enabled: Boolean)
    fun getLastSyncTimestamp(): Long?
}

data class SyncOperation(
        val operationId: String,
        val operationType: SyncOperationType,
        val data: Any,
        val timestamp: Long = System.currentTimeMillis()
)

enum class SyncOperationType {
    INSERT,
    UPDATE,
    DELETE
}

enum class SyncStatus {
    SUCCESS,
    FAILED,
    PARTIAL,
    PENDING
}
