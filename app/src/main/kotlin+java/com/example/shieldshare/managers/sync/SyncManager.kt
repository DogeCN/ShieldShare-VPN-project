package com.example.shieldshare.managers.sync

interface SyncManager {
    fun enqueueSync()
}

class SyncManagerNoop : SyncManager {
    override fun enqueueSync() {
        // TODO
    }
}
