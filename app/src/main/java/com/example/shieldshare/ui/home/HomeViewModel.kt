package com.example.shieldshare.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
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
class HomeViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val proxyServer: ProxyServer,
    private val appPrefs: AppPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Observe VPN status changes
        viewModelScope.launch {
            vpnManager.subscribeToStatusChanges().collect { status ->
                _uiState.value = _uiState.value.copy(
                    vpnStatus = status.name,
//                    isVpnConnected = status == VpnStatus.CONNECTED,
//                    isVpnConnecting = status == VpnStatus.CONNECTING
                    isVpnConnecting = false,
                    isVpnConnected = true
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
                        Log.i("HomeViewModel", "VPN connected: $connection")
                    },
                    onFailure = { error ->
                        Log.e("HomeViewModel", "Failed to connect VPN", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception connecting VPN", e)
            }
        }
    }

    fun stopVpn() {
        viewModelScope.launch {
            try {
                val result = vpnManager.disconnectVpn()
                result.fold(
                    onSuccess = {
                        Log.i("HomeViewModel", "VPN disconnected")
                    },
                    onFailure = { error ->
                        Log.e("HomeViewModel", "Failed to disconnect VPN", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception disconnecting VPN", e)
            }
        }
    }

    fun startProxyServer() {
        viewModelScope.launch {
            try {
                // Load proxy settings from AppPrefs
                val proxyPort = appPrefs.getInt("proxy_port", 8080)
                val authEnabled = appPrefs.getBoolean("auth_enabled", false)
                
                val config = ProxyConfig(
                    port = proxyPort,
                    authEnabled = authEnabled,
                    allowedClients = emptyList(),
                    proxyType = ProxyType.BOTH
                )

                val result = proxyServer.startProxy(config)
                result.fold(
                    onSuccess = { proxyInstance ->
                        Log.i("HomeViewModel", "Proxy server started on port ${config.port}: $proxyInstance")
                        _uiState.value = _uiState.value.copy(
                            isProxyRunning = true,
                            proxyPort = config.port
                        )
                    },
                    onFailure = { error ->
                        Log.e("HomeViewModel", "Failed to start proxy server", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception starting proxy server", e)
            }
        }
    }

    fun stopProxyServer() {
        viewModelScope.launch {
            try {
                val result = proxyServer.stopProxy()
                result.fold(
                    onSuccess = {
                        Log.i("HomeViewModel", "Proxy server stopped")
                        _uiState.value = _uiState.value.copy(
                            isProxyRunning = false,
                            proxyPort = 0
                        )
                    },
                    onFailure = { error ->
                        Log.e("HomeViewModel", "Failed to stop proxy server", error)
                    }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception stopping proxy server", e)
            }
        }
    }
}

data class HomeUiState(
    val vpnStatus: String = "DISCONNECTED",
    val isVpnConnected: Boolean = false,
    val isVpnConnecting: Boolean = false,
    val isProxyRunning: Boolean = false,
    val proxyPort: Int = 0,
    val activeConnections: Int = 0,
    val dataTransferred: String = "0 MB"
)
