package com.example.shieldshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPrefs: AppPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(
                vpnServerAddress = appPrefs.getString("vpn_server_address", "") ?: "",
                vpnUsername = appPrefs.getString("vpn_username", "") ?: "",
                vpnPassword = appPrefs.getString("vpn_password", "") ?: "",
                proxyPort = appPrefs.getInt("proxy_port", 8080),
                authEnabled = appPrefs.getBoolean("auth_enabled", false),
                darkMode = appPrefs.getBoolean("dark_mode", false),
                notificationsEnabled = appPrefs.getBoolean("notifications_enabled", true)
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

    fun updateAuthEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(authEnabled = enabled)
    }

    fun updateDarkMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkMode = enabled)
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
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
    val notificationsEnabled: Boolean = true
)
