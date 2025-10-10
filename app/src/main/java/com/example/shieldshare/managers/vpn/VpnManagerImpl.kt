package com.example.shieldshare.managers.vpn

import android.content.Context
import android.content.Intent

class VpnManagerImpl(private val context: Context) : VpnManager {
    @Volatile private var running = false

    override fun start(config: VpnConfig) {
        val i = Intent(context, VpnPermissionActivity::class.java)
            .putExtra(VpnPermissionActivity.EXTRA_CONFIG, config)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)  //
    }

    override fun stop() {
        context.stopService(Intent(context, VpnTunnelService::class.java))
        running = false
    }

    override val isRunning: Boolean get() = running

    internal fun markRunning(value: Boolean) { running = value }
}
