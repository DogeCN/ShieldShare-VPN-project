package com.example.shieldshare.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.managers.performance.PerformanceMonitor
import com.example.shieldshare.managers.performance.PerformanceSample
import com.example.shieldshare.managers.performance.PerformanceSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PerformanceUiState(
    val summary: PerformanceSummary? = null,
    val recentSamples: List<PerformanceSample> = emptyList()
)

@HiltViewModel
class PerformanceViewModel @Inject constructor(
    private val performanceMonitor: PerformanceMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            performanceMonitor.summary.collect { summary ->
                _uiState.value = _uiState.value.copy(summary = summary)
            }
        }
        viewModelScope.launch {
            performanceMonitor.samples.collect { samples ->
                _uiState.value = _uiState.value.copy(recentSamples = samples)
            }
        }
    }
}

