package com.example.shieldshare.ui.advanced

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.filter.TrafficFilterManager
import com.example.shieldshare.managers.consumption.ConsumptionTracker
import com.example.shieldshare.managers.consumption.AbuseCheckResult
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.quota.QuotaManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class AveragePeriod(val days: Int, val displayName: String) {
    DAILY(1, "Daily (1 day)"),
    WEEKLY(7, "Weekly (7 days)"),
    MONTHLY(30, "Monthly (30 days)")
}

data class AdvancedTrafficRegulationUiState(
    val portFilteringEnabled: Boolean = false,
    val urlBlockingEnabled: Boolean = false,
    val blockedPorts: Set<Int> = emptySet(),
    val blockedDomains: Set<String> = emptySet(),
    val customPortInput: String = "",
    val customDomainInput: String = "",
    val abuseDetectionEnabled: Boolean = false,
    val abuseThresholdMultiplier: Float = 2.0f,
    val averagePeriod: AveragePeriod = AveragePeriod.WEEKLY,
    val globalAverage: Long = 0L, // Global average consumption (all clients)
    val abuseStatus: Map<String, AbuseCheckResult> = emptyMap(), // clientIp -> abuse check result
    val isLoading: Boolean = false
)

@HiltViewModel
class AdvancedTrafficRegulationViewModel @Inject constructor(
    private val trafficFilterManager: TrafficFilterManager,
    private val appPrefs: AppPrefs,
    private val consumptionTracker: ConsumptionTracker,
    private val trafficMeter: TrafficMeter,
    private val quotaManager: QuotaManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedTrafficRegulationUiState())
    val uiState: StateFlow<AdvancedTrafficRegulationUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        // Start periodic refresh of abuse status (every 30 seconds for real-time display)
        viewModelScope.launch {
            while (true) {
                delay(30_000) // 30 seconds
                if (_uiState.value.abuseDetectionEnabled) {
                    refreshAbuseStatus()
                }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val abuseDetectionEnabled = appPrefs.getBoolean("dynamic_quota_enabled", false)
            val abuseThresholdMultiplier = appPrefs.getFloat("dynamic_quota_multiplier", 2.0f)
            
            // Load average period (default: WEEKLY = 7 days)
            val averagePeriodDays = appPrefs.getInt("dynamic_quota_average_days", 7)
            val averagePeriod = when (averagePeriodDays) {
                1 -> AveragePeriod.DAILY
                30 -> AveragePeriod.MONTHLY
                else -> AveragePeriod.WEEKLY // Default to 7 days
            }
            
            // Get global baseline rate and abuse status (rate-based detection)
            val globalBaselineRate = consumptionTracker.getGlobalBaselineRate()
            val abuseStatus = if (abuseDetectionEnabled) {
                consumptionTracker.getAllAbuseStatus(abuseThresholdMultiplier, averagePeriod.days)
            } else {
                emptyMap()
            }
            
            _uiState.value = _uiState.value.copy(
                portFilteringEnabled = trafficFilterManager.isPortFilteringEnabled(),
                urlBlockingEnabled = trafficFilterManager.isUrlBlockingEnabled(),
                blockedPorts = trafficFilterManager.getBlockedPorts(),
                blockedDomains = trafficFilterManager.getBlockedDomains(),
                abuseDetectionEnabled = abuseDetectionEnabled,
                abuseThresholdMultiplier = abuseThresholdMultiplier,
                averagePeriod = averagePeriod,
                globalAverage = globalBaselineRate, // Store baseline rate
                abuseStatus = abuseStatus,
                isLoading = false
            )
        }
    }

    fun updatePortFilteringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trafficFilterManager.setPortFilteringEnabled(enabled)
            _uiState.value = _uiState.value.copy(portFilteringEnabled = enabled)
        }
    }

    fun updateUrlBlockingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            trafficFilterManager.setUrlBlockingEnabled(enabled)
            _uiState.value = _uiState.value.copy(urlBlockingEnabled = enabled)
        }
    }

    fun updateCustomPortInput(text: String) {
        _uiState.value = _uiState.value.copy(customPortInput = text)
    }

    fun updateCustomDomainInput(text: String) {
        _uiState.value = _uiState.value.copy(customDomainInput = text)
    }

    fun addPort(port: Int) {
        viewModelScope.launch {
            val currentPorts = _uiState.value.blockedPorts.toMutableSet()
            currentPorts.add(port)
            trafficFilterManager.updateBlockedPorts(currentPorts)
            _uiState.value = _uiState.value.copy(
                blockedPorts = currentPorts,
                customPortInput = ""
            )
        }
    }

    fun removePort(port: Int) {
        viewModelScope.launch {
            val currentPorts = _uiState.value.blockedPorts.toMutableSet()
            currentPorts.remove(port)
            trafficFilterManager.updateBlockedPorts(currentPorts)
            _uiState.value = _uiState.value.copy(blockedPorts = currentPorts)
        }
    }

    fun addDomain(domain: String) {
        viewModelScope.launch {
            val trimmedDomain = domain.trim()
            if (trimmedDomain.isNotEmpty()) {
                val currentDomains = _uiState.value.blockedDomains.toMutableSet()
                currentDomains.add(trimmedDomain)
                trafficFilterManager.updateBlockedDomains(currentDomains)
                _uiState.value = _uiState.value.copy(
                    blockedDomains = currentDomains,
                    customDomainInput = ""
                )
            }
        }
    }

    fun removeDomain(domain: String) {
        viewModelScope.launch {
            val currentDomains = _uiState.value.blockedDomains.toMutableSet()
            currentDomains.remove(domain)
            trafficFilterManager.updateBlockedDomains(currentDomains)
            _uiState.value = _uiState.value.copy(blockedDomains = currentDomains)
        }
    }

    fun togglePopularPort(appPort: Int) {
        viewModelScope.launch {
            val currentPorts = _uiState.value.blockedPorts.toMutableSet()
            if (currentPorts.contains(appPort)) {
                currentPorts.remove(appPort)
            } else {
                currentPorts.add(appPort)
            }
            trafficFilterManager.updateBlockedPorts(currentPorts)
            _uiState.value = _uiState.value.copy(blockedPorts = currentPorts)
        }
    }
    
    fun updateAbuseDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.putBoolean("dynamic_quota_enabled", enabled)
            // Reload abuse status when enabled/disabled
            val currentPeriod = _uiState.value.averagePeriod
            val currentMultiplier = _uiState.value.abuseThresholdMultiplier
            val globalBaselineRate = consumptionTracker.getGlobalBaselineRate()
            val abuseStatus = if (enabled) {
                consumptionTracker.getAllAbuseStatus(currentMultiplier, currentPeriod.days)
            } else {
                emptyMap()
            }
            _uiState.value = _uiState.value.copy(
                abuseDetectionEnabled = enabled,
                globalAverage = globalBaselineRate,
                abuseStatus = abuseStatus
            )
        }
    }
    
    fun updateAbuseThresholdMultiplier(multiplier: Float) {
        viewModelScope.launch {
            appPrefs.putFloat("dynamic_quota_multiplier", multiplier)
            // Reload abuse status with new multiplier
            val currentPeriod = _uiState.value.averagePeriod
            val globalBaselineRate = consumptionTracker.getGlobalBaselineRate()
            val abuseStatus = if (_uiState.value.abuseDetectionEnabled) {
                consumptionTracker.getAllAbuseStatus(multiplier, currentPeriod.days)
            } else {
                emptyMap()
            }
            _uiState.value = _uiState.value.copy(
                abuseThresholdMultiplier = multiplier,
                globalAverage = globalBaselineRate,
                abuseStatus = abuseStatus
            )
        }
    }
    
    fun resetConsumptionHistory(clientIp: String? = null) {
        viewModelScope.launch {
            consumptionTracker.resetConsumptionHistory(clientIp)
            // Reload abuse status after reset
            refreshAbuseStatus()
        }
    }
    
    fun updateAveragePeriod(period: AveragePeriod) {
        viewModelScope.launch {
            appPrefs.putInt("dynamic_quota_average_days", period.days)
            
            // Reload global baseline rate and abuse status with new period
            val currentMultiplier = _uiState.value.abuseThresholdMultiplier
            val globalBaselineRate = consumptionTracker.getGlobalBaselineRate()
            val abuseStatus = if (_uiState.value.abuseDetectionEnabled) {
                consumptionTracker.getAllAbuseStatus(currentMultiplier, period.days)
            } else {
                emptyMap()
            }
            
            _uiState.value = _uiState.value.copy(
                averagePeriod = period,
                globalAverage = globalBaselineRate,
                abuseStatus = abuseStatus
            )
        }
    }
    
    fun refreshAbuseStatus() {
        viewModelScope.launch {
            // Update consumption for all clients
            consumptionTracker.updateAllClientsConsumption()
            
            // Reload global baseline rate and abuse status
            val currentPeriod = _uiState.value.averagePeriod
            val currentMultiplier = _uiState.value.abuseThresholdMultiplier
            val globalBaselineRate = consumptionTracker.getGlobalBaselineRate()
            val abuseStatus = if (_uiState.value.abuseDetectionEnabled) {
                consumptionTracker.getAllAbuseStatus(currentMultiplier, currentPeriod.days)
            } else {
                emptyMap()
            }
            _uiState.value = _uiState.value.copy(
                globalAverage = globalBaselineRate,
                abuseStatus = abuseStatus
            )
        }
    }
    
    /**
     * Block an abusive client
     * Blocks the client for 24 hours to prevent further abuse
     */
    fun blockAbusiveClient(clientIp: String) {
        viewModelScope.launch {
            try {
                // Block the client for 24 hours
                quotaManager.blockClient(clientIp, blockDurationMs = 24 * 60 * 60 * 1000L)
                
                // Refresh abuse status to update UI
                refreshAbuseStatus()
                
                // Update UI state to show the client is now blocked
                val updatedAbuseStatus = _uiState.value.abuseStatus.toMutableMap()
                // The client will still show as abusing, but they're now blocked
                _uiState.value = _uiState.value.copy(abuseStatus = updatedAbuseStatus)
            } catch (e: Exception) {
                android.util.Log.e("AdvancedTrafficRegulationViewModel", "Error blocking client: $clientIp", e)
            }
        }
    }
}

