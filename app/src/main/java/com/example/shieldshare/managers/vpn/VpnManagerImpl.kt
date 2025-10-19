package com.example.shieldshare.managers.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

class VpnManagerImpl(private val context: Context) : VpnManager {

    override suspend fun connectVpn(config: VpnConfig): Result<VpnConnection> {
        return try {
            // priority to open the  third-party VPN application. if not, return to the system VPN settings page.
            val launchIntent = config.thirdPartyPackage
                ?.let { context.packageManager.getLaunchIntentForPackage(it) }
            val intent = (launchIntent ?: Intent(Settings.ACTION_VPN_SETTINGS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Result.success(
                VpnConnection(
                    instanceId = "external_${System.currentTimeMillis()}",
                    config = config
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnectVpn(): Result<Unit> {
        // Unable to directly disconnect third-party VPN: Take the user to the system's VPN settings page.
        return try {
            context.startActivity(
                Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getConnectionStatus(): VpnStatus {
        return if (isVpnActive()) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED
    }

    override fun subscribeToStatusChanges(): Flow<VpnStatus> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        fun emit() {
            trySend(if (isVpnActive()) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED)
        }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emit()
            override fun onLost(network: Network) = emit()
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = emit()
        }

        try {
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            }
        } catch (_: Exception) {
            emit()
            close()
            return@callbackFlow
        }

        emit()
        awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    private fun isVpnActive(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                cm.allNetworks.any { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_VPN
            }
        } catch (_: Exception) {
            false
        }
    }
}
