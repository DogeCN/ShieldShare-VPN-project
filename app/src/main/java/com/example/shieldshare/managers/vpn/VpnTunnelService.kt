package com.example.shieldshare.managers.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.shieldshare.MainActivity
import com.example.shieldshare.R

class VpnTunnelService : VpnService() {

    override fun onCreate() {
        super.onCreate()
        startForeground(1, NotificationCompat.Builder(this, ensureChannel())
            .setContentTitle("ShieldShare VPN")
            .setContentText("VPN service placeholder")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO
        return START_STICKY
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}

// notification
private const val CHANNEL_ID = "vpn"
private fun Service.ensureChannel(): String {
    if (Build.VERSION.SDK_INT >= 26) {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    return CHANNEL_ID
}
