package com.example.shieldshare.managers.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
// WIFI_AP_STATE constants are not available in newer Android versions
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress
import java.net.NetworkInterface

class HotspotManagerImpl(private val context: Context) : HotspotManager {
    companion object {
        private const val TAG = "HotspotManagerImpl"
        private const val DEFAULT_SSID = "ShieldShare"
        private const val DEFAULT_PASSWORD = "shieldshare123"
        private const val HOTSPOT_IP = "192.168.43.1"
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun startTethering() {
        try {
            Log.i(TAG, "Starting hotspot tethering...")
            
            // Note: On Android 10+ (API 29+), apps cannot directly enable/disable hotspot
            // We can only check status and guide users to enable it manually
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Log.i(TAG, "Android 10+ detected - hotspot must be enabled manually by user")
                // TODO: Show user guidance dialog
                return
            }

            // For older Android versions, try to enable hotspot programmatically
            val wifiConfiguration = WifiConfiguration().apply {
                SSID = "\"$DEFAULT_SSID\""
                preSharedKey = "\"$DEFAULT_PASSWORD\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }

            // This method is deprecated and may not work on newer devices
            @Suppress("DEPRECATION")
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(wifiManager, wifiConfiguration, true)
            
            Log.i(TAG, "Hotspot tethering started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotspot tethering", e)
        }
    }

    fun stopTethering() {
        try {
            Log.i(TAG, "Stopping hotspot tethering...")
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Log.i(TAG, "Android 10+ detected - hotspot must be disabled manually by user")
                return
            }

            @Suppress("DEPRECATION")
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(wifiManager, null, false)
            
            Log.i(TAG, "Hotspot tethering stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop hotspot tethering", e)
        }
    }

    fun isTetheringEnabled(): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // For Android 10+, check if hotspot is enabled
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                method.invoke(wifiManager) as Boolean
            } else {
                @Suppress("DEPRECATION")
                val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
                method.invoke(wifiManager) as Boolean
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking hotspot status", e)
            false
        }
    }

    override fun getHotspotClients(): List<ConnectedClient> {
        val clients = mutableListOf<ConnectedClient>()
        
        try {
            // Read ARP table to get connected devices
            val arpTable = readArpTable()
            for ((ip, mac) in arpTable) {
                if (ip.startsWith("192.168.43.") && ip != HOTSPOT_IP) {
                    clients.add(ConnectedClient(
                        macAddress = mac,
                        ipAddress = ip,
                        hostname = getHostname(ip)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting connected clients", e)
        }
        
        return clients
    }

    override fun subscribeToClientChanges(): Flow<List<ConnectedClient>> = flow {
        while (true) {
            try {
                val clients = getHotspotClients()
                emit(clients)
                delay(5000) // Poll every 5 seconds
            } catch (e: Exception) {
                Log.e(TAG, "Error in client monitoring", e)
                emit(emptyList())
                delay(5000)
            }
        }
    }

    override fun guideUserToEnableHotspot() {
        // TODO: Implement user guidance for hotspot setup
        Log.i(TAG, "Guiding user to enable hotspot")
    }

    override fun detectHotspotState(): HotspotState {
        return if (isTetheringEnabled()) {
            HotspotState.ENABLED
        } else {
            HotspotState.DISABLED
        }
    }

    fun getHotspotInfo(): HotspotInfo? {
        return try {
            if (isTetheringEnabled()) {
                HotspotInfo(
                    ssid = DEFAULT_SSID,
                    password = DEFAULT_PASSWORD,
                    ipAddress = HOTSPOT_IP,
                    isEnabled = true
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot info", e)
            null
        }
    }

    private fun readArpTable(): Map<String, String> {
        val arpTable = mutableMapOf<String, String>()
        
        try {
            val reader = BufferedReader(FileReader("/proc/net/arp"))
            var line: String?
            
            // Skip header line
            reader.readLine()
            
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split("\\s+".toRegex())
                if (parts.size >= 4) {
                    val ip = parts[0]
                    val mac = parts[3]
                    if (mac != "00:00:00:00:00:00" && mac != "<incomplete>") {
                        arpTable[ip] = mac
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ARP table", e)
        }
        
        return arpTable
    }

    private fun getHostname(ipAddress: String): String? {
        return try {
            val address = InetAddress.getByName(ipAddress)
            address.hostName
        } catch (e: Exception) {
            null
        }
    }
}
