package com.example.shieldshare.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import com.example.shieldshare.managers.meter.ClientTrafficStats
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MonitoringViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val proxyServer: ProxyServer,
    private val trafficMeter: TrafficMeter
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitoringUiState())
    val uiState: StateFlow<MonitoringUiState> = _uiState.asStateFlow()

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

        // Update proxy status and traffic data periodically
        viewModelScope.launch {
            while (true) {
                val proxyInfo = proxyServer.getProxyInfo()
                val trafficStats = trafficMeter.getCurrentStats()
                val rawLogs = (trafficMeter as? TrafficMeterSimple)?.getRawLogs() ?: emptyList()
                
                // Calculate total traffic
                val totalBytesUp = trafficStats.sumOf { it.totalBytesUp }
                val totalBytesDown = trafficStats.sumOf { it.totalBytesDown }
                
                _uiState.value = _uiState.value.copy(
                    isProxyRunning = proxyInfo.isRunning,
                    activeConnections = proxyInfo.activeConnections,
                    trafficStats = trafficStats,
                    totalBytesUp = totalBytesUp,
                    totalBytesDown = totalBytesDown,
                    activeClients = trafficStats.size,
                    rawLogs = rawLogs
                )
                kotlinx.coroutines.delay(2000) // Update every 2 seconds for real-time feel
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
    val rawLogs: List<String> = emptyList()
)
