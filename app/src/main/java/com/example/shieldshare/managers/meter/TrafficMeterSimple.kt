package com.example.shieldshare.managers.meter

import android.content.Context
import android.util.Log
import com.example.shieldshare.data.repository.TrafficRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.NetworkInterface
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*

/**
 * STAGE 2: Simple Traffic Metering Implementation
 *
 * Collects basic traffic data for monitoring:
 * - Real-time traffic statistics (upload/download bytes)
 * - Client IP tracking
 * - Basic session management
 * - Persistent storage via TrafficRepository
 */
@Singleton
class TrafficMeterSimple
@Inject
constructor(
        private val context: Context,
        private val trafficRepository: TrafficRepository,
        private val appPrefs: com.example.shieldshare.data.prefs.AppPrefs
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
        startPeriodicCleanup()
    }

    /**
     * Normalize IP address - extract just the IP part, removing port if present Handles formats
     * like "192.168.1.1:12345" or "/192.168.1.1:12345" -> "192.168.1.1"
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
     *
     * Records traffic both in-memory (for real-time display) and persistently (via repository).
     */
    override fun recordTraffic(clientIp: String, bytesUp: Long, bytesDown: Long) {
        try {
            // Normalize IP address to extract just the IP (remove port/socket info)
            val normalizedIp = normalizeIpAddress(clientIp)

            // Update global counters
            totalBytesUp.addAndGet(bytesUp)
            totalBytesDown.addAndGet(bytesDown)

            // Update or create client stats - use normalized IP as key
            val currentStats = clientStats[normalizedIp]
            val macAddress =
                    if (currentStats != null) {
                        currentStats.macAddress
                    } else {
                        resolveMacAddress(normalizedIp) // Resolve MAC address for new clients
                    }

            val updatedStats =
                    if (currentStats != null) {
                        currentStats.copy(
                                totalBytesUp = currentStats.totalBytesUp + bytesUp,
                                totalBytesDown = currentStats.totalBytesDown + bytesDown,
                                lastSeen = System.currentTimeMillis(),
                                connectionCount = currentStats.connectionCount + 1
                        )
                    } else {
                        ClientTrafficStats(
                                clientId = normalizedIp,
                                macAddress = macAddress,
                                ipAddress = normalizedIp,
                                totalBytesUp = bytesUp,
                                totalBytesDown = bytesDown,
                                lastSeen = System.currentTimeMillis(),
                                connectionCount = 1
                        )
                    }

            // Update in-memory cache (for real-time UI display)
            clientStats[normalizedIp] = updatedStats

            // Determine protocol from active session if available
            val activeSession =
                    activeSessions.values.find { it.clientIp == normalizedIp && it.isActive }
            val protocol = activeSession?.protocolType ?: "HTTP" // Default to HTTP if unknown

            // Persist to database via repository (batched automatically) without blocking caller
            scope.launch {
                try {
                    trafficRepository.recordTraffic(
                            clientIp = normalizedIp,
                            macAddress = macAddress,
                            bytesUploaded = bytesUp,
                            bytesDownloaded = bytesDown,
                            protocol = protocol,
                            sessionId = activeSession?.sessionId
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error persisting traffic for $normalizedIp", e)
                }
            }

            Log.d(
                    TAG,
                    "Traffic recorded for $normalizedIp (from $clientIp): ↑${bytesUp}B ↓${bytesDown}B"
            )
            addRawLog(
                    "Traffic recorded for $normalizedIp (${macAddress}): ↑${bytesUp}B ↓${bytesDown}B"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error recording traffic for $clientIp", e)
        }
    }

    /**
     * START SESSION - Called when new connection starts
     *
     * Creates session both in-memory (for real-time tracking) and persistently (via repository).
     */
    fun startSession(clientIp: String, protocolType: String): String {
        val normalizedIp = normalizeIpAddress(clientIp)
        val sessionId = UUID.randomUUID().toString()

        // Get or resolve MAC address
        val currentStats = clientStats[normalizedIp]
        val macAddress = currentStats?.macAddress ?: resolveMacAddress(normalizedIp)

        val session =
                TrafficSession(
                        sessionId = sessionId,
                        clientIp = normalizedIp,
                        startTime = Date(),
                        protocolType = protocolType
                )

        // Store in-memory (for real-time tracking)
        activeSessions[sessionId] = session
        totalConnections.incrementAndGet()

        // Persist to database via repository
        scope.launch {
            try {
                val deviceName = currentStats?.deviceAlias
                trafficRepository.startSession(
                        sessionId = sessionId,
                        clientIp = normalizedIp,
                        macAddress = macAddress,
                        protocolType = protocolType,
                        deviceName = deviceName,
                        userAgent = null // Can be enhanced later if available
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error persisting session start", e)
            }
        }

        Log.i(TAG, "Session started: $sessionId for $normalizedIp ($protocolType)")
        return sessionId
    }

    /**
     * END SESSION - Called when connection ends
     *
     * Ends session both in-memory and persistently (via repository).
     */
    fun endSession(sessionId: String) {
        val session = activeSessions.remove(sessionId)
        if (session != null) {
            session.endTime = Date()
            session.isActive = false

            // Persist session end to database via repository
            scope.launch {
                try {
                    trafficRepository.endSession(
                            sessionId = sessionId,
                            totalBytesUploaded = session.bytesUploaded,
                            totalBytesDownloaded = session.bytesDownloaded,
                            connectionCount = session.connectionCount,
                            hostsAccessed =
                                    session.hostsAccessed.toList().takeIf { it.isNotEmpty() }
                    )

                    // Increment session count for this client
                    trafficRepository.incrementSessionCount(session.clientIp)
                } catch (e: Exception) {
                    Log.e(TAG, "Error persisting session end", e)
                }
            }

            Log.i(TAG, "Session ended: $sessionId (${session.clientIp})")
        }
    }

    /**
     * GET CURRENT STATS for UI display Returns only active devices (those seen within the idle
     * timeout period) Inactive devices are removed from real-time display but remain in persistent
     * storage
     */
    override fun getCurrentStats(): List<ClientTrafficStats> {
        val timeoutMinutes = appPrefs.getInt("device_idle_timeout_minutes", 5)
        val timeoutMs = timeoutMinutes * 60_000L
        val currentTime = System.currentTimeMillis()

        // Filter out devices that haven't been seen within the timeout period
        return clientStats.values.filter { stats -> (currentTime - stats.lastSeen) <= timeoutMs }
    }

    /** GET HISTORICAL STATS (basic implementation) */
    override fun getHistoricalStats(timeRange: TimeRange): List<ClientTrafficStats> {
        val startTime = timeRange.startTime
        val endTime = timeRange.endTime

        return clientStats.values.filter { stats ->
            stats.lastSeen >= startTime && stats.lastSeen <= endTime
        }
    }

    /** RESOLVE MAC ADDRESS for IP (Multiple approaches due to Android limitations) */
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

    /** Try reading ARP table (may fail due to Android permissions) */
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

    /** Try getting MAC from NetworkInterface (limited) */
    private fun tryNetworkInterface(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.contains("wlan") || networkInterface.name.contains("ap")
                ) {
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

    /** Generate device fingerprint when MAC is unavailable */
    private fun generateDeviceFingerprint(ipAddress: String): String {
        // Create a consistent fingerprint based on IP pattern and time
        val lastOctet = ipAddress.substringAfterLast(".")
        val timeHash =
                (System.currentTimeMillis() / 3600000).toString() // Hour-based hash for consistency
        return "DEV-${lastOctet}-${timeHash.takeLast(4)}"
    }

    /** MAP IP TO MAC (enhanced implementation) */
    override fun mapIpToMac(): Map<String, String> {
        return clientStats.values.associate { stats ->
            stats.ipAddress to (stats.macAddress.takeIf { it.isNotEmpty() } ?: "unknown")
        }
    }

    /**
     * Periodic cleanup: Remove inactive devices from real-time display Devices are removed from
     * in-memory clientStats but remain in persistent storage This keeps the real-time monitor clean
     * while preserving historical data
     */
    private fun startPeriodicCleanup() {
        scope.launch {
            while (true) {
                try {
                    delay(CLEANUP_INTERVAL_MS) // Run every minute

                    val timeoutMinutes = appPrefs.getInt("device_idle_timeout_minutes", 5)
                    val timeoutMs = timeoutMinutes * 60_000L
                    val currentTime = System.currentTimeMillis()

                    val iterator = clientStats.iterator()
                    var removedCount = 0

                    while (iterator.hasNext()) {
                        val (ip, stats) = iterator.next()
                        val timeSinceLastSeen = currentTime - stats.lastSeen

                        if (timeSinceLastSeen > timeoutMs) {
                            iterator.remove()
                            removedCount++
                            Log.d(
                                    TAG,
                                    "Removed inactive device from real-time display: $ip (idle for ${timeSinceLastSeen / 1000}s, timeout: ${timeoutMs / 1000}s)"
                            )
                            addRawLog(
                                    "Removed inactive device: $ip (idle ${timeSinceLastSeen / 1000}s)"
                            )
                        }
                    }

                    if (removedCount > 0) {
                        Log.i(
                                TAG,
                                "Cleaned up $removedCount inactive device(s) from real-time display (timeout: ${timeoutMinutes} minutes)"
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic cleanup", e)
                    delay(60_000) // Wait longer on error
                }
            }
        }
    }

    /**
     * Periodic logging for monitoring Also flushes any pending traffic records to database
     * periodically
     */
    private fun startPeriodicLogging() {
        scope.launch {
            while (true) {
                delay(30_000) // Log every 30 seconds

                // Flush any pending traffic records to database
                try {
                    trafficRepository.flushTrafficRecords()
                } catch (e: Exception) {
                    Log.w(TAG, "Error flushing traffic records", e)
                }

                val totalUp = totalBytesUp.get()
                val totalDown = totalBytesDown.get()
                val connections = totalConnections.get()
                val activeClients = clientStats.size
                val activeSess = activeSessions.size

                Log.i(
                        TAG,
                        "**STAGE 2 Stats**: $activeClients clients, $activeSess sessions, " +
                                "↑${totalUp}B, ↓${totalDown}B, ${connections} connections"
                )

                // Log top clients with MAC addresses
                val sortedClients =
                        clientStats.values.sortedByDescending {
                            it.totalBytesUp + it.totalBytesDown
                        }
                sortedClients.take(3).forEach { client ->
                    val total = client.totalBytesUp + client.totalBytesDown
                    Log.i(
                            TAG,
                            "   **${client.ipAddress}** (${client.macAddress}): ${total}B (↑${client.totalBytesUp}B ↓${client.totalBytesDown}B)"
                    )
                    addRawLog(
                            "    **${client.ipAddress}** (${client.macAddress}): ${total}B (↑${client.totalBytesUp}B ↓${client.totalBytesDown}B)"
                    )
                }

                // Add summary to raw logs
                addRawLog(
                        "**STAGE 2 Stats**: $activeClients clients, $activeSess sessions, " +
                                "↑${totalUp}B, ↓${totalDown}B, ${connections} connections"
                )
            }
        }
    }

    /** Add entry to raw logs buffer */
    private fun addRawLog(message: String) {
        synchronized(rawLogs) {
            val timestamp =
                    java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                            .format(java.util.Date())
            rawLogs.add("$timestamp I/$TAG: $message")

            // Keep only the last maxLogEntries
            if (rawLogs.size > maxLogEntries) {
                rawLogs.removeAt(0)
            }
        }
    }

    /** Get raw logs for UI display */
    fun getRawLogs(): List<String> {
        synchronized(rawLogs) {
            return rawLogs.toList()
        }
    }

    /**
     * Reset/clear current session statistics. Called when a new service session starts to ensure
     * clean state. This clears per-device traffic stats and active sessions so new traffic doesn't
     * accumulate on top of previous session data.
     */
    override fun resetCurrentSessionStats() {
        // End all active client sessions first to ensure they're saved to database
        val sessionsToEnd = activeSessions.values.toList()
        val clientStatsCount = clientStats.size

        // End sessions asynchronously (they'll be saved to database)
        sessionsToEnd.forEach { session -> endSession(session.sessionId) }

        // Clear in-memory stats synchronously to prevent race conditions
        // This ensures new traffic doesn't accumulate on old data
        clientStats.clear()
        activeSessions.clear()

        // Reset global counters for the new session
        totalBytesUp.set(0)
        totalBytesDown.set(0)

        Log.i(
                TAG,
                "Current session stats reset - cleared ${sessionsToEnd.size} sessions and $clientStatsCount client stats"
        )
        addRawLog("Current session stats reset - starting fresh session")
    }
}
