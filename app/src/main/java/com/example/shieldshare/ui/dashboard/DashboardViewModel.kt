package com.example.shieldshare.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.managers.proxy.ProxyConfig
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.proxy.ProxyType
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val proxyServer: ProxyServer
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Observe VPN status changes
        viewModelScope.launch {
            vpnManager.subscribeToStatusChanges().collect { status ->
                _uiState.value = _uiState.value.copy(
                    vpnStatus = status.name,
                    isVpnConnected = status == VpnStatus.CONNECTED,
                    isVpnConnecting = status == VpnStatus.CONNECTING
                )
            }
        }
    }

    fun startVpn() {
        viewModelScope.launch {
            try {
                val config = com.example.shieldshare.managers.vpn.VpnConfig()
                val result = vpnManager.connectVpn(config)
                result.fold(
                    onSuccess = { connection ->
                        Log.i("DashboardViewModel", "VPN connected: $connection")
                    },
                    onFailure = { error ->
                        Log.e("DashboardViewModel", "Failed to connect VPN", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Exception connecting VPN", e)
            }
        }
    }

    fun stopVpn() {
        viewModelScope.launch {
            try {
                val result = vpnManager.disconnectVpn()
                result.fold(
                    onSuccess = {
                        Log.i("DashboardViewModel", "VPN disconnected")
                    },
                    onFailure = { error ->
                        Log.e("DashboardViewModel", "Failed to disconnect VPN", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Exception disconnecting VPN", e)
            }
        }
    }

    fun startProxyServer() {
        viewModelScope.launch {
            try {
                val config = ProxyConfig(
                    port = 8080,
                    authEnabled = false,
                    allowedClients = emptyList(),
                    proxyType = ProxyType.BOTH
                )

                val result = proxyServer.startProxy(config)
                result.fold(
                    onSuccess = { proxyInstance ->
                        Log.i("DashboardViewModel", "Proxy server started: $proxyInstance")
                        _uiState.value = _uiState.value.copy(
                            isProxyRunning = true,
                            proxyPort = config.port
                        )
                    },
                    onFailure = { error ->
                        Log.e("DashboardViewModel", "Failed to start proxy server", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Exception starting proxy server", e)
            }
        }
    }

    fun stopProxyServer() {
        viewModelScope.launch {
            try {
                val result = proxyServer.stopProxy()
                result.fold(
                    onSuccess = {
                        Log.i("DashboardViewModel", "Proxy server stopped")
                        _uiState.value = _uiState.value.copy(
                            isProxyRunning = false,
                            proxyPort = 0
                        )
                    },
                    onFailure = { error ->
                        Log.e("DashboardViewModel", "Failed to stop proxy server", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Exception stopping proxy server", e)
            }
        }
    }
}

data class DashboardUiState(
    val vpnStatus: String = "DISCONNECTED",
    val isVpnConnected: Boolean = false,
    val isVpnConnecting: Boolean = false,
    val isProxyRunning: Boolean = false,
    val proxyPort: Int = 0,
    val activeConnections: Int = 0,
    val dataTransferred: String = "0 MB"
)
