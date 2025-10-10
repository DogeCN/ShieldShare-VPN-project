package com.example.shieldshare.managers.vpn

import android.content.Context
import android.content.Intent

class VpnManagerImpl(private val context: Context) : VpnManager {
    @Volatile private var running = false

    override suspend fun connectVpn(config: VpnConfig): Result<VpnConnection> {
        return try {
            val i = Intent(context, VpnPermissionActivity::class.java)
                .putExtra(VpnPermissionActivity.EXTRA_CONFIG, config)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            running = true
            
            val connection = VpnConnection(
                connectionId = "vpn_connection_${System.currentTimeMillis()}",
                status = VpnStatus.CONNECTED,
                serverAddress = config.serverAddress
            )
            Result.success(connection)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnectVpn(): Result<Unit> {
        return try {
            context.stopService(Intent(context, VpnTunnelService::class.java))
            running = false
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getConnectionStatus(): VpnStatus {
        return if (running) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED
    }

    override fun subscribeToStatusChanges(): kotlinx.coroutines.flow.Flow<VpnStatus> {
        return kotlinx.coroutines.flow.flowOf(getConnectionStatus())
    }

    internal fun markRunning(value: Boolean) { running = value }
}
