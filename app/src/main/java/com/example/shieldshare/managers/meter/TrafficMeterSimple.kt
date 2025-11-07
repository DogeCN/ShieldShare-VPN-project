package com.example.shieldshare.managers.meter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * STAGE 2: Simple Traffic Metering Implementation
 * 
 * Collects basic traffic data for monitoring:
 * - Real-time traffic statistics (upload/download bytes)
 * - Client IP tracking
 * - Basic session management
 */
@Singleton
class TrafficMeterSimple @Inject constructor(
    private val context: Context
) : TrafficMeter {
    
    companion object {
        private const val TAG = "TrafficMeterImpl"
        private const val CLEANUP_INTERVAL_MS = 60_000L // 1 minute
    }
    
    // Real-time traffic tracking
    private val clientStats = ConcurrentHashMap<String, ClientTrafficStats>()
    private val activeSessions = ConcurrentHashMap<String, TrafficSession>()
    
    // Raw logs buffer for UI display
    private val rawLogs = mutableListOf<String>()
    private val maxLogEntries = 50 // Keep last 50 log entries
    
    // Performance counters
    private val totalBytesUp = AtomicLong(0)
    private val totalBytesDown = AtomicLong(0)
    private val totalConnections = AtomicLong(0)
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        Log.i(TAG, "STAGE 2: Traffic Metering Implementation Started")
        startPeriodicLogging()
    }
    
    /**
     * Normalize IP address - extract just the IP part, removing port if present
     * Handles formats like "192.168.1.1:12345" or "/192.168.1.1:12345" -> "192.168.1.1"
     */
    private fun normalizeIpAddress(ipAddress: String): String {
        // Remove leading "/" if present (from socket.remoteSocketAddress.toString())
        var normalized = ipAddress.trim().removePrefix("/")
        // Extract IP part before ":" (removes port)
        normalized = normalized.substringBefore(":")
        return normalized.trim()
    }
    
    /**
     * MAIN TRAFFIC RECORDING METHOD - Called by proxy handlers
     */
    override fun recordTraffic(clientIp: String, bytesUp: Long, bytesDown: Long) {
        scope.launch {
            try {
                // Normalize IP address to extract just the IP (remove port/socket info)
                val normalizedIp = normalizeIpAddress(clientIp)
                
                // Update global counters
                totalBytesUp.addAndGet(bytesUp)
                totalBytesDown.addAndGet(bytesDown)
                
                // Update or create client stats - use normalized IP as key
                val currentStats = clientStats[normalizedIp]
                val updatedStats = if (currentStats != null) {
                    currentStats.copy(
                        totalBytesUp = currentStats.totalBytesUp + bytesUp,
                        totalBytesDown = currentStats.totalBytesDown + bytesDown,
                        lastSeen = System.currentTimeMillis(),
                        connectionCount = currentStats.connectionCount + 1
                    )
                } else {
                    ClientTrafficStats(
                        clientId = normalizedIp,
                        macAddress = resolveMacAddress(normalizedIp), // Resolve MAC address
                        ipAddress = normalizedIp,
                        totalBytesUp = bytesUp,
                        totalBytesDown = bytesDown,
                        lastSeen = System.currentTimeMillis(),
                        connectionCount = 1
                    )
                }
                
                clientStats[normalizedIp] = updatedStats

                Log.d(TAG, "Traffic recorded for $normalizedIp (from $clientIp): ↑${bytesUp}B ↓${bytesDown}B")
                addRawLog("Traffic recorded for $normalizedIp (${updatedStats.macAddress}): ↑${bytesUp}B ↓${bytesDown}B")
            } catch (e: Exception) {
                Log.e(TAG, "Error recording traffic for $clientIp", e)
            }
        }
    }
    
    /**
     * START SESSION - Called when new connection starts
     */
    fun startSession(clientIp: String, protocolType: String): String {
        val normalizedIp = normalizeIpAddress(clientIp)
        val sessionId = UUID.randomUUID().toString()
        val session = TrafficSession(
            sessionId = sessionId,
            clientIp = normalizedIp,
            startTime = Date(),
            protocolType = protocolType
        )
        
        activeSessions[sessionId] = session
        totalConnections.incrementAndGet()
        
        Log.i(TAG, "Session started: $sessionId for $normalizedIp ($protocolType)")
        return sessionId
    }
    
    /**
     * END SESSION - Called when connection ends
     */
    fun endSession(sessionId: String) {
        val session = activeSessions.remove(sessionId)
        if (session != null) {
            session.endTime = Date()
            session.isActive = false
            Log.i(TAG, "Session ended: $sessionId (${session.clientIp})")
        }
    }
    
    /**
     * GET CURRENT STATS for UI display
     */
    override fun getCurrentStats(): List<ClientTrafficStats> {
        return clientStats.values.toList()
    }
    
    /**
     * GET HISTORICAL STATS (basic implementation)
     */
    override fun getHistoricalStats(timeRange: TimeRange): List<ClientTrafficStats> {
        // For now, just return current stats filtered by time range
        val startTime = timeRange.startTime
        val endTime = timeRange.endTime
        
        return clientStats.values.filter { stats ->
            stats.lastSeen >= startTime && stats.lastSeen <= endTime
        }
    }
    
    /**
     * RESOLVE MAC ADDRESS for IP (Multiple approaches due to Android limitations)
     */
    private fun resolveMacAddress(ipAddress: String): String {
        // Try multiple approaches due to Android security restrictions
        
        // Approach 1: Try ARP table (may fail due to permissions)
        val arpMac = tryArpTable(ipAddress)
        if (arpMac.isNotEmpty() && arpMac != "unknown") {
            Log.d(TAG, "MAC resolved via ARP for $ipAddress: $arpMac")
            addRawLog("MAC resolved via ARP for $ipAddress: $arpMac")
            return arpMac
        }
        
        // Approach 2: Try NetworkInterface (limited)
        val networkMac = tryNetworkInterface()
        if (networkMac.isNotEmpty() && networkMac != "unknown") {
            Log.d(TAG, "MAC resolved via NetworkInterface for $ipAddress: $networkMac")
            addRawLog("MAC resolved via NetworkInterface for $ipAddress: $networkMac")
            return networkMac
        }
        
        // Approach 3: Generate device fingerprint from IP pattern
        val fingerprint = generateDeviceFingerprint(ipAddress)
        Log.d(TAG, "Generated fingerprint for $ipAddress: $fingerprint")
        addRawLog("Generated fingerprint for $ipAddress: $fingerprint")
        return fingerprint
    }
    
    /**
     * Try reading ARP table (may fail due to Android permissions)
     */
    private fun tryArpTable(targetIp: String): String {
        return try {
            val process = Runtime.getRuntime().exec("cat /proc/net/arp")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            reader.useLines { lines ->
                lines.forEach { line ->
                    if (line.contains(targetIp)) {
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val mac = parts[3]
                            if (mac.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
                                return@useLines mac.uppercase()
                            }
                        }
                    }
                }
                "unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "ARP table read failed (expected on Android): ${e.message}")
            "unknown"
        }
    }
    
    /**
     * Try getting MAC from NetworkInterface (limited)
     */
    private fun tryNetworkInterface(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("wlan") || networkInterface.name.contains("ap")) {
                    val mac = networkInterface.hardwareAddress
                    if (mac != null && mac.size == 6) {
                        return mac.joinToString(":") { String.format("%02X", it) }
                    }
                }
            }
            "unknown"
        } catch (e: Exception) {
            Log.w(TAG, "NetworkInterface MAC read failed: ${e.message}")
            "unknown"
        }
    }
    
    /**
     * Generate device fingerprint when MAC is unavailable
     */
    private fun generateDeviceFingerprint(ipAddress: String): String {
        // Create a consistent fingerprint based on IP pattern and time
        val lastOctet = ipAddress.substringAfterLast(".")
        val timeHash = (System.currentTimeMillis() / 3600000).toString() // Hour-based hash for consistency
        return "DEV-${lastOctet}-${timeHash.takeLast(4)}"
    }
    
    /**
     * MAP IP TO MAC (enhanced implementation)
     */
    override fun mapIpToMac(): Map<String, String> {
        return clientStats.values.associate { stats ->
            stats.ipAddress to (stats.macAddress.takeIf { it.isNotEmpty() } ?: "unknown")
        }
    }
    
    /**
     * Periodic logging for monitoring
     */
    private fun startPeriodicLogging() {
        scope.launch {
            while (true) {
                delay(30_000) // Log every 30 seconds
                
                val totalUp = totalBytesUp.get()
                val totalDown = totalBytesDown.get()
                val connections = totalConnections.get()
                val activeClients = clientStats.size
                val activeSess = activeSessions.size

                Log.i(TAG, "**STAGE 2 Stats**: $activeClients clients, $activeSess sessions, " +
                           "↑${totalUp}B, ↓${totalDown}B, ${connections} connections")
                
                // Log top clients with MAC addresses
                val sortedClients = clientStats.values.sortedByDescending { it.totalBytesUp + it.totalBytesDown }
                sortedClients.take(3).forEach { client ->
                    val total = client.totalBytesUp + client.totalBytesDown
                    Log.i(TAG, "   **${client.ipAddress}** (${client.macAddress}): ${total}B (↑${client.totalBytesUp}B ↓${client.totalBytesDown}B)")
                    addRawLog("    **${client.ipAddress}** (${client.macAddress}): ${total}B (↑${client.totalBytesUp}B ↓${client.totalBytesDown}B)")
                }
                
                // Add summary to raw logs
                addRawLog("**STAGE 2 Stats**: $activeClients clients, $activeSess sessions, " +
                         "↑${totalUp}B, ↓${totalDown}B, ${connections} connections")
            }
        }
    }
    
    /**
     * Add entry to raw logs buffer
     */
    private fun addRawLog(message: String) {
        synchronized(rawLogs) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())
            rawLogs.add("$timestamp I/$TAG: $message")
            
            // Keep only the last maxLogEntries
            if (rawLogs.size > maxLogEntries) {
                rawLogs.removeAt(0)
            }
        }
    }
    
    /**
     * Get raw logs for UI display
     */
    fun getRawLogs(): List<String> {
        synchronized(rawLogs) {
            return rawLogs.toList()
        }
    }
}