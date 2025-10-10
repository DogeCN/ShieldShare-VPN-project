package com.example.shieldshare.managers.vpn

import kotlinx.coroutines.flow.Flow

/**
 * VPN Manager Interface
 * Based on the class diagram specification (IVpnManager)
 */
interface VpnManager {
    suspend fun connectVpn(config: VpnConfig): Result<VpnConnection>
    suspend fun disconnectVpn(): Result<Unit>
    fun getConnectionStatus(): VpnStatus
    fun subscribeToStatusChanges(): Flow<VpnStatus>
}
