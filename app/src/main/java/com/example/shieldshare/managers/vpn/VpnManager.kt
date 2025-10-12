package com.example.shieldshare.managers.vpn

import kotlinx.coroutines.flow.Flow

/** Manages VPN connections, status monitoring, and connection lifecycle */
interface VpnManager {
    suspend fun connectVpn(config: VpnConfig): Result<VpnConnection>
    suspend fun disconnectVpn(): Result<Unit>
    fun getConnectionStatus(): VpnStatus
    fun subscribeToStatusChanges(): Flow<VpnStatus>
}
