package com.example.shieldshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.proxy.ProxyServer
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
        private val proxyServer: ProxyServer
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            _uiState.value =
                    SettingsUiState(
                            authEnabled = appPrefs.getBoolean("auth_enabled", false),
                            darkMode = appPrefs.getBoolean("dark_mode", false),
                            notificationsEnabled =
                                    appPrefs.getBoolean("notifications_enabled", true),
                            databaseEncryption = appPrefs.getBoolean("database_encryption", false)
                    )
        }
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

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            appPrefs.putBoolean("auth_enabled", state.authEnabled)
            appPrefs.putBoolean("dark_mode", state.darkMode)
            appPrefs.putBoolean("notifications_enabled", state.notificationsEnabled)
            appPrefs.putBoolean("database_encryption", state.databaseEncryption)
        }
    }
}

data class SettingsUiState(
        val authEnabled: Boolean = false,
        val darkMode: Boolean = false,
        val notificationsEnabled: Boolean = true,
        val databaseEncryption: Boolean = false
)
