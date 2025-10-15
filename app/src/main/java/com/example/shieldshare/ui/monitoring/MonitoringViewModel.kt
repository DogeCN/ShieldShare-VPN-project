package com.example.shieldshare.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val proxyServer: ProxyServer
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

        // Update proxy status periodically
        viewModelScope.launch {
            while (true) {
                val proxyInfo = proxyServer.getProxyInfo()
                _uiState.value = _uiState.value.copy(
                    isProxyRunning = proxyInfo.isRunning,
                    activeConnections = proxyInfo.activeConnections
                )
                kotlinx.coroutines.delay(1000) // Update every second
            }
        }
    }
}

data class MonitoringUiState(
    val vpnStatus: String = "DISCONNECTED",
    val isVpnConnected: Boolean = false,
    val isProxyRunning: Boolean = false,
    val activeConnections: Int = 0
)
