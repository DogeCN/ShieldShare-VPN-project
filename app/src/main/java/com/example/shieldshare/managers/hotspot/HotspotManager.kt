package com.example.shieldshare.managers.hotspot

import kotlinx.coroutines.flow.Flow

/**
 * Hotspot Manager Interface
 * Based on the class diagram specification (IHotspotManager)
 */
interface HotspotManager {
    fun guideUserToEnableHotspot()
    fun detectHotspotState(): HotspotState
    fun getHotspotClients(): List<ConnectedClient>
    fun subscribeToClientChanges(): Flow<List<ConnectedClient>>
}

data class ConnectedClient(
    val macAddress: String,
    val ipAddress: String,
    val hostname: String? = null,
    val connectionTime: Long = System.currentTimeMillis()
)

data class HotspotInfo(
    val ssid: String,
    val password: String,
    val ipAddress: String,
    val isEnabled: Boolean
)

enum class HotspotState {
    DISABLED,
    ENABLING,
    ENABLED,
    DISABLING,
    ERROR
}
