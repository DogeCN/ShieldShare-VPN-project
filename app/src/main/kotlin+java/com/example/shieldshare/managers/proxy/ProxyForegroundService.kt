package com.example.shieldshare.managers.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.shieldshare.R

class ProxyForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val id = "proxy"
            if (mgr.getNotificationChannel(id) == null) {
                mgr.createNotificationChannel(NotificationChannel(id,"Proxy", NotificationManager.IMPORTANCE_LOW))
            }
            id
        } else "proxy"
        startForeground(2, NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ShieldShare Proxy")
            .setContentText("Proxy service placeholder")
            .build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
