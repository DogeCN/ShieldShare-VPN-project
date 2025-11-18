package com.example.shieldshare.managers.quota

import android.content.Context
import android.util.Log
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.meter.TrafficMeter
import kotlinx.coroutines.*
import kotlin.math.max
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages traffic quotas for clients
 * 
 * Features:
 * - Equal quota allocation among all clients (including host)
 * - Real-time quota tracking
 * - Quota enforcement (blocking + throttling)
 * - Quota exhaustion notifications
 */
@Singleton
class QuotaManager @Inject constructor(
    private val context: Context,
    private val appPrefs: AppPrefs,
    private val trafficMeter: TrafficMeter
) {
    companion object {
        private const val TAG = "QuotaManager"
        private const val QUOTA_STATE_PREFIX = "quota_state_"
    }
    
    // In-memory quota states (key: client IP)
    private val quotaStates = ConcurrentHashMap<String, QuotaState>()
    
    // Current configuration
    private var config: QuotaConfig = QuotaConfig.default()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Callback for quota exhaustion events
    var onQuotaExceeded: ((clientIp: String, macAddress: String) -> Unit)? = null
    
    init {
        loadConfig()
        // Load blocked clients after config is loaded
        scope.launch {
            loadBlockedClients()
        }
        startPeriodicCleanup()
    }
    
    /**
     * Load configuration from preferences
     */
    fun loadConfig() {
        val previousBlockDuration = config.blockDurationMs
        config = QuotaConfig.fromPreferences(
            enabled = appPrefs.getBoolean("quota_enabled", false),
            totalBandwidthMb = appPrefs.getLong("quota_total_bandwidth_mb", 0),
            resetIntervalHours = 24, // Default to daily reset (not configurable in UI)
            blockDurationHours = appPrefs.getInt("quota_block_duration_hours", 1),
            warningThreshold = 0.8, // Default 80% (not configurable in UI)
            throttleSpeedKbps = 1 // Default 1 KB/s (not configurable in UI)
        )
        Log.i(TAG, "Quota config loaded: enabled=${config.enabled}, totalBandwidth=${config.totalBandwidthBytes} bytes, blockDuration=${config.blockDurationMs}ms")
        
        // If block duration is set to 0, clear all existing blocks
        if (config.blockDurationMs == 0L && previousBlockDuration != 0L) {
            clearAllBlocks()
        }
    }
    
    /**
     * Update configuration
     */
    fun updateConfig(newConfig: QuotaConfig) {
        val previousBlockDuration = config.blockDurationMs
        config = newConfig
        // Save to preferences
        appPrefs.putBoolean("quota_enabled", config.enabled)
        appPrefs.putLong("quota_total_bandwidth_mb", config.totalBandwidthBytes / (1024 * 1024))
        appPrefs.putInt("quota_reset_interval_hours", (config.quotaResetIntervalMs / (3600 * 1000)).toInt())
        appPrefs.putInt("quota_block_duration_hours", (config.blockDurationMs / (3600 * 1000)).toInt())
        appPrefs.putFloat("quota_warning_threshold", config.warningThreshold.toFloat())
        appPrefs.putInt("quota_throttle_speed_kbps", (config.throttleSpeedBps / 1024).toInt())
        
        // If block duration is set to 0, clear all existing blocks
        if (config.blockDurationMs == 0L && previousBlockDuration != 0L) {
            clearAllBlocks()
        }
        
        // Recalculate quotas for all clients
        if (config.enabled) {
            recalculateQuotas()
        }
    }
    
    /**
     * Initialize or update quotas for all connected clients
     * Should be called when clients connect/disconnect
     * 
     * @param connectedClients List of connected client IPs (excluding host)
     * @param hostIp Host device IP (included in quota calculation)
     */
    fun initializeQuotas(connectedClients: List<String>, hostIp: String? = null) {
        if (!config.enabled || config.totalBandwidthBytes <= 0) {
            Log.d(TAG, "Quota system disabled or unlimited bandwidth")
            return
        }
        
        val totalClients = connectedClients.size + (if (hostIp != null) 1 else 0)
        if (totalClients == 0) {
            Log.d(TAG, "No clients connected, skipping quota initialization")
            return
        }
        
        val quotaPerClient = config.totalBandwidthBytes / totalClients
        val quotaPerClientMb = quotaPerClient.toDouble() / (1024 * 1024)
        Log.i(TAG, "Initializing quotas: $totalClients clients, ${String.format("%.2f", quotaPerClientMb)} MB per client (${quotaPerClient} bytes)")
        
        // Update quotas for existing clients
        // IMPORTANT: When recalculating quotas (e.g., after settings change or new client connects),
        // we should preserve usedBytes if the quota increased, but reset if it decreased significantly
        // For simplicity and fairness, we'll reset usedBytes when quota changes to ensure fair distribution
        connectedClients.forEach { clientIp ->
            val existingState = quotaStates[clientIp]
            val macAddress = existingState?.macAddress ?: "unknown"
            val actualUsage = getActualUsageFromTrafficMeter(clientIp)
            
            // If quota allocation changed significantly (more than 10% difference), reset usage for fairness
            val shouldResetUsage = existingState?.let { 
                val oldQuota = it.allocatedQuotaBytes
                val quotaChange = kotlin.math.abs(quotaPerClient - oldQuota).toDouble() / oldQuota.coerceAtLeast(1)
                quotaChange > 0.1 // More than 10% change
            } ?: true // New client, always reset
            
            val baseline = existingState?.usageBaselineBytes ?: actualUsage
            val adjustedUsage = existingState?.let { calculateAdjustedUsage(it, actualUsage) } ?: 0L
            val newBaseline = if (shouldResetUsage) actualUsage else baseline
            val newUsed = if (shouldResetUsage) 0 else adjustedUsage.coerceAtMost(quotaPerClient)
            
            quotaStates[clientIp] = existingState?.copy(
                allocatedQuotaBytes = quotaPerClient,
                usedBytes = newUsed,
                usageBaselineBytes = newBaseline,
                quotaExceededAt = if (shouldResetUsage) null else existingState.quotaExceededAt,
                isBlocked = if (shouldResetUsage) false else existingState.isBlocked,
                blockedUntil = if (shouldResetUsage) null else existingState.blockedUntil,
                resetAt = System.currentTimeMillis()
            ) ?: QuotaState(
                clientIp = clientIp,
                macAddress = macAddress,
                allocatedQuotaBytes = quotaPerClient,
                usedBytes = 0,
                usageBaselineBytes = actualUsage,
                resetAt = System.currentTimeMillis()
            )
        }
        
        // Handle host quota if specified
        hostIp?.let { ip ->
            val existingState = quotaStates[ip]
            val actualUsage = getActualUsageFromTrafficMeter(ip)
            val shouldResetUsage = existingState?.let { 
                val oldQuota = it.allocatedQuotaBytes
                val quotaChange = kotlin.math.abs(quotaPerClient - oldQuota).toDouble() / oldQuota.coerceAtLeast(1)
                quotaChange > 0.1
            } ?: true
            val baseline = existingState?.usageBaselineBytes ?: actualUsage
            val adjustedUsage = existingState?.let { calculateAdjustedUsage(it, actualUsage) } ?: 0L
            val newBaseline = if (shouldResetUsage) actualUsage else baseline
            val newUsed = if (shouldResetUsage) 0 else adjustedUsage.coerceAtMost(quotaPerClient)
            
            quotaStates[ip] = existingState?.copy(
                allocatedQuotaBytes = quotaPerClient,
                usedBytes = newUsed,
                usageBaselineBytes = newBaseline,
                quotaExceededAt = if (shouldResetUsage) null else existingState.quotaExceededAt,
                isBlocked = if (shouldResetUsage) false else existingState.isBlocked,
                blockedUntil = if (shouldResetUsage) null else existingState.blockedUntil,
                resetAt = System.currentTimeMillis()
            ) ?: QuotaState(
                clientIp = ip,
                macAddress = "HOST",
                allocatedQuotaBytes = quotaPerClient,
                usedBytes = 0,
                usageBaselineBytes = actualUsage,
                resetAt = System.currentTimeMillis()
            )
        }
    }
    
    /**
     * Recalculate quotas for all existing clients
     * This is called when quota settings change (e.g., total bandwidth changed)
     */
    private fun recalculateQuotas() {
        val connectedClients = quotaStates.keys.filter { it != "HOST" && !quotaStates[it]?.macAddress.equals("HOST") }
        // Get host IP from any existing state marked as HOST, or pass null
        val hostIp = quotaStates.values.firstOrNull { it.macAddress == "HOST" }?.clientIp
        initializeQuotas(connectedClients, hostIp)
    }
    
    /**
     * Check if a client is currently blocked
     */
    fun isClientBlocked(clientIp: String): Boolean {
        if (!config.enabled) return false
        // If block duration is 0, never block clients
        if (config.blockDurationMs == 0L) return false
        
        val state = quotaStates[clientIp] ?: return false
        return state.isCurrentlyBlocked()
    }
    
    /**
     * Check quota before allowing traffic
     * Uses TrafficMeter as the source of truth for actual usage (matches Per Device Traffic meter)
     * 
     * @param clientIp Client IP address
     * @param bytesToTransfer Number of bytes about to be transferred
     * @return QuotaStatus indicating whether to proceed, throttle, or block
     */
    fun checkQuota(clientIp: String, bytesToTransfer: Long = 0): QuotaStatus {
        if (!config.enabled) {
            return QuotaStatus.OK
        }
        
        val state = quotaStates[clientIp] ?: return QuotaStatus.OK
        
        // Check if client is blocked (but not if block duration is 0)
        if (config.blockDurationMs > 0 && state.isCurrentlyBlocked()) {
            return QuotaStatus.Blocked(
                blockedUntil = state.blockedUntil ?: 0,
                reason = "Quota exceeded"
            )
        }
        
        // Get actual usage from TrafficMeter (source of truth - matches Per Device Traffic meter)
        val actualUsedBytes = getActualUsageFromTrafficMeter(clientIp)
        val adjustedUsedBytes = calculateAdjustedUsage(state, actualUsedBytes)
        
        // Check if quota is exceeded using actual TrafficMeter values
        val isExceeded = adjustedUsedBytes >= state.allocatedQuotaBytes && state.allocatedQuotaBytes > 0
        
        if (isExceeded) {
            // Update quota state to reflect exceeded status
            if (state.quotaExceededAt == null) {
                // Quota just exceeded - update state and block
                val updatedState = state.copy(
                    usedBytes = adjustedUsedBytes, // delta usage
                    quotaExceededAt = System.currentTimeMillis(),
                    isBlocked = config.blockDurationMs > 0,
                    blockedUntil = if (config.blockDurationMs > 0) {
                        System.currentTimeMillis() + config.blockDurationMs
                    } else null
                )
                quotaStates[clientIp] = updatedState
                recordQuotaExceeded(clientIp)
            } else {
                // Already exceeded - just sync usage
                quotaStates[clientIp] = state.copy(usedBytes = adjustedUsedBytes)
            }
            
            return QuotaStatus.Exceeded(
                usedBytes = adjustedUsedBytes,
                allocatedBytes = state.allocatedQuotaBytes,
                allowMinimal = config.allowMinimalTraffic
            )
        }
        
        // Check if quota would be exceeded after this transfer
        if (state.allocatedQuotaBytes > 0 && adjustedUsedBytes + bytesToTransfer > state.allocatedQuotaBytes) {
            // Will exceed quota, but allow this transfer and then block
            return QuotaStatus.Exceeded(
                usedBytes = adjustedUsedBytes,
                allocatedBytes = state.allocatedQuotaBytes,
                allowMinimal = config.allowMinimalTraffic
            )
        }
        
        // Sync usage with TrafficMeter for accurate tracking
        if (adjustedUsedBytes != state.usedBytes) {
            quotaStates[clientIp] = state.copy(usedBytes = adjustedUsedBytes)
        }
        
        // Check warning threshold using actual usage
        val usagePercentage = if (state.allocatedQuotaBytes > 0) {
            adjustedUsedBytes.toDouble() / state.allocatedQuotaBytes.toDouble()
        } else 0.0
        
        if (usagePercentage >= config.warningThreshold && !isExceeded) {
            return QuotaStatus.Warning(
                usagePercentage = usagePercentage,
                remainingBytes = maxOf(0, state.allocatedQuotaBytes - adjustedUsedBytes)
            )
        }
        
        return QuotaStatus.OK
    }
    
    /**
     * Get actual usage from TrafficMeter (source of truth - matches Per Device Traffic meter)
     */
    private fun getActualUsageFromTrafficMeter(clientIp: String): Long {
        return try {
            val stats = trafficMeter.getCurrentStats()
            val clientStats = stats.find { it.ipAddress == clientIp || it.clientId == clientIp }
            if (clientStats != null) {
                clientStats.totalBytesUp + clientStats.totalBytesDown
            } else {
                // Fallback to quota state if not found in TrafficMeter yet
                quotaStates[clientIp]?.usedBytes ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting usage from TrafficMeter for $clientIp", e)
            // Fallback to quota state
            quotaStates[clientIp]?.usedBytes ?: 0L
        }
    }
    
    private fun calculateAdjustedUsage(state: QuotaState, actualUsedBytes: Long): Long {
        return max(0L, actualUsedBytes - state.usageBaselineBytes)
    }
    
    /**
     * Record traffic usage for a client
     * This method now syncs with TrafficMeter (source of truth) instead of tracking separately
     * This ensures quota decisions match the Per Device Traffic meter exactly
     * 
     * @param clientIp Client IP address
     * @param bytesUp Upload bytes (not used for tracking, but kept for API compatibility)
     * @param bytesDown Download bytes (not used for tracking, but kept for API compatibility)
     * @param macAddress MAC/ID (optional, for new clients)
     */
    @Suppress("UNUSED_PARAMETER")
    fun recordUsage(clientIp: String, bytesUp: Long, bytesDown: Long, macAddress: String? = null) {
        if (!config.enabled) {
            Log.d(TAG, "Quota system disabled, skipping recordUsage for $clientIp")
            return
        }
        
        var currentState = quotaStates[clientIp]
        if (currentState == null) {
            Log.w(TAG, "No quota state found for $clientIp - creating provisional state")
            val initialUsage = getActualUsageFromTrafficMeter(clientIp)
            val resolvedMac = macAddress
                ?: quotaStates.values.firstOrNull { it.clientIp == clientIp }?.macAddress
                ?: "unknown"
            val provisionalState = QuotaState(
                clientIp = clientIp,
                macAddress = resolvedMac,
                allocatedQuotaBytes = config.totalBandwidthBytes.coerceAtLeast(1),
                usedBytes = 0,
                usageBaselineBytes = initialUsage
            )
            quotaStates[clientIp] = provisionalState
            currentState = provisionalState
        }
        
        // Get actual usage from TrafficMeter (source of truth - matches Per Device Traffic meter)
        val actualUsedBytes = getActualUsageFromTrafficMeter(clientIp)
        val adjustedUsedBytes = calculateAdjustedUsage(currentState, actualUsedBytes)
        
        // Update MAC/ID if provided and current one is "unknown"
        val updatedMacAddress = if (macAddress != null && macAddress != "unknown" && (currentState.macAddress == "unknown" || currentState.macAddress.isEmpty())) {
            macAddress
        } else {
            currentState.macAddress
        }
        
        val wasExceeded = currentState.isExceeded
        val isNowExceeded = adjustedUsedBytes >= currentState.allocatedQuotaBytes && currentState.allocatedQuotaBytes > 0
        
        val updatedState = currentState.copy(
            clientIp = clientIp,
            macAddress = updatedMacAddress,
            usedBytes = adjustedUsedBytes, // Always sync with adjusted usage
            quotaExceededAt = if (isNowExceeded && !wasExceeded) System.currentTimeMillis() else currentState.quotaExceededAt,
            isBlocked = isNowExceeded && config.blockDurationMs > 0, // Only block if duration > 0
            blockedUntil = if (isNowExceeded && currentState.blockedUntil == null && config.blockDurationMs > 0) {
                System.currentTimeMillis() + config.blockDurationMs
            } else if (config.blockDurationMs == 0L) {
                // Block duration is 0, clear any existing block
                null
            } else {
                currentState.blockedUntil
            }
        )
        
        quotaStates[clientIp] = updatedState
        
        Log.d(TAG, "Synced quota state for $clientIp: used=${updatedState.usedBytes} (from TrafficMeter), allocated=${updatedState.allocatedQuotaBytes}, exceeded=${updatedState.isExceeded}")
        
        // Check if quota was just exceeded
        if (updatedState.isExceeded && updatedState.quotaExceededAt == System.currentTimeMillis()) {
            Log.w(TAG, "Quota exceeded for $clientIp - calling recordQuotaExceeded")
            recordQuotaExceeded(clientIp)
        } else if (updatedState.isExceeded) {
            Log.d(TAG, "Quota already exceeded for $clientIp (exceeded at ${updatedState.quotaExceededAt})")
        }
    }
    
    /**
     * Record that a client has exceeded their quota
     */
    private fun recordQuotaExceeded(clientIp: String) {
        val state = quotaStates[clientIp] ?: return
        
        Log.w(TAG, "Client $clientIp (${state.macAddress}) has exceeded quota: ${state.usedBytes} / ${state.allocatedQuotaBytes} bytes")
        
        // Only block if block duration is > 0
        if (config.blockDurationMs > 0) {
            val blockedUntil = System.currentTimeMillis() + config.blockDurationMs
            
            // Update state to mark as blocked
            quotaStates[clientIp] = state.copy(
                isBlocked = true,
                blockedUntil = blockedUntil
            )
            
            // Persist blocked client
            saveBlockedClient(clientIp, blockedUntil)
        } else {
            // Block duration is 0, so don't block but still mark as exceeded
            quotaStates[clientIp] = state.copy(
                isBlocked = false,
                blockedUntil = null
            )
            Log.d(TAG, "Block duration is 0, client $clientIp will not be blocked")
        }
        
        // Trigger callback for notification (even if not blocking)
        onQuotaExceeded?.invoke(clientIp, state.macAddress)
    }
    
    /**
     * Check if quota system is enabled
     */
    fun isQuotaEnabled(): Boolean = config.enabled
    
    /**
     * Expose current quota configuration
     */
    fun getConfig(): QuotaConfig = config
    
    /**
     * Get quota state for a client
     */
    fun getQuotaState(clientIp: String): QuotaState? {
        return quotaStates[clientIp]
    }
    
    /**
     * Get all quota states
     */
    fun getAllQuotaStates(): Map<String, QuotaState> {
        return quotaStates.toMap()
    }
    
    /**
     * Reset quota for a specific client
     */
    fun resetQuota(clientIp: String) {
        val state = quotaStates[clientIp] ?: return
        val actualUsage = getActualUsageFromTrafficMeter(clientIp)
        quotaStates[clientIp] = state.copy(
            usedBytes = 0,
            usageBaselineBytes = actualUsage,
            quotaExceededAt = null,
            isBlocked = false,
            blockedUntil = null,
            lastWarningAt = null,
            resetAt = System.currentTimeMillis()
        )
        removeBlockedClient(clientIp) // Remove from persistence
        Log.i(TAG, "Quota reset for client: $clientIp")
    }
    
    /**
     * Reset all quotas
     */
    fun resetAllQuotas() {
        quotaStates.keys.forEach { clientIp ->
            resetQuota(clientIp)
        }
        Log.i(TAG, "All quotas reset")
    }
    
    /**
     * Manually unblock a client (used by monitoring UI)
     * This clears the blocked flag immediately but keeps actual usage intact
     */
    fun unblockClient(clientIp: String) {
        val state = quotaStates[clientIp] ?: return
        val actualUsage = getActualUsageFromTrafficMeter(clientIp)
        val adjustedUsage = calculateAdjustedUsage(state, actualUsage)
        quotaStates[clientIp] = state.copy(
            usedBytes = adjustedUsage,
            quotaExceededAt = null,
            isBlocked = false,
            blockedUntil = null
        )
        removeBlockedClient(clientIp)
        Log.i(TAG, "Manually unblocked client: $clientIp")
    }
    
    /**
     * Remove quota state for a disconnected client
     */
    fun removeClient(clientIp: String) {
        quotaStates.remove(clientIp)
        Log.d(TAG, "Removed quota state for client: $clientIp")
    }
    
    /**
     * Clear all existing blocks (useful when block duration is set to 0)
     */
    private fun clearAllBlocks() {
        var clearedCount = 0
        
        quotaStates.forEach { (clientIp, state) ->
            if (state.isBlocked) {
                quotaStates[clientIp] = state.copy(
                    isBlocked = false,
                    blockedUntil = null
                )
                removeBlockedClient(clientIp)
                clearedCount++
            }
        }
        
        // Also clear from preferences
        appPrefs.putString("quota_blocked_clients", "")
        
        Log.i(TAG, "Cleared $clearedCount existing blocks (block duration set to 0)")
    }
    
    /**
     * Load blocked clients from preferences (persistence across app restarts)
     */
    private fun loadBlockedClients() {
        if (!config.enabled) return
        
        try {
            val blockedClientsJson = appPrefs.getString("quota_blocked_clients", null)
            if (blockedClientsJson.isNullOrEmpty()) return
            
            // Simple format: "ip1:timestamp1,ip2:timestamp2,..."
            val now = System.currentTimeMillis()
            blockedClientsJson.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val ip = parts[0]
                    val blockedUntil = parts[1].toLongOrNull() ?: 0
                    
                    // Only restore if block hasn't expired
                    if (blockedUntil > now) {
                        val existingState = quotaStates[ip]
                        if (existingState != null) {
                            quotaStates[ip] = existingState.copy(
                                isBlocked = true,
                                blockedUntil = blockedUntil
                            )
                        } else {
                            // Create minimal state for blocked client
                            quotaStates[ip] = QuotaState(
                                clientIp = ip,
                                macAddress = "unknown",
                                allocatedQuotaBytes = 0,
                                usedBytes = 0,
                                isBlocked = true,
                                blockedUntil = blockedUntil
                            )
                        }
                        Log.d(TAG, "Restored blocked client: $ip (blocked until ${java.util.Date(blockedUntil)})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading blocked clients", e)
        }
    }
    
    /**
     * Save blocked client to preferences (persistence across app restarts)
     */
    private fun saveBlockedClient(clientIp: String, blockedUntil: Long) {
        try {
            val existing = appPrefs.getString("quota_blocked_clients", null) ?: ""
            val entries = if (existing.isNotEmpty()) {
                existing.split(",").toMutableList()
            } else {
                mutableListOf()
            }
            
            // Remove existing entry for this IP
            entries.removeAll { it.startsWith("$clientIp:") }
            
            // Add new entry
            entries.add("$clientIp:$blockedUntil")
            
            // Save back
            appPrefs.putString("quota_blocked_clients", entries.joinToString(","))
            Log.d(TAG, "Saved blocked client: $clientIp (blocked until ${java.util.Date(blockedUntil)})")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving blocked client", e)
        }
    }
    
    /**
     * Remove blocked client from preferences
     */
    private fun removeBlockedClient(clientIp: String) {
        try {
            val existing = appPrefs.getString("quota_blocked_clients", null) ?: ""
            if (existing.isEmpty()) return
            
            val entries = existing.split(",").toMutableList()
            entries.removeAll { it.startsWith("$clientIp:") }
            
            appPrefs.putString("quota_blocked_clients", entries.joinToString(","))
            Log.d(TAG, "Removed blocked client: $clientIp")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing blocked client", e)
        }
    }
    
    /**
     * Periodic cleanup: remove expired blocks, reset quotas if needed
     */
    private fun startPeriodicCleanup() {
        scope.launch {
            while (true) {
                delay(60000) // Check every minute
                
                if (!config.enabled) continue
                
                val now = System.currentTimeMillis()
                var resetNeeded = false
                
                quotaStates.forEach { (clientIp, state) ->
                    // Check if block has expired
                    if (state.isBlocked && state.blockedUntil != null && now >= state.blockedUntil) {
                        quotaStates[clientIp] = state.copy(
                            isBlocked = false,
                            blockedUntil = null
                        )
                        removeBlockedClient(clientIp) // Remove from persistence
                        Log.d(TAG, "Block expired for client: $clientIp")
                    }
                    
                    // Check if quota reset interval has passed
                    if (config.quotaResetIntervalMs > 0 && 
                        now - state.resetAt >= config.quotaResetIntervalMs) {
                        resetNeeded = true
                    }
                }
                
                // Reset quotas if interval has passed
                if (resetNeeded) {
                    resetAllQuotas()
                    Log.i(TAG, "Periodic quota reset completed")
                }
            }
        }
    }
}

