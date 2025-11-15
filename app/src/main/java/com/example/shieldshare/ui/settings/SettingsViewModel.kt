package com.example.shieldshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.data.repository.TrafficRepository
import com.example.shieldshare.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
        private val appPrefs: AppPrefs,
        private val trafficRepository: TrafficRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val themeModeString = appPrefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
            val themeMode = try {
                ThemeMode.valueOf(themeModeString)
            } catch (e: Exception) {
                ThemeMode.SYSTEM
            }
            
            _uiState.value =
                    SettingsUiState(
                            authEnabled = appPrefs.getBoolean("auth_enabled", false),
                            themeMode = themeMode,
                            notificationsEnabled =
                                    appPrefs.getBoolean("notifications_enabled", true),
                            httpHttpsEnabled = appPrefs.getBoolean("http_https_enabled", true),
                            socks5Enabled = appPrefs.getBoolean("socks5_enabled", true)
                    )
        }
    }

    // TODO: Add authentication functionality
    fun updateAuthEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(authEnabled = enabled)
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        _uiState.value = _uiState.value.copy(themeMode = themeMode)
        // Auto-save theme mode immediately for instant feedback
        appPrefs.putString("theme_mode", themeMode.name)
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
        // Auto-save notification setting immediately for instant feedback
        appPrefs.putBoolean("notifications_enabled", enabled)
    }

    fun updateHttpHttpsEnabled(enabled: Boolean) {
        val currentState = _uiState.value
        // Prevent disabling if SOCKS5 is already disabled
        if (!enabled && !currentState.socks5Enabled) {
            _uiState.value = currentState.copy(
                validationError = "At least one proxy protocol must be enabled"
            )
            return
        }
        _uiState.value = currentState.copy(
            httpHttpsEnabled = enabled,
            validationError = null
        )
    }

    fun updateSocks5Enabled(enabled: Boolean) {
        val currentState = _uiState.value
        // Prevent disabling if HTTP/HTTPS is already disabled
        if (!enabled && !currentState.httpHttpsEnabled) {
            _uiState.value = currentState.copy(
                validationError = "At least one proxy protocol must be enabled"
            )
            return
        }
        _uiState.value = currentState.copy(
            socks5Enabled = enabled,
            validationError = null
        )
    }

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            appPrefs.putBoolean("auth_enabled", state.authEnabled)
            appPrefs.putString("theme_mode", state.themeMode.name)
            appPrefs.putBoolean("notifications_enabled", state.notificationsEnabled)
            appPrefs.putBoolean("http_https_enabled", state.httpHttpsEnabled)
            appPrefs.putBoolean("socks5_enabled", state.socks5Enabled)
        }
    }

    /**
     * Clear all traffic data from the database.
     * Shows loading state during operation and error/success messages.
     */
    fun clearTrafficData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isClearingDatabase = true,
                clearDatabaseError = null,
                clearDatabaseSuccess = false
            )
            
            try {
                trafficRepository.clearAllTrafficData()
                _uiState.value = _uiState.value.copy(
                    isClearingDatabase = false,
                    clearDatabaseSuccess = true,
                    clearDatabaseError = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isClearingDatabase = false,
                    clearDatabaseSuccess = false,
                    clearDatabaseError = "Failed to clear traffic data: ${e.message}"
                )
            }
        }
    }

    /**
     * Reset clear database UI state (called after showing snackbar).
     */
    fun resetClearDatabaseState() {
        _uiState.value = _uiState.value.copy(
            clearDatabaseSuccess = false,
            clearDatabaseError = null
        )
    }
}

data class SettingsUiState(
        val authEnabled: Boolean = false,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val notificationsEnabled: Boolean = true,
        val httpHttpsEnabled: Boolean = true,
        val socks5Enabled: Boolean = true,
        val validationError: String? = null,
        // Clear database states
        val isClearingDatabase: Boolean = false,
        val clearDatabaseSuccess: Boolean = false,
        val clearDatabaseError: String? = null
)
