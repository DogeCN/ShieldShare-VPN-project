package com.example.shieldshare.managers.hotspot

import kotlinx.coroutines.flow.Flow

/** Manages mobile hotspot state, client detection, and connection monitoring */
interface HotspotManager {
    fun guideUserToEnableHotspot()
    fun detectHotspotState(): HotspotState
    fun getHotspotClients(): List<ConnectedClient>
    fun subscribeToClientChanges(): Flow<List<ConnectedClient>>
    fun getHotspotIpAddress(): String?
    /**
     * Check if device is connected to a WiFi Access Point (not hotspot).
     * Returns true if WiFi is connected but hotspot is not enabled.
     */
    fun isConnectedToWifiAp(): Boolean
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
