package com.example.shieldshare.managers.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class VpnPermissionActivity : ComponentActivity() {

    @Inject lateinit var vpnManager: VpnManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = intent.getParcelableExtra<VpnConfig>(EXTRA_CONFIG) ?: VpnConfig()
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            startActivityForResult(prepare, REQ_PREPARE)
        } else {
            startVpn(cfg)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val cfg = intent.getParcelableExtra<VpnConfig>(EXTRA_CONFIG) ?: VpnConfig()
        if (requestCode == REQ_PREPARE && resultCode == Activity.RESULT_OK) {
            startVpn(cfg)
        }
        finish()
    }

    private fun startVpn(cfg: VpnConfig) {
        val i = Intent(this, VpnTunnelService::class.java)
            .putExtra(EXTRA_CONFIG, cfg)
        startService(i)
        if (vpnManager is VpnManagerImpl) (vpnManager as VpnManagerImpl).markRunning(true)
    }

    companion object {
        const val EXTRA_CONFIG = "vpn_config"
        private const val REQ_PREPARE = 1001
    }
}
