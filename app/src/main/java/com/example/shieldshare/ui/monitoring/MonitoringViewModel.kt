package com.example.shieldshare.ui.monitoring

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.repository.TrafficRepository
import com.example.shieldshare.data.repository.DatabaseStats
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import com.example.shieldshare.managers.meter.ClientTrafficStats
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import com.example.shieldshare.managers.hotspot.HotspotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val proxyServer: ProxyServer,
    private val trafficMeter: TrafficMeter,
    private val hotspotManager: HotspotManager,
    private val trafficRepository: TrafficRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()
    
    // Track previous stats for speed calculation (IP -> previous bytes)
    private val previousStats = mutableMapOf<String, Pair<Long, Long>>() // IP -> (bytesUp, bytesDown)
    private var lastUpdateTime = System.currentTimeMillis()
    private val UPDATE_INTERVAL_MS = 3000L // 3 seconds (reduced frequency for better performance)

    init {
        // Observe VPN status changes
        viewModelScope.launch {
            vpnManager.subscribeToStatusChanges().collect { status ->
                _uiState.value = _uiState.value.copy(
                    vpnStatus = status.name,
                    isVpnConnected = status == VpnStatus.CONNECTED
                )
            }
        }
        
        // Observe service sessions from database
        // Room Flow automatically emits when data changes, so we can collect directly
        viewModelScope.launch {
            var previousActiveSessionId: String? = null
            trafficRepository.getAllServiceSessions()
                .collect { serviceSessions ->
                    val activeSession = serviceSessions.find { it.isActive }
                    val currentActiveSessionId = activeSession?.sessionId
                    
                    // Detect when a new active session starts (transition from no active session to active)
                    val newSessionStarted = previousActiveSessionId == null && currentActiveSessionId != null
                    
                    if (newSessionStarted) {
                        // Clear previous stats when a new session starts to prevent showing old data
                        previousStats.clear()
                        // Immediately update UI with empty stats to prevent showing stale data
                        // Don't read from trafficMeter here to avoid any race conditions - just show empty
                        _uiState.value = _uiState.value.copy(
                            serviceSessions = serviceSessions,
                            trafficStats = emptyList(), // Empty list ensures no old data is shown
                            totalBytesUp = 0,
                            totalBytesDown = 0,
                            activeClients = 0
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(serviceSessions = serviceSessions)
                    }
                    
                    previousActiveSessionId = currentActiveSessionId
                    
                    // Automatically update database stats when service sessions change
                    loadDatabaseStats()
                    // Reload unique IP summary when sessions change
                    loadUniqueIpTrafficSummary()
                }
        }
        
        // Load initial database statistics
        loadDatabaseStats()
        
        // Load unique IP traffic summary
        loadUniqueIpTrafficSummary()

        // Update proxy status and traffic data periodically
        viewModelScope.launch {
            while (true) {
                val currentTime = System.currentTimeMillis()
                val timeDelta = (currentTime - lastUpdateTime).coerceAtLeast(100) // At least 100ms to avoid division by zero
                lastUpdateTime = currentTime
                
                val proxyInfo = proxyServer.getProxyInfo()
                val allTrafficStats = trafficMeter.getCurrentStats()
                
                // Filter out host device IP from traffic stats
                val hostIp = hotspotManager.getHotspotIpAddress()
                val trafficStats = if (hostIp != null) {
                    allTrafficStats.filter { it.ipAddress != hostIp }
                } else {
                    allTrafficStats
                }
                
                // Calculate speeds for each client
                val statsWithSpeeds = trafficStats.map { stats ->
                    val previous = previousStats[stats.ipAddress]
                    val (rateUp, rateDown) = if (previous != null) {
                        // Calculate bytes per second
                        val bytesUpDiff = stats.totalBytesUp - previous.first
                        val bytesDownDiff = stats.totalBytesDown - previous.second
                        val rateUpBps = (bytesUpDiff * 1000.0) / timeDelta // bytes per second
                        val rateDownBps = (bytesDownDiff * 1000.0) / timeDelta // bytes per second
                        Pair(rateUpBps, rateDownBps)
                    } else {
                        Pair(0.0, 0.0)
                    }
                    
                    // Update previous stats
                    previousStats[stats.ipAddress] = Pair(stats.totalBytesUp, stats.totalBytesDown)
                    
                    // Return stats with calculated speeds
                    stats.copy(
                        currentRateUp = rateUp,
                        currentRateDown = rateDown
                    )
                }
                
                // Clean up previous stats for clients that are no longer active
                val activeIps = trafficStats.map { it.ipAddress }.toSet()
                previousStats.keys.removeAll { it !in activeIps }
                
                val rawLogs = (trafficMeter as? TrafficMeterSimple)?.getRawLogs() ?: emptyList()
                
                // Calculate total traffic (excluding host device)
                val totalBytesUp = trafficStats.sumOf { it.totalBytesUp }
                val totalBytesDown = trafficStats.sumOf { it.totalBytesDown }
                
                // Update state - let Compose handle optimization with keys
                _uiState.value = _uiState.value.copy(
                    isProxyRunning = proxyInfo.isRunning,
                    activeConnections = proxyInfo.activeConnections,
                    trafficStats = statsWithSpeeds,
                    totalBytesUp = totalBytesUp,
                    totalBytesDown = totalBytesDown,
                    activeClients = trafficStats.size,
                    rawLogs = rawLogs
                )
                kotlinx.coroutines.delay(UPDATE_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Load database statistics.
     */
    private fun loadDatabaseStats() {
        viewModelScope.launch {
            try {
                val stats = trafficRepository.getDatabaseStats()
                _uiState.value = _uiState.value.copy(
                    databaseStats = stats
                )
            } catch (e: Exception) {
                // Error loading stats - keep existing state
            }
        }
    }
    
    
    /**
     * Load client traffic for a specific service session.
     */
    fun loadClientTrafficForSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val clientTraffic = trafficRepository.getClientTrafficForServiceSession(sessionId)
                _uiState.value = _uiState.value.copy(
                    clientTrafficPerSession = _uiState.value.clientTrafficPerSession + (sessionId to clientTraffic)
                )
            } catch (e: Exception) {
                Log.e("MonitoringViewModel", "Error loading client traffic for session", e)
            }
        }
    }
    
    /**
     * Load aggregated traffic summary for all unique IPs.
     */
    private fun loadUniqueIpTrafficSummary() {
        viewModelScope.launch {
            try {
                val summary = trafficRepository.getAllUniqueIpTrafficSummary()
                _uiState.value = _uiState.value.copy(
                    uniqueIpTrafficSummary = summary
                )
            } catch (e: Exception) {
                Log.e("MonitoringViewModel", "Error loading unique IP traffic summary", e)
            }
        }
    }
}

data class MonitoringUiState(
    val vpnStatus: String = "DISCONNECTED",
    val isVpnConnected: Boolean = false,
    val isProxyRunning: Boolean = false,
    val activeConnections: Int = 0,
    val trafficStats: List<ClientTrafficStats> = emptyList(),
    val totalBytesUp: Long = 0,
    val totalBytesDown: Long = 0,
    val activeClients: Int = 0,
    val rawLogs: List<String> = emptyList(),
    // Persistent data from database
    val databaseStats: DatabaseStats? = null,
    val serviceSessions: List<com.example.shieldshare.data.db.ServiceSessionEntity> = emptyList(),
    // Client traffic per service session (sessionId -> Map<clientIp, Pair<bytesUp, bytesDown>>)
    val clientTrafficPerSession: Map<String, Map<String, Pair<Long, Long>>> = emptyMap(),
    // Aggregated traffic summary for all unique IPs (IP -> Pair<bytesUp, bytesDown>)
    val uniqueIpTrafficSummary: Map<String, Pair<Long, Long>> = emptyMap()
)
