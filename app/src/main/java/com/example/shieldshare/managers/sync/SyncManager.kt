package com.example.shieldshare.managers.sync

interface SyncManager {
    fun enqueueSync()
}

class SyncManagerNoop : SyncManager {
    override fun enqueueSync() {
        // No-op implementation for basic functionality
    }
}
