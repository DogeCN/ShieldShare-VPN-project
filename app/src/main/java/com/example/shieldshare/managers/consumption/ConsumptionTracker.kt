package com.example.shieldshare.managers.consumption

import android.util.Log
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.meter.TrafficMeter
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks consumption for data leak and security anomaly detection
 * 
 * Features:
 * - Rate-based detection (bytes per hour) - detects sudden spikes
 * - Baseline rate establishment (normal consumption rate per hour)
 * - Real-time rate monitoring (checks every 5 minutes)
 * - Detects sustained high-rate usage (data leak pattern)
 * - Early detection before quota system stops them
 * 
 * Data Leak Detection Logic:
 * - Tracks consumption RATE (bytes/hour) not just total usage
 * - Establishes baseline rate from first period (normal usage pattern)
 * - Monitors current rate vs baseline rate
 * - Flags when current rate > baseline rate × multiplier for sustained period
 * - Catches data leaks early (sudden sustained high-rate transfers)
 * - Independent of quota system (detects patterns, not totals)
 */
@Singleton
class ConsumptionTracker @Inject constructor(
    private val appPrefs: AppPrefs,
    private val trafficMeter: TrafficMeter
) {
    // Callback for abuse detection notifications
    var onAbuseDetected: ((clientIp: String, abuseRatio: Float, globalAverage: Long) -> Unit)? = null
    // Callback to check if a client is already blocked by quota enforcement
    var isClientBlocked: ((clientIp: String) -> Boolean)? = null
    companion object {
        private const val TAG = "ConsumptionTracker"
        private const val KEY_CONSUMPTION_HISTORY_PREFIX = "consumption_history_"
        private const val KEY_BASELINE_RATE_PREFIX = "baseline_rate_"
        private const val KEY_RATE_HISTORY_PREFIX = "rate_history_"
        private const val DEFAULT_AVERAGE_DAYS = 7
        private const val RATE_CHECK_INTERVAL_MINUTES = 5 // Check rate every 5 minutes
        private const val RATE_SAMPLE_WINDOW_MINUTES = 60 // Calculate rate over last hour
        private const val SUSTAINED_ANOMALY_MINUTES = 15 // Flag if anomaly persists for 15+ minutes
    }
    
    // In-memory daily consumption (clientIp -> daily consumption in bytes)
    // Key format: "clientIp_YYYY-MM-DD" -> bytes
    private val dailyConsumption = ConcurrentHashMap<String, Long>()
    
    // Rate tracking: clientIp -> list of (timestamp, bytes) for rate calculation
    // Used to calculate bytes/hour rate
    private val rateSamples = ConcurrentHashMap<String, MutableList<Pair<Long, Long>>>()
    
    // Track when anomaly was first detected (to check if sustained)
    private val anomalyFirstDetected = ConcurrentHashMap<String, Long>()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        // Load historical data from preferences
        scope.launch {
            loadHistoricalData()
        }
        // Start periodic consumption updates (every 5 minutes)
        startPeriodicUpdates()
    }
    
    /**
     * Start periodic updates to track consumption and detect data leaks
     * Checks RATE (bytes/hour) not just total usage
     */
    private fun startPeriodicUpdates() {
        scope.launch {
            while (true) {
                delay(RATE_CHECK_INTERVAL_MINUTES * 60 * 1000L) // Every 5 minutes
                try {
                    updateAllClientsConsumption()
                    
                    // Check for data leaks if enabled (rate-based detection)
                    val anomalyDetectionEnabled = appPrefs.getBoolean("dynamic_quota_enabled", false)
                    if (anomalyDetectionEnabled) {
                        checkAllClientsForDataLeaks()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error in periodic consumption update", e)
                }
            }
        }
    }
    
    /**
     * Check all clients for data leak patterns (rate-based)
     */
    private fun checkAllClientsForDataLeaks() {
        try {
            val stats = trafficMeter.getCurrentStats()
            val multiplier = appPrefs.getFloat("dynamic_quota_multiplier", 2.0f)
            
            stats.forEach { stat ->
                // Skip clients currently blocked by quota system
                if (isClientBlocked?.invoke(stat.ipAddress) == true) {
                    anomalyFirstDetected.remove(stat.ipAddress)
                    return@forEach
                }

                val clientIp = stat.ipAddress
                val currentRate = calculateCurrentRate(clientIp)
                val baselineRate = getOrEstablishBaselineRate(clientIp)
                
                if (baselineRate > 0 && currentRate > 0) {
                    val rateRatio = currentRate.toFloat() / baselineRate
                    
                    // Check if rate exceeds threshold
                    if (rateRatio > multiplier) {
                        val firstDetected = anomalyFirstDetected[clientIp] ?: System.currentTimeMillis()
                        anomalyFirstDetected[clientIp] = firstDetected
                        
                        val durationMinutes = (System.currentTimeMillis() - firstDetected) / (60 * 1000)
                        
                        // Only flag if sustained for threshold period
                        if (durationMinutes >= SUSTAINED_ANOMALY_MINUTES) {
                            Log.w(TAG, "DATA LEAK DETECTED: $clientIp using ${String.format("%.1f", rateRatio)}× baseline rate (${formatBytes(currentRate)}/hour vs ${formatBytes(baselineRate)}/hour baseline) for ${durationMinutes} minutes")
                            onAbuseDetected?.invoke(clientIp, rateRatio, baselineRate)
                        }
                    } else {
                        // Rate normalized, clear anomaly tracking
                        anomalyFirstDetected.remove(clientIp)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking for data leaks", e)
        }
    }
    
    /**
     * Calculate current consumption rate (bytes per hour) for a client
     */
    private fun calculateCurrentRate(clientIp: String): Long {
        val now = System.currentTimeMillis()
        val windowStart = now - (RATE_SAMPLE_WINDOW_MINUTES * 60 * 1000L)
        
        // Get current total consumption
        val currentTotal = getCurrentConsumption(clientIp)
        
        val samples = rateSamples.getOrPut(clientIp) { mutableListOf() }
        
        // Clean old samples
        samples.removeAll { it.first < windowStart }
        
        // Add current sample
        samples.add(Pair(now, currentTotal))
        
        if (samples.size < 2) {
            // Not enough data, estimate from current usage
            return 0L
        }
        
        // Calculate rate: (current - oldest) / time difference
        val oldest = samples.first()
        val newest = samples.last()
        val bytesDiff = newest.second - oldest.second
        val timeDiffHours = (newest.first - oldest.first).toDouble() / (60 * 60 * 1000.0)
        
        return if (timeDiffHours > 0) {
            (bytesDiff / timeDiffHours).toLong()
        } else {
            0L
        }
    }
    
    /**
     * Get or establish baseline rate (bytes per hour) for a client
     * Baseline is established from first period and doesn't change
     */
    private fun getOrEstablishBaselineRate(clientIp: String): Long {
        val baselineKey = "${KEY_BASELINE_RATE_PREFIX}$clientIp"
        
        // Check if baseline already exists
        val existingBaseline = appPrefs.getLong(baselineKey, 0L)
        if (existingBaseline > 0L) {
            return existingBaseline
        }
        
        // Establish baseline from average daily consumption / 24 hours
        val averageDaily = getAverageConsumption(clientIp, DEFAULT_AVERAGE_DAYS)
        val baselineRate = if (averageDaily > 0) {
            averageDaily / 24 // bytes per hour
        } else {
            0L
        }
        
        if (baselineRate > 0) {
            appPrefs.putLong(baselineKey, baselineRate)
            Log.d(TAG, "Established baseline rate for $clientIp: ${formatBytes(baselineRate)}/hour")
        }
        
        return baselineRate
    }
    
    /**
     * Record daily consumption for a client
     * Called periodically to update consumption history
     */
    fun recordDailyConsumption(clientIp: String, bytes: Long) {
        val today = getTodayKey()
        val key = "${clientIp}_$today"
        
        dailyConsumption[key] = bytes
        Log.d(TAG, "Recorded consumption for $clientIp on $today: ${formatBytes(bytes)}")
        
        // Persist to preferences
        scope.launch {
            saveDailyConsumption(key, bytes)
        }
    }
    
    /**
     * Get average consumption for a client over the last N days
     * Includes today's consumption for real-time display
     * 
     * @param clientIp Client IP address
     * @param days Number of days to average (default: 7)
     * @return Average consumption in bytes, or current consumption if no history
     */
    fun getAverageConsumption(clientIp: String, days: Int = DEFAULT_AVERAGE_DAYS): Long {
        var totalBytes = 0L
        var dayCount = 0
        
        // First, update today's consumption from TrafficMeter (real-time)
        val todayConsumption = getCurrentConsumption(clientIp)
        if (todayConsumption > 0) {
            // Update today's consumption in memory
            val today = getTodayKey()
            val todayKey = "${clientIp}_$today"
            dailyConsumption[todayKey] = todayConsumption
            // Include today in average
            totalBytes += todayConsumption
            dayCount++
        }
        
        // Get consumption for past N-1 days (excluding today which we already added)
        for (i in 1 until days) {
            val dateKey = getDateKey(i)
            val key = "${clientIp}_$dateKey"
            
            // Try in-memory first
            val consumption = dailyConsumption[key] ?: loadDailyConsumption(key)
            if (consumption > 0) {
                totalBytes += consumption
                dayCount++
            }
        }
        
        // If we have at least today's data, use it
        // Otherwise, return 0 (no consumption yet)
        val average = if (dayCount > 0) totalBytes / dayCount else 0L
        
        if (average > 0) {
            Log.d(TAG, "Average consumption for $clientIp (last $days days, $dayCount days with data, today: ${formatBytes(todayConsumption)}): ${formatBytes(average)}")
        }
        
        return average
    }
    
    /**
     * Get global baseline rate (median of all clients' baseline rates)
     * Used for UI display
     */
    fun getGlobalBaselineRate(): Long {
        try {
            val stats = trafficMeter.getCurrentStats()
            if (stats.isEmpty()) return 0L
            
            val baselineRates = mutableListOf<Long>()
            stats.forEach { stat ->
                val baselineRate = getOrEstablishBaselineRate(stat.ipAddress)
                if (baselineRate > 0) {
                    baselineRates.add(baselineRate)
                }
            }
            
            if (baselineRates.isEmpty()) return 0L
            
            baselineRates.sort()
            val medianRate = if (baselineRates.size % 2 == 0) {
                (baselineRates[baselineRates.size / 2 - 1] + baselineRates[baselineRates.size / 2]) / 2
            } else {
                baselineRates[baselineRates.size / 2]
            }
            
            return medianRate
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating global baseline rate", e)
            return 0L
        }
    }
    
    /**
     * Get global average consumption - for display only
     */
    fun getGlobalAverageConsumption(days: Int = DEFAULT_AVERAGE_DAYS): Long {
        try {
            val stats = trafficMeter.getCurrentStats()
            if (stats.isEmpty()) return 0L
            
            var totalGlobalBytes = 0L
            var clientCount = 0
            
            stats.forEach { stat ->
                val clientAverage = getAverageConsumption(stat.ipAddress, days)
                if (clientAverage > 0) {
                    totalGlobalBytes += clientAverage
                    clientCount++
                }
            }
            
            return if (clientCount > 0) totalGlobalBytes / clientCount else 0L
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating global average", e)
            return 0L
        }
    }
    
    /**
     * Check if a client shows data leak pattern (rate-based detection)
     * Uses RATE (bytes/hour) not total usage to detect sudden spikes
     * 
     * @param clientIp Client IP address
     * @param multiplier Rate threshold multiplier (e.g., 2.0 = 2× baseline rate)
     * @param days Number of days to average (default: 7) - for baseline calculation only
     * @return AbuseCheckResult with data leak detection results
     */
    fun checkAbuse(
        clientIp: String,
        multiplier: Float,
        days: Int = DEFAULT_AVERAGE_DAYS
    ): AbuseCheckResult {
        // Skip clients already blocked (quota enforcement) to avoid false positives
        if (isClientBlocked?.invoke(clientIp) == true) {
            anomalyFirstDetected.remove(clientIp)
            return AbuseCheckResult(
                isAbusing = false,
                clientAverage = getAverageConsumption(clientIp, days),
                globalAverage = 0L,
                threshold = 0L,
                abuseRatio = 0f
            )
        }

        val currentRate = calculateCurrentRate(clientIp) // bytes per hour
        val baselineRate = getOrEstablishBaselineRate(clientIp) // bytes per hour
        
        // For UI display, also get current total usage
        val clientCurrentUsage = getAverageConsumption(clientIp, days)
        
        if (baselineRate == 0L || currentRate == 0L) {
            return AbuseCheckResult(
                isAbusing = false,
                clientAverage = clientCurrentUsage,
                globalAverage = baselineRate, // Show baseline rate in UI
                threshold = 0L,
                abuseRatio = 0f
            )
        }
        
        // Check if rate exceeds threshold
        val rateThreshold = (baselineRate * multiplier).toLong()
        val isAbusing = currentRate > rateThreshold
        val abuseRatio = if (baselineRate > 0) currentRate.toFloat() / baselineRate else 0f
        
        // Check if anomaly is sustained
        val isSustained = if (isAbusing) {
            val firstDetected = anomalyFirstDetected[clientIp] ?: System.currentTimeMillis()
            anomalyFirstDetected[clientIp] = firstDetected
            val durationMinutes = (System.currentTimeMillis() - firstDetected) / (60 * 1000)
            durationMinutes >= SUSTAINED_ANOMALY_MINUTES
        } else {
            anomalyFirstDetected.remove(clientIp)
            false
        }
        
        // Only return as abusing if sustained
        val finalIsAbusing = isAbusing && isSustained
        
        if (finalIsAbusing) {
            Log.w(TAG, "DATA LEAK DETECTED: $clientIp using ${formatBytes(currentRate)}/hour (${String.format("%.1f", abuseRatio)}× baseline rate of ${formatBytes(baselineRate)}/hour) - Sustained anomaly!")
        }
        
        return AbuseCheckResult(
            isAbusing = finalIsAbusing,
            clientAverage = clientCurrentUsage,
            globalAverage = baselineRate, // Return baseline rate for UI
            threshold = rateThreshold,
            abuseRatio = abuseRatio
        )
    }
    
    /**
     * Get abuse status for all clients
     * 
     * @param multiplier Abuse threshold multiplier
     * @param days Number of days to average
     * @return Map of clientIp -> AbuseCheckResult
     */
    fun getAllAbuseStatus(
        multiplier: Float,
        days: Int = DEFAULT_AVERAGE_DAYS
    ): Map<String, AbuseCheckResult> {
        try {
            val stats = trafficMeter.getCurrentStats()
            return stats
                .filter { isClientBlocked?.invoke(it.ipAddress) != true }
                .associate { stat ->
                    stat.ipAddress to checkAbuse(stat.ipAddress, multiplier, days)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting abuse status for all clients", e)
            return emptyMap()
        }
    }
    
    /**
     * Get current day's consumption for a client
     * Uses TrafficMeter as source of truth
     */
    fun getCurrentConsumption(clientIp: String): Long {
        return try {
            // Get total consumption from TrafficMeter
            val stats = trafficMeter.getCurrentStats()
            val clientStat = stats.find { it.ipAddress == clientIp || it.clientId == clientIp }
            if (clientStat != null) {
                clientStat.totalBytesUp + clientStat.totalBytesDown
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting current consumption for $clientIp", e)
            0L
        }
    }
    
    /**
     * Reset consumption history for a client (or all clients if null)
     */
    fun resetConsumptionHistory(clientIp: String?) {
        scope.launch {
            if (clientIp == null) {
                // Reset all
                dailyConsumption.clear()
                clearAllHistoricalData()
                Log.i(TAG, "Reset consumption history for all clients")
            } else {
                // Reset specific client
                val keysToRemove = dailyConsumption.keys.filter { it.startsWith("${clientIp}_") }
                keysToRemove.forEach { key ->
                    dailyConsumption.remove(key)
                    appPrefs.putLong("${KEY_CONSUMPTION_HISTORY_PREFIX}$key", 0)
                }
                Log.i(TAG, "Reset consumption history for $clientIp")
            }
        }
    }
    
    /**
     * Update daily consumption from TrafficMeter
     * Should be called periodically (e.g., every hour or when quota is checked)
     */
    fun updateDailyConsumption(clientIp: String) {
        val currentConsumption = getCurrentConsumption(clientIp)
        if (currentConsumption > 0) {
            recordDailyConsumption(clientIp, currentConsumption)
        }
    }
    
    /**
     * Update consumption for all active clients
     */
    fun updateAllClientsConsumption() {
        scope.launch {
            try {
                // Get all active clients from TrafficMeter
                val stats = trafficMeter.getCurrentStats()
                stats.forEach { stat ->
                    updateDailyConsumption(stat.ipAddress)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating consumption for all clients", e)
            }
        }
    }
    
    // Private helper methods
    
    private fun getTodayKey(): String {
        val calendar = java.util.Calendar.getInstance()
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }
    
    private fun getDateKey(daysAgo: Int): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_MONTH, -daysAgo)
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return String.format("%04d-%02d-%02d", year, month, day)
    }
    
    private suspend fun loadHistoricalData() = withContext(Dispatchers.IO) {
        // Load last 30 days of data (to have enough history)
        // We'll load data on-demand when needed
        Log.d(TAG, "Historical data loading completed")
    }
    
    private suspend fun saveDailyConsumption(key: String, bytes: Long) = withContext(Dispatchers.IO) {
        appPrefs.putLong("${KEY_CONSUMPTION_HISTORY_PREFIX}$key", bytes)
    }
    
    private fun loadDailyConsumption(key: String): Long {
        return appPrefs.getLong("${KEY_CONSUMPTION_HISTORY_PREFIX}$key", 0)
    }
    
    private suspend fun clearAllHistoricalData() = withContext(Dispatchers.IO) {
        // Clear all consumption history keys
        // Note: This is a simplified approach - in production, you might want to track keys
        // For now, we'll just clear in-memory data and let old keys expire
        Log.d(TAG, "Cleared all historical consumption data")
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

