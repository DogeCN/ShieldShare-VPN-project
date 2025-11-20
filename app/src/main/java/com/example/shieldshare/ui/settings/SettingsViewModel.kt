package com.example.shieldshare.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.data.repository.TrafficRepository
import com.example.shieldshare.managers.quota.QuotaManager
import com.example.shieldshare.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
        private val trafficRepository: TrafficRepository,
        private val quotaManager: QuotaManager,
        @ApplicationContext private val context: Context
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
                            socks5Enabled = appPrefs.getBoolean("socks5_enabled", true),
                            // Quota settings (simplified)
                            quotaEnabled = appPrefs.getBoolean("quota_enabled", false),
                            quotaTotalBandwidthMb = appPrefs.getLong("quota_total_bandwidth_mb", 0),
                            quotaBlockDurationHours = appPrefs.getInt("quota_block_duration_hours", 1)
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
            
            // Save quota settings (simplified - only essential ones)
            appPrefs.putBoolean("quota_enabled", state.quotaEnabled)
            if (state.quotaEnabled) {
                appPrefs.putLong("quota_total_bandwidth_mb", state.quotaTotalBandwidthMb)
                appPrefs.putInt("quota_block_duration_hours", state.quotaBlockDurationHours)
            }
            
            // Reload quota configuration
            quotaManager.loadConfig()
        }
    }
    
    // Quota settings update methods (simplified)
    fun updateQuotaEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(quotaEnabled = enabled)
    }
    
    fun updateQuotaSettings(totalBandwidthMb: Long, blockDurationHours: Int) {
        _uiState.value = _uiState.value.copy(
            quotaTotalBandwidthMb = totalBandwidthMb,
            quotaBlockDurationHours = blockDurationHours
        )
        // Save settings and reload quota config (which will clear blocks if duration is 0)
        saveSettings()
    }
    
    /**
     * Detect available bandwidth using NetworkCapabilities API
     * Returns bandwidth in MB, or null if detection fails
     */
    fun detectBandwidth(): Long? {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return null
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
            
            // Try to get link bandwidth (available on Android 6.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val downstreamBps = networkCapabilities.getLinkDownstreamBandwidthKbps()
                
                // Use downstream (download) bandwidth as primary indicator
                // Convert from Kbps to MB (approximate monthly quota)
                // Formula: (Kbps * 3600 seconds * 30 days) / (8 bits * 1024 * 1024) = MB per month
                // But for quota purposes, we want a reasonable daily/hourly limit
                // Let's use a conservative estimate: assume 80% of theoretical max
                if (downstreamBps > 0) {
                    // Convert Kbps to Mbps, then estimate monthly data cap
                    // For quota, we'll suggest a daily limit based on typical usage
                    // Example: 100 Mbps = ~1000 GB/month, but for quota we want daily/hourly limits
                    // Let's suggest: (downstreamMbps * 100) MB as a reasonable quota
                    // This gives ~100 MB per 1 Mbps, which is conservative
                    val downstreamMbps = downstreamBps / 1000.0
                    val suggestedMb = (downstreamMbps * 100).toLong()
                    
                    // Cap at reasonable values (min 100 MB, max 10 GB)
                    return suggestedMb.coerceIn(100, 10_000)
                }
            }
            
            null
        } catch (e: Exception) {
            null
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
        val clearDatabaseError: String? = null,
        // Quota settings (simplified - only essential)
        val quotaEnabled: Boolean = false,
        val quotaTotalBandwidthMb: Long = 0,
        val quotaBlockDurationHours: Int = 1
)
