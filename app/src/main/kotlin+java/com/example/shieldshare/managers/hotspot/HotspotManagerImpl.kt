package com.example.shieldshare.managers.hotspot

import android.content.Context
import android.net.TetheringManager
import android.os.Build

class HotspotManagerImpl(private val context: Context) : HotspotManager {
    override fun startTethering() {
        // TODO
        if (Build.VERSION.SDK_INT >= 29) {
            val tm = context.getSystemService(TetheringManager::class.java)
            tm?.startTethering(TetheringManager.TETHERING_WIFI, true, { }, null)
        }
    }
    override fun stopTethering() {
        if (Build.VERSION.SDK_INT >= 29) {
            val tm = context.getSystemService(TetheringManager::class.java)
            tm?.stopTethering(TetheringManager.TETHERING_WIFI)
        }
    }
}
