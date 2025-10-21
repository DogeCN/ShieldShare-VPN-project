package com.example.shieldshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.network.IpAddressProvider
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.vpn.VpnManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
        private val appPrefs: AppPrefs,
        private val vpnManager: VpnManager,
        private val proxyServer: ProxyServer,
        private val ipProvider: IpAddressProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var ipAutoJob: Job? = null
    private val ipRefreshIntervalMs = 30_000L // refresh every 30 sec

    init {
        loadSettings()
        refreshVpnIp()

        // Monitor VPN status changes and auto-refresh IP
        viewModelScope.launch {
            vpnManager.subscribeToStatusChanges().collect { status ->
                val connected = (status == com.example.shieldshare.managers.vpn.VpnStatus.CONNECTED)
                if (connected) {
                    refreshVpnIp() // refresh IP immediately
                    if (ipAutoJob?.isActive != true) {
                        ipAutoJob =
                                viewModelScope.launch {
                                    // refresh it when it starts
                                    refreshVpnIp()
                                    // refresh it as time we set
                                    while (isActive) {
                                        delay(ipRefreshIntervalMs)
                                        refreshVpnIp()
                                    }
                                }
                    }
                } else {
                    // when disconnect, stop auto-refresh and clear IP
                    ipAutoJob?.cancel()
                    _uiState.value = _uiState.value.copy(vpnServerAddress = "")
                }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.value =
                    SettingsUiState(
                            vpnServerAddress = appPrefs.getString("vpn_server_address", "") ?: "",
                            vpnUsername = appPrefs.getString("vpn_username", "") ?: "",
                            vpnPassword = appPrefs.getString("vpn_password", "") ?: "",
                            proxyPort = appPrefs.getInt("proxy_port", 8080),
                            authEnabled = appPrefs.getBoolean("auth_enabled", false),
                            darkMode = appPrefs.getBoolean("dark_mode", false),
                            notificationsEnabled =
                                    appPrefs.getBoolean("notifications_enabled", true),
                            databaseEncryption = appPrefs.getBoolean("database_encryption", false)
                    )
        }
    }

    fun updateVpnServerAddress(address: String) {
        _uiState.value = _uiState.value.copy(vpnServerAddress = address)
    }

    fun updateVpnUsername(username: String) {
        _uiState.value = _uiState.value.copy(vpnUsername = username)
    }

    fun updateVpnPassword(password: String) {
        _uiState.value = _uiState.value.copy(vpnPassword = password)
    }

    fun updateProxyPort(port: Int) {
        _uiState.value = _uiState.value.copy(proxyPort = port)
    }

    // TODO: Add authentication functionality
    fun updateAuthEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(authEnabled = enabled)
    }

    // TODO: Add database encryption functionality
    fun updateDatabaseEncryption(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(databaseEncryption = enabled)
    }

    // TODO: Add dark mode functionality
    fun updateDarkMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkMode = enabled)
    }

    // TODO: Add notifications functionality
    fun updateNotificationsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
    }

    fun refreshVpnIp() =
            viewModelScope.launch {
                try {
                    val result = ipProvider.getPublicIp()
                    if (result.isSuccess) {
                        val vpnIp = result.getOrNull()
                        if (vpnIp != null) {
                            _uiState.value = _uiState.value.copy(vpnServerAddress = vpnIp)
                        }
                    }
                } catch (e: Exception) {
                    // Keep current server address if IP fetch fails
                }
            }

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            appPrefs.putString("vpn_server_address", state.vpnServerAddress)
            appPrefs.putString("vpn_username", state.vpnUsername)
            appPrefs.putString("vpn_password", state.vpnPassword)
            appPrefs.putInt("proxy_port", state.proxyPort)
            appPrefs.putBoolean("auth_enabled", state.authEnabled)
            appPrefs.putBoolean("dark_mode", state.darkMode)
            appPrefs.putBoolean("notifications_enabled", state.notificationsEnabled)
            appPrefs.putBoolean("database_encryption", state.databaseEncryption)
        }
    }
}

data class SettingsUiState(
        val vpnServerAddress: String = "",
        val vpnUsername: String = "",
        val vpnPassword: String = "",
        val proxyPort: Int = 8080,
        val authEnabled: Boolean = false,
        val darkMode: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val databaseEncryption: Boolean = false
)
